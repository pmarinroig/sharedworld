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

    /** Hands a selection list to the tab manager where lists are widgets. */
    protected final void sharedworldVisitListChild(
            java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> consumer,
            VersionedSelectionList<?> list
    ) {
        consumer.accept(list);
    }
}
