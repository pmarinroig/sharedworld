package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Responsibility:
 * Watch the authoritative backend runtime while the player is connected as a guest, so the
 * client exits promptly through the coordinated rejoin flow when the hosting session ends,
 * instead of hanging until the vanilla connection timeout.
 *
 * Preconditions:
 * Watching only applies while an active guest play session is connected and no host/release
 * flow owns the local client.
 *
 * Postconditions:
 * At most one departure fires per guest session, and it always routes through the session
 * coordinator's host-departure rejoin flow.
 *
 * Stale-work rule:
 * Observations are dropped once the watched world changed, the session ended, or a departure
 * already fired. Poll failures (including revoked/deleted, which the presence manager owns)
 * never trigger a departure: only an authoritative runtime observation may.
 *
 * Authority source:
 * The backend runtime status for the connected world, compared against the joined runtime epoch.
 */
public final class SharedWorldGuestRuntimeWatcher implements link.sharedworld.realtime.RealtimeEvents.Subscriber {
    private static final long POLL_INTERVAL_MS = 5_000L;
    /** Departure detection may lag by at most this much under server throttling. */
    private static final long MAX_SUGGESTED_POLL_INTERVAL_MS = 60_000L;
    /**
     * While the realtime channel is connected, runtime changes arrive as
     * pushed events and polling is only the safety net — so it slows to
     * this cadence. Disconnection snaps it back to the default instantly.
     */
    private static final long PUSH_CONNECTED_POLL_INTERVAL_MS = 60_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(SharedWorldGuestRuntimeWatcher.class);

    private final RuntimeStatusBackend backend;
    private final Executor backgroundExecutor;
    private final Executor mainThreadExecutor;
    private final DepartureHandler departureHandler;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile String activeWorldId;
    private volatile long lastPollAt;
    private volatile long pollIntervalMs = POLL_INTERVAL_MS;
    private volatile boolean departed;
    private volatile boolean pushConnected;

    public SharedWorldGuestRuntimeWatcher(SharedWorldApiClient apiClient) {
        this(
                apiClient::runtimeStatus,
                SharedWorldClient.ioExecutor(),
                runnable -> Minecraft.getInstance().execute(runnable),
                SharedWorldGuestRuntimeWatcher::handleHostDeparture
        );
    }

    SharedWorldGuestRuntimeWatcher(
            RuntimeStatusBackend backend,
            Executor backgroundExecutor,
            Executor mainThreadExecutor,
            DepartureHandler departureHandler
    ) {
        this.backend = backend;
        this.backgroundExecutor = backgroundExecutor;
        this.mainThreadExecutor = mainThreadExecutor;
        this.departureHandler = departureHandler;
    }

    public void tick(Minecraft client) {
        SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
        if (session == null || session.role() != SharedWorldPlaySessionTracker.SessionRole.GUEST) {
            clearActiveWorld(session == null ? null : session.worldId());
            return;
        }
        if (client.level == null || client.getConnection() == null) {
            return;
        }
        if (SharedWorldClient.hostingManager().phase() != link.sharedworld.host.SharedWorldHostingManager.Phase.IDLE) {
            return;
        }
        if (SharedWorldClient.releaseCoordinator().isActive()) {
            return;
        }
        tickGuestSession(session, System.currentTimeMillis());
    }

    void tickGuestSession(SharedWorldPlaySessionTracker.ActiveWorldSession session, long now) {
        boolean worldChanged = !session.worldId().equals(this.activeWorldId);
        if (worldChanged) {
            this.activeWorldId = session.worldId();
            this.departed = false;
            this.pollIntervalMs = POLL_INTERVAL_MS;
        } else if (this.departed || now - this.lastPollAt < this.effectivePollIntervalMs()) {
            return;
        }
        if (!this.inFlight.compareAndSet(false, true)) {
            return;
        }
        this.lastPollAt = now;
        this.backgroundExecutor.execute(() -> {
            WorldRuntimeStatusDto status;
            try {
                status = this.backend.runtimeStatus(session.worldId());
            } catch (Exception exception) {
                LOGGER.debug("SharedWorld guest runtime watch poll failed", exception);
                this.inFlight.set(false);
                return;
            }
            this.inFlight.set(false);
            if (status != null) {
                this.pollIntervalMs = link.sharedworld.util.ServerPacing.clampSuggestedInterval(
                        status.suggestedPollIntervalMs(), POLL_INTERVAL_MS, MAX_SUGGESTED_POLL_INTERVAL_MS);
            }
            this.mainThreadExecutor.execute(() -> handleObservation(session, status));
        });
    }

