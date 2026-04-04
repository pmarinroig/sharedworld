package link.sharedworld.host;

import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

final class SharedWorldHostPermissionPolicyTest {
    @Test
    void hostingOwnerGetsOwnerPermissions() {
        assertSame(
                LevelBasedPermissionSet.OWNER,
                SharedWorldHostPermissionPolicy.effectivePermissions(
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
                SharedWorldHostPermissionPolicy.effectivePermissions(
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
                SharedWorldHostPermissionPolicy.effectivePermissions(
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
                SharedWorldHostPermissionPolicy.effectivePermissions(
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
                SharedWorldHostPermissionPolicy.effectivePermissions(
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
                SharedWorldHostPermissionPolicy.effectivePermissions(
                        LevelBasedPermissionSet.ADMIN,
                        false,
                        "00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000001"
                )
        );
    }
}
