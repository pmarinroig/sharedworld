package link.sharedworld.devhelper;

import org.lwjgl.glfw.GLFW;

/**
 * Window-visibility policy for automated runs (e2e drills, smoke boots, UI
 * tours). Linux CI hides the game inside xvfb; macOS has no equivalent, so
 * harnesses set SHAREDWORLD_HIDE_WINDOW and the dev helper keeps the GLFW
 * window from ever becoming visible.
 *
 * How the window is kept invisible differs per Minecraft version (see the
 * per-bucket WindowDevHelperMixin): through 1.21.x the window is created
 * visible by the Window constructor and never shown explicitly, so the mixin
 * adds a GLFW_VISIBLE=false hint after glfwDefaultWindowHints; 26.1 moves
 * creation to Window.createGlfwWindow (still created visible); 26.2 already
 * creates it hidden and shows it explicitly at the end of client init, so
 * there the mixin suppresses that glfwShowWindow call instead. 26.3 swapped
 * GLFW for SDL: src/devhelperVersioned/26.3 replaces this class.
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

    /** Adds the invisible-window hint for the upcoming glfwCreateWindow, if requested. */
    public static void applyHiddenWindowHint() {
        if (!HIDE_WINDOW_REQUESTED) {
            return;
        }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    }

    /** Resizes the game window (promo capture framing). */
    public static void setWindowSize(long window, int width, int height) {
        GLFW.glfwSetWindowSize(window, width, height);
    }

    public enum FirstTickHide { NO_WINDOW, WAS_VISIBLE, ALREADY_HIDDEN }

    /**
     * First-tick safety net: hides the window whose GL context is current. The handle
     * comes from the current context instead of Minecraft's Window accessor so this
     * compiles unchanged against every mapped version.
     */
    public static FirstTickHide hideCurrentWindow() {
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) {
            return FirstTickHide.NO_WINDOW;
        }
        boolean wasVisible = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE;
        GLFW.glfwHideWindow(window);
        return wasVisible ? FirstTickHide.WAS_VISIBLE : FirstTickHide.ALREADY_HIDDEN;
    }
}
