package link.sharedworld.versioned;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;

/**
 * Version-neutral selection-list base: normalizes the ObjectSelectionList constructor and
 * the geometry calls whose signatures changed across Minecraft versions.
 */
public abstract class VersionedSelectionList<E extends ObjectSelectionList.Entry<E>> extends ObjectSelectionList<E> {
    protected VersionedSelectionList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
        super(minecraft, width, height, y, itemHeight);
    }

    public void sharedworldUpdateSize(int width, HeaderAndFooterLayout layout) {
        this.updateSize(width, layout);
    }
}
