package link.sharedworld.versioned;

import net.minecraft.network.chat.ClickEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClickEventCompatTest {
    @Test
    void returnsCopyToClipboardPayload() {
        ClickEvent event = new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, "abc.e4mc.link");
        assertEquals("abc.e4mc.link", ClickEventCompat.copyToClipboardValue(event));
    }

    @Test
    void returnsNullForOtherActions() {
        ClickEvent event = new ClickEvent(ClickEvent.Action.OPEN_URL, "https://example.com");
        assertNull(ClickEventCompat.copyToClipboardValue(event));
    }
}
