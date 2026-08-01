package link.sharedworld.versioned;

import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.host.SharedWorldHostPermissionPolicy;

/**
 * Version-specific host permission mapping for Minecraft versions where vanilla
 * models profile permissions as integer op levels (0 = regular player, 4 = full
 * owner). The owner keeps 4, members with the command grant get 3, everyone else 0.
 */
public final class HostPermissionsCompat {
    public static final int OWNER_PERMISSION_LEVEL = 4;
    public static final int OPERATOR_PERMISSION_LEVEL = 3;
    public static final int DEFAULT_PERMISSION_LEVEL = 0;

    private HostPermissionsCompat() {
    }

    public static int effectivePermissions(
            int vanillaPermissions,
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
            case OWNER -> OWNER_PERMISSION_LEVEL;
            case OPERATOR -> OPERATOR_PERMISSION_LEVEL;
            case NONE -> DEFAULT_PERMISSION_LEVEL;
        };
    }
}
