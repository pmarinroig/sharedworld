package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.support.SharedWorldCoordinatorHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldSessionCoordinatorTest {
    @Test
    void enterSessionConnectsImmediately() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.connectResponse(world, 7L, "join.example"));

            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("connect:join.example"));
            assertNull(harness.sessionCoordinator.waitingView());
        } finally {
            harness.close();
        }
    }

    @Test
    void freshJoinLetsBackendMintTheWaiterSessionId() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.waitResponse(world));

            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            assertEquals(1, harness.sessionBackend.enterWaiterSessionIds().size());
            assertNull(harness.sessionBackend.enterWaiterSessionIds().get(0));
            assertNotNull(harness.sessionCoordinator.waitingView());
        } finally {
            harness.close();
        }
    }

    @Test
    void waitingFlowPollsAndThenConnects() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.waitResponse(world));
            harness.sessionBackend.setCurrentObserve(SharedWorldCoordinatorHarness.observeWait(
                    "world-1",
                    "wait-test",
                    SharedWorldCoordinatorHarness.runtime("world-1", "handoff-waiting", 7L, null, null)
            ));

            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("setScreen:waiting"));
            assertNotNull(harness.sessionCoordinator.waitingView());

            harness.sessionBackend.setCurrentObserve(SharedWorldCoordinatorHarness.observeConnect("world-1", 8L, "join.example"));
            harness.advanceTime(1_000L);
            harness.tickSession();
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("connect:join.example"));
            assertNull(harness.recoveryStore.load());
        } finally {
            harness.close();
        }
    }

    @Test
    void immediateConnectFailureShowsJoinErrorAndClearsPendingRuntimeContext() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.connectResponse(world, 7L, "join.example"));
            harness.clientShell.failNextConnect(new IllegalStateException("boom"));

            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("connectFailed:join.example"));
            assertTrue(harness.clientShell.actions().contains("setScreen:join-error"));
            assertNull(harness.sessionCoordinator.waitingView());
            assertNull(harness.recoveryStore.load());

            harness.sessionCoordinator.onUnexpectedGuestDisconnect(new SharedWorldPlaySessionTracker.RecoverySession(
                    "world-1",
                    "World",
                    "join.example"
            ));

            assertEquals(0L, harness.recoveryStore.load().runtimeEpoch());
        } finally {
            harness.close();
        }
    }

    @Test
    void waitingConnectFailureClearsWaitingStateBeforeDeferredRenderThreadFailureAndAllowsRetry() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.waitResponse(world));

            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            harness.clientShell.setRenderThread(false);
            harness.clientShell.failNextConnect(new IllegalStateException("boom"));
            harness.sessionBackend.setCurrentObserve(SharedWorldCoordinatorHarness.observeConnect("world-1", 8L, "join.example"));
            harness.advanceTime(1_000L);
            harness.tickSession();
            harness.runNextAsync();
            harness.async.flushMainThread();

            assertNull(harness.sessionCoordinator.waitingView());
            assertNull(harness.recoveryStore.load());

            harness.sessionCoordinator.cancelWaiting();
            assertNull(harness.sessionCoordinator.waitingView());

            harness.clientShell.flushRenderThreadTasks();

            assertTrue(harness.clientShell.actions().contains("connectFailed:join.example"));
            assertTrue(harness.clientShell.actions().contains("setScreen:join-error"));

            harness.clientShell.setRenderThread(true);
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.connectResponse(world, 9L, "join.retry"));
            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("connect:join.retry"));
        } finally {
            harness.close();
        }
    }

    @Test
    void cancelWaitingClearsRecoveryAndReturnsToParent() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.waitResponse(world));

            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();
            harness.sessionCoordinator.cancelWaiting();
            harness.runUntilIdle();

            assertNull(harness.sessionCoordinator.waitingView());
            assertNull(harness.recoveryStore.load());
            assertTrue(harness.clientShell.actions().contains("clearPlaySession"));
        } finally {
            harness.close();
        }
    }

    @Test
    void crashDuringWaitingAutoResumesFromPersistedRecovery() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.recoveryStore.save(new SharedWorldRecoveryStore.RecoveryRecord("world-1", "World", 7L, "disconnect-recovery", "join.old", null));

            SharedWorldCoordinatorHarness restarted = harness.restart();
            restarted.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.waitResponse(
                    SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner")
            ));
            restarted.clientShell.setLocalServerState(false, false, false);
            restarted.tickSession();
            restarted.runUntilIdle();

            assertTrue(restarted.clientShell.actions().contains("setScreen:waiting"));
            assertEquals("world-1", restarted.sessionCoordinator.waitingView().worldId());
            restarted.close();
        } finally {
            harness.close();
        }
    }

    @Test
    void deletedWorldWhileWaitingShowsErrorScreen() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.waitResponse(world));
            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            harness.sessionBackend.failures().add("observeWaiting", new SharedWorldApiClient.SharedWorldApiException("world_not_found", "deleted", 404));
            harness.advanceTime(1_000L);
            harness.tickSession();
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("setScreen:deleted"));
            assertNull(harness.sessionCoordinator.waitingView());
        } finally {
            harness.close();
        }
    }

    @Test
    void membershipRevokedWhileWaitingShowsRevokedFlow() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.waitResponse(world));
            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            harness.sessionBackend.failures().add("observeWaiting", new SharedWorldApiClient.SharedWorldApiException("membership_revoked", "revoked", 403));
            harness.advanceTime(1_000L);
            harness.tickSession();
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("openRevoked"));
            assertNull(harness.sessionCoordinator.waitingView());
        } finally {
            harness.close();
        }
    }

    @Test
    void waitingPromotionRestartsThroughEnterSessionAndOpensHostAcquiredScreen() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.waitResponse(world));
            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            harness.sessionBackend.setCurrentObserve(SharedWorldCoordinatorHarness.observeRestart(
                    "world-1",
                    new link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto("world-1", "host-starting", 7L, "player-host", "Host", "player-host", "Host", null, null, null, null, null)
            ));
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.hostResponse(world, 7L, "token-7"));
            harness.advanceTime(1_000L);
            harness.tickSession();
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("setScreen:host-acquired"));
            assertEquals(2, harness.sessionBackend.enterCalls());
        } finally {
            harness.close();
        }
    }

    @Test
    void staleRuntimePollAfterCancelIsIgnored() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.waitResponse(world));
            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            harness.sessionBackend.setCurrentObserve(SharedWorldCoordinatorHarness.observeConnect("world-1", 8L, "join.example"));
            harness.advanceTime(1_000L);
            harness.tickSession();

            harness.sessionCoordinator.cancelWaiting();
            harness.runLatestAsync();
            harness.flushMainThread();
            harness.runNextAsync();
            harness.flushMainThread();

            assertNull(harness.sessionCoordinator.waitingView());
            assertTrue(harness.clientShell.actions().contains("clearPlaySession"));
            assertTrue(harness.clientShell.actions().stream().noneMatch(action -> action.equals("connect:join.example")));
        } finally {
            harness.close();
        }
    }

    @Test
    void warnHostShowsWarningScreenAndRelaunchesWithAcknowledgement() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(new link.sharedworld.api.SharedWorldModels.EnterSessionResponseDto(
                    "warn-host",
                    world,
                    null,
                    new link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto(
                            "world-1",
                            "idle",
                            0L,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            new link.sharedworld.api.SharedWorldModels.UncleanShutdownWarningDto(
                                    "player-previous",
                                    "Previous",
                                    "host-finalizing",
                                    java.time.Instant.ofEpochMilli(harness.clock.nowMillis()).toString()
                            )
                    ),
                    null,
                    null
            ));
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.hostResponse(world, 7L, "token-7"));

            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("setScreen:unclean-shutdown-warning"));
            assertEquals(java.util.List.of(false), harness.sessionBackend.enterAcknowledgeFlags());

            harness.sessionCoordinator.acknowledgeUncleanShutdown(harness.parentScreen(), "world-1", "World");
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("setScreen:host-acquired"));
            assertEquals(java.util.List.of(false, true), harness.sessionBackend.enterAcknowledgeFlags());
        } finally {
            harness.close();
        }
    }

    @Test
    void idleObservationRestartsJoinInsteadOfWaitingForever() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.waitResponse(world));
            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            harness.sessionBackend.setCurrentObserve(SharedWorldCoordinatorHarness.observeRestart(
                    "world-1",
                    new link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto("world-1", "idle", 0L, null, null, null, null, null, null, null, null, null)
            ));
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.hostResponse(world, 9L, "token-9"));
            harness.advanceTime(1_000L);
            harness.tickSession();
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("setScreen:host-acquired"));
            assertEquals(2, harness.sessionBackend.enterCalls());
        } finally {
            harness.close();
        }
    }

    @Test
    void restartObservationCanReconnectAfterCandidateLoss() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.waitResponse(world));
            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            harness.sessionBackend.setCurrentObserve(SharedWorldCoordinatorHarness.observeRestart(
                    "world-1",
                    new link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto("world-1", "idle", 0L, null, null, null, null, null, null, null, null, null)
            ));
            harness.sessionBackend.enqueueEnterResponse(SharedWorldCoordinatorHarness.connectResponse(world, 10L, "join.recovered"));
            harness.advanceTime(1_000L);
            harness.tickSession();
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("connect:join.recovered"));
            assertNull(harness.sessionCoordinator.waitingView());
            assertEquals(2, harness.sessionBackend.enterCalls());
        } finally {
            harness.close();
        }
    }

    @Test
    void waitWithoutWaiterSessionEscalatesToJoinError() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            var world = SharedWorldCoordinatorHarness.world("world-1", "World", "player-owner");
            harness.sessionBackend.enqueueEnterResponse(new link.sharedworld.api.SharedWorldModels.EnterSessionResponseDto("wait", world, null, null, null, null));

            harness.sessionCoordinator.beginJoin(harness.parentScreen(), world);
            harness.runUntilIdle();

            assertTrue(harness.clientShell.actions().contains("setScreen:join-error"));
            assertNull(harness.sessionCoordinator.waitingView());
        } finally {
            harness.close();
        }
    }
}
