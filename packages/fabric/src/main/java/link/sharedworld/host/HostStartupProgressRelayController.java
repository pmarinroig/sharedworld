package link.sharedworld.host;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.api.SharedWorldModels.StartupProgressDto;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

final class HostStartupProgressRelayController {
    private static final long MIN_RELAY_INTERVAL_MS = 1_000L;

    private final Object flushLock = new Object();
    private final ProgressSender progressSender;
    private final Executor executor;
    private final LongSupplier clock;
    private RelayCommand desiredState;
    private boolean desiredImmediate;
    private RelayCommand inFlightState;
    private RelayCommand lastDispatchedState;
    private boolean inFlight;
    private long lastDispatchAt;
    private long generation = 1L;

    HostStartupProgressRelayController(ProgressSender progressSender, Executor executor, LongSupplier clock) {
        this.progressSender = progressSender;
        this.executor = executor;
        this.clock = clock;
    }

    void relay(AuthorityContext authority, StartupProgressDto progress) {
        RelayCommand next = RelayCommand.progress(authority, progress, currentGeneration());
        synchronized (this.flushLock) {
            boolean immediate = shouldSendImmediatelyLocked(next);
            if (sameCommand(next, this.lastDispatchedState) || sameCommand(next, this.inFlightState)) {
                this.desiredImmediate = false;
            } else {
                this.desiredImmediate = this.desiredImmediate || immediate;
            }
            this.desiredState = next;
        }

        drain();
    }

    void clear(AuthorityContext authority) {
        RelayCommand next;
        synchronized (this.flushLock) {
            next = RelayCommand.clear(authority, ++this.generation);
            this.desiredState = next;
            this.desiredImmediate = true;
        }

        drain();
    }

    void reset() {
        synchronized (this.flushLock) {
            this.generation += 1L;
            this.desiredState = null;
            this.desiredImmediate = false;
            this.inFlightState = null;
            this.lastDispatchedState = null;
            this.lastDispatchAt = 0L;
        }
    }

    void tick() {
        drain();
    }

    private void drain() {
        RelayCommand next = null;
        synchronized (this.flushLock) {
            if (this.inFlight || this.desiredState == null) {
                return;
            }
            if (sameCommand(this.desiredState, this.lastDispatchedState)) {
                return;
            }

            long now = this.clock.getAsLong();
            if (!this.desiredImmediate && now - this.lastDispatchAt < MIN_RELAY_INTERVAL_MS) {
                return;
            }

            next = this.desiredState;
            this.desiredImmediate = false;
            this.inFlight = true;
            this.inFlightState = next;
            this.lastDispatchAt = now;
            this.lastDispatchedState = next;
        }

        scheduleFlush(next);
    }

    private void scheduleFlush(RelayCommand state) {
        this.executor.execute(() -> flush(state));
    }

    private void flush(RelayCommand state) {
        try {
            if (state.generation() == currentGeneration()) {
                this.progressSender.setHostStartupProgress(
                        state.authority().worldId(),
                        state.authority().runtimeEpoch(),
                        state.authority().hostToken(),
                        state.progress()
                );
            }
        } catch (Exception exception) {
            SharedWorldClient.LOGGER.debug("SharedWorld failed to relay startup progress", exception);
        }

        synchronized (this.flushLock) {
            this.inFlight = false;
            if (this.inFlightState == state) {
                this.inFlightState = null;
            }
        }

        drain();
    }

    private long currentGeneration() {
        synchronized (this.flushLock) {
            return this.generation;
        }
    }

    private boolean shouldSendImmediatelyLocked(RelayCommand next) {
        RelayCommand reference = this.desiredState != null ? this.desiredState : this.lastDispatchedState;
        if (reference == null) {
            return true;
        }
        if (!reference.authority().sameTarget(next.authority())) {
            return true;
        }
        if (reference.progress() == null || next.progress() == null) {
            return true;
        }
        return !Objects.equals(reference.progress().label(), next.progress().label())
                || !Objects.equals(reference.progress().mode(), next.progress().mode());
    }

    private static boolean sameCommand(RelayCommand left, RelayCommand right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.authority().sameTarget(right.authority()) && Objects.equals(left.progress(), right.progress());
    }

    @FunctionalInterface
    interface ProgressSender {
        void setHostStartupProgress(String worldId, long runtimeEpoch, String hostToken, StartupProgressDto progress) throws Exception;
    }

    record AuthorityContext(String worldId, long runtimeEpoch, String hostToken, long attemptGeneration) {
        AuthorityContext {
            if (worldId == null || worldId.isBlank()) {
                throw new IllegalArgumentException("worldId must not be blank");
            }
            if (hostToken == null || hostToken.isBlank()) {
                throw new IllegalArgumentException("hostToken must not be blank");
            }
        }

        private boolean sameTarget(AuthorityContext other) {
            return this.worldId.equals(other.worldId)
                    && this.runtimeEpoch == other.runtimeEpoch
                    && this.attemptGeneration == other.attemptGeneration
                    && this.hostToken.equals(other.hostToken);
        }
    }

    private record RelayCommand(AuthorityContext authority, StartupProgressDto progress, long generation) {
        private RelayCommand {
            Objects.requireNonNull(authority, "authority");
        }

        private static RelayCommand progress(AuthorityContext authority, StartupProgressDto progress, long generation) {
            if (progress == null) {
                throw new IllegalArgumentException("progress must not be null");
            }
            return new RelayCommand(authority, progress, generation);
        }

        private static RelayCommand clear(AuthorityContext authority, long generation) {
            return new RelayCommand(authority, null, generation);
        }
    }
}
