package link.sharedworld.versioned;

import link.sharedworld.util.MonotonicClock;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;

/**
 * Version-neutral input and render seam for SharedWorld list entries; see
 * {@link VersionedScreen}. Before Minecraft 1.21.9, list entries received their row
 * geometry as render parameters instead of carrying a position, drew via a wide
 * {@code render} method instead of {@code renderContent}, and detected double clicks
 * themselves; this variant adapts all three to the newer shape that shared code targets.
 */
public abstract class VersionedSelectionEntry<E extends VersionedSelectionEntry<E>> extends ObjectSelectionList.Entry<E> {
    private static final long DOUBLE_CLICK_THRESHOLD_MS = 250L;
    private static final int ROW_CONTENT_INSET = 2;

    private int renderLeft;
    private int renderTop;
    private long lastClickTime;

    protected abstract boolean sharedworldMouseClicked(double mouseX, double mouseY, boolean doubleClick);

    public abstract void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick);

    @Override
    public final void render(
            GuiGraphics guiGraphics,
            int index,
            int top,
            int left,
            int width,
            int height,
            int mouseX,
            int mouseY,
            boolean hovered,
            float partialTick
    ) {
        this.renderLeft = left;
        this.renderTop = top;
        this.renderContent(guiGraphics, mouseX, mouseY, hovered, partialTick);
    }

    @Override
    public final boolean mouseClicked(double mouseX, double mouseY, int button) {
        long now = MonotonicClock.millis();
        boolean doubleClick = now - this.lastClickTime < DOUBLE_CLICK_THRESHOLD_MS;
        this.lastClickTime = now;
        return this.sharedworldMouseClicked(mouseX, mouseY, doubleClick);
    }

    // Position getters matching the 1.21.9+ entry API: the old render parameters carry the
    // content origin, and the row box extends one inset beyond it on each side.
    protected final int getContentX() {
        return this.renderLeft;
    }

    protected final int getContentY() {
        return this.renderTop;
    }

    protected final int getX() {
        return this.renderLeft - ROW_CONTENT_INSET;
    }

    protected final int getY() {
        return this.renderTop - ROW_CONTENT_INSET;
    }
}
