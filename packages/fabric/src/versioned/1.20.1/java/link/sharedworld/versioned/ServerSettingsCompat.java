package link.sharedworld.versioned;

import link.sharedworld.host.SharedWorldGameRule;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;

/** Applies owner-chosen world settings to the running integrated server. */
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
        switch (rule) {
            case KEEP_INVENTORY -> server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(value, server);
            case MOB_GRIEFING -> server.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(value, server);
            case DAYLIGHT_CYCLE -> server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(value, server);
            case WEATHER_CYCLE -> server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(value, server);
            // No pvp gamerule on this version; the server flag is applied per
            // session (settings arrive on every heartbeat, so it never sticks
            // stale across hosts).
            case PVP -> server.setPvpAllowed(value);
        }
    }
}
