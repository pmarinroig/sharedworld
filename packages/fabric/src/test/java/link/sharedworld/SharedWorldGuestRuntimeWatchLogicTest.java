package link.sharedworld;

import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SharedWorldGuestRuntimeWatchLogicTest {
    @Test
    void liveRuntimeWithMatchingEpochContinues() {
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.CONTINUE,
                SharedWorldGuestRuntimeWatchLogic.evaluate(7L, runtime("host-live", 7L))
        );
    }

    @Test
    void liveRuntimeWithUnknownJoinedEpochContinues() {
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.CONTINUE,
                SharedWorldGuestRuntimeWatchLogic.evaluate(0L, runtime("host-live", 9L))
        );
    }

    @Test
    void liveRuntimeWithNewerEpochIsHostChanged() {
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.HOST_CHANGED,
                SharedWorldGuestRuntimeWatchLogic.evaluate(7L, runtime("host-live", 8L))
        );
    }

    @Test
    void startingRuntimeWithNewerEpochIsHostChanged() {
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.HOST_CHANGED,
                SharedWorldGuestRuntimeWatchLogic.evaluate(7L, runtime("host-starting", 8L))
        );
    }

    @Test
    void finalizingRuntimeForJoinedEpochIsHostLeaving() {
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.HOST_LEAVING,
                SharedWorldGuestRuntimeWatchLogic.evaluate(7L, runtime("host-finalizing", 7L))
        );
    }

    @Test
    void finalizingRuntimeWithUnknownJoinedEpochIsStillHostLeaving() {
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.HOST_LEAVING,
                SharedWorldGuestRuntimeWatchLogic.evaluate(0L, runtime("host-finalizing", 7L))
        );
    }

    @Test
    void finalizingRuntimeForDifferentEpochIsHostChanged() {
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.HOST_CHANGED,
                SharedWorldGuestRuntimeWatchLogic.evaluate(7L, runtime("host-finalizing", 8L))
        );
    }

    @Test
    void idleRuntimeIsHostGone() {
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.HOST_GONE,
                SharedWorldGuestRuntimeWatchLogic.evaluate(7L, runtime("idle", 0L))
        );
    }

    @Test
    void handoffWaitingRuntimeIsHostGone() {
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.HOST_GONE,
                SharedWorldGuestRuntimeWatchLogic.evaluate(7L, runtime("handoff-waiting", 0L))
        );
    }

    @Test
    void missingOrUnknownObservationsFailSafeToContinue() {
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.CONTINUE,
                SharedWorldGuestRuntimeWatchLogic.evaluate(7L, null)
        );
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.CONTINUE,
                SharedWorldGuestRuntimeWatchLogic.evaluate(7L, runtime(null, 7L))
        );
        assertEquals(
                SharedWorldGuestRuntimeWatchLogic.Outcome.CONTINUE,
                SharedWorldGuestRuntimeWatchLogic.evaluate(7L, runtime("future-unknown-phase", 7L))
        );
    }

    private static WorldRuntimeStatusDto runtime(String phase, long runtimeEpoch) {
        return new WorldRuntimeStatusDto(
                "world-1",
                phase,
                runtimeEpoch,
                "player-host",
                "Host",
                null,
                null,
                "join.example",
                null,
                null,
                null,
                null
        );
    }
}
