package link.sharedworld.versioned;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/**
 * Version-specific screen-backdrop install hook. 1.21/1.21.1 draw the screen
 * background (including the blur pass) inside Screen.render — after any content a
 * subclass drew first, which blurs that content. This before-render hook restores the
 * newer draw-background-first order; VersionedScreen suppresses the vanilla pass.
 */
public final class ScreenBackdropCompat {
    private ScreenBackdropCompat() {
    }

    public static void install() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof VersionedScreen versionedScreen) {
                ScreenEvents.beforeRender(screen).register((currentScreen, guiGraphics, mouseX, mouseY, tickDelta) ->
                        versionedScreen.sharedworldRenderBackdropBeforeRender(guiGraphics, tickDelta));
            }
        });
    }
}
