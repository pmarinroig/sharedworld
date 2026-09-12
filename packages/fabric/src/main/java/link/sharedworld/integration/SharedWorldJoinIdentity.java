package link.sharedworld.integration;

import java.util.Locale;

/**
 * The server identity a guest's Minecraft client is told about for a shared
 * world. Client mods key their per-server data on {@code ServerData.ip} (Xaero's
 * maps and waypoints, Bobby, VoxelMap, JourneyMap) or {@code ServerData.name}
 * (Distant Horizons); the e4mc domain changes every session and the world name
 * can be renamed, so neither is handed to them. The connection itself still
 * uses the real join target.
 *
 * <p>Only hex characters and dots: Xaero's Minimap escapes {@code _} and other
 * punctuation in folder names while the World Map does not, and keeping the
 * address free of them gives both mods the same root folder name, which the
 * host-side Xaero's hooks reproduce for the same world.
 */
public final class SharedWorldJoinIdentity {
    static final String DOMAIN = "sharedworld.link";

    private SharedWorldJoinIdentity() {
    }

    /** {@code de822dd1dbf74fb091e4776329f94957.sharedworld.link} for {@code world_de822dd1dbf74fb091e4776329f94957}. */
    public static String serverAddress(String worldId) {
        String core = worldId == null ? "" : worldId.toLowerCase(Locale.ROOT);
        if (core.startsWith("world_")) {
            core = core.substring("world_".length());
        }
        StringBuilder cleaned = new StringBuilder(core.length());
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')) {
                cleaned.append(c);
            }
        }
        if (cleaned.isEmpty()) {
            cleaned.append("unknown");
        }
        return cleaned + "." + DOMAIN;
    }

    /** The folder both Xaero's mods derive from that address for a multiplayer session. */
    public static String xaeroMultiplayerRoot(String worldId) {
        return "Multiplayer_" + serverAddress(worldId);
    }
}
