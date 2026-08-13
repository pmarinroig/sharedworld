package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(SharedWorldGuestRuntimeWatcher.class);

    private final DepartureHandler departureHandler;
    private volatile String activeWorldId;
    private volatile boolean departed;

    public SharedWorldGuestRuntimeWatcher(SharedWorldApiClient apiClient) {
        this(SharedWorldGuestRuntimeWatcher::handleHostDeparture);
    }

    SharedWorldGuestRuntimeWatcher(DepartureHandler departureHandler) {
        this.departureHandler = departureHandler;
    }

    /**
     * Socket-native: the watcher no longer polls at all. Observations arrive
     * as pushed runtime-changed payloads (connected) or as the runtime slice
     * of the presence manager's 15s merged beat (disconnected fallback). The
     * tick survives only for session bookkeeping.
     */
    public void tick(Minecraft client) {
        SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
        if (session == null || session.role() != SharedWorldPlaySessionTracker.SessionRole.GUEST) {
            clearActiveWorld(session == null ? null : session.worldId());
            return;
        }
        if (client.level == null || client.getConnection() == null) {
            return;
        }
        adoptSessionWorld(session);
    }

    /**
     * The session — never a poll — is the authority for "what am I
     * watching". This is also the bootstrap fix: pre-0.4.1, activeWorldId
     * was only assigned by the poll tick, so a session that had not polled
     * yet silently ignored every pushed observation.
     */
    private void adoptSessionWorld(SharedWorldPlaySessionTracker.ActiveWorldSession session) {
        if (!session.worldId().equals(this.activeWorldId)) {
            this.activeWorldId = session.worldId();
            this.departed = false;
        }
    }

    /** Runtime slice of a merged beat (disconnected fallback lane); main thread. */
    public void onMergedObservation(String worldId, WorldRuntimeStatusDto status) {
        SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
        if (session == null || session.role() != SharedWorldPlaySessionTracker.SessionRole.GUEST) {
            return;
        }
        if (!guestWorldIsOpen()) {
            return;
        }
        if (SharedWorldClient.hostingManager().phase() != link.sharedworld.host.SharedWorldHostingManager.Phase.IDLE) {
            return;
        }
        if (SharedWorldClient.releaseCoordinator().isActive()) {
            return;
        }
        observeForSession(session, worldId, status);
    }

    /**
     * The 0.4.1 socket-native rewrite moved observations off the poll tick,
     * and this gate did not come with it: a departure observation only means
     * anything while the player is actually INSIDE a world. Without it, a
     * zombie session (see SharedWorldPlaySessionTracker keyed-disconnect
     * handling) let a runtime-changed push convert a player idling on the
     * world LIST screen straight into hosting.
     */
    private static boolean guestWorldIsOpen() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && client.getConnection() != null;
    }

    /**
     * Testable seam: the caller vouches that {@code session} is the CURRENT
     * guest session (the production wrappers re-read it under their gates).
     */
    void observeForSession(
            SharedWorldPlaySessionTracker.ActiveWorldSession session,
            String worldId,
            WorldRuntimeStatusDto status
    ) {
        adoptSessionWorld(session);
        handlePushedRuntime(session, worldId, status);
    }

    public void onDisconnect(SharedWorldPlaySessionTracker.ActiveWorldSession session) {
        clearActiveWorld(session == null ? null : session.worldId());
    }

    @Override
    public void onRealtimeConnectionChanged(boolean connected) {
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
        if (!guestWorldIsOpen()) {
            return;
        }
        // Same gates as the merged-beat lane: the two entry paths carry the
        // same kind of observation and MUST be equally guarded. A push that
        // lands while a hosting startup or a release owns the client would
        // otherwise convert a stale guest reading into a rejoin that tears
        // the client's own hosting down.
        if (SharedWorldClient.hostingManager().phase() != link.sharedworld.host.SharedWorldHostingManager.Phase.IDLE) {
            return;
        }
        if (SharedWorldClient.releaseCoordinator().isActive()) {
            return;
        }
        observeForSession(session, event.worldId(), event.runtime());
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
            LOGGER.info("SharedWorld host-departure rejoin was not accepted; the watcher will retry on a later observation.");
        }
    }

    private void clearActiveWorld(String worldId) {
        if (worldId == null || worldId.equals(this.activeWorldId)) {
            this.activeWorldId = null;
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
    interface DepartureHandler {
        boolean onHostDeparture(
                SharedWorldPlaySessionTracker.ActiveWorldSession session,
                SharedWorldGuestRuntimeWatchLogic.Outcome outcome
        );
    }
}
