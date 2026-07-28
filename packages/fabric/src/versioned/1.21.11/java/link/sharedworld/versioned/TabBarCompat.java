package link.sharedworld.versioned;

import net.minecraft.client.gui.components.tabs.TabNavigationBar;

/** Version-specific tab-bar state control (setTabActiveState is 1.21.6+). */
public final class TabBarCompat {
    private TabBarCompat() {
    }

    public static void setTabActive(TabNavigationBar tabNavigationBar, int index, boolean active) {
        tabNavigationBar.setTabActiveState(index, active);
    }
}
