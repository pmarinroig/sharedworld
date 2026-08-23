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

    /**
     * Permission tier of a connecting profile while hosting: the world owner keeps
     * full owner permissions, members with the command grant get operator-level
     * permissions, and everyone else (members without the grant, non-members,
     * unparseable profiles) stays a regular player.
     */
    public enum Tier {
        OWNER,
        OPERATOR,
        NONE
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

    public static Tier effectiveTier(
            boolean hostingSharedWorld,
            String requestedProfileUuid,
            String sharedWorldOwnerUuid,
            java.util.Map<String, MemberCommandGrant> memberGrants
    ) {
        if (hasSharedWorldOwnerPermissions(hostingSharedWorld, requestedProfileUuid, sharedWorldOwnerUuid)) {
            return Tier.OWNER;
        }
        if (!hostingSharedWorld || requestedProfileUuid == null || requestedProfileUuid.isBlank() || memberGrants == null) {
            return Tier.NONE;
        }
        MemberCommandGrant grant = memberGrants.get(commandGrantKey(requestedProfileUuid));
        return grant != null && grant.canUseCommands() ? Tier.OPERATOR : Tier.NONE;
    }

    /**
     * Lenient lookup key for grant maps: hyphen-insensitive, case-insensitive, and
     * never throws; an unparseable profile UUID simply never matches a grant.
     */
    public static String commandGrantKey(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }
}
