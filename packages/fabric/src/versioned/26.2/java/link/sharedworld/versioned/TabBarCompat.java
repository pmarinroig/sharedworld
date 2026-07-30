package link.sharedworld.versioned;

import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;

/** Version-specific tab-bar control; 26.2 replaced the builder with MenuTabBar. */
public final class TabBarCompat {
    private TabBarCompat() {
    }

    public static void setTabActive(TabNavigationBar tabNavigationBar, int index, boolean active) {
        tabNavigationBar.setTabActiveState(index, active);
    }

    /** Build the standard tab bar; the concrete bar class changed in newer versions. */
    public static TabNavigationBar create(
            net.minecraft.client.gui.components.tabs.TabManager tabManager,
            int width,
            net.minecraft.client.gui.components.tabs.Tab... tabs
    ) {
        return MenuTabBar.builder(tabManager, width).addTabs(tabs).build();
    }

    /** Resize and re-layout the bar; newer versions fold both into one call. */
    public static void arrange(TabNavigationBar tabNavigationBar, int width) {
        tabNavigationBar.arrangeElements(width);
    }
}
