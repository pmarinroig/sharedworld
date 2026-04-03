package link.sharedworld;

import link.sharedworld.host.SharedWorldReleaseCoordinator;
import link.sharedworld.host.SharedWorldReleasePhase;
import link.sharedworld.host.SharedWorldTerminalReasonKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void retryPathUsesProvidedParentInsteadOfDefaultSavingScreen() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("user.dir"), "src/main/java/link/sharedworld/SharedWorldClientLifecycleRouter.java"));

        assertTrue(source.contains("setScreen(savingScreen(parent, releaseCoordinator.activeWorldName()))"));
    }

    @Test
    void recoverableErrorActionDoesNotRouteToPendingUploadRecoveryScreen() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("user.dir"), "src/main/java/link/sharedworld/SharedWorldClientLifecycleRouter.java"));

        assertFalse(source.contains("PendingUploadRecoveryScreen"));
        assertFalse(source.contains("resume_upload"));
        assertTrue(source.contains("releaseCoordinator.discardLocalReleaseState()"));
    }

    @Test
    void sharedWorldScreenKeepsNormalJoinAndDeleteLabels() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("user.dir"), "src/main/java/link/sharedworld/screen/SharedWorldScreen.java"));

        assertTrue(source.contains("this.joinButton.setMessage("));
        assertTrue(source.contains("this.deleteButton.setMessage("));
        assertFalse(source.contains("pendingReleaseMatches("));
    }
}
