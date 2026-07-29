package link.sharedworld.host;

/**
 * A member's command-permission state while this client hosts their shared world,
 * keyed in the bridge by {@link SharedWorldHostPermissionPolicy#commandGrantKey}.
 * The canonical (hyphenated) UUID and player name ride along so in-game commands
 * can resolve members by name — including offline ones — and address the backend.
 */
public record MemberCommandGrant(String playerUuid, String playerName, boolean canUseCommands) {
}
