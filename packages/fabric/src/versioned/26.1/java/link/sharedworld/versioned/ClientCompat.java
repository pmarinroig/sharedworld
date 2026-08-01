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
        minecraft.gui.extractDeferredSubtitles();
    }

    public static void joinServer(Minecraft minecraft, java.util.UUID profileId, String accessToken, String serverId)
            throws com.mojang.authlib.exceptions.AuthenticationException {
        sessionService(minecraft).joinServer(profileId, accessToken, serverId);
    }

    /**
     * The Mojang-signed profile keypair (chat-signing certificate) backing
     * SharedWorld certificate auth, or empty when the account has none
     * (offline profile, certificate-blocking mods).
     */
    public static java.util.Optional<link.sharedworld.api.ProfileCertificateData> profileCertificate(Minecraft minecraft)
            throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        return minecraft.getProfileKeyPairManager().prepareKeyPair()
                .get(10, java.util.concurrent.TimeUnit.SECONDS)
                .map(pair -> new link.sharedworld.api.ProfileCertificateData(
                        pair.privateKey(),
                        pair.publicKey().data().key().getEncoded(),
                        pair.publicKey().data().expiresAt().toEpochMilli(),
                        pair.publicKey().data().keySignature()));
    }

    public static java.util.UUID profileId(com.mojang.authlib.GameProfile profile) {
        return profile.id();
    }

    public static String profileName(com.mojang.authlib.GameProfile profile) {
        return profile.name();
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

    /** Show a screen; screen management moved off Minecraft in newer versions. */
    public static void setScreen(Minecraft minecraft, net.minecraft.client.gui.screens.Screen screen) {
        minecraft.setScreen(screen);
    }

    /** The currently displayed screen, or null when none is open. */
    public static net.minecraft.client.gui.screens.Screen currentScreen(Minecraft minecraft) {
        return minecraft.screen;
    }

    /**
     * Whether the screen belongs to the vanilla world-entry flow (world selection/creation,
     * connecting, level loading). SharedWorld lifecycle screens must never be forced over these.
     */
    public static boolean isWorldEntryScreen(net.minecraft.client.gui.screens.Screen screen) {
        return screen instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
            || screen instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen
            || screen instanceof net.minecraft.client.gui.screens.ConnectScreen
            || screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
            || screen instanceof net.minecraft.client.gui.screens.ProgressScreen
            || screen instanceof net.minecraft.client.gui.screens.GenericMessageScreen;
    }
}
