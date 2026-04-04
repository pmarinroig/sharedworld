package link.sharedworld.integration.session;

import link.sharedworld.SharedWorldSessionCoordinator;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import link.sharedworld.support.SharedWorldCoordinatorHarness;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class BackendModSessionIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void coordinatorConnectsToLiveHostAgainstRealBackend() throws Exception {
        SharedWorldIntegrationBackend.SessionTestWorld world = SharedWorldIntegrationBackend.createIdleWorldForSessionTests("Integration Connect");
        SharedWorldModels.HostAssignmentDto assignment = hostLive(world.hostClient(), world.world().id(), "join.example");
        SessionFixture guest = SessionFixture.guest(world.guestClient());

        guest.coordinator.beginJoin(null, SharedWorldIntegrationBackend.worldSummary(world.world()));
        guest.async.runUntilIdle();

        assertTrue(assignment.runtimeEpoch() > 0L);
        assertTrue(guest.clientShell.actions().contains("connect:join.example"));
        assertNull(guest.coordinator.waitingView());
    }

    @Test
    void coordinatorWaitsThroughFinalizationAndClaimsHostAgainstRealBackend() throws Exception {
        SharedWorldIntegrationBackend.SessionTestWorld world = SharedWorldIntegrationBackend.createIdleWorldForSessionTests("Integration Handoff");
        SharedWorldModels.HostAssignmentDto assignment = hostLive(world.hostClient(), world.world().id(), "join.example");
        SessionFixture guest = SessionFixture.guest(world.guestClient());

        world.hostClient().beginFinalization(world.world().id(), assignment.runtimeEpoch(), assignment.hostToken());
        guest.coordinator.beginJoin(null, SharedWorldIntegrationBackend.worldSummary(world.world()));
        guest.async.runUntilIdle();
        assertTrue(guest.clientShell.actions().contains("setScreen:waiting"));

        world.hostClient().completeFinalization(world.world().id(), assignment.runtimeEpoch(), assignment.hostToken());

        guest.clock.advance(1_000L);
        guest.coordinator.tick(null);
        guest.async.runUntilIdle();
        guest.clock.advance(1_000L);
        guest.coordinator.tick(null);
        guest.async.runUntilIdle();

        assertTrue(guest.clientShell.actions().contains("setScreen:host-acquired"));
    }

    @Test
    void cancelWaitingClearsStateAgainstRealBackend() throws Exception {
        SharedWorldIntegrationBackend.SessionTestWorld world = SharedWorldIntegrationBackend.createIdleWorldForSessionTests("Integration Cancel");
        SharedWorldModels.HostAssignmentDto assignment = hostLive(world.hostClient(), world.world().id(), "join.example");
        SessionFixture guest = SessionFixture.guest(world.guestClient());

        world.hostClient().beginFinalization(world.world().id(), assignment.runtimeEpoch(), assignment.hostToken());
        guest.coordinator.beginJoin(null, SharedWorldIntegrationBackend.worldSummary(world.world()));
        guest.async.runUntilIdle();

        guest.coordinator.cancelWaiting();
        guest.async.runUntilIdle();
        world.hostClient().completeFinalization(world.world().id(), assignment.runtimeEpoch(), assignment.hostToken());

        guest.clock.advance(1_000L);
        guest.coordinator.tick(null);
        guest.async.runUntilIdle();

        assertNull(guest.coordinator.waitingView());
        assertTrue(guest.clientShell.actions().contains("clearPlaySession"));
    }

    @Test
    void membershipRevokedWhileWaitingUsesRealBackendErrors() throws Exception {
        SharedWorldIntegrationBackend.SessionTestWorld world = SharedWorldIntegrationBackend.createIdleWorldForSessionTests("Integration Revoked");
        SharedWorldModels.HostAssignmentDto assignment = hostLive(world.hostClient(), world.world().id(), "join.example");
        SessionFixture guest = SessionFixture.guest(world.guestClient());

        world.hostClient().beginFinalization(world.world().id(), assignment.runtimeEpoch(), assignment.hostToken());
        guest.coordinator.beginJoin(null, SharedWorldIntegrationBackend.worldSummary(world.world()));
        guest.async.runUntilIdle();

        world.hostClient().kickMember(world.world().id(), SharedWorldIntegrationBackend.GUEST.playerUuid());
        guest.clock.advance(1_000L);
        guest.coordinator.tick(null);
        guest.async.runUntilIdle();

        assertTrue(guest.clientShell.actions().contains("openRevoked"));
    }

    @Test
    void deletedWorldWhileWaitingUsesRealBackendErrors() throws Exception {
        SharedWorldIntegrationBackend.SessionTestWorld world = SharedWorldIntegrationBackend.createIdleWorldForSessionTests("Integration Deleted");
        SharedWorldModels.HostAssignmentDto assignment = hostLive(world.hostClient(), world.world().id(), "join.example");
        SessionFixture guest = SessionFixture.guest(world.guestClient());

        world.hostClient().beginFinalization(world.world().id(), assignment.runtimeEpoch(), assignment.hostToken());
        guest.coordinator.beginJoin(null, SharedWorldIntegrationBackend.worldSummary(world.world()));
        guest.async.runUntilIdle();

        world.hostClient().deleteWorld(world.world().id());
        guest.clock.advance(1_000L);
        guest.coordinator.tick(null);
        guest.async.runUntilIdle();

        assertTrue(guest.clientShell.actions().contains("setScreen:deleted"));
    }

    private static SharedWorldModels.HostAssignmentDto hostLive(
            SharedWorldApiClient hostClient,
            String worldId,
            String joinTarget
    ) throws Exception {
        SharedWorldModels.EnterSessionResponseDto entered = hostClient.enterSession(worldId);
        SharedWorldModels.HostAssignmentDto assignment = entered.assignment();
        hostClient.heartbeatHost(worldId, assignment.runtimeEpoch(), assignment.hostToken(), joinTarget);
        return assignment;
    }

    private static final class SessionFixture {
        private final SharedWorldCoordinatorHarness.DeterministicAsync async;
        private final SharedWorldCoordinatorHarness.FakeClock clock;
        private final SharedWorldCoordinatorHarness.FakeClientShell clientShell;
        private final SharedWorldSessionCoordinator coordinator;

        private SessionFixture(
                SharedWorldCoordinatorHarness.DeterministicAsync async,
                SharedWorldCoordinatorHarness.FakeClock clock,
                SharedWorldCoordinatorHarness.FakeClientShell clientShell,
                SharedWorldSessionCoordinator coordinator
        ) {
            this.async = async;
            this.clock = clock;
            this.clientShell = clientShell;
            this.coordinator = coordinator;
        }

        private static SessionFixture guest(SharedWorldApiClient client) {
            SharedWorldCoordinatorHarness.DeterministicAsync async = new SharedWorldCoordinatorHarness.DeterministicAsync();
            SharedWorldCoordinatorHarness.FakeClock clock = new SharedWorldCoordinatorHarness.FakeClock(1_700_000_000_000L);
            SharedWorldCoordinatorHarness.FakeClientShell clientShell = new SharedWorldCoordinatorHarness.FakeClientShell();
            SharedWorldSessionCoordinator coordinator = new SharedWorldSessionCoordinator(
                    new RealSessionBackend(client),
                    new SharedWorldCoordinatorHarness.InMemoryRecoveryStore(),
                    async,
                    clock,
                    clientShell,
                    new SharedWorldCoordinatorHarness.FakePlayerIdentity(SharedWorldIntegrationBackend.GUEST.playerUuid()),
                    (parent, result, startupMode) -> {
                    },
                    new SharedWorldSessionCoordinator.SessionUi() {
                        @Override
                        public Screen joinError(Screen parent, Throwable error) {
                            clientShell.markNextScreen("join-error");
                            return null;
                        }

                        @Override
                        public Screen hostAcquired(Screen parent, SharedWorldModels.EnterSessionResponseDto result) {
                            clientShell.markNextScreen("host-acquired");
                            return null;
                        }

                        @Override
                        public Screen waiting(Screen parent, String worldId, String worldName, String ownerUuid) {
                            clientShell.markNextScreen("waiting");
                            return null;
                        }

                        @Override
                        public Screen uncleanShutdownWarning(Screen parent, String worldId, String worldName, SharedWorldModels.WorldRuntimeStatusDto runtimeStatus) {
                            clientShell.markNextScreen("unclean-shutdown-warning");
                            return null;
                        }

                        @Override
                        public Screen deleted(Screen parent) {
                            clientShell.markNextScreen("deleted");
                            return null;
                        }
                    }
            );
            return new SessionFixture(async, clock, clientShell, coordinator);
        }
    }

    private static final class RealSessionBackend implements SharedWorldSessionCoordinator.SessionBackend {
        private final SharedWorldApiClient client;

        private RealSessionBackend(SharedWorldApiClient client) {
            this.client = client;
        }

        @Override
        public SharedWorldModels.EnterSessionResponseDto enterSession(String worldId, String waiterSessionId, boolean acknowledgeUncleanShutdown) throws Exception {
            return this.client.enterSession(worldId, waiterSessionId, acknowledgeUncleanShutdown);
        }

        @Override
        public SharedWorldModels.ObserveWaitingResponseDto observeWaiting(String worldId, String waiterSessionId) throws Exception {
            return this.client.observeWaiting(worldId, waiterSessionId);
        }

        @Override
        public SharedWorldModels.WorldRuntimeStatusDto cancelWaiting(String worldId, String waiterSessionId) throws Exception {
            return this.client.cancelWaiting(worldId, waiterSessionId);
        }

        @Override
        public SharedWorldModels.FinalizationActionResultDto abandonFinalization(String worldId) throws Exception {
            return this.client.abandonFinalization(worldId);
        }
    }
}
