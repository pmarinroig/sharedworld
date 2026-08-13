package link.sharedworld;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

/**
 * Responsibility:
 * Own the client's notion of "which SharedWorld session am I in right now".
 *
 * Authority rule (the zombie-session invariant):
 * A tracked session must always describe the CURRENT connection. The fabric
 * PLAY DISCONNECT event is a best-effort input, not the authority — on relayed
 * transports (e4mc dialtone, observed on 26.x) the underlying channel can stay
 * open after a manual quit and the event never fires. Every lifecycle boundary
 * therefore re-establishes the invariant itself: a new PLAY join evicts any
 * guest session bound to a different connection, and a hosting startup evicts
 * any guest session outright (hosting and guesting are mutually exclusive).
 */
public final class SharedWorldPlaySessionTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-session");
    /** A pending guest connect that has not reached PLAY within this window is considered abandoned. */
    static final long PENDING_GUEST_SESSION_TTL_MS = 90_000L;

    private final LongSupplier clock;
    private PendingGuestSession pendingGuestSession;
    private ActiveSession activeSession;
    private RecoverySession pendingRecoverySession;
    private Object currentConnectionKey;
    private boolean currentConnectionKeyBound;

    public SharedWorldPlaySessionTracker() {
        this(System::currentTimeMillis);
    }

    SharedWorldPlaySessionTracker(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized void beginGuestConnect(String worldId, String worldName, String joinTarget, long runtimeEpoch) {
        this.pendingGuestSession = new PendingGuestSession(worldId, worldName, joinTarget, runtimeEpoch, this.clock.getAsLong());
        this.activeSession = null;
        this.pendingRecoverySession = null;
    }

    public synchronized void beginHostSession(String worldId, String worldName) {
        this.pendingGuestSession = null;
        this.pendingRecoverySession = null;
        this.activeSession = new ActiveSession(
                worldId,
                worldName,
                SessionRole.HOST,
                null,
                0L,
                false,
                false,
                this.currentConnectionKey,
                this.currentConnectionKeyBound
        );
    }

    public synchronized void onPlayJoin() {
        onPlayJoin(null);
    }

    public synchronized void onPlayJoin(Object connectionKey) {
        onPlayJoin(connectionKey, false);
    }

    public synchronized void onPlayJoin(Object connectionKey, boolean localServer) {
        this.currentConnectionKey = connectionKey;
        this.currentConnectionKeyBound = true;
        if (this.activeSession != null
                && this.activeSession.role() == SessionRole.GUEST
                && !this.activeSession.matchesConnectionKey(connectionKey)) {
            // A new PLAY connection is proof the tracked guest session's own
            // connection is history, whether or not its disconnect event ever
            // fired. Without this eviction the stale session survives into the
            // new world and lets a runtime push hijack it (e.g. tearing down a
            // freshly started hosting via a phantom "host changed" rejoin).
            LOGGER.warn(
                    "SharedWorld guest session for {} was still tracked when a new connection reached PLAY; evicting the stale session.",
                    this.activeSession.worldId()
            );
            this.activeSession = null;
        }
        if (this.pendingGuestSession != null
                && (localServer || this.clock.getAsLong() - this.pendingGuestSession.armedAtMillis() > PENDING_GUEST_SESSION_TTL_MS)) {
            // A guest connect that never reached PLAY (cancelled ConnectScreen, dead target) leaves its
            // pending session armed; adopting it for a local/integrated or long-stale join would bind
            // SharedWorld guest state to a plain vanilla world.
            this.pendingGuestSession = null;
        }
        if (this.pendingGuestSession == null) {
            if (this.activeSession != null
                    && this.activeSession.role() == SessionRole.HOST
                    && !this.activeSession.connectionKeyBound()) {
                this.activeSession = this.activeSession.withConnectionKey(connectionKey);
            }
            return;
        }
        this.activeSession = new ActiveSession(
                this.pendingGuestSession.worldId(),
                this.pendingGuestSession.worldName(),
                SessionRole.GUEST,
                this.pendingGuestSession.joinTarget(),
                this.pendingGuestSession.runtimeEpoch(),
                true,
                false,
                connectionKey,
                true
        );
        this.pendingGuestSession = null;
    }

    public synchronized RecoverySession onDisconnect() {
        return onDisconnectInternal(null, false);
    }

    public synchronized RecoverySession onDisconnect(Object connectionKey) {
        return onDisconnectInternal(connectionKey, true);
    }

    private RecoverySession onDisconnectInternal(Object connectionKey, boolean requireConnectionMatch) {
        if (this.activeSession == null) {
            if (!requireConnectionMatch) {
                this.pendingGuestSession = null;
                clearCurrentConnectionKey();
            } else if (matchesCurrentConnectionKey(connectionKey)) {
                clearCurrentConnectionKey();
            }
            this.pendingRecoverySession = null;
            return null;
        }
        if (requireConnectionMatch && !this.activeSession.matchesConnectionKey(connectionKey)) {
            return null;
        }
        this.pendingGuestSession = null;
        if (!requireConnectionMatch || matchesCurrentConnectionKey(connectionKey)) {
            clearCurrentConnectionKey();
        }

        if (this.activeSession.role() == SessionRole.HOST || this.activeSession.userInitiatedDisconnect() || !this.activeSession.recoveryEnabled()) {
            this.activeSession = null;
            this.pendingRecoverySession = null;
            return null;
        }

        this.pendingRecoverySession = new RecoverySession(
                this.activeSession.worldId(),
                this.activeSession.worldName(),
                this.activeSession.joinTarget(),
                this.activeSession.runtimeEpoch()
        );
        this.activeSession = null;
        return this.pendingRecoverySession;
    }

    public synchronized void markUserInitiatedDisconnect() {
        this.pendingGuestSession = null;
        this.pendingRecoverySession = null;
        if (this.activeSession != null) {
            this.activeSession = this.activeSession.withUserInitiatedDisconnect(true);
        }
    }

    /**
     * Hosting and guesting are mutually exclusive by definition: the moment a
     * hosting startup begins, any tracked guest session is stale no matter how
     * its connection ended. Called from the hosting startup boundary; pending
     * recovery is preserved (it belongs to the unexpected-disconnect flow, not
     * to this session slot).
     */
    public synchronized void clearGuestSessionForHostStartup() {
        this.pendingGuestSession = null;
        if (this.activeSession != null && this.activeSession.role() == SessionRole.GUEST) {
            LOGGER.warn(
                    "SharedWorld guest session for {} was still tracked when a hosting startup began; evicting the stale session.",
                    this.activeSession.worldId()
            );
            this.activeSession = null;
        }
    }

    public synchronized void clear() {
        this.pendingGuestSession = null;
        this.activeSession = null;
        this.pendingRecoverySession = null;
        clearCurrentConnectionKey();
    }

    public synchronized ActiveWorldSession currentSession() {
        return currentSessionInternal(null, false);
    }

    public synchronized ActiveWorldSession currentSession(Object connectionKey) {
        return currentSessionInternal(connectionKey, true);
    }

    private ActiveWorldSession currentSessionInternal(Object connectionKey, boolean requireConnectionMatch) {
        if (this.activeSession == null) {
            return null;
        }
        if (requireConnectionMatch && !this.activeSession.matchesConnectionKey(connectionKey)) {
            return null;
        }
        return new ActiveWorldSession(
                this.activeSession.worldId(),
                this.activeSession.worldName(),
                this.activeSession.role(),
                this.activeSession.joinTarget(),
                this.activeSession.runtimeEpoch()
        );
    }

    /**
     * Whether the disconnect that is being shown (or is about to be shown) belongs to a SharedWorld
     * guest session. Non-consuming; used to gate the DisconnectedScreen recovery hijack so a vanilla
     * disconnect with a stale persisted recovery record on disk is left alone. Checks the active
     * session too because the screen can be initialized before or after the disconnect event runs.
     */
    public synchronized boolean hasPendingRecovery() {
        return this.pendingRecoverySession != null
                || (this.activeSession != null && this.activeSession.role() == SessionRole.GUEST);
    }

    private boolean matchesCurrentConnectionKey(Object connectionKey) {
        return this.currentConnectionKeyBound && this.currentConnectionKey == connectionKey;
    }

    private void clearCurrentConnectionKey() {
        this.currentConnectionKey = null;
        this.currentConnectionKeyBound = false;
    }

    public synchronized RecoverySession consumePendingRecovery() {
        RecoverySession recoverySession = this.pendingRecoverySession;
        this.pendingRecoverySession = null;
        return recoverySession;
    }

    /** runtimeEpoch is the backend epoch the session connected under; 0 when unknown. */
    public record RecoverySession(String worldId, String worldName, String previousJoinTarget, long runtimeEpoch) {
    }

    /** runtimeEpoch is the backend epoch the session connected under; 0 when unknown. */
    public record ActiveWorldSession(String worldId, String worldName, SessionRole role, String joinTarget, long runtimeEpoch) {
    }

    private record PendingGuestSession(String worldId, String worldName, String joinTarget, long runtimeEpoch, long armedAtMillis) {
    }

    private record ActiveSession(
            String worldId,
            String worldName,
            SessionRole role,
            String joinTarget,
            long runtimeEpoch,
            boolean recoveryEnabled,
            boolean userInitiatedDisconnect,
            Object connectionKey,
            boolean connectionKeyBound
    ) {
        private ActiveSession withUserInitiatedDisconnect(boolean userInitiatedDisconnect) {
            return new ActiveSession(this.worldId, this.worldName, this.role, this.joinTarget, this.runtimeEpoch, this.recoveryEnabled, userInitiatedDisconnect, this.connectionKey, this.connectionKeyBound);
        }

        private ActiveSession withConnectionKey(Object connectionKey) {
            return new ActiveSession(this.worldId, this.worldName, this.role, this.joinTarget, this.runtimeEpoch, this.recoveryEnabled, this.userInitiatedDisconnect, connectionKey, true);
        }

        private boolean matchesConnectionKey(Object connectionKey) {
            return this.connectionKeyBound && this.connectionKey == connectionKey;
        }
    }

    public enum SessionRole {
        HOST,
        GUEST
    }
}
