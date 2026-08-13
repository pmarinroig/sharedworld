package link.sharedworld.devhelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only client hooks. Today: headless-ish runs. Linux CI hides the game
 * inside xvfb, but macOS has no equivalent, so automated runs (e2e drills,
 * smoke boots, UI tours) would pop a real focusable window that a stray click
 * or keypress could corrupt. When the harness sets SHAREDWORLD_HIDE_WINDOW,
 * the GLFW window is hidden on the first client tick; the game keeps
 * rendering and ticking exactly as under xvfb.
 *
 * Timing: client entrypoints run BEFORE GLFW is initialized (calling any GLFW
 * function there queues a "not initialized" error that crashes the later
 * GLX init check), so the hide is deferred to the first tick, when the window
 * exists and its GL context is current on this thread. The handle comes from
 * the current context instead of Minecraft's Window accessor so this compiles
 * unchanged against every mapped version.
 */
public final class DevHelperClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-dev-helper");

    private boolean windowHidden;

    @Override
    public void onInitializeClient() {
        String hideWindow = System.getenv("SHAREDWORLD_HIDE_WINDOW");
        if (hideWindow == null || !(hideWindow.equals("1") || hideWindow.equalsIgnoreCase("true"))) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.windowHidden) {
                return;
            }
            this.windowHidden = true;
            long window = GLFW.glfwGetCurrentContext();
            if (window == 0L) {
                LOGGER.warn("SHAREDWORLD_HIDE_WINDOW is set but no GL context is current on the first tick; leaving the window visible.");
                return;
            }
            GLFW.glfwHideWindow(window);
            LOGGER.info("SharedWorld dev helper hid the game window (SHAREDWORLD_HIDE_WINDOW).");
        });
    }
}
