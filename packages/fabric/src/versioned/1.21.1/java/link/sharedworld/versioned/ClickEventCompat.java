package link.sharedworld.versioned;

import net.minecraft.network.chat.ClickEvent;

/** Version-specific click-event payload access (pre-1.21.5 uses the action enum). */
public final class ClickEventCompat {
    private ClickEventCompat() {
    }

    /** Returns the copy-to-clipboard payload of the event, or null when it is a different action. */
    public static String copyToClipboardValue(ClickEvent clickEvent) {
        if (clickEvent.getAction() == ClickEvent.Action.COPY_TO_CLIPBOARD) {
            return clickEvent.getValue();
        }
        return null;
    }

    /** Builds a copy-to-clipboard click event (test/support factory). */
    public static ClickEvent copyToClipboard(String value) {
        return new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value);
    }
}
