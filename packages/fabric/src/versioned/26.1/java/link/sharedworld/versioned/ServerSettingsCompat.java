package link.sharedworld.versioned;

import link.sharedworld.host.SharedWorldGameRule;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

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

    public static void setGameRule(MinecraftServer server, SharedWorldGameRule rule, boolean value) {
        GameRules rules = server.getGameRules();
        switch (rule) {
            case KEEP_INVENTORY -> rules.set(GameRules.KEEP_INVENTORY, value, server);
            case MOB_GRIEFING -> rules.set(GameRules.MOB_GRIEFING, value, server);
            case DAYLIGHT_CYCLE -> rules.set(GameRules.ADVANCE_TIME, value, server);
            case WEATHER_CYCLE -> rules.set(GameRules.ADVANCE_WEATHER, value, server);
            case PVP -> rules.set(GameRules.PVP, value, server);
        }
    }
}
