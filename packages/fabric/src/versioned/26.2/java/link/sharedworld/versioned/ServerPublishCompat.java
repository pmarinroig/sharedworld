package link.sharedworld.versioned;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;

/** Version-specific LAN publish; 26.2 adds a multiplayer-scope parameter. */
public final class ServerPublishCompat {
    private ServerPublishCompat() {
    }

    /** Publish without forcing a game mode (null keeps each player's stored mode) and without cheats. */
    public static boolean publish(IntegratedServer server, GameType gameMode, int port) {
        return server.publishServer(MinecraftServer.MultiplayerScope.LAN, gameMode, false, port);
    }
}
