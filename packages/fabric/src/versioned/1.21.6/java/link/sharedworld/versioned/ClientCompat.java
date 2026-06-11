package link.sharedworld.versioned;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.client.Minecraft;

/**
 * Version-specific client entry points for Minecraft 1.21.6-1.21.8: the session service
 * still hangs directly off Minecraft, leaving a world goes through the saving-screen
 * disconnect, and subtitles are not deferred through screens at all.
 */
public final class ClientCompat {
    private ClientCompat() {
    }

    public static MinecraftSessionService sessionService(Minecraft minecraft) {
        return minecraft.getMinecraftSessionService();
    }

    public static void disconnectFromWorld(Minecraft minecraft) {
        minecraft.disconnectWithSavingScreen();
    }

    public static void drawDeferredSubtitles(Minecraft minecraft) {
    }

    public static java.util.UUID profileId(com.mojang.authlib.GameProfile profile) {
        return profile.getId();
    }

    public static String profileName(com.mojang.authlib.GameProfile profile) {
        return profile.getName();
    }
}
