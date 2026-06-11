package link.sharedworld.versioned;

import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Version-neutral input seam for SharedWorld list entries; see {@link VersionedScreen}.
 * This variant adapts the event-object input shape introduced in Minecraft 1.21.9.
 */
public abstract class VersionedSelectionEntry<E extends VersionedSelectionEntry<E>> extends ObjectSelectionList.Entry<E> {
    protected abstract boolean sharedworldMouseClicked(double mouseX, double mouseY, boolean doubleClick);

    @Override
    public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return this.sharedworldMouseClicked(event.x(), event.y(), doubleClick);
    }
}
