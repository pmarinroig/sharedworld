package link.sharedworld.host;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldHostPermissionPolicyTest {
    private static final String OWNER_UUID = "00000000-0000-0000-0000-000000000001";

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
}
