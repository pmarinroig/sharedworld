package link.sharedworld.versioned;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HostPermissionsCompatTest {
    @Test
    void hostingOwnerGetsOwnerPermissionLevel() {
        assertEquals(
                HostPermissionsCompat.OWNER_PERMISSION_LEVEL,
                HostPermissionsCompat.effectivePermissions(
                        0,
                        true,
                        "00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000001"
                )
        );
    }

    @Test
    void hostingNonOwnerFallsBackToDefaultLevel() {
        assertEquals(
                HostPermissionsCompat.DEFAULT_PERMISSION_LEVEL,
                HostPermissionsCompat.effectivePermissions(
                        3,
                        true,
                        "00000000-0000-0000-0000-000000000002",
                        "00000000-0000-0000-0000-000000000001"
                )
        );
    }

    @Test
    void hostingWithoutOwnerUuidFallsBackToDefaultLevelForEveryone() {
        assertEquals(
                HostPermissionsCompat.DEFAULT_PERMISSION_LEVEL,
                HostPermissionsCompat.effectivePermissions(
                        4,
                        true,
                        "00000000-0000-0000-0000-000000000001",
                        null
                )
        );
    }

    @Test
    void nonHostingSessionKeepsVanillaPermissionLevel() {
        assertEquals(
                3,
                HostPermissionsCompat.effectivePermissions(
                        3,
                        false,
                        "00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000001"
                )
        );
    }
}
