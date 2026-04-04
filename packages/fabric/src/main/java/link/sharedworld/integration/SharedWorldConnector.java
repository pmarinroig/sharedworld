package link.sharedworld.integration;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.screen.SharedWorldErrorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

public final class SharedWorldConnector {
    private static final Logger LOGGER = LoggerFactory.getLogger(SharedWorldConnector.class);

    private SharedWorldConnector() {
    }

    public static void connect(Screen parent, String target, String worldName) {
        connect(parent, target, null, worldName);
    }

    public static void connect(Screen parent, String target, String worldId, String worldName) {
        Minecraft minecraft = Minecraft.getInstance();
        connect(parent, target, worldId, worldName, minecraft, ConnectScreen::startConnecting, (currentParent, error) -> minecraft.setScreen(new SharedWorldErrorScreen(
                currentParent,
                Component.translatable("screen.sharedworld.error_join_title"),
                Component.translatable("screen.sharedworld.join_connect_failed")
        )));
    }

    public static void connect(Screen parent, String target, String worldId, String worldName, Consumer<Throwable> failureHandler) {
        Objects.requireNonNull(failureHandler, "failureHandler");
        Minecraft minecraft = Minecraft.getInstance();
        connect(parent, target, worldId, worldName, minecraft, ConnectScreen::startConnecting, (currentParent, error) -> failureHandler.accept(error));
    }

    static void connect(
            Screen parent,
            String target,
            String worldId,
            String worldName,
            Minecraft minecraft,
            ConnectStarter connectStarter,
            ConnectFailureHandler connectFailureHandler
    ) {
        ServerAddress address = ServerAddress.parseString(target);
        ServerData serverData = new ServerData(worldName, target, ServerData.Type.OTHER);
        if (worldId != null) {
            SharedWorldClient.playSessionTracker().beginGuestConnect(worldId, worldName, target);
        }

        try {
            connectStarter.start(parent, minecraft, address, serverData, false, null);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to open the Minecraft connect screen for SharedWorld target {}", target, exception);
            if (worldId != null) {
                SharedWorldClient.playSessionTracker().clear();
            }
            connectFailureHandler.onFailure(parent, exception);
        }
    }

    @FunctionalInterface
    interface ConnectStarter {
        void start(
                Screen parent,
                Minecraft minecraft,
                ServerAddress address,
                ServerData serverData,
                boolean quickPlay,
                TransferState transferState
        );
    }

    @FunctionalInterface
    interface ConnectFailureHandler {
        void onFailure(Screen parent, Throwable error);
    }
}
