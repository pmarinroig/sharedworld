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

    public static void joinServer(Minecraft minecraft, java.util.UUID profileId, String accessToken, String serverId)
            throws com.mojang.authlib.exceptions.AuthenticationException {
        sessionService(minecraft).joinServer(profileId, accessToken, serverId);
    }

    public static java.util.UUID profileId(com.mojang.authlib.GameProfile profile) {
        return profile.getId();
    }

    public static String profileName(com.mojang.authlib.GameProfile profile) {
        return profile.getName();
    }

    /** The running client's world data version, or a permissive maximum when undetectable. */
    public static int currentDataVersion() {
        try {
            net.minecraft.WorldVersion version = net.minecraft.SharedConstants.getCurrentVersion();
            return version == null ? Integer.MAX_VALUE : version.dataVersion().version();
        } catch (RuntimeException exception) {
            // Headless/undetected version: never block on an unknowable comparison.
            return Integer.MAX_VALUE;
        }
    }

    /** The running client's Minecraft version name (for example "1.21.11"), or null. */
    public static String currentMinecraftVersion() {
        try {
            net.minecraft.WorldVersion version = net.minecraft.SharedConstants.getCurrentVersion();
            return version == null ? null : version.name();
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
