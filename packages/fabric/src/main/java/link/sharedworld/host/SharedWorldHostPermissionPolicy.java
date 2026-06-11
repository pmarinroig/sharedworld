package link.sharedworld.host;

import link.sharedworld.CanonicalPlayerIdentity;

/**
 * Decides whether a connecting profile owns the hosted SharedWorld. The mapping of that
 * decision onto vanilla's permission model lives in the per-Minecraft-version
 * {@code link.sharedworld.versioned.HostPermissionsCompat}, because the model itself
 * changed across versions (integer op levels vs. level-based permission sets).
 */
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
}
