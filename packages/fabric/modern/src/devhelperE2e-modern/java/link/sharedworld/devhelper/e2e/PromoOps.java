package link.sharedworld.devhelper.e2e;

import link.sharedworld.versioned.ClientCompat;
import net.minecraft.client.Minecraft;

/**
 * Modern-project stand-in for the shared PromoOps: promo footage capture (frame
 * recorder, camera choreography) hooks classic-bucket rendering and stays a
 * classic tool. The two-player drills only need "shutdown" and screenName here;
 * every other promo command is answered with promo-unsupported.
 */
final class PromoOps {
    /** Returns true when "shutdown" was read. */
    boolean tick(Minecraft minecraft, E2eCommands commands, E2eMarkers markers) {
        String command = commands.poll();
        if (command == null) {
            return false;
        }
        if ("shutdown".equals(command)) {
            markers.emit("shutdown-received", null);
            return true;
        }
        this.handle(minecraft, command, markers);
        return false;
    }

    void tickEffects(Minecraft minecraft, E2eMarkers markers) {
    }

    void handle(Minecraft minecraft, String command, E2eMarkers markers) {
        markers.emit("promo-unsupported", command);
    }

    static String screenName(Minecraft minecraft) {
        return ClientCompat.currentScreen(minecraft) == null ? "none" : ClientCompat.currentScreen(minecraft).getClass().getSimpleName();
    }
}
