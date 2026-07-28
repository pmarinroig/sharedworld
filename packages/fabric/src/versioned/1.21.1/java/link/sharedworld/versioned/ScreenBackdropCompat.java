package link.sharedworld.versioned;

/**
 * Version-specific screen-backdrop install hook. On this Minecraft version, vanilla renders
 * screen backgrounds before Screen.render, so nothing needs to be installed.
 */
public final class ScreenBackdropCompat {
    private ScreenBackdropCompat() {
    }

    public static void install() {
    }
}
