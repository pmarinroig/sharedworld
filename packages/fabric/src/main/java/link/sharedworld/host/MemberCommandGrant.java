package link.sharedworld.host;

/**
 * A member's command-permission state while this client hosts their shared world,
 * keyed in the bridge by {@link SharedWorldHostPermissionPolicy#commandGrantKey}.
 * The player name rides along so in-game commands can resolve offline members.
 */
public record MemberCommandGrant(String playerName, boolean canUseCommands) {
}
