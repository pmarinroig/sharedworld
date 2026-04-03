package link.sharedworld.integration;

import link.sharedworld.SharedWorldSessionCoordinator;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import link.sharedworld.integration.support.SharedWorldIntegrationFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class BackendModRaceIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void twoPlayersJoiningCloseTogetherOnlyOneClaimsIdleWorldAgainstRealBackend() throws Exception {
        SharedWorldIntegrationFixtures.ReleasedWorld world = SharedWorldIntegrationFixtures.createReleasedWorld(
                "Integration Idle Race",
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.ALPHA,
                SharedWorldIntegrationBackend.BRAVO
        );

        SharedWorldIntegrationFixtures.SessionFixture alpha = SharedWorldIntegrationFixtures.sessionFixture(SharedWorldIntegrationBackend.ALPHA);
        SharedWorldIntegrationFixtures.SessionFixture bravo = SharedWorldIntegrationFixtures.sessionFixture(SharedWorldIntegrationBackend.BRAVO);

        alpha.coordinator.beginJoin(null, SharedWorldIntegrationBackend.worldSummary(world.world()));
        bravo.coordinator.beginJoin(null, SharedWorldIntegrationBackend.worldSummary(world.world()));

        alpha.async.runNextBackground();
        bravo.async.runNextBackground();
        alpha.async.flushMainThread();
        bravo.async.flushMainThread();

        int totalHostStarts = alpha.hostStartCount() + bravo.hostStartCount();
        assertEquals(1, totalHostStarts);
        assertTrue(
                alpha.clientShell.actions().contains("setScreen:host-acquired")
                        || bravo.clientShell.actions().contains("setScreen:host-acquired")
        );
        assertTrue(
                alpha.clientShell.actions().contains("setScreen:waiting")
                        || bravo.clientShell.actions().contains("setScreen:waiting")
        );
    }

    @Test
    void cancelingChosenCandidatePromotesTheSecondWaiterAgainstRealBackend() throws Exception {
        SharedWorldIntegrationFixtures.HostedWorld hosted = SharedWorldIntegrationFixtures.createHostedWorld(
                "Integration Handoff Race",
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.ALPHA,
                SharedWorldIntegrationBackend.BRAVO
        );

        SharedWorldIntegrationFixtures.SessionFixture alpha = SharedWorldIntegrationFixtures.sessionFixture(SharedWorldIntegrationBackend.ALPHA);
        SharedWorldIntegrationFixtures.SessionFixture bravo = SharedWorldIntegrationFixtures.sessionFixture(SharedWorldIntegrationBackend.BRAVO);

        hosted.ownerClient().beginFinalization(
                hosted.world().id(),
                hosted.assignment().runtimeEpoch(),
                hosted.assignment().hostToken()
        );

        alpha.coordinator.beginJoin(null, SharedWorldIntegrationBackend.worldSummary(hosted.world()));
        bravo.coordinator.beginJoin(null, SharedWorldIntegrationBackend.worldSummary(hosted.world()));
        alpha.async.runUntilIdle();
        bravo.async.runUntilIdle();

        assertNotNull(alpha.coordinator.waitingView());
        assertNotNull(bravo.coordinator.waitingView());

        hosted.ownerClient().completeFinalization(
                hosted.world().id(),
                hosted.assignment().runtimeEpoch(),
                hosted.assignment().hostToken()
        );

        alpha.pollOnce();
        bravo.pollOnce();

        SharedWorldIntegrationFixtures.SessionFixture chosen = alpha.hostStartCount() == 1 ? alpha : bravo;
        SharedWorldIntegrationFixtures.SessionFixture other = chosen == alpha ? bravo : alpha;

        assertEquals(1, chosen.hostStartCount());
        assertEquals(0, other.hostStartCount());

        chosen.releaseLatestHost(false);

        other.pollOnce();
        chosen.pollOnce();
        other.pollOnce();

        assertEquals(1, chosen.hostStartCount());
        assertEquals(1, other.hostStartCount());
        assertTrue(other.clientShell.actions().contains("setScreen:host-acquired"));
        assertTrue(chosen.clientShell.actions().stream().noneMatch(action -> action.equals("connect:join.example")));
    }

    @Test
    void forcedReleaseWhileGuestIsWaitingNeverReconnectsToStaleJoinTargetAgainstRealBackend() throws Exception {
        SharedWorldIntegrationFixtures.HostedWorld hosted = SharedWorldIntegrationFixtures.createHostedWorld(
                "Integration Forced Release Waiter",
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.ALPHA
        );
        SharedWorldIntegrationFixtures.SessionFixture guest = SharedWorldIntegrationFixtures.sessionFixture(SharedWorldIntegrationBackend.ALPHA);

        hosted.ownerClient().beginFinalization(
                hosted.world().id(),
                hosted.assignment().runtimeEpoch(),
                hosted.assignment().hostToken()
        );

        guest.coordinator.beginJoin(null, SharedWorldIntegrationBackend.worldSummary(hosted.world()));
        guest.async.runUntilIdle();

        assertNotNull(guest.coordinator.waitingView());
        assertTrue(guest.clientShell.actions().contains("setScreen:waiting"));

        hosted.ownerClient().releaseHost(
                hosted.world().id(),
                false,
                hosted.assignment().runtimeEpoch(),
                hosted.assignment().hostToken()
        );

        assertTrue(guest.clientShell.actions().stream().noneMatch(action -> action.equals("connect:join.example")));

        guest.pollOnce();

        assertEquals(1, guest.hostStartCount());
        assertTrue(guest.clientShell.actions().contains("setScreen:host-acquired"));
        assertTrue(guest.clientShell.actions().stream().noneMatch(action -> action.equals("connect:join.example")));
    }
}
