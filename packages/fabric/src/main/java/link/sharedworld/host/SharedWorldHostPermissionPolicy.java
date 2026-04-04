package link.sharedworld.host;

import link.sharedworld.CanonicalPlayerIdentity;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

public final class SharedWorldHostPermissionPolicy {
    private SharedWorldHostPermissionPolicy() {
    }

    public static boolean hasSharedWorldOwnerPermissions(
            boolean hostingSharedWorld,
            String requestedProfileUuid,
            String sharedWorldOwnerUuid
    ) {
        return hostingSharedWorld
                && requestedProfileUuid != null
                && !requestedProfileUuid.isBlank()
                && sharedWorldOwnerUuid != null
                && !sharedWorldOwnerUuid.isBlank()
                && CanonicalPlayerIdentity.sameUuid(requestedProfileUuid, sharedWorldOwnerUuid);
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

        return hasSharedWorldOwnerPermissions(hostingSharedWorld, requestedProfileUuid, sharedWorldOwnerUuid)
                ? LevelBasedPermissionSet.OWNER
                : LevelBasedPermissionSet.ALL;
    }
}
