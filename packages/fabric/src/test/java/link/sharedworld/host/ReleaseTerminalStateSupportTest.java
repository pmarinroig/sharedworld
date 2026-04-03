package link.sharedworld.host;

import link.sharedworld.support.SharedWorldCoordinatorHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReleaseTerminalStateSupportTest {
    @Test
    void matchingHostRuntimeIsStillOwnedByCurrentPlayer() {
        assertTrue(
                ReleaseTerminalStateSupport.isRuntimeStillOwnedByHost(
                        "player-host",
                        7L,
                        SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example")
                )
        );
    }

    @Test
    void terminalPhaseMappingRemainsSymmetricForClosedTerminalStates() {
        assertEquals(
                SharedWorldReleasePhase.TERMINATED_DELETED,
                ReleaseTerminalStateSupport.phaseFor(SharedWorldTerminalReasonKind.TERMINATED_DELETED)
        );
        assertEquals(
                SharedWorldTerminalReasonKind.TERMINATED_REVOKED,
                ReleaseTerminalStateSupport.reasonKindForPhase(SharedWorldReleasePhase.TERMINATED_REVOKED)
        );
        assertNull(ReleaseTerminalStateSupport.reasonKindForPhase(SharedWorldReleasePhase.COMPLETE));
    }
}
