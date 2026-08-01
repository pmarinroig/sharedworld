package link.sharedworld.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Main-thread delivery for async screen completions, with a liveness guard:
 * the action runs only if the given screen is still the current screen.
 * A player who escaped a screen mid-request must not be yanked to a stale
 * screen (or have dead widgets mutated) when the request finally completes.
 * Same pattern as SharedWorldScreen.tick()'s currentScreen check and the
 * create wizard's attempt guard, applied at the completion boundary.
 */
final class ScreenGuards {
    private ScreenGuards() {
    }

    static void runIfCurrent(Screen screen, Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (link.sharedworld.versioned.ClientCompat.currentScreen(minecraft) == screen) {
                action.run();
            }
        });
    }
}
