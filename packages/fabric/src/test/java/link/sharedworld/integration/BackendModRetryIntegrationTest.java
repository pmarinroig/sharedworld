package link.sharedworld.integration;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import link.sharedworld.integration.support.SharedWorldIntegrationFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shutdown-time requests are exactly the ones clients retry after network
 * flaps. A retry of a call that already succeeded must replay success on the
 * real wire instead of surfacing as a lost lease.
 */
@Tag("integration")
final class BackendModRetryIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void retriedShutdownRequestsReplaySuccessOnTheRealWire() throws Exception {
        SharedWorldIntegrationFixtures.HostedWorld hosted = SharedWorldIntegrationFixtures.createHostedWorld(
                "Integration Retry Replay",
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.OWNER
        );
        SharedWorldApiClient host = hosted.hostClient();
        String worldId = hosted.world().id();
        long epoch = hosted.assignment().runtimeEpoch();
        String token = hosted.assignment().hostToken();

        assertEquals("finalizing", host.beginFinalization(worldId, epoch, token).status());
        // The begin retried after a flap; the original already landed.
        assertEquals("finalizing", host.beginFinalization(worldId, epoch, token).status());

        assertEquals("idle", host.completeFinalization(worldId, epoch, token).status());
        // A retried complete and a late release both replay success.
        assertEquals("idle", host.completeFinalization(worldId, epoch, token).status());
        host.releaseHost(worldId, true, epoch, token);

        // The replays never block the next real session: a fresh epoch is claimed.
        var reentered = host.enterSession(worldId);
        assertEquals("host", reentered.action());
        assertNotNull(reentered.assignment());
        assertEquals(epoch + 1, reentered.assignment().runtimeEpoch());
    }
}
