package link.sharedworld.devhelper;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge twin of the Fabric DevHelperClient safety net: if the
 * WindowDevHelperMixin's injection point drifted and the window is still
 * visible on the first tick, hide it late and warn. Same timing rationale as
 * the Fabric twin: GLFW is not initialized at mod-construction time.
 */
@Mod(value = "sharedworld_dev_helper", dist = Dist.CLIENT)
public final class DevHelperNeoForge {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-dev-helper");

    private boolean windowHidden;

    public DevHelperNeoForge(IEventBus modBus) {
        if (!DevHelperWindowPolicy.hideWindowRequested()) {
            return;
        }
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            if (this.windowHidden) {
                return;
            }
            this.windowHidden = true;
            long window = GLFW.glfwGetCurrentContext();
            if (window == 0L) {
                LOGGER.warn("SHAREDWORLD_HIDE_WINDOW is set but no GL context is current on the first tick; leaving the window visible.");
                return;
            }
            boolean wasVisible = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE;
            GLFW.glfwHideWindow(window);
            if (wasVisible) {
                LOGGER.warn("SharedWorld dev helper hid the game window on the first tick; the WindowDevHelperMixin should have kept it invisible from creation - check its injection point against this Minecraft version.");
            } else {
                LOGGER.info("SharedWorld dev helper confirmed the game window is hidden (SHAREDWORLD_HIDE_WINDOW).");
            }
        });
    }
}
