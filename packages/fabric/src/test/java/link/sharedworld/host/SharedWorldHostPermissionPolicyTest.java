package link.sharedworld.host;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldHostPermissionPolicyTest {
    private static final String OWNER_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String MEMBER_UUID = "00000000-0000-0000-0000-000000000002";

    @Test
    void hostingOwnerHasOwnerPermissions() {
        assertTrue(SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(true, OWNER_UUID, OWNER_UUID));
    }

    @Test
    void ownerUuidComparisonIsCanonical() {
        assertTrue(SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(
                true,
                OWNER_UUID.replace("-", "").toUpperCase(),
                OWNER_UUID
        ));
    }

    @Test
    void nonOwnerDoesNotGetOwnerPermissions() {
        assertFalse(SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(
                true,
                "00000000-0000-0000-0000-000000000002",
                OWNER_UUID
        ));
    }

    @Test
    void notHostingNeverGrantsOwnerPermissions() {
        assertFalse(SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(false, OWNER_UUID, OWNER_UUID));
    }

    @Test
    void missingIdentitiesNeverGrantOwnerPermissions() {
        assertFalse(SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(true, null, OWNER_UUID));
        assertFalse(SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(true, " ", OWNER_UUID));
        assertFalse(SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(true, OWNER_UUID, null));
        assertFalse(SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(true, OWNER_UUID, " "));
    }

    @Test
    void ownerTierBeatsAnyGrantEntry() {
        Map<String, MemberCommandGrant> grants = Map.of(
                SharedWorldHostPermissionPolicy.commandGrantKey(OWNER_UUID),
                new MemberCommandGrant("Owner", false)
        );
        assertEquals(
                SharedWorldHostPermissionPolicy.Tier.OWNER,
                SharedWorldHostPermissionPolicy.effectiveTier(true, OWNER_UUID, OWNER_UUID, grants)
        );
    }

    @Test
    void grantedMemberGetsOperatorTier() {
        Map<String, MemberCommandGrant> grants = Map.of(
                SharedWorldHostPermissionPolicy.commandGrantKey(MEMBER_UUID),
                new MemberCommandGrant("Member", true)
        );
        assertEquals(
                SharedWorldHostPermissionPolicy.Tier.OPERATOR,
                SharedWorldHostPermissionPolicy.effectiveTier(true, MEMBER_UUID, OWNER_UUID, grants)
        );
    }

    @Test
    void grantLookupIsHyphenAndCaseInsensitive() {
        Map<String, MemberCommandGrant> grants = Map.of(
                SharedWorldHostPermissionPolicy.commandGrantKey(MEMBER_UUID),
                new MemberCommandGrant("Member", true)
        );
        assertEquals(
                SharedWorldHostPermissionPolicy.Tier.OPERATOR,
                SharedWorldHostPermissionPolicy.effectiveTier(
                        true,
                        MEMBER_UUID.replace("-", "").toUpperCase(),
                        OWNER_UUID,
                        grants
                )
        );
    }

    @Test
    void ungrantedMembersUnknownProfilesAndGarbageStayNone() {
        Map<String, MemberCommandGrant> grants = Map.of(
                SharedWorldHostPermissionPolicy.commandGrantKey(MEMBER_UUID),
                new MemberCommandGrant("Member", false)
        );
        assertEquals(
                SharedWorldHostPermissionPolicy.Tier.NONE,
                SharedWorldHostPermissionPolicy.effectiveTier(true, MEMBER_UUID, OWNER_UUID, grants)
        );
        assertEquals(
                SharedWorldHostPermissionPolicy.Tier.NONE,
                SharedWorldHostPermissionPolicy.effectiveTier(true, "00000000-0000-0000-0000-00000000000f", OWNER_UUID, grants)
        );
        assertEquals(
                SharedWorldHostPermissionPolicy.Tier.NONE,
                SharedWorldHostPermissionPolicy.effectiveTier(true, "not-a-uuid", OWNER_UUID, grants)
        );
        assertEquals(
                SharedWorldHostPermissionPolicy.Tier.NONE,
                SharedWorldHostPermissionPolicy.effectiveTier(true, null, OWNER_UUID, grants)
        );
        assertEquals(
                SharedWorldHostPermissionPolicy.Tier.NONE,
                SharedWorldHostPermissionPolicy.effectiveTier(true, MEMBER_UUID, OWNER_UUID, null)
        );
    }

    @Test
    void notHostingIsAlwaysNoneTier() {
        Map<String, MemberCommandGrant> grants = Map.of(
                SharedWorldHostPermissionPolicy.commandGrantKey(MEMBER_UUID),
                new MemberCommandGrant("Member", true)
        );
        assertEquals(
                SharedWorldHostPermissionPolicy.Tier.NONE,
                SharedWorldHostPermissionPolicy.effectiveTier(false, MEMBER_UUID, OWNER_UUID, grants)
        );
        assertEquals(
                SharedWorldHostPermissionPolicy.Tier.NONE,
                SharedWorldHostPermissionPolicy.effectiveTier(false, OWNER_UUID, OWNER_UUID, grants)
        );
    }
}
