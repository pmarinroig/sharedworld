package link.sharedworld.host;

import link.sharedworld.api.ResumableBlobUploader;
import link.sharedworld.api.SharedWorldApiClient.SharedWorldApiException;
import link.sharedworld.host.SharedWorldHostingManager.AutosaveFailureKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static link.sharedworld.host.SharedWorldHostingManager.classifyAutosaveFailure;
import static link.sharedworld.host.SharedWorldHostingManager.shouldAnnounceAutosaveFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AutosaveFailureAnnouncementTest {
    private static final long NOW = 1_000_000L;
    private static final long HALF_HOUR = 30 * 60_000L;

    @Test
    void driveFullAndReauthAreAnnouncedOnFirstFailure() {
        assertTrue(shouldAnnounceAutosaveFailure(AutosaveFailureKind.DRIVE_FULL, 1, 0L, null, NOW));
        assertTrue(shouldAnnounceAutosaveFailure(AutosaveFailureKind.DRIVE_REAUTH, 1, 0L, null, NOW));
    }

    @Test
    void genericFailuresStayQuietUntilTheThirdConsecutiveFailure() {
        assertFalse(shouldAnnounceAutosaveFailure(AutosaveFailureKind.GENERIC, 1, 0L, null, NOW));
        assertFalse(shouldAnnounceAutosaveFailure(AutosaveFailureKind.GENERIC, 2, 0L, null, NOW));
        assertTrue(shouldAnnounceAutosaveFailure(AutosaveFailureKind.GENERIC, 3, 0L, null, NOW));
    }

    @Test
    void anAnnouncedEpisodeRemindsOnlyAfterTheReannounceInterval() {
        long announcedAt = NOW;
        assertFalse(shouldAnnounceAutosaveFailure(
                AutosaveFailureKind.DRIVE_FULL, 2, announcedAt, AutosaveFailureKind.DRIVE_FULL, NOW + HALF_HOUR - 1));
        assertTrue(shouldAnnounceAutosaveFailure(
                AutosaveFailureKind.DRIVE_FULL, 7, announcedAt, AutosaveFailureKind.DRIVE_FULL, NOW + HALF_HOUR));
    }

    @Test
    void aChangeOfFailureKindReannouncesWithoutWaitingOutTheInterval() {
        // "backups failing" escalating to "Drive is full" must not sit silent
        // behind the reminder interval.
        assertTrue(shouldAnnounceAutosaveFailure(
                AutosaveFailureKind.DRIVE_FULL, 4, NOW, AutosaveFailureKind.GENERIC, NOW + 1));
    }

    @Test
    void classifiesBackendCodesAndTheDirectUploaderException() {
        assertEquals(AutosaveFailureKind.DRIVE_FULL,
                classifyAutosaveFailure(new SharedWorldApiException("drive_storage_full", "Drive is full.", 403)));
        assertEquals(AutosaveFailureKind.DRIVE_REAUTH,
                classifyAutosaveFailure(new SharedWorldApiException("drive_reauth_required", "Reconnect Drive.", 401)));
        assertEquals(AutosaveFailureKind.GENERIC,
                classifyAutosaveFailure(new IOException("connection reset")));
        // The direct-to-Drive uploader signals a full Drive with its own
        // exception type, wrapped by the sync pipeline.
        assertEquals(AutosaveFailureKind.DRIVE_FULL,
                classifyAutosaveFailure(new IOException("upload failed",
                        new ResumableBlobUploader.DriveStorageFullException())));
    }
}
