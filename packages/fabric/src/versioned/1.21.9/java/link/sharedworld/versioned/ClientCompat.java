package link.sharedworld.versioned;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.client.Minecraft;

/** Version-specific client entry points whose location moved across Minecraft versions. */
public final class ClientCompat {
    private ClientCompat() {
    }

    public static MinecraftSessionService sessionService(Minecraft minecraft) {
        return minecraft.services().sessionService();
    }

    public static void disconnectFromWorld(Minecraft minecraft) {
        minecraft.disconnectFromWorld(null);
    }

    public static void drawDeferredSubtitles(Minecraft minecraft) {
        minecraft.gui.renderDeferredSubtitles();
    }

    public static java.util.UUID profileId(com.mojang.authlib.GameProfile profile) {
        return profile.id();
    }

    public static String profileName(com.mojang.authlib.GameProfile profile) {
        return profile.name();
    }
}
