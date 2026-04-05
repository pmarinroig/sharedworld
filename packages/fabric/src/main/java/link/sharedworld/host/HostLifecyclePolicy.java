package link.sharedworld.host;

import link.sharedworld.SharedWorldText;

final class HostLifecyclePolicy {
    private HostLifecyclePolicy() {
    }

    static boolean shouldAttemptHeartbeat(long now, long lastHeartbeatAt, long lastHeartbeatAttemptAt, long heartbeatIntervalMs, long retryIntervalMs) {
        return now - lastHeartbeatAt >= heartbeatIntervalMs
                && now - lastHeartbeatAttemptAt >= retryIntervalMs;
    }

    static boolean shouldSendStartupHeartbeat(SharedWorldHostingManager.Phase phase) {
        return phase != SharedWorldHostingManager.Phase.IDLE
                && phase != SharedWorldHostingManager.Phase.ERROR
                && phase != SharedWorldHostingManager.Phase.RUNNING
                && phase != SharedWorldHostingManager.Phase.SAVING
                && phase != SharedWorldHostingManager.Phase.RELEASING
                && phase != SharedWorldHostingManager.Phase.CANCELLING;
    }

    static boolean shouldMaintainLiveLease(SharedWorldHostingManager.Phase phase) {
        return phase == SharedWorldHostingManager.Phase.RUNNING
                || phase == SharedWorldHostingManager.Phase.SAVING;
    }

    static SharedWorldReleaseCoordinator.HostAuthorityLossStage authorityLossStage(SharedWorldHostingManager.Phase phase) {
        return phase == SharedWorldHostingManager.Phase.CONFIRMING_HOST
                ? SharedWorldReleaseCoordinator.HostAuthorityLossStage.STARTUP
                : phase == SharedWorldHostingManager.Phase.SAVING
                ? SharedWorldReleaseCoordinator.HostAuthorityLossStage.SNAPSHOT_UPLOAD
                : SharedWorldReleaseCoordinator.HostAuthorityLossStage.LIVE;
    }

    static String runningStatusMessage(String publishedJoinTarget) {
        return publishedJoinTarget == null
                ? SharedWorldText.string("screen.sharedworld.hosting_running")
                : SharedWorldText.string("screen.sharedworld.hosting_live_at", publishedJoinTarget);
    }
}
