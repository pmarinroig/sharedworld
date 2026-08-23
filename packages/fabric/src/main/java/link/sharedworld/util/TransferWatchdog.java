package link.sharedworld.util;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stall detector for blob transfers. java.net.http has no read timeout, and a
 * whole-exchange deadline is wrong for transfers whose healthy duration is
 * unbounded (a multi-GB blob on a slow link); the correct signal is "no bytes
 * moved for a while". The transfer pulses this watchdog on every chunk of
 * progress; when the stall window elapses without a pulse, the watchdog closes
 * the transfer's underlying stream, which surfaces in the transfer thread as
 * an IOException that callers convert to a retryable stall error.
 */
public final class TransferWatchdog implements AutoCloseable {
    /** Tests shrink this via the system property to keep stall scenarios fast. */
    private static final String STALL_TIMEOUT_PROPERTY = "sharedworld.transferStallTimeoutMs";
    private static final long DEFAULT_STALL_TIMEOUT_MS = 30_000L;

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sharedworld-transfer-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final Closeable abortTarget;
    private final long stallTimeoutMillis;
    private final AtomicLong lastProgressAt = new AtomicLong(MonotonicClock.millis());
    private final AtomicBoolean stalled = new AtomicBoolean(false);
    private final ScheduledFuture<?> checker;

    private TransferWatchdog(Closeable abortTarget, long stallTimeoutMillis) {
        this.abortTarget = abortTarget;
        this.stallTimeoutMillis = stallTimeoutMillis;
        long interval = Math.max(50L, stallTimeoutMillis / 4L);
        this.checker = SCHEDULER.scheduleAtFixedRate(this::check, interval, interval, TimeUnit.MILLISECONDS);
    }

    public static TransferWatchdog watching(Closeable abortTarget) {
        return new TransferWatchdog(abortTarget, stallTimeoutMillis());
    }

    public static long stallTimeoutMillis() {
        String override = System.getProperty(STALL_TIMEOUT_PROPERTY, "").trim();
        if (!override.isEmpty()) {
            try {
                long parsed = Long.parseLong(override);
                if (parsed > 0L) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // A broken override must not change production behavior.
            }
        }
        return DEFAULT_STALL_TIMEOUT_MS;
    }

    /** Call on every unit of transfer progress. */
    public void pulse() {
        this.lastProgressAt.set(MonotonicClock.millis());
    }

    /** True once the watchdog aborted the transfer for lack of progress. */
    public boolean stalled() {
        return this.stalled.get();
    }

    private void check() {
        if (MonotonicClock.millis() - this.lastProgressAt.get() < this.stallTimeoutMillis) {
            return;
        }
        if (!this.stalled.compareAndSet(false, true)) {
            return;
        }
        this.checker.cancel(false);
        try {
            this.abortTarget.close();
        } catch (IOException ignored) {
            // The abort is best-effort; the reading thread will fail on its own.
        }
    }

    @Override
    public void close() {
        this.checker.cancel(false);
    }
}
