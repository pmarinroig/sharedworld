package link.sharedworld.realtime;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import link.sharedworld.api.SharedWorldModels.RealtimeEventDto;
import link.sharedworld.api.SharedWorldModels.RealtimeFrameDto;
import link.sharedworld.api.SharedWorldModels.RoomPlayerDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The one realtime WebSocket per player (0.3.0). Awareness only: pushed
 * events, room presence, and liveness by socket keepalive. Every
 * authoritative write stays an HTTP request through SharedWorldApiClient.
 *
 * Connection state is a signal, never truth: consumers keep their polling
 * fallbacks and merely stretch them while the channel reports connected.
 * The channel reconnects forever with jittered exponential backoff; a
 * deploy killing the socket degrades to fallback polling for a few seconds.
 *
 * Threading: all channel state is confined to the injected single-threaded
 * scheduler. Listener callbacks are dispatched on the injected main-thread
 * executor.
 */
public final class SharedWorldPushChannel {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld");

    /** Wire vocabulary mirrored from shared/src/realtime.ts — keep in sync. */
    public static final int PROTOCOL_VERSION = 1;
    public static final String KEEPALIVE_REQUEST = "sw-keepalive";
    public static final String KEEPALIVE_RESPONSE = "sw-keepalive-ack";

    private static final long KEEPALIVE_INTERVAL_MS = 20_000L;
    private static final long RECONNECT_BASE_DELAY_MS = 1_000L;
    private static final long RECONNECT_MAX_DELAY_MS = 60_000L;

    /** One live socket. Implementations must be safe to close twice. */
    public interface Transport {
        void sendText(String text);

        void close();
    }

    /** Delivered by the transport on its own threads; the channel trampolines. */
    public interface TransportEvents {
        void onMessage(String text);

        void onDisconnect();
    }

    /** Opens one socket; blocking is fine (runs on the channel scheduler). */
    public interface TransportConnector {
        Transport connect(URI uri, String bearerToken, TransportEvents events) throws Exception;
    }

    /** Supplies a fresh session token; blocking is fine. */
    public interface SessionTokenSource {
        String currentToken() throws Exception;
    }

    /** Consumer callbacks, dispatched on the main-thread executor. */
    public interface Listener {
        default void onConnectionChanged(boolean connected) {
        }

        default void onEvent(RealtimeEventDto event) {
        }
    }

    private final URI endpoint;
    private final TransportConnector connector;
    private final SessionTokenSource tokenSource;
    private final ScheduledExecutorService scheduler;
    private final Executor mainThread;
    private final Listener listener;
    private final long keepaliveIntervalMs;
    private final Gson gson = new Gson();

    // Scheduler-confined state.
    private Transport transport;
    private boolean started;
    private int failedAttempts;
    private ScheduledFuture<?> pendingReconnect;
    private ScheduledFuture<?> keepaliveTask;
    private long connectionGeneration;
    private volatile boolean connected;

    public SharedWorldPushChannel(
            String baseUrl,
            TransportConnector connector,
            SessionTokenSource tokenSource,
            ScheduledExecutorService scheduler,
            Executor mainThread,
            Listener listener
    ) {
        this(baseUrl, connector, tokenSource, scheduler, mainThread, listener, KEEPALIVE_INTERVAL_MS);
    }

    SharedWorldPushChannel(
            String baseUrl,
            TransportConnector connector,
            SessionTokenSource tokenSource,
            ScheduledExecutorService scheduler,
            Executor mainThread,
            Listener listener,
            long keepaliveIntervalMs
    ) {
        this.endpoint = websocketEndpoint(baseUrl);
        this.connector = Objects.requireNonNull(connector);
        this.tokenSource = Objects.requireNonNull(tokenSource);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.listener = Objects.requireNonNull(listener);
        this.keepaliveIntervalMs = keepaliveIntervalMs;
    }

    static URI websocketEndpoint(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String ws = lower.startsWith("https://")
                ? "wss://" + trimmed.substring("https://".length())
                : lower.startsWith("http://")
                ? "ws://" + trimmed.substring("http://".length())
                : trimmed;
        return URI.create(ws + "/ws");
    }

    public boolean isConnected() {
        return connected;
    }

    public void start() {
        scheduler.execute(() -> {
            if (started) {
                return;
            }
            started = true;
            failedAttempts = 0;
            connectNow();
        });
    }

