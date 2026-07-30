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

    /** Position+size in one call (newer versions also re-lay cached entry rectangles here). */
    public void sharedworldSetBounds(int x, int y, int width, int height) {
        this.setX(x);
        this.setY(y);
        this.setWidth(width);
        this.setHeight(height);
    }

    /** Tab-visibility sync; on this version the widget visible flag is authoritative. */
    public void sharedworldSetVisibleForTab(boolean visibleForTab) {
        this.visible = visibleForTab;
    }

    /**
     * Vanilla derives the scrollbar x from the centered-row geometry, which for
     * SharedWorld's near-full-width rows lands past the widget's right edge —
     * rendering the bar outside the bounds that mouse clicks are hit-tested
     * against, so it could never be dragged. Pin it just inside the right edge.
     */
    @Override
    protected int scrollBarX() {
        return this.getRight() - 6;
    }
}
