package link.sharedworld.host;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HostLifecyclePolicyTest {
    @Test
    void heartbeatPolicyRequiresIntervalAndRetryWindow() {
        assertTrue(HostLifecyclePolicy.shouldAttemptHeartbeat(31_000L, 0L, 0L, 30_000L, 1_000L));
        assertFalse(HostLifecyclePolicy.shouldAttemptHeartbeat(30_500L, 0L, 30_000L, 30_000L, 1_000L));
        assertFalse(HostLifecyclePolicy.shouldAttemptHeartbeat(500L, 0L, 0L, 30_000L, 1_000L));
    }

    @Test
    void startupHeartbeatPolicyExcludesNonStartupPhases() {
        assertTrue(HostLifecyclePolicy.shouldSendStartupHeartbeat(SharedWorldHostingManager.Phase.PREPARING));
        assertTrue(HostLifecyclePolicy.shouldSendStartupHeartbeat(SharedWorldHostingManager.Phase.CONFIRMING_HOST));
        assertFalse(HostLifecyclePolicy.shouldSendStartupHeartbeat(SharedWorldHostingManager.Phase.RUNNING));
        assertFalse(HostLifecyclePolicy.shouldSendStartupHeartbeat(SharedWorldHostingManager.Phase.SAVING));
        assertFalse(HostLifecyclePolicy.shouldSendStartupHeartbeat(SharedWorldHostingManager.Phase.ERROR));
    }

    @Test
    void liveLeasePolicyCoversRunningAndSavingOnly() {
        assertTrue(HostLifecyclePolicy.shouldMaintainLiveLease(SharedWorldHostingManager.Phase.RUNNING));
        assertTrue(HostLifecyclePolicy.shouldMaintainLiveLease(SharedWorldHostingManager.Phase.SAVING));
        assertFalse(HostLifecyclePolicy.shouldMaintainLiveLease(SharedWorldHostingManager.Phase.CONFIRMING_HOST));
        assertFalse(HostLifecyclePolicy.shouldMaintainLiveLease(SharedWorldHostingManager.Phase.RELEASING));
    }

    @Test
    void authorityLossStageTracksHostingPhase() {
        assertEquals(
                SharedWorldReleaseCoordinator.HostAuthorityLossStage.STARTUP,
                HostLifecyclePolicy.authorityLossStage(SharedWorldHostingManager.Phase.CONFIRMING_HOST)
        );
        assertEquals(
                SharedWorldReleaseCoordinator.HostAuthorityLossStage.SNAPSHOT_UPLOAD,
                HostLifecyclePolicy.authorityLossStage(SharedWorldHostingManager.Phase.SAVING)
        );
        assertEquals(
                SharedWorldReleaseCoordinator.HostAuthorityLossStage.LIVE,
                HostLifecyclePolicy.authorityLossStage(SharedWorldHostingManager.Phase.RUNNING)
        );
    }
}
