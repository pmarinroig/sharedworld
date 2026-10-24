package link.sharedworld.devhelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only client hooks. Today: headless-ish runs. Linux CI hides the game
 * inside xvfb, but macOS has no equivalent, so automated runs (e2e drills,
 * smoke boots, UI tours) would pop a real focusable window that a stray click
 * or keypress could corrupt. When the harness sets SHAREDWORLD_HIDE_WINDOW,
 * the per-bucket WindowDevHelperMixin keeps the window from ever becoming
 * visible; this first-tick hide is the safety net in case the mixin's
 * injection point drifts on a future Minecraft version.
 *
 * Timing: client entrypoints run BEFORE the windowing library is initialized
 * (calling any GLFW function there queues a "not initialized" error that
 * crashes the later GLX init check), so the fallback hide is deferred to the
 * first tick, when the window exists. The windowing calls live in
 * DevHelperWindowPolicy, which 26.3 replaces with an SDL version.
 */
public final class DevHelperClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-dev-helper");

    private boolean windowHidden;

    @Override
    public void onInitializeClient() {
        if (!DevHelperWindowPolicy.hideWindowRequested()) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.windowHidden) {
                return;
            }
            this.windowHidden = true;
            switch (DevHelperWindowPolicy.hideCurrentWindow()) {
                case NO_WINDOW -> LOGGER.warn("SHAREDWORLD_HIDE_WINDOW is set but no game window was found on the first tick; leaving it visible.");
                case WAS_VISIBLE -> LOGGER.warn("SharedWorld dev helper hid the game window on the first tick; the WindowDevHelperMixin should have kept it invisible from creation - check its injection point against this Minecraft version.");
                case ALREADY_HIDDEN -> LOGGER.info("SharedWorld dev helper confirmed the game window is hidden (SHAREDWORLD_HIDE_WINDOW).");
            }
        });
    }
}
