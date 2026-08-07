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
        return switch (rule) {
            case KEEP_INVENTORY -> server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).get();
            case MOB_GRIEFING -> server.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).get();
            case DAYLIGHT_CYCLE -> server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).get();
            case WEATHER_CYCLE -> server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).get();
            // Mirrors the setter: pvp has no gamerule here, so read the server flag.
            case PVP -> server.isPvpAllowed();
        };
    }

    /** Display name of a vanilla ban-command target (GameProfile here; NameAndId on 1.21.9+). */
    public static String profileDisplayName(Object banTarget) {
        return banTarget instanceof com.mojang.authlib.GameProfile profile ? profile.getName() : null;
    }

    /**
     * Drop a player from the server's local vanilla ban list. Membership is the
     * only ban authority on a hosted shared world, and banned-players.json
     * outlives sessions on whichever machine happened to host (server thread).
     */
    public static void pruneLocalBan(MinecraftServer server, java.util.UUID playerUuid, String playerName) {
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(playerUuid, playerName);
        if (server.getPlayerList().getBans().isBanned(profile)) {
            server.getPlayerList().getBans().remove(profile);
        }
    }

    /**
     * Membership is the only join authority on a hosted shared world; a
     * whitelist left enabled (an earlier session's /whitelist on, or e4mc's
     * useWhiteList config) would silently refuse legit members (server
     * thread). The toggle moved to MinecraftServer in 1.21.9.
     */
    public static void forceWhitelistOff(MinecraftServer server) {
        if (server.getPlayerList().isUsingWhitelist()) {
            server.getPlayerList().setUsingWhiteList(false);
        }
    }
}
