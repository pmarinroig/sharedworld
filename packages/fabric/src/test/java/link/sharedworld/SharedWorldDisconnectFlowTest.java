package link.sharedworld;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                        new SharedWorldPlaySessionTracker.ActiveWorldSession("world-1", "World", SharedWorldPlaySessionTracker.SessionRole.HOST, null, 0L)
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
                        new SharedWorldPlaySessionTracker.ActiveWorldSession("world-1", "World", SharedWorldPlaySessionTracker.SessionRole.GUEST, "join.example", 7L)
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
                        new SharedWorldPlaySessionTracker.ActiveWorldSession("world-1", "World", SharedWorldPlaySessionTracker.SessionRole.HOST, null, 0L)
                )
        );
    }
}
