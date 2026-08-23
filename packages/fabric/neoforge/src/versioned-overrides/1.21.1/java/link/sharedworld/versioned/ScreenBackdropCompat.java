package link.sharedworld.versioned;

import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge replacement for the 1.21/1.21.1 backdrop hook (the shared
 * versioned copy uses Fabric ScreenEvents and is excluded from this build).
 * Same contract: draw the SharedWorld backdrop before Screen.render so the
 * blur pass never blurs content a subclass drew first; VersionedScreen
 * suppresses the vanilla background pass.
 */
public final class ScreenBackdropCompat {
    private ScreenBackdropCompat() {
    }

    public static void install() {
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Pre event) -> {
            if (event.getScreen() instanceof VersionedScreen versionedScreen) {
                versionedScreen.sharedworldRenderBackdropBeforeRender(event.getGuiGraphics(), event.getPartialTick());
            }
        });
    }
}
