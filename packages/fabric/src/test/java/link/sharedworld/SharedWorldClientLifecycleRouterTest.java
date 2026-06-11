package link.sharedworld;

import link.sharedworld.host.SharedWorldReleaseCoordinator;
import link.sharedworld.host.SharedWorldReleasePhase;
import link.sharedworld.host.SharedWorldTerminalReasonKind;
import link.sharedworld.support.SharedWorldCoordinatorHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldClientLifecycleRouterTest {
    @Test
    void revokedTerminalViewUsesLocalizedRevokedCopy() {
        SharedWorldReleaseCoordinator.ReleaseView view = new SharedWorldReleaseCoordinator.ReleaseView(
                "world-1",
                "World",
                SharedWorldReleasePhase.TERMINATED_REVOKED,
                null,
                null,
                SharedWorldTerminalReasonKind.TERMINATED_REVOKED,
                false,
                false,
                false
        );

        assertEquals("screen.sharedworld.revoked_title", SharedWorldClientLifecycleRouter.titleFor(view).getString());
        assertEquals("screen.sharedworld.revoked_detail", SharedWorldClientLifecycleRouter.detailFor(view));
    }

    @Test
    void deletedTerminalViewUsesLocalizedDeletedCopy() {
        SharedWorldReleaseCoordinator.ReleaseView view = new SharedWorldReleaseCoordinator.ReleaseView(
                "world-1",
                "World",
                SharedWorldReleasePhase.TERMINATED_DELETED,
                null,
                null,
                SharedWorldTerminalReasonKind.TERMINATED_DELETED,
                false,
                false,
                false
        );

        assertEquals("screen.sharedworld.deleted_title", SharedWorldClientLifecycleRouter.titleFor(view).getString());
        assertEquals("screen.sharedworld.deleted_detail", SharedWorldClientLifecycleRouter.detailFor(view));
    }

    @Test
    void lifecycleFallbackDetailUsesLocalizedAuthorityLossCopy() {
        SharedWorldReleaseCoordinator.ReleaseView view = new SharedWorldReleaseCoordinator.ReleaseView(
                "world-1",
                "World",
                SharedWorldReleasePhase.ERROR_RECOVERABLE,
                null,
                null,
                SharedWorldTerminalReasonKind.AUTHORITATIVE_LOSS,
                false,
                false,
                false
        );

        assertEquals("screen.sharedworld.lifecycle_authoritative_loss", SharedWorldClientLifecycleRouter.detailFor(view));
    }

    @Test
    void explicitLifecycleErrorMessageStillWinsOverFallbackCopy() {
        SharedWorldReleaseCoordinator.ReleaseView view = new SharedWorldReleaseCoordinator.ReleaseView(
                "world-1",
                "World",
                SharedWorldReleasePhase.ERROR_RECOVERABLE,
                null,
                "Custom error detail",
                SharedWorldTerminalReasonKind.AUTHORITATIVE_LOSS,
                false,
                false,
                false
        );

        assertEquals("Custom error detail", SharedWorldClientLifecycleRouter.detailFor(view));
    }

    @Test
    void completedReleaseIsAcknowledgedOnceBackAtMenu() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));

            assertNotNull(harness.releaseCoordinator.beginGracefulDisconnect(null));
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));

            for (int i = 0; i < 16; i++) {
                harness.tickRelease();
                harness.runUntilIdle();
                SharedWorldReleaseCoordinator.ReleaseView current = harness.releaseCoordinator.view();
                if (current != null && current.phase() == SharedWorldReleasePhase.COMPLETE && !harness.hasPendingWork()) {
                    break;
                }
            }

            assertNotNull(harness.releaseCoordinator.view());
            assertEquals(SharedWorldReleasePhase.COMPLETE, harness.releaseCoordinator.view().phase());
            assertTrue(SharedWorldClientLifecycleRouter.autoAcknowledgeCompletedReleaseAtMenu(
                    harness.releaseCoordinator,
                    false,
                    false
            ));
            assertNull(harness.releaseCoordinator.view());
        } finally {
            harness.close();
        }
    }

}
