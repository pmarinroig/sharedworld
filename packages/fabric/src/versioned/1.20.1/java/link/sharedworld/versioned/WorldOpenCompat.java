package link.sharedworld.versioned;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;

/** Version-specific world-open entry point (1.20.x loads by level id with a parent screen). */
public final class WorldOpenCompat {
    private WorldOpenCompat() {
    }

    /**
     * Opens the level with the given id from the given storage, on the render thread.
     * 1.20.x has no cancel hook: a cancelled vanilla prompt returns to the current screen.
     */
    public static void openExistingWorld(Minecraft minecraft, LevelStorageSource levelSource, String levelId, Runnable onCancel) {
        WorldOpenFlows flows = new WorldOpenFlows(minecraft, levelSource);
        flows.loadLevel(minecraft.screen, levelId);
    }
}
