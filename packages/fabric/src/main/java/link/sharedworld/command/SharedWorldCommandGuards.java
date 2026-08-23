package link.sharedworld.command;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import link.sharedworld.host.MemberCommandGrant;
import link.sharedworld.host.SharedWorldHostPermissionPolicy;

/**
 * Pure authorization and name-resolution rules for SharedWorld's in-game
 * commands. Backend-mutating commands (/op, /deop, /ban) execute on the host's
 * machine with the host's backend credentials, so the backend can only honor
 * them when the host IS the owner; hence the double check here.
 */
public final class SharedWorldCommandGuards {
    private SharedWorldCommandGuards() {
    }

    /** The runner must be the world owner AND the local host must be the owner. */
    public static boolean canRunOwnerCommand(
            boolean hostingSharedWorld,
            String runnerUuid,
            String ownerUuid,
            String localHostPlayerUuid
    ) {
        return SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(hostingSharedWorld, runnerUuid, ownerUuid)
                && SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(hostingSharedWorld, localHostPlayerUuid, ownerUuid);
    }

    /** Case-insensitive member lookup by player name over the hosted grant map. */
    public static Optional<MemberCommandGrant> resolveMemberByName(Map<String, MemberCommandGrant> grants, String playerName) {
        if (grants == null || playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        String wanted = playerName.trim().toLowerCase(Locale.ROOT);
        return grants.values().stream()
                .filter(grant -> grant.playerName() != null && grant.playerName().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }
}
