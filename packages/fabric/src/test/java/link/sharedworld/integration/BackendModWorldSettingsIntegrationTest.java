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

    @Test
    void hostGameRuleReportMergesAndSurvivesToTheNextHeartbeat() throws Exception {
        // The GUEST hosts: exactly the writer the owner-only settings PUT
        // cannot cover, which is why the report is runtime-authorized.
        SharedWorldIntegrationFixtures.HostedWorld hosted = SharedWorldIntegrationFixtures.createHostedWorld(
                "Integration Gamerule Report",
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.GUEST
        );
        SharedWorldApiClient owner = hosted.ownerClient();
        SharedWorldApiClient host = hosted.hostClient();
        String worldId = hosted.world().id();
        long epoch = hosted.assignment().runtimeEpoch();
        String token = hosted.assignment().hostToken();

        owner.putWorldSettings(worldId, new SharedWorldModels.WorldSettingsDto(
                "hard", "survival", Map.of("keepInventory", false)));

        SharedWorldModels.HostGameRulesReportResponseDto reported = host.reportHostGameRules(worldId, epoch, token, Map.of("keepInventory", true, "mobGriefing", false), null, null);
        assertEquals(2L, (long) reported.settingsRevision());
        assertEquals("hard", reported.settings().difficulty(), "difficulty survives the merge");
        assertEquals("survival", reported.settings().defaultGameMode(), "game mode survives the merge");
        assertEquals(Boolean.TRUE, reported.settings().gamerules().get("keepInventory"));
        assertEquals(Boolean.FALSE, reported.settings().gamerules().get("mobGriefing"));

        SharedWorldModels.HostGameRulesReportResponseDto difficultyReport =
                host.reportHostGameRules(worldId, epoch, token, Map.of(), "peaceful", "adventure");
        assertEquals("peaceful", difficultyReport.settings().difficulty(), "host-reported difficulty persists");
        assertEquals("adventure", difficultyReport.settings().defaultGameMode(), "host-reported game mode persists");

        SharedWorldModels.HostHeartbeatResponseDto heartbeat = host.heartbeatHost(worldId, epoch, token, "join.example");
        assertEquals(3L, (long) heartbeat.settingsRevision());
        assertEquals(Boolean.TRUE, heartbeat.settings().gamerules().get("keepInventory"));
        assertEquals("peaceful", heartbeat.settings().difficulty());

        // A wrong host token is fenced out and writes nothing.
        Exception denied = assertThrows(Exception.class, () -> host.reportHostGameRules(worldId, epoch, "rt-wrong", Map.of("pvp", true), null, null));
        assertTrue(denied.getMessage().toLowerCase().contains("hosting"), denied.getMessage());
        assertEquals(3L, (long) owner.getWorld(worldId).settingsRevision());
    }
}
