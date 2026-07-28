package link.sharedworld.versioned;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;

/**
 * Version-neutral selection-list base for 1.20.x: the six-arg (y0/y1) constructor era,
 * where lists are not layout elements and geometry setters do not exist yet.
 */
public abstract class VersionedSelectionList<E extends ObjectSelectionList.Entry<E>> extends ObjectSelectionList<E> {
    protected VersionedSelectionList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
        super(minecraft, width, height, y, y + height, itemHeight);
        // Screens draw the menu background themselves; the list's own dirt fill would
        // paint over it.
        this.setRenderBackground(false);
    }

    public void sharedworldUpdateSize(int width, HeaderAndFooterLayout layout) {
        this.updateSize(width, layout.getHeight(), layout.getHeaderHeight(), layout.getHeight() - layout.getFooterHeight());
    }

    public void setPosition(int x, int y) {
        int listHeight = this.y1 - this.y0;
        this.setLeftPos(x);
        this.y0 = y;
        this.y1 = y + listHeight;
    }

    public void setWidth(int width) {
        this.width = width;
        this.x1 = this.x0 + width;
    }

    public void setHeight(int height) {
        this.height = height;
        this.y1 = this.y0 + height;
    }

    public int getY() {
        return this.y0;
    }

    @Override
    protected int getScrollbarPosition() {
        // The era default (width / 2 + 124) assumes a full-screen centered list;
        // anchor to the list's own right edge like newer versions do, so narrow
        // tab-positioned lists keep their scrollbar attached.
        return this.x1 - 6;
    }

    /** Tab-visibility sync; hidden lists skip rendering and swallow no input. */
    public void sharedworldSetVisibleForTab(boolean visibleForTab) {
        this.sharedworldVisibleForTab = visibleForTab;
    }

    private boolean sharedworldVisibleForTab = true;

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!this.sharedworldVisibleForTab) {
            return;
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return this.sharedworldVisibleForTab && super.isMouseOver(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return this.sharedworldVisibleForTab && super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return this.sharedworldVisibleForTab && super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return this.sharedworldVisibleForTab && super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }
}
