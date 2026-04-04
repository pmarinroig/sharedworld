package link.sharedworld.mixin;

import link.sharedworld.SharedWorldPlaySessionTracker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldDisconnectFlowTest {
    @Test
    void hostDisconnectStartsGracefulReleaseFromPauseLikeFlow() {
        assertEquals(
                SharedWorldDisconnectFlow.DisconnectAction.HOST_GRACEFUL_RELEASE,
                SharedWorldDisconnectFlow.decide(false, true, true, null)
        );
    }

    @Test
    void hostDisconnectStartsGracefulReleaseFromNonPauseConfirmLikeFlow() {
        assertEquals(
                SharedWorldDisconnectFlow.DisconnectAction.HOST_GRACEFUL_RELEASE,
                SharedWorldDisconnectFlow.decide(
                        false,
                        true,
                        true,
                        new SharedWorldPlaySessionTracker.ActiveWorldSession("world-1", "World", SharedWorldPlaySessionTracker.SessionRole.HOST, null)
                )
        );
    }

    @Test
    void guestDisconnectDoesNotStartGracefulHostRelease() {
        assertEquals(
                SharedWorldDisconnectFlow.DisconnectAction.GUEST_ONLY,
                SharedWorldDisconnectFlow.decide(
                        false,
                        false,
                        false,
                        new SharedWorldPlaySessionTracker.ActiveWorldSession("world-1", "World", SharedWorldPlaySessionTracker.SessionRole.GUEST, "join.example")
                )
        );
    }

    @Test
    void nonSharedWorldLocalDisconnectDoesNotTriggerSharedWorldHandling() {
        assertEquals(
                SharedWorldDisconnectFlow.DisconnectAction.NO_SHAREDWORLD_ACTION,
                SharedWorldDisconnectFlow.decide(false, true, false, null)
        );
    }

    @Test
    void passThroughDisconnectIsIgnoredEvenForHosts() {
        assertEquals(
                SharedWorldDisconnectFlow.DisconnectAction.IGNORE_PASS_THROUGH,
                SharedWorldDisconnectFlow.decide(
                        true,
                        true,
                        true,
                        new SharedWorldPlaySessionTracker.ActiveWorldSession("world-1", "World", SharedWorldPlaySessionTracker.SessionRole.HOST, null)
                )
        );
    }

    @Test
    void sourceLeavesGuestPresenceCleanupToPlayDisconnectLifecycle() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("user.dir"), "src/main/java/link/sharedworld/mixin/MinecraftDisconnectMixin.java"));

        assertTrue(source.contains("markUserInitiatedDisconnect()"));
        assertFalse(source.contains("presenceManager().onDisconnect"));
    }
}
