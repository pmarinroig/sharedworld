package link.sharedworld.versioned;

import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.host.SharedWorldHostPermissionPolicy;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

/**
 * Version-specific host permission mapping for Minecraft versions where vanilla models
 * profile permissions as {@link LevelBasedPermissionSet} rather than integer op levels.
 * The owner keeps OWNER, members with the command grant get ADMIN, everyone else ALL.
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

        return switch (SharedWorldHostPermissionPolicy.effectiveTier(
                hostingSharedWorld,
                requestedProfileUuid,
                sharedWorldOwnerUuid,
                SharedWorldDevSessionBridge.hostedMemberGrants()
        )) {
            case OWNER -> LevelBasedPermissionSet.OWNER;
            case OPERATOR -> LevelBasedPermissionSet.ADMIN;
            case NONE -> LevelBasedPermissionSet.ALL;
        };
    }
}
