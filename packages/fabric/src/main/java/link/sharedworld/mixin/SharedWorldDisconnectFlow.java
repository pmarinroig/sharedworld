package link.sharedworld.mixin;

import link.sharedworld.SharedWorldPlaySessionTracker;

final class SharedWorldDisconnectFlow {
    private SharedWorldDisconnectFlow() {
    }

    static DisconnectAction decide(
            boolean releasePassThroughArmed,
            boolean localServer,
            boolean hasActiveHostSession,
            SharedWorldPlaySessionTracker.ActiveWorldSession session
    ) {
        if (releasePassThroughArmed) {
            return DisconnectAction.IGNORE_PASS_THROUGH;
        }
        if (!localServer) {
            if (isGuestSession(session)) {
                return DisconnectAction.GUEST_ONLY;
            }
            return DisconnectAction.NO_SHAREDWORLD_ACTION;
        }
        if (hasActiveHostSession) {
            return DisconnectAction.HOST_GRACEFUL_RELEASE;
        }
        if (isGuestSession(session)) {
            return DisconnectAction.GUEST_ONLY;
        }
        return DisconnectAction.NO_SHAREDWORLD_ACTION;
    }

    private static boolean isGuestSession(SharedWorldPlaySessionTracker.ActiveWorldSession session) {
        return session != null && session.role() == SharedWorldPlaySessionTracker.SessionRole.GUEST;
    }

    enum DisconnectAction {
        IGNORE_PASS_THROUGH,
        GUEST_ONLY,
        HOST_GRACEFUL_RELEASE,
        NO_SHAREDWORLD_ACTION
    }
}
