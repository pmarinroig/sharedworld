package link.sharedworld.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class UncleanShutdownWarningScreenTest {
    @Test
    void localRecoveryUsesRecoverLocalNoticeKey() {
        assertEquals(
                "screen.sharedworld.unclean_shutdown_notice_self_recover_local",
                UncleanShutdownWarningScreen.ownShutdownNoticeKey(true)
        );
    }

    @Test
    void missingLocalRecoveryUsesLatestSnapshotNoticeKey() {
        assertEquals(
                "screen.sharedworld.unclean_shutdown_notice_self_latest_snapshot",
                UncleanShutdownWarningScreen.ownShutdownNoticeKey(false)
        );
    }
}
