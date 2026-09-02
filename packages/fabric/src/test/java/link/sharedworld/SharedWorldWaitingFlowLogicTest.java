package link.sharedworld;

import link.sharedworld.api.SharedWorldModels.ObserveWaitingResponseDto;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SharedWorldWaitingFlowLogicTest {
    @Test
    void runtimeWithNewJoinTargetConnectsImmediately() {
        SharedWorldWaitingFlowLogic.PollDecision decision = SharedWorldWaitingFlowLogic.evaluateObservation(
                false,
                new ObserveWaitingResponseDto(
                        "connect",
                        new WorldRuntimeStatusDto("world-1", "host-live", 7L, "player-host", "Host", null, null, "new.target", null, null, null, null),
                        null,
                        null
                )
        );

        assertEquals(SharedWorldWaitingFlowLogic.Outcome.CONNECT, decision.outcome());
        assertEquals("new.target", decision.connectTarget());
        assertEquals(7L, decision.runtimeEpoch());
    }

    @Test
    void hostObservationIsRejectedBecauseWaitingPromotionMustRestart() {
        SharedWorldWaitingFlowLogic.PollDecision decision = SharedWorldWaitingFlowLogic.evaluateObservation(
                true,
                new ObserveWaitingResponseDto(
                        "host",
                        new WorldRuntimeStatusDto("world-1", "host-starting", 8L, "player-host", "Host", "player-host", "Host", null, null, null, null, null),
                        null,
                        null
                )
        );

        assertEquals(SharedWorldWaitingFlowLogic.Outcome.ERROR, decision.outcome());
        assertEquals(
                "screen.sharedworld.waiting_error_unexpected_host",
                decision.errorMessage()
        );
    }
}
