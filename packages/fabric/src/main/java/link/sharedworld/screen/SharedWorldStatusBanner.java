package link.sharedworld.screen;

import java.util.List;
import java.util.function.LongSupplier;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The one status line every SharedWorld screen shares: a short colored message
 * anchored above the footer. Sticky by default; success confirmations are
 * usually transient so they get out of the way on their own. This replaces the
 * screen-by-screen bespoke message lines (each with its own colors and
 * lifetime) that used to make feedback inconsistent or invisible.
 */
public final class SharedWorldStatusBanner {
    public enum Kind {
        INFO(0xFFB8C5D6),
        SUCCESS(0xFF9FE3A5),
        WARNING(0xFFFFD37A),
        ERROR(0xFFFF5555);

        private final int color;

        Kind(int color) {
            this.color = color;
        }

        public int color() {
            return this.color;
        }
    }

    private static final int MAX_LINES = 2;
    private static final int LINE_HEIGHT = 10;
    /**
     * The vertical slot a screen reserves when it lays widgets out around a banner:
     * a full-height message plus a little breathing room. Keeping it derived from
     * MAX_LINES guarantees layout reservations can never be smaller than the text.
     */
    public static final int BAND_HEIGHT = MAX_LINES * LINE_HEIGHT + 2;

    private final LongSupplier clock;
    private Component message;
    private Kind kind = Kind.INFO;
    /** 0 means sticky: visible until replaced or cleared. */
    private long expiresAtMillis;

    public SharedWorldStatusBanner() {
        this(link.sharedworld.util.MonotonicClock::millis);
    }

    SharedWorldStatusBanner(LongSupplier clock) {
        this.clock = clock;
    }

    public void set(Kind kind, Component message) {
        this.kind = kind;
        this.message = message;
        this.expiresAtMillis = 0;
    }

    public void setTransient(Kind kind, Component message, long ttlMillis) {
        this.kind = kind;
        this.message = message;
        this.expiresAtMillis = this.clock.getAsLong() + Math.max(0, ttlMillis);
    }

    public void clear() {
        this.message = null;
        this.expiresAtMillis = 0;
    }

    /**
     * Clear only a sticky message. A transient confirmation keeps its remaining
     * time, so state-derived refreshes (which re-run on every selection or tab
     * change) cannot wipe a "Saved." the player has not had time to read.
     */
    public void clearSticky() {
        if (this.expiresAtMillis == 0) {
            this.clear();
        }
    }

    public boolean isVisible() {
        if (this.message == null) {
            return false;
        }
        if (this.expiresAtMillis != 0 && this.clock.getAsLong() >= this.expiresAtMillis) {
            this.message = null;
            this.expiresAtMillis = 0;
            return false;
        }
        return true;
    }

    Component message() {
        return this.isVisible() ? this.message : null;
    }

    Kind kind() {
        return this.kind;
    }

    /**
     * Draw the message centered on {@code centerX}, with the last line's
     * baseline row ending at {@code bottomY} so the banner grows upward and
     * never collides with the footer below it.
     */
    public void renderBottomCentered(GuiGraphics guiGraphics, Font font, int centerX, int bottomY, int maxWidth) {
        if (!this.isVisible()) {
            return;
        }
        List<FormattedCharSequence> lines = font.split(this.message, Math.max(50, maxWidth));
        if (lines.size() > MAX_LINES) {
            lines = lines.subList(0, MAX_LINES);
        }
        int y = bottomY - lines.size() * LINE_HEIGHT;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(font, line, centerX - font.width(line) / 2, y, this.kind.color());
            y += LINE_HEIGHT;
        }
    }
}
