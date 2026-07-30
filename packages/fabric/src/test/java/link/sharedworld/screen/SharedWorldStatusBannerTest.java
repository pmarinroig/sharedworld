package link.sharedworld.screen;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedWorldStatusBannerTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final SharedWorldStatusBanner banner = new SharedWorldStatusBanner(now::get);

    @Test
    void startsHidden() {
        assertFalse(banner.isVisible());
        assertNull(banner.message());
    }

    @Test
    void stickyMessagesStayUntilReplacedOrCleared() {
        banner.set(SharedWorldStatusBanner.Kind.ERROR, Component.literal("Something broke."));
        now.addAndGet(3_600_000);
        assertTrue(banner.isVisible());
        assertEquals(SharedWorldStatusBanner.Kind.ERROR, banner.kind());

        banner.clear();
        assertFalse(banner.isVisible());
    }

    @Test
    void transientMessagesExpireOnTheirOwn() {
        banner.setTransient(SharedWorldStatusBanner.Kind.SUCCESS, Component.literal("Saved."), 5_000);
        assertTrue(banner.isVisible());

        now.addAndGet(4_999);
        assertTrue(banner.isVisible());

        now.addAndGet(1);
        assertFalse(banner.isVisible());
        assertNull(banner.message());
    }

    @Test
    void clearStickyLeavesALiveTransientAlone() {
        banner.setTransient(SharedWorldStatusBanner.Kind.SUCCESS, Component.literal("Saved."), 5_000);
        banner.clearSticky();
        assertTrue(banner.isVisible());

        banner.set(SharedWorldStatusBanner.Kind.INFO, Component.literal("Loading..."));
        banner.clearSticky();
        assertFalse(banner.isVisible());
    }

    @Test
    void replacingATransientWithAStickyMakesItSticky() {
        banner.setTransient(SharedWorldStatusBanner.Kind.SUCCESS, Component.literal("Saved."), 5_000);
        banner.set(SharedWorldStatusBanner.Kind.WARNING, Component.literal("Click again to confirm."));
        now.addAndGet(3_600_000);
        assertTrue(banner.isVisible());
        assertEquals(SharedWorldStatusBanner.Kind.WARNING, banner.kind());
    }

    @Test
    void eachKindHasItsColor() {
        assertEquals(0xFFB8C5D6, SharedWorldStatusBanner.Kind.INFO.color());
        assertEquals(0xFF9FE3A5, SharedWorldStatusBanner.Kind.SUCCESS.color());
        assertEquals(0xFFFFD37A, SharedWorldStatusBanner.Kind.WARNING.color());
        assertEquals(0xFFFF5555, SharedWorldStatusBanner.Kind.ERROR.color());
    }
}
