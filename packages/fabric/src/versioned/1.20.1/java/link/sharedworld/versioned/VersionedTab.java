package link.sharedworld.versioned;

import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.network.chat.Component;

/**
 * Version-neutral base for SharedWorld tabs. The 1.21/1.21.1 Tab interface has no
 * getTabExtraNarration, so the sharedworld* hook is accepted but unused here.
 */
public abstract class VersionedTab implements Tab {
    protected Component sharedworldTabExtraNarration() {
        return Component.empty();
    }

    /**
     * 1.20.x lists are not widgets, so tab switching cannot add/remove them through the
     * TabManager; screens register them directly (LayoutCompat.registerTabList) and sync
     * visibility per frame via sharedworldSetVisibleForTab.
     */
    protected final void sharedworldVisitListChild(
            java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> consumer,
            VersionedSelectionList<?> list
    ) {
    }
}
