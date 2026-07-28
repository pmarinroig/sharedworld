package link.sharedworld.versioned;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Version-specific server-connect entry point (startConnecting arity and ServerData moved). */
public final class ConnectCompat {
    private ConnectCompat() {
    }

    public static void startConnecting(Screen parent, Minecraft minecraft, String target, String worldName) {
        ServerAddress address = ServerAddress.parseString(target);
        ServerData serverData = new ServerData(worldName, target, false);
        ConnectScreen.startConnecting(parent, minecraft, address, serverData, false);
    }
}
