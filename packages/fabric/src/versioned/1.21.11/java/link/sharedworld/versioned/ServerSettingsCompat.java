package link.sharedworld.versioned;

import link.sharedworld.host.SharedWorldGameRule;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Applies owner-chosen world settings to the running integrated server. This
 * version has the registry-based gamerule system and MinecraftServer no
 * longer exposes getGameRules(); the world-wide rules live on the level.
 */
public final class ServerSettingsCompat {
    private ServerSettingsCompat() {
    }

    public static void setDifficulty(MinecraftServer server, Difficulty difficulty) {
        server.setDifficulty(difficulty, true);
    }

    public static void setDefaultGameMode(MinecraftServer server, GameType gameType) {
        server.setDefaultGameType(gameType);
    }

    /** While hosting a shared world, difficulty is owner-managed (Settings tab): lock the pause-menu control. */
    public static void setDifficultyLocked(MinecraftServer server, boolean locked) {
        server.setDifficultyLocked(locked);
    }

    public static void setGameRule(MinecraftServer server, SharedWorldGameRule rule, boolean value) {
        GameRules rules = server.overworld().getGameRules();
        switch (rule) {
            case KEEP_INVENTORY -> rules.set(GameRules.KEEP_INVENTORY, value, server);
            case MOB_GRIEFING -> rules.set(GameRules.MOB_GRIEFING, value, server);
            case DAYLIGHT_CYCLE -> rules.set(GameRules.ADVANCE_TIME, value, server);
            case WEATHER_CYCLE -> rules.set(GameRules.ADVANCE_WEATHER, value, server);
            case PVP -> rules.set(GameRules.PVP, value, server);
        }
    }

    /** Read the current value of a managed rule from the running server (server thread). */
    public static boolean getGameRule(MinecraftServer server, SharedWorldGameRule rule) {
        GameRules rules = server.overworld().getGameRules();
        return switch (rule) {
            case KEEP_INVENTORY -> rules.get(GameRules.KEEP_INVENTORY);
            case MOB_GRIEFING -> rules.get(GameRules.MOB_GRIEFING);
            case DAYLIGHT_CYCLE -> rules.get(GameRules.ADVANCE_TIME);
            case WEATHER_CYCLE -> rules.get(GameRules.ADVANCE_WEATHER);
            case PVP -> rules.get(GameRules.PVP);
        };
    }
}
