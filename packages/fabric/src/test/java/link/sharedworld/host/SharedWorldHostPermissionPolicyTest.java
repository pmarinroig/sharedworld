package link.sharedworld.host;

import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

final class SharedWorldHostPermissionPolicyTest {
    @Test
    void hostingOwnerGetsVanillaOwnerPermissions() {
        assertSame(
                LevelBasedPermissionSet.OWNER,
                SharedWorldHostPermissionPolicy.effectivePermissions(LevelBasedPermissionSet.ALL, true, true)
        );
    }

    @Test
    void hostingGuestKeepsVanillaPermissions() {
        assertSame(
                LevelBasedPermissionSet.MODERATOR,
                SharedWorldHostPermissionPolicy.effectivePermissions(LevelBasedPermissionSet.MODERATOR, true, false)
        );
    }

    @Test
    void nonHostingOwnerKeepsVanillaPermissions() {
        assertSame(
                LevelBasedPermissionSet.ALL,
                SharedWorldHostPermissionPolicy.effectivePermissions(LevelBasedPermissionSet.ALL, false, true)
        );
    }
}
