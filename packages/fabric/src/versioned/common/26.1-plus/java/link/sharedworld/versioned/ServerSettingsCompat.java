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

    /** While hosting a shared world, difficulty is owner-managed (Settings tab): lock the pause-menu control. */
    public static void setDifficultyLocked(MinecraftServer server, boolean locked) {
        server.setDifficultyLocked(locked);
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

    /** Read the current value of a managed rule from the running server (server thread). */
    /** Read side for host-reported default-game-mode persistence (0.3.0). */
    public static GameType getDefaultGameMode(MinecraftServer server) {
        return server.getDefaultGameType();
    }

    /** Read side for host-reported difficulty persistence (0.3.0). */
    public static Difficulty getDifficulty(MinecraftServer server) {
        return server.getWorldData().getDifficulty();
    }

    public static boolean getGameRule(MinecraftServer server, SharedWorldGameRule rule) {
        GameRules rules = server.getGameRules();
        return switch (rule) {
            case KEEP_INVENTORY -> rules.get(GameRules.KEEP_INVENTORY);
            case MOB_GRIEFING -> rules.get(GameRules.MOB_GRIEFING);
            case DAYLIGHT_CYCLE -> rules.get(GameRules.ADVANCE_TIME);
            case WEATHER_CYCLE -> rules.get(GameRules.ADVANCE_WEATHER);
            case PVP -> rules.get(GameRules.PVP);
        };
    }

    /** Display name of a vanilla ban-command target (NameAndId on this version; GameProfile before 1.21.9). */
    public static String profileDisplayName(Object banTarget) {
        return banTarget instanceof net.minecraft.server.players.NameAndId nameAndId ? nameAndId.name() : null;
    }

    /**
     * Drop a player from the server's local vanilla ban list. Membership is the
     * only ban authority on a hosted shared world, and banned-players.json
     * outlives sessions on whichever machine happened to host (server thread).
     */
    public static void pruneLocalBan(MinecraftServer server, java.util.UUID playerUuid, String playerName) {
        net.minecraft.server.players.NameAndId nameAndId = new net.minecraft.server.players.NameAndId(playerUuid, playerName);
        if (server.getPlayerList().getBans().isBanned(nameAndId)) {
            server.getPlayerList().getBans().remove(nameAndId);
        }
    }

    /**
     * Membership is the only join authority on a hosted shared world; a
     * whitelist left enabled (an earlier session's /whitelist on, or e4mc's
     * useWhiteList config) would silently refuse legit members (server
     * thread). PlayerList.setUsingWhiteList moved here in 1.21.9.
     */
    public static void forceWhitelistOff(MinecraftServer server) {
        if (server.isUsingWhitelist()) {
            server.setUsingWhitelist(false);
        }
    }
}
