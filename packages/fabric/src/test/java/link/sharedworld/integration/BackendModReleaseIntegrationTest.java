package link.sharedworld.integration;

import link.sharedworld.host.SharedWorldReleasePhase;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import link.sharedworld.integration.support.SharedWorldIntegrationFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class BackendModReleaseIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void crashBeforeFinalizationAckDoesNotSurfaceMenuRecoveryAgainstRealBackend() throws Exception {
        SharedWorldIntegrationFixtures.ReleaseFixture fixture = SharedWorldIntegrationFixtures.releaseFixture(
                SharedWorldIntegrationFixtures.createHostedWorld(
                        "Integration Release Resume Staged",
                        SharedWorldIntegrationBackend.HOST,
                        SharedWorldIntegrationBackend.HOST
                )
        );
        try {
            assertNotNull(fixture.coordinator.beginGracefulDisconnect(null));
            fixture.clientShell.setLocalServerState(false, false, false);
            assertNotNull(fixture.coordinator.onClientDisconnectReturnDisplay(null));

            assertEquals(SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION, fixture.coordinator.view().phase());
            assertEquals(SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION, fixture.storedPhase());

            SharedWorldIntegrationFixtures.ReleaseFixture restarted = fixture.restartDisconnected();
            assertNull(restarted.coordinator.view());
            assertNotNull(restarted.releaseStore.load());
            restarted.close();
        } finally {
            fixture.close();
        }
    }

    @Test
    void crashAfterBackendFinalizationBeginsBeforeVanillaDisconnectDoesNotSurfaceMenuRecoveryAgainstRealBackend() throws Exception {
        SharedWorldIntegrationFixtures.ReleaseFixture fixture = SharedWorldIntegrationFixtures.releaseFixture(
                SharedWorldIntegrationFixtures.createHostedWorld(
                        "Integration Release Resume Finalizing",
                        SharedWorldIntegrationBackend.HOST,
                        SharedWorldIntegrationBackend.HOST
                )
        );
        try {
            assertNotNull(fixture.coordinator.beginGracefulDisconnect(null));

            fixture.coordinator.tick(null);
            fixture.async.runNextBackground();
            fixture.async.flushMainThread();

            assertEquals(SharedWorldReleasePhase.WAITING_FOR_VANILLA_DISCONNECT, fixture.coordinator.view().phase());
            assertEquals(SharedWorldReleasePhase.WAITING_FOR_VANILLA_DISCONNECT, fixture.storedPhase());

            SharedWorldIntegrationFixtures.ReleaseFixture restarted = fixture.restartDisconnected();
            assertNull(restarted.coordinator.view());
            assertNotNull(restarted.releaseStore.load());
            restarted.close();
        } finally {
            fixture.close();
        }
    }

    @Test
    void crashAfterBackendFinalizationBeginsCanBeDiscardedAndStopsFinalizingAgainstRealBackend() throws Exception {
        SharedWorldIntegrationFixtures.ReleaseFixture fixture = SharedWorldIntegrationFixtures.releaseFixture(
                SharedWorldIntegrationFixtures.createHostedWorld(
                        "Integration Release Discard Finalizing",
                        SharedWorldIntegrationBackend.HOST,
                        SharedWorldIntegrationBackend.HOST
                )
        );
        try {
            assertNotNull(fixture.coordinator.beginGracefulDisconnect(null));

            fixture.coordinator.tick(null);
            fixture.async.runNextBackground();
            fixture.async.flushMainThread();

            assertEquals(SharedWorldReleasePhase.WAITING_FOR_VANILLA_DISCONNECT, fixture.coordinator.view().phase());

            SharedWorldIntegrationFixtures.ReleaseFixture restarted = fixture.restartDisconnected();
            assertTrue(restarted.coordinator.abandonPendingReleaseIfMatches(fixture.worldId()));

            assertNull(restarted.releaseStore.load());
            assertEquals(1, restarted.releaseBackend.releaseCalls());
            assertEquals("idle", restarted.hostedWorld().hostClient().runtimeStatus(fixture.worldId()).phase());

            restarted.close();
        } finally {
            fixture.close();
        }
    }

    @Test
    void crashAfterVanillaDisconnectDoesNotSurfaceMenuRecoveryAgainstRealBackend() throws Exception {
        SharedWorldIntegrationFixtures.ReleaseFixture fixture = SharedWorldIntegrationFixtures.releaseFixture(
                SharedWorldIntegrationFixtures.createHostedWorld(
                        "Integration Release Resume Disconnect",
                        SharedWorldIntegrationBackend.HOST,
                        SharedWorldIntegrationBackend.HOST
                )
        );
        try {
            assertNotNull(fixture.coordinator.beginGracefulDisconnect(null));
            fixture.clientShell.setLocalServerState(false, false, false);
            assertNotNull(fixture.coordinator.onClientDisconnectReturnDisplay(null));

            fixture.async.runNextBackground();
            fixture.async.flushMainThread();
            fixture.coordinator.tick(null);

            assertEquals(SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT, fixture.coordinator.view().phase());
            assertEquals(SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT, fixture.storedPhase());
            assertEquals(0, fixture.clientShell.disconnectCalls());

            SharedWorldIntegrationFixtures.ReleaseFixture restarted = fixture.restartDisconnected();
            assertNull(restarted.coordinator.view());
            assertNotNull(restarted.releaseStore.load());
            restarted.close();
        } finally {
            fixture.close();
        }
    }

    @Test
    void crashAfterUploadCompletesDoesNotSurfaceMenuRecoveryAgainstRealBackend() throws Exception {
        SharedWorldIntegrationFixtures.ReleaseFixture fixture = SharedWorldIntegrationFixtures.releaseFixture(
                SharedWorldIntegrationFixtures.createHostedWorld(
                        "Integration Release Resume Uploaded",
                        SharedWorldIntegrationBackend.HOST,
                        SharedWorldIntegrationBackend.HOST
                )
        );
        try {
            assertNotNull(fixture.coordinator.beginGracefulDisconnect(null));
            fixture.clientShell.setLocalServerState(false, false, false);
            assertNotNull(fixture.coordinator.onClientDisconnectReturnDisplay(null));

            fixture.coordinator.tick(null);
            fixture.async.runNextBackground();
            fixture.async.flushMainThread();
            fixture.coordinator.tick(null);
            fixture.async.runNextBackground();
            fixture.async.flushMainThread();

            assertEquals(SharedWorldReleasePhase.COMPLETING_BACKEND_FINALIZATION, fixture.coordinator.view().phase());
            assertEquals(SharedWorldReleasePhase.COMPLETING_BACKEND_FINALIZATION, fixture.storedPhase());
            assertEquals(1, fixture.hostControl.uploadCalls());

            SharedWorldIntegrationFixtures.ReleaseFixture restarted = fixture.restartDisconnected();
            assertNull(restarted.coordinator.view());
            assertNotNull(restarted.releaseStore.load());
            restarted.close();
        } finally {
            fixture.close();
        }
    }

    @Test
    void abandonedFinalizationRejectsStaleUploadWithoutRestoringOlderState() throws Exception {
        SharedWorldIntegrationFixtures.ReleaseFixture fixture = SharedWorldIntegrationFixtures.releaseFixture(
                SharedWorldIntegrationFixtures.createHostedWorld(
                        "Integration Release Stale Upload",
                        SharedWorldIntegrationBackend.OWNER,
                        SharedWorldIntegrationBackend.HOST
                )
        );
        try {
            assertNotNull(fixture.coordinator.beginGracefulDisconnect(null));
            fixture.clientShell.setLocalServerState(false, false, false);
            assertNotNull(fixture.coordinator.onClientDisconnectReturnDisplay(null));

            fixture.coordinator.tick(null);
            fixture.async.runNextBackground();
            fixture.async.flushMainThread();
            assertEquals(SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT, fixture.coordinator.view().phase());
            fixture.ownerClient().abandonFinalization(fixture.worldId());

            fixture.coordinator.tick(null);
            fixture.async.runNextBackground();
            fixture.async.flushMainThread();

            assertEquals(SharedWorldReleasePhase.ERROR_RECOVERABLE, fixture.coordinator.view().phase());
            assertTrue(fixture.coordinator.view().errorMessage().contains("screen.sharedworld.release_upload_snapshot_failed"));
            assertEquals(1, fixture.hostControl.uploadCalls());
        } finally {
            fixture.close();
        }
    }

    @Test
    void deletingWorldDuringGracefulReleaseStopsFurtherRestoreAgainstRealBackend() throws Exception {
        SharedWorldIntegrationFixtures.ReleaseFixture fixture = SharedWorldIntegrationFixtures.releaseFixture(
                SharedWorldIntegrationFixtures.createHostedWorld(
                        "Integration Release Delete",
                        SharedWorldIntegrationBackend.OWNER,
                        SharedWorldIntegrationBackend.HOST
                )
        );
        try {
            assertNotNull(fixture.coordinator.beginGracefulDisconnect(null));
            fixture.clientShell.setLocalServerState(false, false, false);
            assertNotNull(fixture.coordinator.onClientDisconnectReturnDisplay(null));

            fixture.coordinator.tick(null);
            fixture.async.runNextBackground();
            fixture.async.flushMainThread();

            fixture.ownerClient().deleteWorld(fixture.worldId());

            fixture.coordinator.tick(null);
            fixture.async.runNextBackground();
            fixture.async.flushMainThread();

            assertEquals(SharedWorldReleasePhase.TERMINATED_DELETED, fixture.coordinator.view().phase());
            assertEquals(1, fixture.hostControl.uploadCalls());
        } finally {
            fixture.close();
        }
    }

    @Test
    void membershipRevokedAfterBackendFinalizationStartsStillFinishesAndEndsRevoked() throws Exception {
        SharedWorldIntegrationFixtures.ReleaseFixture fixture = SharedWorldIntegrationFixtures.releaseFixture(
                SharedWorldIntegrationFixtures.createHostedWorld(
                        "Integration Release Revoked",
                        SharedWorldIntegrationBackend.OWNER,
                        SharedWorldIntegrationBackend.HOST
                )
        );
        try {
            assertNotNull(fixture.coordinator.beginGracefulDisconnect(null));

            fixture.coordinator.tick(null);
            fixture.async.runNextBackground();
            fixture.async.flushMainThread();

            assertTrue(fixture.hostControl.backendFinalizationStarted());
            fixture.ownerClient().kickMember(
                    fixture.worldId(),
                    SharedWorldIntegrationBackend.HOST.playerUuid()
            );
            fixture.coordinator.onMembershipRevoked();
            fixture.clientShell.setLocalServerState(false, false, false);
            assertNotNull(fixture.coordinator.onClientDisconnectReturnDisplay(null));
            fixture.driveUntilTerminal();

            assertEquals(SharedWorldReleasePhase.TERMINATED_REVOKED, fixture.coordinator.view().phase());
            assertEquals(1, fixture.hostControl.uploadCalls());
            assertEquals(1, fixture.releaseBackend.completeCalls());
        } finally {
            fixture.close();
        }
    }

    @Test
    void membershipRevokedBeforeGracefulReleaseStartsUsesVanillaDisconnectAndEndsRevoked() throws Exception {
        SharedWorldIntegrationFixtures.ReleaseFixture fixture = SharedWorldIntegrationFixtures.releaseFixture(
                SharedWorldIntegrationFixtures.createHostedWorld(
                        "Integration Release Revoked Early",
                        SharedWorldIntegrationBackend.OWNER,
                        SharedWorldIntegrationBackend.HOST
                )
        );
        try {
            fixture.ownerClient().kickMember(
                    fixture.worldId(),
                    SharedWorldIntegrationBackend.HOST.playerUuid()
            );

            fixture.coordinator.onMembershipRevoked();
            fixture.driveUntilTerminal();

            assertEquals(SharedWorldReleasePhase.TERMINATED_REVOKED, fixture.coordinator.view().phase());
            assertTrue(fixture.hostControl.coordinatedReleaseStarted());
            assertTrue(fixture.hostControl.backendFinalizationStarted());
            assertEquals(1, fixture.clientShell.disconnectCalls());
            assertEquals(1, fixture.hostControl.uploadCalls());
            assertEquals(1, fixture.releaseBackend.beginCalls());
            assertEquals(1, fixture.releaseBackend.completeCalls());
        } finally {
            fixture.close();
        }
    }
}
