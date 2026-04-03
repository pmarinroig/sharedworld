package link.sharedworld.host;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.support.SharedWorldCoordinatorHarness;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldReleaseCoordinatorTest {
    @Test
    void gracefulLeaveCompletesThroughPersistedCheckpoints() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));

            beginGracefulVanillaDisconnect(harness);

            driveRelease(harness);

            assertEquals(SharedWorldReleasePhase.COMPLETE, harness.releaseCoordinator.view().phase());
            assertEquals(1, harness.hostControl.clearCalls());
            assertEquals(1, harness.releaseBackend.beginCalls());
            assertEquals(1, harness.releaseBackend.completeCalls());
            assertEquals(1, harness.snapshotDriver.uploads().size());
            assertFalse(harness.releaseCoordinator.shouldKeepSavingScreenVisible());
            assertNull(harness.releaseStore.load());
        } finally {
            harness.close();
        }
    }

    @Test
    void crashBeforeFinalizationAckResumesAndCompletesOnRestart() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));

            assertEquals(SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION, harness.releaseCoordinator.view().phase());
            assertNotNull(harness.releaseStore.load());

            SharedWorldCoordinatorHarness restarted = harness.restart();
            restarted.clientShell.setLocalServerState(false, false, false);
            restarted.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            driveRelease(restarted);

            assertNotNull(restarted.releaseCoordinator.view());
            assertEquals(SharedWorldReleasePhase.COMPLETE, restarted.releaseCoordinator.view().phase());
            assertNull(restarted.releaseStore.load());
            assertEquals(1, restarted.releaseBackend.beginCalls());
            assertEquals(1, restarted.releaseBackend.completeCalls());
            assertEquals(1, restarted.snapshotDriver.uploads().size());
            restarted.close();
        } finally {
            harness.close();
        }
    }

    @Test
    void retryAfterTransientUploadFailureDoesNotDuplicateFinalization() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.hostControl.failures().add("upload", new IOException("network down"));

            beginGracefulVanillaDisconnect(harness);
            driveRelease(harness);

            assertEquals(SharedWorldReleasePhase.ERROR_RECOVERABLE, harness.releaseCoordinator.view().phase());
            assertNotNull(harness.releaseStore.load());

            assertTrue(harness.releaseCoordinator.retry());
            driveRelease(harness);

            assertEquals(SharedWorldReleasePhase.COMPLETE, harness.releaseCoordinator.view().phase());
            assertEquals(1, harness.releaseBackend.beginCalls());
            assertEquals(1, harness.releaseBackend.completeCalls());
            assertEquals(1, harness.snapshotDriver.uploads().size());
        } finally {
            harness.close();
        }
    }

    @Test
    void hostAuthorityLossDuringReleaseBecomesRecoverableError() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));

            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.releaseCoordinator.onHostAuthorityLost(
                    harness.hostControl.activeHostSession(),
                    SharedWorldReleaseCoordinator.HostAuthorityLossStage.LIVE,
                    "Shared World lost backend host authority before finalization could begin."
            );

            assertEquals(SharedWorldReleasePhase.ERROR_RECOVERABLE, harness.releaseCoordinator.view().phase());
            assertEquals("Shared World lost backend host authority before finalization could begin.", harness.releaseCoordinator.view().errorMessage());
            assertNotNull(harness.releaseStore.load());
        } finally {
            harness.close();
        }
    }

    @Test
    void localReleaseStateCanBeDiscardedWhenAlreadyBackAtMenu() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));

            assertTrue(harness.releaseCoordinator.canDiscardLocalReleaseState());
            assertTrue(harness.releaseCoordinator.discardLocalReleaseState());
            assertNull(harness.releaseCoordinator.view());
            assertNull(harness.releaseStore.load());
        } finally {
            harness.close();
        }
    }

    @Test
    void startupRecoveryReactivatesPersistedReleaseAfterAsyncCheck() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));

            SharedWorldCoordinatorHarness restarted = harness.restart();
            restarted.clientShell.setLocalServerState(false, false, false);

            restarted.tickRelease();

            assertNull(restarted.releaseCoordinator.view());
            assertNotNull(restarted.releaseStore.load());

            restarted.runUntilIdle();

            assertNotNull(restarted.releaseCoordinator.view());
            assertEquals(SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION, restarted.releaseCoordinator.view().phase());

            restarted.close();
        } finally {
            harness.close();
        }
    }

    @Test
    void discardingPendingReleaseByWorldMatchClearsPersistedRelease() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));

            SharedWorldCoordinatorHarness restarted = harness.restart();
            restarted.clientShell.setLocalServerState(false, false, false);
            restarted.tickRelease();
            restarted.runUntilIdle();

            assertTrue(restarted.releaseCoordinator.discardPendingReleaseIfMatches("world-1"));
            assertNull(restarted.releaseStore.load());

            restarted.close();
        } finally {
            harness.close();
        }
    }

    @Test
    void abandoningPendingReleaseReleasesBackendRuntimeBeforeClearingLocalState() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-finalizing", 7L, null, null));
            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));

            SharedWorldCoordinatorHarness restarted = harness.restart();
            restarted.clientShell.setLocalServerState(false, false, false);
            restarted.tickRelease();
            restarted.runUntilIdle();

            assertTrue(restarted.releaseCoordinator.abandonPendingReleaseIfMatches("world-1"));
            assertEquals(1, restarted.releaseBackend.releaseCalls());
            assertNull(restarted.releaseStore.load());
            assertEquals("idle", restarted.releaseBackend.runtimeStatus("world-1").phase());

            restarted.close();
        } finally {
            harness.close();
        }
    }

    @Test
    void discardingPendingReleaseIgnoresDifferentWorldIds() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));

            SharedWorldCoordinatorHarness restarted = harness.restart();
            restarted.clientShell.setLocalServerState(false, false, false);

            assertFalse(restarted.releaseCoordinator.discardPendingReleaseIfMatches("world-2"));
            assertNotNull(restarted.releaseStore.load());

            restarted.close();
        } finally {
            harness.close();
        }
    }

    @Test
    void startupCleanupClearsRevokedPendingReleaseWithoutShowingUi() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));

            SharedWorldCoordinatorHarness restarted = harness.restart();
            restarted.clientShell.setLocalServerState(false, false, false);
            restarted.releaseBackend.failures().add("runtimeStatus", new SharedWorldApiClient.SharedWorldApiException("membership_revoked", "revoked", 403));

            restarted.tickRelease();
            restarted.runUntilIdle();

            assertNull(restarted.releaseCoordinator.view());
            assertNull(restarted.releaseStore.load());

            restarted.close();
        } finally {
            harness.close();
        }
    }

    @Test
    void pendingReleaseIsOnlyHiddenUntilStartupRecoveryFinishes() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));

            SharedWorldCoordinatorHarness restarted = harness.restart();
            restarted.clientShell.setLocalServerState(false, false, false);

            restarted.tickRelease();

            assertNull(restarted.releaseCoordinator.view());
            assertNotNull(restarted.releaseStore.load());

            restarted.runUntilIdle();

            assertNotNull(restarted.releaseCoordinator.view());
            assertNotNull(restarted.releaseStore.load());

            restarted.close();
        } finally {
            harness.close();
        }
    }

    @Test
    void disconnectCallbackQueuesSavingScreenWhenCalledOffRenderThread() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.clientShell.setRenderThread(false);

            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));

            assertFalse(harness.clientShell.actions().contains("setScreen:saving"));

            harness.clientShell.flushRenderThreadTasks();

            assertTrue(harness.clientShell.actions().contains("setScreen:saving"));
        } finally {
            harness.close();
        }
    }

    @Test
    void deletedWorldDuringReleaseTerminatesWithoutLaterAsyncCorruptingState() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.releaseCoordinator.beginGracefulDisconnect(null);

            harness.tickRelease();
            harness.releaseCoordinator.onWorldDeleted();
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));
            harness.runNextAsync();
            harness.flushMainThread();

            assertEquals(SharedWorldReleasePhase.TERMINATED_DELETED, harness.releaseCoordinator.view().phase());
            assertEquals(0, harness.clientShell.disconnectCalls());
        } finally {
            harness.close();
        }
    }

    @Test
    void membershipRevokedAfterBackendFinalizationStillFinishesAndEndsRevoked() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.releaseCoordinator.beginGracefulDisconnect(null);

            harness.tickRelease();
            harness.runUntilIdle();
            harness.tickRelease();
            harness.runUntilIdle();
            harness.tickRelease();
            harness.runUntilIdle();

            assertTrue(harness.hostControl.backendFinalizationStarted());

            harness.releaseCoordinator.onMembershipRevoked();
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));
            driveRelease(harness);

            assertEquals(SharedWorldReleasePhase.TERMINATED_REVOKED, harness.releaseCoordinator.view().phase());
            assertEquals(1, harness.snapshotDriver.uploads().size());
            assertEquals(1, harness.releaseBackend.completeCalls());
        } finally {
            harness.close();
        }
    }

    @Test
    void membershipRevokedBeforeGracefulReleaseStartsRunsFullReleaseAndEndsRevoked() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));

            harness.releaseCoordinator.onMembershipRevoked();
            driveRelease(harness);

            assertEquals(SharedWorldReleasePhase.TERMINATED_REVOKED, harness.releaseCoordinator.view().phase());
            assertTrue(harness.hostControl.coordinatedReleaseStarted());
            assertTrue(harness.hostControl.backendFinalizationStarted());
            assertEquals(1, harness.clientShell.disconnectCalls());
            assertEquals(1, harness.snapshotDriver.uploads().size());
            assertEquals(1, harness.releaseBackend.beginCalls());
            assertEquals(1, harness.releaseBackend.completeCalls());
            assertEquals(1, harness.hostControl.clearCalls());
        } finally {
            harness.close();
        }
    }

    @Test
    void hostAuthorityLossOutsideGracefulReleaseUsesForcedExitOwner() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(new WorldRuntimeStatusDto("world-1", "host-live", 8L, "player-next", "Next", null, null, "join-next", null, null, null, null));

            harness.releaseCoordinator.onHostAuthorityLost(
                    harness.hostControl.activeHostSession(),
                    SharedWorldReleaseCoordinator.HostAuthorityLossStage.LIVE,
                    "Shared World lost backend host authority while hosting. The local host session is no longer authoritative."
            );

            assertTrue(harness.releaseCoordinator.shouldKeepSavingScreenVisible());
            assertEquals(1, harness.clientShell.disconnectCalls());

            harness.runUntilIdle();
            harness.tickRelease();

            SharedWorldReleaseCoordinator.ReleaseView view = harness.releaseCoordinator.view();
            assertNotNull(view);
            assertEquals(SharedWorldReleasePhase.ERROR_RECOVERABLE, view.phase());
            assertEquals(SharedWorldTerminalReasonKind.AUTHORITATIVE_LOSS, view.errorKind());
            assertFalse(view.blocking());
            assertEquals(1, harness.hostControl.clearCalls());
        } finally {
            harness.close();
        }
    }

    @Test
    void guestMembershipRevokedUsesForcedExitOwner() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.releaseCoordinator.beginForcedGuestExit(
                    "world-1",
                    "World",
                    SharedWorldTerminalReasonKind.TERMINATED_REVOKED,
                    "You no longer have access to this Shared World."
            );

            assertEquals(1, harness.clientShell.disconnectCalls());

            harness.tickRelease();

            SharedWorldReleaseCoordinator.ReleaseView view = harness.releaseCoordinator.view();
            assertNotNull(view);
            assertEquals(SharedWorldReleasePhase.TERMINATED_REVOKED, view.phase());
            assertEquals(SharedWorldTerminalReasonKind.TERMINATED_REVOKED, view.errorKind());
            assertFalse(view.blocking());
        } finally {
            harness.close();
        }
    }

    @Test
    void forcedExitKeepsLifecycleOwnershipUntilAcknowledged() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.releaseCoordinator.beginForcedGuestExit(
                    "world-1",
                    "World",
                    SharedWorldTerminalReasonKind.TERMINATED_REVOKED,
                    "You no longer have access to this Shared World."
            );

            harness.tickRelease();

            SharedWorldReleaseCoordinator.ReleaseView view = harness.releaseCoordinator.view();
            assertNotNull(view);
            assertFalse(view.blocking());

            harness.releaseCoordinator.acknowledgeTerminal();

            assertNull(harness.releaseCoordinator.view());
        } finally {
            harness.close();
        }
    }

    @Test
    void staleForcedExitReconciliationCannotOverwriteNewerTerminalReason() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(new WorldRuntimeStatusDto("world-1", "host-live", 8L, "player-next", "Next", null, null, "join-next", null, null, null, null));

            harness.releaseCoordinator.onHostAuthorityLost(
                    harness.hostControl.activeHostSession(),
                    SharedWorldReleaseCoordinator.HostAuthorityLossStage.LIVE,
                    "Shared World lost backend host authority while hosting."
            );

            harness.releaseCoordinator.beginForcedGuestExit(
                    "world-2",
                    "Other World",
                    SharedWorldTerminalReasonKind.TERMINATED_DELETED,
                    "This Shared World was deleted while you were connected."
            );
            harness.runUntilIdle();
            harness.tickRelease();

            SharedWorldReleaseCoordinator.ReleaseView view = harness.releaseCoordinator.view();
            assertNotNull(view);
            assertEquals("world-2", view.worldId());
            assertEquals(SharedWorldTerminalReasonKind.TERMINATED_DELETED, view.errorKind());
        } finally {
            harness.close();
        }
    }

    @Test
    void leaveWhileAutosaveIsAlreadyInFlightWaitsBeforeUploadingFinalSnapshot() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.hostControl.setBackgroundSaveInFlight(true);

            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));
            harness.tickRelease();
            harness.runNextAsync();
            harness.flushMainThread();
            harness.tickRelease();

            assertEquals(SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT, harness.releaseCoordinator.view().phase());
            assertEquals(0, harness.snapshotDriver.uploads().size());

            harness.hostControl.setBackgroundSaveInFlight(false);
            driveRelease(harness);

            assertEquals(SharedWorldReleasePhase.COMPLETE, harness.releaseCoordinator.view().phase());
        } finally {
            harness.close();
        }
    }

    @Test
    void resumedReleaseSurfacesRecoverableUploadFailureAndCanRetry() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));
            harness.releaseCoordinator.beginGracefulDisconnect(null);
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));

            harness.tickRelease();
            harness.runNextAsync();
            harness.flushMainThread();

            SharedWorldCoordinatorHarness restarted = harness.restart();
            restarted.clientShell.setLocalServerState(false, false, false);
            restarted.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-finalizing", 7L, null, null));
            restarted.hostControl.failures().add("upload", new IOException("network down"));

            driveRelease(restarted);

            assertNotNull(restarted.releaseCoordinator.view());
            assertEquals(SharedWorldReleasePhase.ERROR_RECOVERABLE, restarted.releaseCoordinator.view().phase());
            assertEquals(0, restarted.snapshotDriver.uploads().size());
            assertNotNull(restarted.releaseStore.load());

            assertTrue(restarted.releaseCoordinator.retry());
            driveRelease(restarted);

            assertEquals(SharedWorldReleasePhase.COMPLETE, restarted.releaseCoordinator.view().phase());
            assertEquals(1, restarted.snapshotDriver.uploads().size());
            assertNull(restarted.releaseStore.load());

            restarted.close();
        } finally {
            harness.close();
        }
    }

    @Test
    void clientStoppingShortlyAfterLeaveStillPersistsAndResumesRelease() throws Exception {
        SharedWorldCoordinatorHarness harness = new SharedWorldCoordinatorHarness();
        try {
            harness.hostControl.setActiveHostSession("world-1", "World", 7L, "token-7", "join.example");
            harness.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));

            harness.releaseCoordinator.onClientStopping(null);
            harness.clientShell.setLocalServerState(false, false, false);
            assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));
            assertNotNull(harness.releaseStore.load());

            SharedWorldCoordinatorHarness restarted = harness.restart();
            restarted.clientShell.setLocalServerState(false, false, false);
            restarted.releaseBackend.setRuntime(SharedWorldCoordinatorHarness.runtime("world-1", "host-live", 7L, null, "join.example"));

            driveRelease(restarted);

            assertNotNull(restarted.releaseCoordinator.view());
            assertEquals(SharedWorldReleasePhase.COMPLETE, restarted.releaseCoordinator.view().phase());
            assertNull(restarted.releaseStore.load());
            assertEquals(1, restarted.releaseBackend.beginCalls());
            assertEquals(1, restarted.releaseBackend.completeCalls());

            restarted.close();
        } finally {
            harness.close();
        }
    }

    @Test
    void reconcileTreatsMatchingFinalizingRuntimeAsAlreadyStarted() {
        SharedWorldReleaseStore.ReleaseRecord record = releaseRecord();
        record.runtimeEpoch = 7L;

        SharedWorldReleasePolicy.ResumeDecision decision = SharedWorldReleasePolicy.reconcile(
                record,
                new WorldRuntimeStatusDto("world-1", "host-finalizing", 7L, "player-host", "Host", null, null, null, null, null, null, null)
        );

        assertTrue(decision.backendFinalizationStarted());
        assertFalse(decision.backendFinalizationCompleted());
        assertNull(decision.recoverableError());
    }

    @Test
    void reconcileTreatsAdvancedRuntimeAsCompletedAfterBackendFinalizationStarted() {
        SharedWorldReleaseStore.ReleaseRecord record = releaseRecord();
        record.runtimeEpoch = 7L;
        record.backendFinalizationStarted = true;
        record.phase = SharedWorldReleasePhase.COMPLETING_BACKEND_FINALIZATION;

        SharedWorldReleasePolicy.ResumeDecision decision = SharedWorldReleasePolicy.reconcile(
                record,
                new WorldRuntimeStatusDto("world-1", "handoff-waiting", 8L, "player-next", "Next", "player-next", "Next", null, null, null, null, null)
        );

        assertTrue(decision.backendFinalizationStarted());
        assertTrue(decision.backendFinalizationCompleted());
        assertNull(decision.recoverableError());
    }

    @Test
    void reconcileClearsObsoleteReleaseWhenRuntimeEpochMovedOn() {
        SharedWorldReleaseStore.ReleaseRecord record = releaseRecord();
        record.runtimeEpoch = 7L;
        record.phase = SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION;

        SharedWorldReleasePolicy.ResumeDecision decision = SharedWorldReleasePolicy.reconcile(
                record,
                new WorldRuntimeStatusDto("world-1", "host-live", 9L, "player-other", "Other", null, null, "join.example", null, null, null, null)
        );

        assertFalse(decision.backendFinalizationStarted());
        assertFalse(decision.backendFinalizationCompleted());
        assertNull(decision.recoverableError());
        assertTrue(decision.clearBecauseObsoleteRecord());
        assertNotNull(decision.obsoleteRecordMessage());
    }

    @Test
    void reconcileClearsPreFinalizationRecordWhenRuntimeIsAlreadyIdle() {
        SharedWorldReleaseStore.ReleaseRecord record = releaseRecord();
        record.phase = SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION;

        SharedWorldReleasePolicy.ResumeDecision decision = SharedWorldReleasePolicy.reconcile(
                record,
                new WorldRuntimeStatusDto("world-1", "idle", 0L, null, null, null, null, null, null, null, null, null)
        );

        assertFalse(decision.backendFinalizationStarted());
        assertFalse(decision.backendFinalizationCompleted());
        assertNull(decision.recoverableError());
        assertTrue(decision.clearBecauseObsoleteRecord());
        assertNotNull(decision.obsoleteRecordMessage());
    }

    @Test
    void releaseRecordCopyPreservesPendingTerminalPhase() {
        SharedWorldReleaseStore.ReleaseRecord record = releaseRecord();
        record.pendingTerminalPhase = SharedWorldReleasePhase.TERMINATED_REVOKED;

        SharedWorldReleaseStore.ReleaseRecord copy = record.copy();

        assertEquals(SharedWorldReleasePhase.TERMINATED_REVOKED, copy.pendingTerminalPhase);
    }

    private static void driveRelease(SharedWorldCoordinatorHarness harness) {
        for (int i = 0; i < 16; i++) {
            harness.tickRelease();
            harness.runUntilIdle();
            SharedWorldReleaseCoordinator.ReleaseView view = harness.releaseCoordinator.view();
            if (view != null && (view.phase() == SharedWorldReleasePhase.COMPLETE
                    || view.phase() == SharedWorldReleasePhase.TERMINATED_DELETED
                    || view.phase() == SharedWorldReleasePhase.TERMINATED_REVOKED
                    || view.phase() == SharedWorldReleasePhase.ERROR_RECOVERABLE) && !harness.hasPendingWork()) {
                return;
            }
        }
    }

    private static void beginGracefulVanillaDisconnect(SharedWorldCoordinatorHarness harness) {
        assertNotNull(harness.releaseCoordinator.beginGracefulDisconnect(null));
        harness.clientShell.setLocalServerState(false, false, false);
        assertNotNull(harness.releaseCoordinator.onClientDisconnectReturnDisplay(null));
    }

    private static SharedWorldReleaseStore.ReleaseRecord releaseRecord() {
        SharedWorldReleaseStore.ReleaseRecord record = new SharedWorldReleaseStore.ReleaseRecord();
        record.worldId = "world-1";
        record.worldName = "World";
        record.hostUuid = "player-host";
        record.hostToken = "token";
        record.releaseAttemptId = 1L;
        record.phase = SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION;
        record.createdAt = "2026-01-01T00:00:00Z";
        record.updatedAt = "2026-01-01T00:00:00Z";
        return record;
    }
}
