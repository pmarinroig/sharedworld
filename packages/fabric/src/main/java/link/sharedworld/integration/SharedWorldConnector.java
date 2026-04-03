package link.sharedworld.integration;

import link.sharedworld.SharedWorldClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class SharedWorldConnector {
    private SharedWorldConnector() {
    }

    public static void connect(Screen parent, String target, String worldName) {
        connect(parent, target, null, worldName);
    }

    public static void connect(Screen parent, String target, String worldId, String worldName) {
        Minecraft minecraft = Minecraft.getInstance();
        ServerAddress address = ServerAddress.parseString(target);
        ServerData serverData = new ServerData(worldName, target, ServerData.Type.OTHER);
        if (worldId != null) {
            SharedWorldClient.playSessionTracker().beginGuestConnect(worldId, worldName, target);
        }

        for (Method method : ConnectScreen.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || !method.getName().equals("startConnecting")) {
                continue;
            }

            Object[] args = buildArguments(method.getParameterTypes(), parent, minecraft, address, serverData);
            if (args == null) {
                continue;
            }

            try {
                method.setAccessible(true);
                method.invoke(null, args);
                return;
            } catch (ReflectiveOperationException exception) {
                SharedWorldClient.LOGGER.warn("Failed to invoke ConnectScreen.startConnecting reflectively", exception);
            }
        }

        minecraft.keyboardHandler.setClipboard(target);
        SharedWorldClient.LOGGER.warn("Could not locate a compatible ConnectScreen.startConnecting signature. Copied {} to clipboard.", target);
    }

    private static Object[] buildArguments(
            Class<?>[] parameterTypes,
            Screen parent,
            Minecraft minecraft,
            ServerAddress address,
            ServerData serverData
    ) {
        Object[] args = new Object[parameterTypes.length];
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> parameter = parameterTypes[index];
            if (Screen.class.isAssignableFrom(parameter)) {
                args[index] = parent;
            } else if (Minecraft.class.isAssignableFrom(parameter)) {
                args[index] = minecraft;
            } else if (ServerAddress.class.isAssignableFrom(parameter)) {
                args[index] = address;
            } else if (ServerData.class.isAssignableFrom(parameter)) {
                args[index] = serverData;
            } else if (parameter == boolean.class || parameter == Boolean.class) {
                args[index] = Boolean.FALSE;
            } else {
                args[index] = null;
            }
        }
        return args;
    }
}
