package link.sharedworld.host;

import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-side twin of WorldSettingsApplier: snapshots the managed gamerules
 * from a running integrated server through the per-version
 * ServerSettingsCompat seam, keyed by SharedWorldGameRule ids (the wire
 * vocabulary). Used by the host to detect in-game /gamerule changes worth
 * persisting to the backend.
 */
public final class WorldSettingsReader {
    private WorldSettingsReader() {
    }

    /** Must run on the server thread (callers wrap in server.execute). */
    public static Map<String, Boolean> readGameRules(MinecraftServer server) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        if (server == null) {
            return values;
        }
        for (SharedWorldGameRule rule : SharedWorldGameRule.values()) {
            values.put(rule.id(), link.sharedworld.versioned.ServerSettingsCompat.getGameRule(server, rule));
        }
        return values;
    }
}
