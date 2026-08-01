package link.sharedworld.versioned;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;

/** Version-specific LAN publish; newer versions add a multiplayer-scope parameter. */
public final class ServerPublishCompat {
    private ServerPublishCompat() {
    }

    /** Publish without forcing a game mode (null keeps each player's stored mode) and without cheats. */
    public static boolean publish(IntegratedServer server, GameType gameMode, int port) {
        return server.publishServer(gameMode, false, port);
    }
}
