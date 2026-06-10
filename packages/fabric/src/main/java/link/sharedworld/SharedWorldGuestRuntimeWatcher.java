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
public final class SharedWorldGuestRuntimeWatcher {
    private static final long POLL_INTERVAL_MS = 5_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(SharedWorldGuestRuntimeWatcher.class);

    private final RuntimeStatusBackend backend;
    private final Executor backgroundExecutor;
    private final Executor mainThreadExecutor;
    private final DepartureHandler departureHandler;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile String activeWorldId;
    private volatile long lastPollAt;
    private volatile boolean departed;

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
        } else if (this.departed || now - this.lastPollAt < POLL_INTERVAL_MS) {
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
            this.mainThreadExecutor.execute(() -> handleObservation(session, status));
        });
    }

    public void onDisconnect(SharedWorldPlaySessionTracker.ActiveWorldSession session) {
        clearActiveWorld(session == null ? null : session.worldId());
    }

    private void handleObservation(SharedWorldPlaySessionTracker.ActiveWorldSession session, WorldRuntimeStatusDto status) {
        if (this.departed || !session.worldId().equals(this.activeWorldId)) {
            return;
        }
        SharedWorldGuestRuntimeWatchLogic.Outcome outcome = SharedWorldGuestRuntimeWatchLogic.evaluate(session.runtimeEpoch(), status);
        if (!outcome.isDeparture()) {
            return;
        }
        this.departed = true;
        LOGGER.info(
                "SharedWorld guest runtime watch observed host departure for {} (outcome={}, runtimePhase={}, runtimeEpoch={}, joinedEpoch={})",
                session.worldId(),
                outcome,
                status == null ? null : status.phase(),
                status == null ? null : status.runtimeEpoch(),
                session.runtimeEpoch()
        );
        this.departureHandler.onHostDeparture(session, outcome);
    }

    private void clearActiveWorld(String worldId) {
        if (worldId == null || worldId.equals(this.activeWorldId)) {
            this.activeWorldId = null;
            this.lastPollAt = 0L;
            this.departed = false;
        }
    }

    /** Invoked on the main thread; the rejoin coordinator owns every later transition. */
    private static void handleHostDeparture(
            SharedWorldPlaySessionTracker.ActiveWorldSession session,
            SharedWorldGuestRuntimeWatchLogic.Outcome outcome
    ) {
        SharedWorldClient.sessionCoordinator().beginHostDepartureRejoin(
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
        void onHostDeparture(
                SharedWorldPlaySessionTracker.ActiveWorldSession session,
                SharedWorldGuestRuntimeWatchLogic.Outcome outcome
        );
    }
}
