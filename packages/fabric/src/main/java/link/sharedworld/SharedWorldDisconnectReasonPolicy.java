package link.sharedworld;

import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Classifies a vanilla disconnect reason so guest recovery knows when NOT to
 * auto-rejoin. Recovery exists for involuntary session loss (host crash,
 * network drop, host quitting into a handoff); a kick or ban is the server
 * deliberately removing the player, and auto-rejoining would undo it on the
 * spot (field finding: a kicked guest bounced straight back in). Only the
 * vanilla translatable reasons are classifiable; a /kick with a custom
 * free-text reason arrives as a literal and still recovers; the SharedWorld
 * membership ban does not depend on this (the revocation push ends the
 * session authoritatively).
 */
public final class SharedWorldDisconnectReasonPolicy {
    private static final Set<String> DELIBERATE_REMOVAL_KEYS = Set.of(
            "multiplayer.disconnect.kicked",
            "multiplayer.disconnect.banned",
            "multiplayer.disconnect.banned.reason",
            "multiplayer.disconnect.banned.expiration",
            "multiplayer.disconnect.duplicate_login",
            "multiplayer.disconnect.not_whitelisted",
            "sharedworld.command.ban.disconnected"
    );

    private SharedWorldDisconnectReasonPolicy() {
    }

    public static boolean isDeliberateRemoval(Component reason) {
        return reason != null
                && reason.getContents() instanceof TranslatableContents translatable
                && DELIBERATE_REMOVAL_KEYS.contains(translatable.getKey());
    }
}
