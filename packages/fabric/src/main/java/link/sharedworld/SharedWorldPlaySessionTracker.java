package link.sharedworld;

public final class SharedWorldPlaySessionTracker {
    private PendingGuestSession pendingGuestSession;
    private ActiveSession activeSession;
    private RecoverySession pendingRecoverySession;
    private Object currentConnectionKey;
    private boolean currentConnectionKeyBound;

    public synchronized void beginGuestConnect(String worldId, String worldName, String joinTarget, long runtimeEpoch) {
        this.pendingGuestSession = new PendingGuestSession(worldId, worldName, joinTarget, runtimeEpoch);
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
        this.currentConnectionKey = connectionKey;
        this.currentConnectionKeyBound = true;
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

    public synchronized boolean isActiveSharedWorld() {
        return this.activeSession != null || this.pendingRecoverySession != null;
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

    private record PendingGuestSession(String worldId, String worldName, String joinTarget, long runtimeEpoch) {
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
