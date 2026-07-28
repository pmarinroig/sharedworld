package link.sharedworld.versioned;

import net.minecraft.network.chat.ClickEvent;

/** Version-specific click-event payload access (the sealed hierarchy is 1.21.5+). */
public final class ClickEventCompat {
    private ClickEventCompat() {
    }

    /** Returns the copy-to-clipboard payload of the event, or null when it is a different action. */
    public static String copyToClipboardValue(ClickEvent clickEvent) {
        if (clickEvent instanceof ClickEvent.CopyToClipboard copyToClipboard) {
            return copyToClipboard.value();
        }
        return null;
    }
}
