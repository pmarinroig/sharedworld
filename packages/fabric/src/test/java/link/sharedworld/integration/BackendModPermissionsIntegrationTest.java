package link.sharedworld.integration;

import java.util.Arrays;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import link.sharedworld.integration.support.SharedWorldIntegrationFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level story for the per-member command permission: the owner toggles it
 * over PATCH, the live host learns about it from the very next heartbeat
 * response, and an in-game /ban (backend kickMember) removes the member and
 * rotates the share code.
 */
@Tag("integration")
final class BackendModPermissionsIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void permissionToggleRoundTripsThroughPatchAndHeartbeat() throws Exception {
        SharedWorldIntegrationFixtures.HostedWorld hosted = SharedWorldIntegrationFixtures.createHostedWorld(
                "Integration Member Permissions",
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.GUEST
        );
        SharedWorldApiClient owner = hosted.hostClient();
        String worldId = hosted.world().id();
        // The backend stores and echoes player UUIDs unhyphenated; clients must
        // address members with the stored form (the Members tab echoes DTO values).
        String guestUuid = SharedWorldIntegrationBackend.GUEST.playerUuidHyphenated().replace("-", "");
        long epoch = hosted.assignment().runtimeEpoch();
        String token = hosted.assignment().hostToken();

        assertFalse(heartbeatGrant(owner, worldId, epoch, token, guestUuid));

        SharedWorldModels.WorldMembershipDto granted = owner.setMemberCommandPermission(worldId, guestUuid, true);
        assertTrue(granted.canUseCommands());
        assertTrue(heartbeatGrant(owner, worldId, epoch, token, guestUuid));

        SharedWorldModels.WorldMembershipDto revoked = owner.setMemberCommandPermission(worldId, guestUuid, false);
        assertFalse(revoked.canUseCommands());
        assertFalse(heartbeatGrant(owner, worldId, epoch, token, guestUuid));

        // Members cannot grant themselves command permissions.
        SharedWorldApiClient guest = SharedWorldIntegrationBackend.apiClient(SharedWorldIntegrationBackend.GUEST);
        Exception denied = assertThrows(Exception.class, () -> guest.setMemberCommandPermission(worldId, guestUuid, true));
        assertTrue(denied.getMessage().toLowerCase().contains("owner"), denied.getMessage());
    }

    @Test
    void banRemovesMembershipRotatesInviteAndDropsHeartbeatEntry() throws Exception {
        SharedWorldIntegrationFixtures.HostedWorld hosted = SharedWorldIntegrationFixtures.createHostedWorld(
                "Integration Ban Semantics",
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.GUEST
        );
        SharedWorldApiClient owner = hosted.hostClient();
        String worldId = hosted.world().id();
        String guestUuid = SharedWorldIntegrationBackend.GUEST.playerUuidHyphenated().replace("-", "");
        long epoch = hosted.assignment().runtimeEpoch();
        String token = hosted.assignment().hostToken();

        String codeBeforeBan = owner.createInvite(worldId).code();
        owner.kickMember(worldId, guestUuid);

        SharedWorldModels.HostHeartbeatResponseDto afterBan = owner.heartbeatHost(worldId, epoch, token, "join.example");
        assertTrue(Arrays.stream(afterBan.memberships())
                .noneMatch(membership -> membership.playerUuid().equalsIgnoreCase(guestUuid)));

        String codeAfterBan = owner.createInvite(worldId).code();
        assertNotEquals(codeBeforeBan, codeAfterBan);

        SharedWorldApiClient guest = SharedWorldIntegrationBackend.apiClient(SharedWorldIntegrationBackend.GUEST);
        assertThrows(Exception.class, () -> guest.redeemInvite(codeBeforeBan));
        assertEquals(worldId, guest.redeemInvite(codeAfterBan).id());
    }

    private static boolean heartbeatGrant(
            SharedWorldApiClient host,
            String worldId,
            long epoch,
            String token,
            String memberUuid
    ) throws Exception {
        SharedWorldModels.HostHeartbeatResponseDto response = host.heartbeatHost(worldId, epoch, token, "join.example");
        return Arrays.stream(response.memberships())
                .filter(membership -> membership.playerUuid().equalsIgnoreCase(memberUuid))
                .findFirst()
                .orElseThrow()
                .canUseCommands();
    }
}
