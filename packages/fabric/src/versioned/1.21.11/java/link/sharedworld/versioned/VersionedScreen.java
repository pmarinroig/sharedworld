package link.sharedworld.versioned;

import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Version-neutral input seam for SharedWorld screens. Minecraft 1.21.9 replaced raw
 * coordinate/keycode input callbacks with event objects; shared screen code implements the
 * sharedworld* hooks instead of overriding the version-specific callbacks directly. This
 * variant adapts the event-object shape.
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.sharedworldMouseClicked(event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.sharedworldMouseDragged(event.x(), event.y())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.sharedworldMouseReleased();
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        TabNavigationBar tabBar = this.sharedworldTabNavigationBar();
        if (tabBar != null && tabBar.keyPressed(event)) {
            return true;
        }
        return super.keyPressed(event);
    }
}
