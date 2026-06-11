package link.sharedworld.versioned;

import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

final class HostPermissionsCompatTest {
    @Test
    void hostingOwnerGetsOwnerPermissions() {
        assertSame(
                LevelBasedPermissionSet.OWNER,
                HostPermissionsCompat.effectivePermissions(
                        LevelBasedPermissionSet.ALL,
                        true,
                        "00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000001"
                )
        );
    }

    @Test
    void hostingRemoteOwnerGetsOwnerPermissions() {
        assertSame(
                LevelBasedPermissionSet.OWNER,
                HostPermissionsCompat.effectivePermissions(
                        LevelBasedPermissionSet.MODERATOR,
                        true,
                        "00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000001"
                )
        );
    }

    @Test
    void hostingNonOwnerLocalHostFallsBackToAll() {
        assertSame(
                LevelBasedPermissionSet.ALL,
                HostPermissionsCompat.effectivePermissions(
                        LevelBasedPermissionSet.ADMIN,
                        true,
                        "00000000-0000-0000-0000-000000000002",
                        "00000000-0000-0000-0000-000000000001"
                )
        );
    }

    @Test
    void hostingNonOwnerRemoteGuestFallsBackToAll() {
        assertSame(
                LevelBasedPermissionSet.ALL,
                HostPermissionsCompat.effectivePermissions(
                        LevelBasedPermissionSet.MODERATOR,
                        true,
                        "00000000-0000-0000-0000-000000000003",
                        "00000000-0000-0000-0000-000000000001"
                )
        );
    }

    @Test
    void hostingWithoutOwnerUuidFallsBackToAllForEveryone() {
        assertSame(
                LevelBasedPermissionSet.ALL,
                HostPermissionsCompat.effectivePermissions(
                        LevelBasedPermissionSet.OWNER,
                        true,
                        "00000000-0000-0000-0000-000000000001",
                        null
                )
        );
    }

    @Test
    void nonHostingSessionKeepsVanillaPermissions() {
        assertSame(
                LevelBasedPermissionSet.ADMIN,
                HostPermissionsCompat.effectivePermissions(
                        LevelBasedPermissionSet.ADMIN,
                        false,
                        "00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000001"
                )
        );
    }
}
