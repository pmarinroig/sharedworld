package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

/**
 * Guest presence, socket-native (0.4.1): while the realtime channel is
 * healthy the guest sends a single world-presence frame on session start and
 * after every reconnect — the socket itself is the liveness, so there is NO
 * periodic beat at all. The merged HTTP beat (presence POST answering with
 * runtime + lastSnapshotId) survives as exactly three things: the reconnect
 * resync, the push-triggered kick/deletion probe (403/404 stay the only
 * verdicts), and the 15s fallback lane while the channel is down. Beat
 * responses are fanned to the runtime watcher and cache warmer so the
 * fallback lane needs no other polls.
 */
public final class SharedWorldPresenceManager implements link.sharedworld.realtime.RealtimeEvents.Subscriber {
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    // Must stay well below the backend's 45s presence timeout or throttled
    // guests would flicker offline in the world list.
    private static final long MAX_SUGGESTED_HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(SharedWorldPresenceManager.class);

    private final PresenceSender presenceSender;
    private final ForcedExitHandler forcedExitHandler;
    private final Executor executor;
    private volatile WorldPresenceAnnouncer announcer = (worldId, present) -> {
    };
    private volatile BeatObserver beatObserver = (worldId, response) -> {
    };
    private volatile String activeGuestWorldId;
    private volatile long activeGuestSessionEpoch;
    private volatile long lastHeartbeatAt;
    private volatile long heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS;
    private long nextGuestSessionEpoch = 1L;
    private long nextPresenceSequence = 1L;
    private volatile boolean pushConnected;
    private volatile boolean oneShotBeatRequested;

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

    /** Wired after construction (the push channel is created later). */
    public void setWorldPresenceAnnouncer(WorldPresenceAnnouncer announcer) {
        this.announcer = announcer;
    }

    /**
     * Receives every merged beat response for the active session. Called on
     * the flush executor; the wiring is responsible for hopping to the main
     * thread before touching game state.
     */
    public void setBeatObserver(BeatObserver beatObserver) {
        this.beatObserver = beatObserver;
    }

    /**
     * Responsibility:
     * Maintain guest presence (socket frames + merged beats) without owning join/host/release lifecycle state.
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
            String previousWorldId = this.activeGuestWorldId;
            startGuestSession(worldId);
            if (previousWorldId != null) {
                this.announcer.sendWorldPresence(previousWorldId, false);
            }
            this.announcer.sendWorldPresence(worldId, true);
            // The session-start beat runs regardless of channel state: it
            // establishes legacy presence (fallback + old-host rosters),
            // primes runtime + lastSnapshotId for the consumers, and is the
            // first authoritative membership probe.
        } else if (this.oneShotBeatRequested) {
            // Push-triggered probe (kick/deletion) or reconnect resync.
        } else if (this.pushConnected) {
            // Socket-native steady state: the socket IS the presence.
            return;
        } else if (now - this.lastHeartbeatAt < this.effectiveHeartbeatIntervalMs()) {
            return;
        }

        this.oneShotBeatRequested = false;
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
        // Withdraw over the socket AND with the authoritative exit beat: the
        // frame updates the roster instantly, the beat covers a dead socket
        // and doubles as the final membership probe of the session.
        this.announcer.sendWorldPresence(session.worldId(), false);
        scheduleFlush(new PresenceUpdate(session.worldId(), false, guestSessionEpoch, presenceSequence));
        this.activeGuestWorldId = null;
        this.activeGuestSessionEpoch = 0L;
        this.lastHeartbeatAt = 0L;
    }

    long effectiveHeartbeatIntervalMs() {
        return this.heartbeatIntervalMs;
    }

    @Override
    public void onRealtimeConnectionChanged(boolean connected) {
        this.pushConnected = connected;
        String worldId = this.activeGuestWorldId;
        if (worldId == null) {
            return;
        }
        if (connected) {
            // Reconnect resync: re-announce (the gateway forgot the presence
            // set on close) and fire exactly one beat to re-fence the
            // session, refresh runtime + lastSnapshotId, and re-verify
            // membership after the outage.
            this.announcer.sendWorldPresence(worldId, true);
            this.oneShotBeatRequested = true;
        } else {
            // Fallback lane starts immediately: the socket entry's 15s grace
            // is riding out, and the first legacy beat keeps the roster
            // continuous past it.
            this.oneShotBeatRequested = true;
        }
    }

    /**
     * A pushed membership or deletion change is a TRIGGER, never a verdict:
     * one merged beat probes over HTTP and the authoritative 403/404
     * response drives the existing forced-exit path with all its fencing.
     */
    @Override
    public void onRealtimeEvent(link.sharedworld.api.SharedWorldModels.RealtimeEventDto event) {
        String activeWorldId = this.activeGuestWorldId;
        if (activeWorldId == null || !activeWorldId.equals(event.worldId())) {
            return;
        }
        if ("membership-changed".equals(event.kind()) || "world-deleted".equals(event.kind())) {
            this.oneShotBeatRequested = true;
        }
    }

    private void startGuestSession(String worldId) {
        this.activeGuestWorldId = worldId;
        this.activeGuestSessionEpoch = this.nextGuestSessionEpoch++;
        this.nextPresenceSequence = 1L;
        this.heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS;
        this.oneShotBeatRequested = false;
    }

    private void scheduleFlush(PresenceUpdate update) {
        this.executor.execute(() -> flush(update));
    }

    private void flush(PresenceUpdate update) {
        try {
            link.sharedworld.api.SharedWorldModels.GuestHeartbeatResponseDto response = this.presenceSender.setPresence(update);
            if (response != null && matchesActiveGuestSession(update.guestSessionEpoch(), update.worldId())) {
                this.heartbeatIntervalMs = link.sharedworld.util.ServerPacing.clampSuggestedInterval(
                        response.suggestedIntervalMs(), HEARTBEAT_INTERVAL_MS, MAX_SUGGESTED_HEARTBEAT_INTERVAL_MS);
                if (update.present()) {
                    this.beatObserver.onGuestBeat(update.worldId(), response);
                }
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
    public interface WorldPresenceAnnouncer {
        void sendWorldPresence(String worldId, boolean present);
    }

    @FunctionalInterface
    public interface BeatObserver {
        void onGuestBeat(String worldId, link.sharedworld.api.SharedWorldModels.GuestHeartbeatResponseDto response);
    }

    @FunctionalInterface
    interface PresenceSender {
        /** May return null: older test doubles and offline paths carry no pacing suggestion. */
        link.sharedworld.api.SharedWorldModels.GuestHeartbeatResponseDto setPresence(PresenceUpdate update) throws Exception;
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
