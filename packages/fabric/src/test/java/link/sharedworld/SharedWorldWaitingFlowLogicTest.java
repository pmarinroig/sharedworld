package link.sharedworld;

import link.sharedworld.api.SharedWorldModels.ObserveWaitingResponseDto;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldWaitingFlowLogicTest {
    @Test
    void runtimeWithNewJoinTargetConnectsImmediately() {
        SharedWorldWaitingFlowLogic.PollDecision decision = SharedWorldWaitingFlowLogic.evaluateObservation(
                new SharedWorldWaitingFlowLogic.WaitingContext("world-1", "World", "old.target", false),
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
                new SharedWorldWaitingFlowLogic.WaitingContext("world-1", "World", null, true),
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

    @Test
    void discardRemainsHiddenWhileFinalizationActivityIsRecent() {
        boolean canDiscard = SharedWorldWaitingFlowLogic.canDiscardPendingFinalization(
                "owner-uuid",
                "owner-uuid",
                new WorldRuntimeStatusDto(
                        "world-1",
                        "host-finalizing",
                        3L,
                        "host-uuid",
                        "Host",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "2026-01-01T00:00:05Z",
                        new link.sharedworld.api.SharedWorldModels.StartupProgressDto("Uploading changed files", "determinate", 0.5D, "2026-01-01T00:00:20Z")
                ),
                false,
                false,
                java.time.Instant.parse("2026-01-01T00:00:34Z").toEpochMilli()
        );

        assertFalse(canDiscard);
    }

    @Test
    void discardAppearsAfterFifteenSecondsWithoutFinalizationActivity() {
        boolean canDiscard = SharedWorldWaitingFlowLogic.canDiscardPendingFinalization(
                "12345678-1234-1234-1234-123456789abc",
                "12345678123412341234123456789abc",
                new WorldRuntimeStatusDto(
                        "world-1",
                        "host-finalizing",
                        3L,
                        "host-uuid",
                        "Host",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "2026-01-01T00:00:05Z",
                        null
                ),
                false,
                false,
                java.time.Instant.parse("2026-01-01T00:00:21Z").toEpochMilli()
        );

        assertTrue(canDiscard);
    }
}
