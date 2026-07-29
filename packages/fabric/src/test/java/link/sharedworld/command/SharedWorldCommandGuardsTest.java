package link.sharedworld.command;

import java.util.Map;
import java.util.Optional;

import link.sharedworld.host.MemberCommandGrant;
import link.sharedworld.host.SharedWorldHostPermissionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldCommandGuardsTest {
    private static final String OWNER_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String MEMBER_UUID = "00000000-0000-0000-0000-000000000002";

    @Test
    void ownerRunningOnTheirOwnHostCanRunOwnerCommands() {
        assertTrue(SharedWorldCommandGuards.canRunOwnerCommand(true, OWNER_UUID, OWNER_UUID, OWNER_UUID));
    }

    @Test
    void ownerAsGuestOnAnotherHostCannotRunOwnerCommands() {
        // The command would execute with the (non-owner) host's backend
        // credentials, so the backend could never authorize it.
        assertFalse(SharedWorldCommandGuards.canRunOwnerCommand(true, OWNER_UUID, OWNER_UUID, MEMBER_UUID));
    }

    @Test
    void memberCannotRunOwnerCommandsEvenOnTheOwnersHost() {
        assertFalse(SharedWorldCommandGuards.canRunOwnerCommand(true, MEMBER_UUID, OWNER_UUID, OWNER_UUID));
    }

    @Test
    void notHostingNeverAllowsOwnerCommands() {
        assertFalse(SharedWorldCommandGuards.canRunOwnerCommand(false, OWNER_UUID, OWNER_UUID, OWNER_UUID));
    }

    @Test
    void nameResolutionIsCaseInsensitiveAndCoversOfflineMembers() {
        Map<String, MemberCommandGrant> grants = Map.of(
                SharedWorldHostPermissionPolicy.commandGrantKey(MEMBER_UUID),
                new MemberCommandGrant(MEMBER_UUID, "SomeGuest", false)
        );
        Optional<MemberCommandGrant> resolved = SharedWorldCommandGuards.resolveMemberByName(grants, "someguest");
        assertTrue(resolved.isPresent());
        assertEquals(MEMBER_UUID, resolved.get().playerUuid());
    }

    @Test
    void unknownNamesBlankNamesAndNullMapsResolveEmpty() {
        Map<String, MemberCommandGrant> grants = Map.of(
                SharedWorldHostPermissionPolicy.commandGrantKey(MEMBER_UUID),
                new MemberCommandGrant(MEMBER_UUID, "SomeGuest", false)
        );
        assertTrue(SharedWorldCommandGuards.resolveMemberByName(grants, "Stranger").isEmpty());
        assertTrue(SharedWorldCommandGuards.resolveMemberByName(grants, " ").isEmpty());
        assertTrue(SharedWorldCommandGuards.resolveMemberByName(grants, null).isEmpty());
        assertTrue(SharedWorldCommandGuards.resolveMemberByName(null, "SomeGuest").isEmpty());
    }
}
