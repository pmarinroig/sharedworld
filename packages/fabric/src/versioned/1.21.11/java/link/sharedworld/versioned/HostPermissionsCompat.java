package link.sharedworld.versioned;

import link.sharedworld.host.SharedWorldHostPermissionPolicy;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

/**
 * Version-specific host permission mapping for Minecraft 1.21.11, where vanilla models
 * profile permissions as {@link LevelBasedPermissionSet} rather than integer op levels.
 */
public final class HostPermissionsCompat {
    private HostPermissionsCompat() {
    }

    public static LevelBasedPermissionSet effectivePermissions(
            LevelBasedPermissionSet vanillaPermissions,
            boolean hostingSharedWorld,
            String requestedProfileUuid,
            String sharedWorldOwnerUuid
    ) {
        if (!hostingSharedWorld) {
            return vanillaPermissions;
        }

        return SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(hostingSharedWorld, requestedProfileUuid, sharedWorldOwnerUuid)
                ? LevelBasedPermissionSet.OWNER
                : LevelBasedPermissionSet.ALL;
    }
}
