package link.sharedworld.versioned;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;

/** Version-specific LAN publish; 26.3 drops the game-mode parameter. */
public final class ServerPublishCompat {
    private ServerPublishCompat() {
    }

    /**
     * Publish without cheats. 26.3 no longer forces a game mode on publish, which is
     * what SharedWorld always asked for (null keeps each player's stored mode).
     */
    public static boolean publish(IntegratedServer server, GameType gameMode, int port) {
        return server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, port);
    }
}
