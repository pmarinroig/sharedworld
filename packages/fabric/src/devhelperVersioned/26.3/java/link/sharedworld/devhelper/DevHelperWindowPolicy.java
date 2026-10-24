package link.sharedworld.devhelper;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;

/**
 * 26.3 replacement for the shared (GLFW) DevHelperWindowPolicy: Minecraft now opens
 * its window through SDL3 and creates it visible, so the WindowDevHelperMixin adds
 * {@link #hiddenWindowFlag()} to the creation flags instead. The modern build drops
 * the shared copy of this file for this bucket.
 */
public final class DevHelperWindowPolicy {
    private static final boolean HIDE_WINDOW_REQUESTED = readHideWindowEnv();

    private DevHelperWindowPolicy() {
    }

    private static boolean readHideWindowEnv() {
        String hideWindow = System.getenv("SHAREDWORLD_HIDE_WINDOW");
        return hideWindow != null && (hideWindow.equals("1") || hideWindow.equalsIgnoreCase("true"));
    }

    public static boolean hideWindowRequested() {
        return HIDE_WINDOW_REQUESTED;
    }

    /** SDL_WINDOW_HIDDEN when requested, for the window-creation flags; 0 otherwise. */
    public static long hiddenWindowFlag() {
        return HIDE_WINDOW_REQUESTED ? SDLVideo.SDL_WINDOW_HIDDEN : 0L;
    }

    /** Resizes the game window (promo capture framing). */
    public static void setWindowSize(long window, int width, int height) {
        SDLVideo.SDL_SetWindowSize(window, width, height);
    }

    public enum FirstTickHide { NO_WINDOW, WAS_VISIBLE, ALREADY_HIDDEN }

    /** First-tick safety net: hides Minecraft's SDL window. */
    public static FirstTickHide hideCurrentWindow() {
        long window = Minecraft.getInstance().getWindow().handle();
        if (window == 0L) {
            return FirstTickHide.NO_WINDOW;
        }
        boolean wasVisible = (SDLVideo.SDL_GetWindowFlags(window) & SDLVideo.SDL_WINDOW_HIDDEN) == 0L;
        SDLVideo.SDL_HideWindow(window);
        return wasVisible ? FirstTickHide.WAS_VISIBLE : FirstTickHide.ALREADY_HIDDEN;
    }
}
