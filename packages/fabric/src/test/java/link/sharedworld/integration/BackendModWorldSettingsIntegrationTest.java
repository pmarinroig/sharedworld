package link.sharedworld.integration;

import java.util.Map;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import link.sharedworld.integration.support.SharedWorldIntegrationFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level story for world settings: the owner saves them over PUT, the
 * details response echoes them with a bumped revision, and the live host
 * receives them from the very next heartbeat response.
 */
@Tag("integration")
final class BackendModWorldSettingsIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void settingsRoundTripThroughPutAndHeartbeat() throws Exception {
        SharedWorldIntegrationFixtures.HostedWorld hosted = SharedWorldIntegrationFixtures.createHostedWorld(
                "Integration World Settings",
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.GUEST
        );
        SharedWorldApiClient owner = hosted.hostClient();
        String worldId = hosted.world().id();
        long epoch = hosted.assignment().runtimeEpoch();
        String token = hosted.assignment().hostToken();

        SharedWorldModels.HostHeartbeatResponseDto before = owner.heartbeatHost(worldId, epoch, token, "join.example");
        assertNull(before.settings());
        assertEquals(0L, (long) before.settingsRevision());

        SharedWorldModels.WorldSettingsDto settings = new SharedWorldModels.WorldSettingsDto(
                "hard",
                "adventure",
                Map.of("keepInventory", true, "pvp", false)
        );
        SharedWorldModels.WorldDetailsDto saved = owner.putWorldSettings(worldId, settings);
        assertEquals("hard", saved.settings().difficulty());
        assertEquals("adventure", saved.settings().defaultGameMode());
        assertEquals(Boolean.TRUE, saved.settings().gamerules().get("keepInventory"));
        assertEquals(1L, (long) saved.settingsRevision());

        SharedWorldModels.HostHeartbeatResponseDto after = owner.heartbeatHost(worldId, epoch, token, "join.example");
        assertEquals("hard", after.settings().difficulty());
        assertEquals(Boolean.FALSE, after.settings().gamerules().get("pvp"));
        assertEquals(1L, (long) after.settingsRevision());

        // Members cannot change settings; unknown values are rejected.
        SharedWorldApiClient guest = SharedWorldIntegrationBackend.apiClient(SharedWorldIntegrationBackend.GUEST);
        Exception denied = assertThrows(Exception.class, () -> guest.putWorldSettings(
                worldId, new SharedWorldModels.WorldSettingsDto("easy", null, null)));
        assertTrue(denied.getMessage().toLowerCase().contains("owner"), denied.getMessage());
        Exception invalid = assertThrows(Exception.class, () -> owner.putWorldSettings(
                worldId, new SharedWorldModels.WorldSettingsDto("impossible", null, null)));
        assertTrue(invalid.getMessage().toLowerCase().contains("difficulty"), invalid.getMessage());
    }
}
