package link.sharedworld.versioned;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/**
 * Version-specific screen-backdrop install hook. 1.20.x screens draw their own backgrounds
 * inside render(), which SharedWorld's shared screens do not do (newer versions render the
 * background before Screen.render). Installing a before-render hook restores that behavior.
 */
public final class ScreenBackdropCompat {
    private ScreenBackdropCompat() {
    }

    public static void install() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof VersionedScreen versionedScreen) {
                ScreenEvents.beforeRender(screen).register((currentScreen, guiGraphics, mouseX, mouseY, tickDelta) ->
                        versionedScreen.sharedworldRenderBackdropBeforeRender(guiGraphics, tickDelta));
                versionedScreen.sharedworldSetInitialFocus();
            }
        });
    }
}
