package link.sharedworld.versioned;

import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;

/**
 * Version-specific tab-bar state control. 1.21/1.21.1 have no setTabActiveState, so this
 * toggles the underlying TabButton's active flag (children() order matches tab order),
 * which blocks both clicks and keyboard cycling for disabled tabs.
 */
public final class TabBarCompat {
    private TabBarCompat() {
    }

    public static void setTabActive(TabNavigationBar tabNavigationBar, int index, boolean active) {
        int tabButtonIndex = 0;
        for (GuiEventListener child : tabNavigationBar.children()) {
            if (child instanceof TabButton tabButton) {
                if (tabButtonIndex == index) {
                    tabButton.active = active;
                    return;
                }
                tabButtonIndex++;
            }
        }
    }
}
