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

    /**
     * One atomic server-thread read of everything the host persists:
     * the managed gamerules plus the current difficulty (0.3.0 — in-game
     * /difficulty persists like /gamerule does). A null difficulty means
     * "unknown, report nothing for it".
     */
    public record Snapshot(Map<String, Boolean> gamerules, String difficulty, String defaultGameMode) {
    }

    /** Must run on the server thread (callers wrap in server.execute). */
    public static Snapshot readSnapshot(MinecraftServer server) {
        return new Snapshot(readGameRules(server), readDifficultyId(server), readDefaultGameModeId(server));
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

    /** The settings-vocabulary game mode id, or null when unavailable/unmanaged. */
    static String readDefaultGameModeId(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        net.minecraft.world.level.GameType mode = link.sharedworld.versioned.ServerSettingsCompat.getDefaultGameMode(server);
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case SURVIVAL -> "survival";
            case CREATIVE -> "creative";
            case ADVENTURE -> "adventure";
            // Spectator is not a managed value; report nothing rather than
            // an id the backend would reject.
            default -> null;
        };
    }

    /** The settings-vocabulary difficulty id, or null when unavailable. */
    static String readDifficultyId(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        net.minecraft.world.Difficulty difficulty = link.sharedworld.versioned.ServerSettingsCompat.getDifficulty(server);
        return difficulty == null ? null : difficulty.name().toLowerCase(java.util.Locale.ROOT);
    }
}