    public void onDisconnect(SharedWorldPlaySessionTracker.ActiveWorldSession session) {
        clearActiveWorld(session == null ? null : session.worldId());
    }

    long effectivePollIntervalMs() {
        return this.pushConnected
                ? Math.max(this.pollIntervalMs, PUSH_CONNECTED_POLL_INTERVAL_MS)
                : this.pollIntervalMs;
    }

    @Override
    public void onRealtimeConnectionChanged(boolean connected) {
        this.pushConnected = connected;
    }

    /** Pushed runtime-changed events accelerate what a poll would observe. */
    @Override
    public void onRealtimeEvent(link.sharedworld.api.SharedWorldModels.RealtimeEventDto event) {
        if (!"runtime-changed".equals(event.kind()) || event.runtime() == null) {
            return;
        }
        SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
        if (session == null || session.role() != SharedWorldPlaySessionTracker.SessionRole.GUEST) {
            return;
        }
        handlePushedRuntime(session, event.worldId(), event.runtime());
    }

    /** Main-thread entry for a pushed status; same gating as a poll result. */
    void handlePushedRuntime(
            SharedWorldPlaySessionTracker.ActiveWorldSession session,
            String worldId,
            WorldRuntimeStatusDto status
    ) {
        if (!session.worldId().equals(worldId) || !worldId.equals(this.activeWorldId)) {
            return;
        }
        handleObservation(session, status);
    }

    private void handleObservation(SharedWorldPlaySessionTracker.ActiveWorldSession session, WorldRuntimeStatusDto status) {
        if (this.departed || !session.worldId().equals(this.activeWorldId)) {
            return;
        }
        SharedWorldGuestRuntimeWatchLogic.Outcome outcome = SharedWorldGuestRuntimeWatchLogic.evaluate(session.runtimeEpoch(), status);
        if (!outcome.isDeparture()) {
            return;
        }
        LOGGER.info(
                "SharedWorld guest runtime watch observed host departure for {} (outcome={}, runtimePhase={}, runtimeEpoch={}, joinedEpoch={})",
                session.worldId(),
                outcome,
                status == null ? null : status.phase(),
                status == null ? null : status.runtimeEpoch(),
                session.runtimeEpoch()
        );
        // One-shot only when the rejoin actually started: a busy session
        // coordinator rejects the dispatch, and this world must be able to
        // fire again on a later poll instead of going silent forever.
        this.departed = this.departureHandler.onHostDeparture(session, outcome);
        if (!this.departed) {
            LOGGER.info("SharedWorld host-departure rejoin was not accepted; the watcher will retry on a later poll.");
        }
    }

    private void clearActiveWorld(String worldId) {
        if (worldId == null || worldId.equals(this.activeWorldId)) {
            this.activeWorldId = null;
            this.lastPollAt = 0L;
            this.pollIntervalMs = POLL_INTERVAL_MS;
            this.departed = false;
        }
    }

    /** Invoked on the main thread; the rejoin coordinator owns every later transition. */
    private static boolean handleHostDeparture(
            SharedWorldPlaySessionTracker.ActiveWorldSession session,
            SharedWorldGuestRuntimeWatchLogic.Outcome outcome
    ) {
        return SharedWorldClient.sessionCoordinator().beginHostDepartureRejoin(
                new JoinMultiplayerScreen(new TitleScreen()),
                session.worldId(),
                session.worldName(),
                session.joinTarget()
        );
    }

    @FunctionalInterface
    interface RuntimeStatusBackend {
        WorldRuntimeStatusDto runtimeStatus(String worldId) throws Exception;
    }

    @FunctionalInterface
    interface DepartureHandler {
        boolean onHostDeparture(
                SharedWorldPlaySessionTracker.ActiveWorldSession session,
                SharedWorldGuestRuntimeWatchLogic.Outcome outcome
        );
    }
}