    public void stop() {
        scheduler.execute(() -> {
            started = false;
            cancelPending();
            dropTransport(false);
        });
    }

    /** Host-side room report: the FULL current roster (self-healing, not deltas). */
    public void sendHostPlayers(String worldId, long runtimeEpoch, List<RoomPlayerDto> players) {
        scheduler.execute(() -> {
            if (transport == null) {
                return;
            }
            JsonObject frame = new JsonObject();
            frame.addProperty("v", PROTOCOL_VERSION);
            frame.addProperty("type", "host-players");
            frame.addProperty("worldId", worldId);
            frame.addProperty("runtimeEpoch", runtimeEpoch);
            frame.add("players", gson.toJsonTree(players));
            trySend(frame.toString());
        });
    }

    // ------------------------------------------------------------ internals

    private void connectNow() {
        if (!started || transport != null) {
            return;
        }
        final long generation = ++this.connectionGeneration;
        Transport opened;
        try {
            String token = tokenSource.currentToken();
            opened = connector.connect(endpoint, token, new TransportEvents() {
                @Override
                public void onMessage(String text) {
                    scheduler.execute(() -> handleMessage(generation, text));
                }

                @Override
                public void onDisconnect() {
                    scheduler.execute(() -> handleDisconnect(generation));
                }
            });
        } catch (Exception error) {
            failedAttempts++;
            LOGGER.debug("SharedWorld realtime connect failed (attempt {}): {}", failedAttempts, error.toString());
            scheduleReconnect();
            return;
        }
        transport = opened;
        failedAttempts = 0;
        connected = true;
        keepaliveTask = scheduler.scheduleAtFixedRate(
                this::sendKeepalive, keepaliveIntervalMs, keepaliveIntervalMs, TimeUnit.MILLISECONDS);
        LOGGER.info("SharedWorld realtime channel connected");
        mainThread.execute(() -> listener.onConnectionChanged(true));
    }

    private void handleMessage(long generation, String text) {
        if (generation != connectionGeneration || text == null) {
            return;
        }
        if (KEEPALIVE_RESPONSE.equals(text)) {
            return;
        }
        RealtimeFrameDto frame;
        try {
            frame = gson.fromJson(text, RealtimeFrameDto.class);
        } catch (RuntimeException error) {
            LOGGER.debug("SharedWorld realtime frame ignored: {}", error.toString());
            return;
        }
        if (frame == null || frame.type() == null) {
            return;
        }
        if ("event".equals(frame.type()) && frame.event() != null) {
            RealtimeEventDto event = frame.event();
            mainThread.execute(() -> listener.onEvent(event));
        }
    }

    private void handleDisconnect(long generation) {
        if (generation != connectionGeneration) {
            return;
        }
        dropTransport(true);
    }

    private void sendKeepalive() {
        trySend(KEEPALIVE_REQUEST);
    }

    private void trySend(String text) {
        Transport current = transport;
        if (current == null) {
            return;
        }
        try {
            current.sendText(text);
        } catch (RuntimeException error) {
            LOGGER.debug("SharedWorld realtime send failed: {}", error.toString());
            dropTransport(true);
        }
    }

    /** Tear down the current socket; optionally begin reconnecting. */
    private void dropTransport(boolean reconnect) {
        connectionGeneration++;
        if (keepaliveTask != null) {
            keepaliveTask.cancel(false);
            keepaliveTask = null;
        }
        Transport current = transport;
        transport = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException ignored) {
                // Best-effort close of an already-dying socket.
            }
        }
        boolean wasConnected = connected;
        connected = false;
        if (wasConnected) {
            LOGGER.info("SharedWorld realtime channel disconnected");
            mainThread.execute(() -> listener.onConnectionChanged(false));
        }
        if (reconnect && started) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!started || pendingReconnect != null) {
            return;
        }
        long backoff = Math.min(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS << Math.min(failedAttempts, 6));
        long delay = backoff + ThreadLocalRandom.current().nextLong(500);
        pendingReconnect = scheduler.schedule(() -> {
            pendingReconnect = null;
            connectNow();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelPending() {
        if (pendingReconnect != null) {
            pendingReconnect.cancel(false);
            pendingReconnect = null;
        }
    }
}
