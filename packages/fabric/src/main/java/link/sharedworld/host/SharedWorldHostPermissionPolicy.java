package link.sharedworld.host;

import net.minecraft.server.permissions.LevelBasedPermissionSet;

public final class SharedWorldHostPermissionPolicy {
    private SharedWorldHostPermissionPolicy() {
    }

    public static LevelBasedPermissionSet effectivePermissions(
            LevelBasedPermissionSet vanillaPermissions,
            boolean hostingSharedWorld,
            boolean singleplayerOwner
    ) {
        if (hostingSharedWorld && singleplayerOwner) {
            return LevelBasedPermissionSet.OWNER;
        }
        return vanillaPermissions;
    }
}
