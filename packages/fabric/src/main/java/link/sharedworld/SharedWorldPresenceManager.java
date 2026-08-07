package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

public final class SharedWorldPresenceManager implements link.sharedworld.realtime.RealtimeEvents.Subscriber {
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    // Must stay well below the backend's 45s presence timeout or throttled
    // guests would flicker offline in the world list.
    private static final long MAX_SUGGESTED_HEARTBEAT_INTERVAL_MS = 30_000L;
    /**
     * While the realtime channel is connected the roster is host-reported
     * and revocation/deletion arrive as pushes, so the self-report heartbeat
     * is only a safety net (it still authoritatively detects revocation on
     * worlds hosted by legacy clients).
     */
    private static final long PUSH_CONNECTED_HEARTBEAT_INTERVAL_MS = 60_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(SharedWorldPresenceManager.class);

    private final PresenceSender presenceSender;
    private final ForcedExitHandler forcedExitHandler;
    private final Executor executor;
    private volatile String activeGuestWorldId;
    private volatile long activeGuestSessionEpoch;
    private volatile long lastHeartbeatAt;
    private volatile long heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS;
    private long nextGuestSessionEpoch = 1L;
    private long nextPresenceSequence = 1L;
    private volatile boolean pushConnected;

    public SharedWorldPresenceManager(SharedWorldApiClient apiClient) {
        this(
                update -> apiClient.setPresence(
                        update.worldId(),
                        update.present(),
                        update.guestSessionEpoch(),
                        update.presenceSequence()
                ),
                SharedWorldClient.ioExecutor(),
                SharedWorldPresenceManager::handleForcedGuestExit
        );
    }

    SharedWorldPresenceManager(PresenceSender presenceSender, Executor executor) {
        this(presenceSender, executor, (reason, worldId) -> {
        });
    }

    SharedWorldPresenceManager(PresenceSender presenceSender, Executor executor, ForcedExitHandler forcedExitHandler) {
        this.presenceSender = presenceSender;
        this.executor = executor;
        this.forcedExitHandler = forcedExitHandler;
    }

    /**
     * Responsibility:
     * Maintain guest presence heartbeats without taking ownership of join/host/release lifecycle state.
     *
     * Preconditions:
     * Presence tracking only applies while the player is actively connected as a guest.
     *
     * Postconditions:
     * The desired present/absent state is eventually flushed in order, with revoked/deleted exits escalated.
     *
     * Stale-work rule:
     * Only the latest desired presence state is kept; older flushes may be superseded before they run.
     *
     * Authority source:
     * Current guest play session, not UI or waiting-flow state.
     */
    public void tick(Minecraft client) {
        SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
        if (session == null || session.role() != SharedWorldPlaySessionTracker.SessionRole.GUEST) {
            return;
        }
        if (client.level == null || client.getConnection() == null) {
            return;
        }

        tickGuestSession(session.worldId(), System.currentTimeMillis());
    }

    void tickGuestSession(String worldId, long now) {
        boolean worldChanged = this.activeGuestWorldId == null || !this.activeGuestWorldId.equals(worldId);
        if (worldChanged) {
            startGuestSession(worldId);
        } else if (now - this.lastHeartbeatAt < this.effectiveHeartbeatIntervalMs()) {
            return;
        }

        this.lastHeartbeatAt = now;
        scheduleFlush(new PresenceUpdate(
                worldId,
                true,
                this.activeGuestSessionEpoch,
                this.nextPresenceSequence++
        ));
    }

    public void onDisconnect(SharedWorldPlaySessionTracker.ActiveWorldSession session) {
        if (session == null || session.role() != SharedWorldPlaySessionTracker.SessionRole.GUEST) {
            this.activeGuestWorldId = null;
            this.activeGuestSessionEpoch = 0L;
            this.lastHeartbeatAt = 0L;
            return;
        }

        long guestSessionEpoch = this.activeGuestWorldId != null && this.activeGuestWorldId.equals(session.worldId())
                ? this.activeGuestSessionEpoch
                : this.nextGuestSessionEpoch++;
        long presenceSequence = this.activeGuestWorldId != null && this.activeGuestWorldId.equals(session.worldId())
                ? this.nextPresenceSequence++
                : 1L;
        scheduleFlush(new PresenceUpdate(session.worldId(), false, guestSessionEpoch, presenceSequence));
        this.activeGuestWorldId = null;
        this.activeGuestSessionEpoch = 0L;
        this.lastHeartbeatAt = 0L;
    }

    long effectiveHeartbeatIntervalMs() {
        return this.pushConnected
                ? Math.max(this.heartbeatIntervalMs, PUSH_CONNECTED_HEARTBEAT_INTERVAL_MS)
                : this.heartbeatIntervalMs;
    }

    @Override
    public void onRealtimeConnectionChanged(boolean connected) {
        this.pushConnected = connected;
    }

