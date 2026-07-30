package link.sharedworld.host;

import link.sharedworld.api.SharedWorldModels.WorldSettingsDto;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;

import java.util.Locale;
import java.util.Map;

/**
 * Maps the backend's world settings onto a running integrated server through
 * the per-version ServerSettingsCompat seam. Unknown values are skipped, never
 * fatal: an older client hosting a world whose owner saved settings from a
 * newer client must not crash over a value it doesn't know.
 */
public final class WorldSettingsApplier {
    private WorldSettingsApplier() {
    }

    /** Must run on the server thread (callers wrap in server.execute). */
    public static void apply(MinecraftServer server, WorldSettingsDto settings) {
        if (server == null || settings == null) {
            return;
        }
        Difficulty difficulty = parseDifficulty(settings.difficulty());
        if (difficulty != null) {
            link.sharedworld.versioned.ServerSettingsCompat.setDifficulty(server, difficulty);
        }
        GameType gameMode = parseGameMode(settings.defaultGameMode());
        if (gameMode != null) {
            link.sharedworld.versioned.ServerSettingsCompat.setDefaultGameMode(server, gameMode);
        }
        if (settings.gamerules() != null) {
            for (Map.Entry<String, Boolean> entry : settings.gamerules().entrySet()) {
                SharedWorldGameRule rule = SharedWorldGameRule.byId(entry.getKey());
                if (rule != null && entry.getValue() != null) {
                    link.sharedworld.versioned.ServerSettingsCompat.setGameRule(server, rule, entry.getValue());
                }
            }
        }
    }

    static Difficulty parseDifficulty(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "peaceful" -> Difficulty.PEACEFUL;
            case "easy" -> Difficulty.EASY;
            case "normal" -> Difficulty.NORMAL;
            case "hard" -> Difficulty.HARD;
            default -> null;
        };
    }

    static GameType parseGameMode(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "survival" -> GameType.SURVIVAL;
            case "creative" -> GameType.CREATIVE;
            case "adventure" -> GameType.ADVENTURE;
            default -> null;
        };
    }
}
