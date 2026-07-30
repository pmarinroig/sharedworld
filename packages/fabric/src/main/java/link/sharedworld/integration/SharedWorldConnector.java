package link.sharedworld.integration;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.screen.SharedWorldErrorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
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
        connect(parent, target, null, worldName, 0L);
    }

    public static void connect(Screen parent, String target, String worldId, String worldName, long runtimeEpoch) {
        Minecraft minecraft = Minecraft.getInstance();
        connect(parent, target, worldId, worldName, runtimeEpoch, minecraft, link.sharedworld.versioned.ConnectCompat::startConnecting, (currentParent, error) -> link.sharedworld.versioned.ClientCompat.setScreen(minecraft, new SharedWorldErrorScreen(
                currentParent,
                Component.translatable("screen.sharedworld.error_join_title"),
                Component.translatable("screen.sharedworld.join_connect_failed")
        )));
    }

    public static void connect(Screen parent, String target, String worldId, String worldName, long runtimeEpoch, Consumer<Throwable> failureHandler) {
        Objects.requireNonNull(failureHandler, "failureHandler");
        Minecraft minecraft = Minecraft.getInstance();
        connect(parent, target, worldId, worldName, runtimeEpoch, minecraft, link.sharedworld.versioned.ConnectCompat::startConnecting, (currentParent, error) -> failureHandler.accept(error));
    }

    static void connect(
            Screen parent,
            String target,
            String worldId,
            String worldName,
            long runtimeEpoch,
            Minecraft minecraft,
            ConnectStarter connectStarter,
            ConnectFailureHandler connectFailureHandler
    ) {
        if (worldId != null) {
            SharedWorldClient.playSessionTracker().beginGuestConnect(worldId, worldName, target, runtimeEpoch);
        }

        try {
            connectStarter.start(parent, minecraft, target, worldName);
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
        void start(Screen parent, Minecraft minecraft, String target, String worldName);
    }

    @FunctionalInterface
    interface ConnectFailureHandler {
        void onFailure(Screen parent, Throwable error);
    }
}
