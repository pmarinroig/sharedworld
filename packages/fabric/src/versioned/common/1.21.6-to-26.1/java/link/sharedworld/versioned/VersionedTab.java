package link.sharedworld.versioned;

import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.network.chat.Component;

/**
 * Version-neutral base for SharedWorld tabs: getTabExtraNarration is not part of the Tab
 * interface on every supported version, so shared tabs implement the sharedworld* hook and
 * this per-version base wires it up where vanilla supports it.
 */
public abstract class VersionedTab implements Tab {
    protected Component sharedworldTabExtraNarration() {
        return Component.empty();
    }

    @Override
    public Component getTabExtraNarration() {
        return this.sharedworldTabExtraNarration();
    }

    /** Hands a selection list to the tab manager where lists are widgets. */
    protected final void sharedworldVisitListChild(
            java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> consumer,
            VersionedSelectionList<?> list
    ) {
        consumer.accept(list);
    }
}