    /**
     * A pushed membership or deletion change is a TRIGGER, never a verdict:
     * the next heartbeat probes over HTTP and the authoritative 403/404
     * response drives the existing forced-exit path with all its fencing.
     */
    @Override
    public void onRealtimeEvent(link.sharedworld.api.SharedWorldModels.RealtimeEventDto event) {
        String activeWorldId = this.activeGuestWorldId;
        if (activeWorldId == null || !activeWorldId.equals(event.worldId())) {
            return;
        }
        if ("membership-changed".equals(event.kind()) || "world-deleted".equals(event.kind())) {
            this.lastHeartbeatAt = 0L;
        }
    }

    private void startGuestSession(String worldId) {
        this.activeGuestWorldId = worldId;
        this.activeGuestSessionEpoch = this.nextGuestSessionEpoch++;
        this.nextPresenceSequence = 1L;
        this.heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS;
    }

    private void scheduleFlush(PresenceUpdate update) {
        this.executor.execute(() -> flush(update));
    }

    private void flush(PresenceUpdate update) {
        try {
            link.sharedworld.api.SharedWorldModels.PresenceHeartbeatResponseDto response = this.presenceSender.setPresence(update);
            if (response != null && matchesActiveGuestSession(update.guestSessionEpoch(), update.worldId())) {
                this.heartbeatIntervalMs = link.sharedworld.util.ServerPacing.clampSuggestedInterval(
                        response.suggestedIntervalMs(), HEARTBEAT_INTERVAL_MS, MAX_SUGGESTED_HEARTBEAT_INTERVAL_MS);
            }
        } catch (Exception exception) {
            if (matchesActiveGuestSession(update.guestSessionEpoch(), update.worldId())) {
                if (SharedWorldApiClient.isMembershipRevokedError(exception)) {
                    this.forcedExitHandler.onForcedExit(ForcedExitReason.REVOKED, update.worldId());
                } else if (SharedWorldApiClient.isDeletedWorldError(exception)) {
                    this.forcedExitHandler.onForcedExit(ForcedExitReason.DELETED, update.worldId());
                }
            }
            LOGGER.debug("SharedWorld presence update failed", exception);
        }
    }

    @FunctionalInterface
    interface PresenceSender {
        /** May return null: older test doubles and offline paths carry no pacing suggestion. */
        link.sharedworld.api.SharedWorldModels.PresenceHeartbeatResponseDto setPresence(PresenceUpdate update) throws Exception;
    }

    @FunctionalInterface
    interface ForcedExitHandler {
        void onForcedExit(ForcedExitReason reason, String worldId);
    }

    record PresenceUpdate(String worldId, boolean present, long guestSessionEpoch, long presenceSequence) {
        PresenceUpdate {
            if (worldId == null || worldId.isBlank()) {
                throw new IllegalArgumentException("worldId must not be blank");
            }
            if (guestSessionEpoch <= 0L) {
                throw new IllegalArgumentException("guestSessionEpoch must be positive");
            }
            if (presenceSequence <= 0L) {
                throw new IllegalArgumentException("presenceSequence must be positive");
            }
        }
    }

    enum ForcedExitReason {
        REVOKED,
        DELETED
    }

    /**
     * Responsibility:
     * Escalate guest-side forced exits into the release coordinator instead of disconnecting directly.
     *
     * Preconditions:
     * Presence observed a revoked/deleted backend response for the active guest world.
     *
     * Postconditions:
     * The unified terminal-flow owner now controls disconnect and terminal UI.
     *
     * Stale-work rule:
     * If the guest session already ended or moved to a different world, this becomes a no-op instead
     * of letting a delayed revoke/delete response from world A terminate world B.
     *
     * Authority source:
     * The failed presence world id plus the active guest play session.
     */
    private static void handleForcedGuestExit(ForcedExitReason reason, String worldId) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
            if (!matchesForcedGuestSession(session, worldId)) {
                return;
            }
            if (reason == ForcedExitReason.REVOKED) {
                SharedWorldClient.releaseCoordinator().beginForcedGuestExit(
                        session.worldId(),
                        session.worldName(),
                        link.sharedworld.host.SharedWorldTerminalReasonKind.TERMINATED_REVOKED,
                        "You no longer have access to this Shared World."
                );
                return;
            }
            SharedWorldClient.releaseCoordinator().beginForcedGuestExit(
                    session.worldId(),
                    session.worldName(),
                    link.sharedworld.host.SharedWorldTerminalReasonKind.TERMINATED_DELETED,
                    "This Shared World was deleted while you were connected."
            );
        });
    }

    static boolean matchesForcedGuestSession(SharedWorldPlaySessionTracker.ActiveWorldSession session, String failedWorldId) {
        return session != null
                && session.role() == SharedWorldPlaySessionTracker.SessionRole.GUEST
                && failedWorldId != null
                && failedWorldId.equals(session.worldId());
    }

    private boolean matchesActiveGuestSession(long guestSessionEpoch, String worldId) {
        return guestSessionEpoch > 0L
                && guestSessionEpoch == this.activeGuestSessionEpoch
                && worldId != null
                && worldId.equals(this.activeGuestWorldId);
    }
}
