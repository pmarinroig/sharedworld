package link.sharedworld.versioned;

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
        return super.mouseClicked(mouseX, mouseY, button);
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
}
