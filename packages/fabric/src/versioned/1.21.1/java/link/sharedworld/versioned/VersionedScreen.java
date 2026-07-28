package link.sharedworld.versioned;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Version-neutral input seam for SharedWorld screens. This variant adapts the raw
 * coordinate/keycode input callbacks used before Minecraft 1.21.9 introduced event objects.
 */
public abstract class VersionedScreen extends Screen {
    protected VersionedScreen(Component title) {
        super(title);
    }

    /** Return true to consume the click before vanilla widget handling runs. */
    protected boolean sharedworldMouseClicked(double mouseX, double mouseY) {
        return false;
    }

    /** Return true to consume the drag before vanilla widget handling runs. */
    protected boolean sharedworldMouseDragged(double mouseX, double mouseY) {
        return false;
    }

    /** Always invoked on release, before vanilla handling. */
    protected void sharedworldMouseReleased() {
    }

    /** A tab bar that should get key events before vanilla handling, or null. */
    protected TabNavigationBar sharedworldTabNavigationBar() {
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.sharedworldMouseClicked(mouseX, mouseY)) {
            return true;
        }
        this.sharedworldMouseClickInProgress = true;
        try {
            return super.mouseClicked(mouseX, mouseY, button);
        } finally {
            this.sharedworldMouseClickInProgress = false;
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.sharedworldMouseDragged(mouseX, mouseY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.sharedworldMouseReleased();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        TabNavigationBar tabBar = this.sharedworldTabNavigationBar();
        if (tabBar != null && tabBar.keyPressed(keyCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Return true to consume the scroll before vanilla widget handling runs. */
    protected boolean sharedworldMouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        return false;
    }

    /** Screens returning true replace the vanilla background with the panorama backdrop. */
    protected boolean sharedworldUsePanoramaBackdrop() {
        return false;
    }

    protected void sharedworldRenderMenuBackground(GuiGraphics guiGraphics) {
        this.renderMenuBackground(guiGraphics);
    }

    protected void sharedworldRenderPanoramaBackdrop(GuiGraphics guiGraphics, float partialTick) {
        this.renderPanorama(guiGraphics, partialTick);
        this.renderBlurredBackground(partialTick);
        renderMenuBackgroundTexture(guiGraphics, MENU_BACKGROUND, 0, 0, 0.0F, 0.0F, this.width, this.height);
        ClientCompat.drawDeferredSubtitles(this.minecraft);
    }

    /**
     * 1.21/1.21.1 render the background (panorama + blur + menu gradient) INSIDE
     * Screen.render, after any content the subclass drew first — blurring that
     * content. ScreenBackdropCompat draws the backdrop before render() instead,
     * and this override suppresses the vanilla mid-render pass.
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    /** Called by ScreenBackdropCompat before render() so backgrounds precede content. */
    final void sharedworldRenderBackdropBeforeRender(GuiGraphics guiGraphics, float partialTick) {
        if (this.sharedworldUsePanoramaBackdrop()) {
            this.sharedworldRenderPanoramaBackdrop(guiGraphics, partialTick);
            return;
        }
        if (this.minecraft != null && this.minecraft.level == null) {
            this.renderPanorama(guiGraphics, partialTick);
        }
        this.renderBlurredBackground(partialTick);
        this.renderMenuBackground(guiGraphics);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.sharedworldMouseScrolled(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /** Version-neutral initial-focus hook (vanilla's no-arg setInitialFocus is 1.20.5+). */
    protected void sharedworldSetInitialFocus() {
    }

    @Override
    protected void setInitialFocus() {
        this.sharedworldSetInitialFocus();
    }

    private boolean sharedworldMouseClickInProgress;

    /**
     * This era sets focus on the clicked widget AFTER its press handler runs, so a
     * clicked button keeps the white focus outline indefinitely (newer versions only
     * render focus outlines for keyboard navigation). Mouse clicks therefore never
     * focus buttons here; edit boxes and lists still take focus normally, and
     * keyboard navigation is unaffected.
     */
    @Override
    public void setFocused(net.minecraft.client.gui.components.events.GuiEventListener listener) {
        if (this.sharedworldMouseClickInProgress
                && listener instanceof net.minecraft.client.gui.components.AbstractButton) {
            super.setFocused(null);
            return;
        }
        super.setFocused(listener);
    }
}
