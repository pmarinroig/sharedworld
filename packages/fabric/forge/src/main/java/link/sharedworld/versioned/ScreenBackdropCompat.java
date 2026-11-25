package link.sharedworld.versioned;

import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Forge replacement for the 1.20.1 backdrop hook (the shared versioned copy
 * uses Fabric ScreenEvents and is excluded from this build).
 */
public final class ScreenBackdropCompat {
    private ScreenBackdropCompat() {
    }

    public static void install() {
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> {
            if (event.getScreen() instanceof VersionedScreen versionedScreen) {
                versionedScreen.sharedworldSetInitialFocus();
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Render.Pre event) -> {
            if (event.getScreen() instanceof VersionedScreen versionedScreen) {
                versionedScreen.sharedworldRenderBackdropBeforeRender(event.getGuiGraphics(), event.getPartialTick());
            }
        });
    }
}
