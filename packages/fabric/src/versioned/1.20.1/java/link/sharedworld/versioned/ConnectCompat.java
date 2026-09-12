package link.sharedworld.versioned;

import link.sharedworld.integration.SharedWorldJoinIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Version-specific server-connect entry point (startConnecting arity and ServerData moved). */
public final class ConnectCompat {
    private ConnectCompat() {
    }

    public static void startConnecting(Screen parent, Minecraft minecraft, String target, String worldId, String worldName) {
        ServerAddress address = ServerAddress.parseString(target);
        // The address resolves and connects; ServerData only names the session
        // for Minecraft and for mods keying per-server data, so it gets the
        // stable per-world identity instead of the changing join target.
        String identity = SharedWorldJoinIdentity.serverAddress(worldId);
        ServerData serverData = new ServerData(identity, identity, false);
        ConnectScreen.startConnecting(parent, minecraft, address, serverData, false);
    }
}
