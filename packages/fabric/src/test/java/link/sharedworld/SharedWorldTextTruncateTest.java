package link.sharedworld;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SharedWorldTextTruncateTest {
    /** 1px per character, like a fixed-width font. */
    private static final SharedWorldText.WidthProbe PROBE = new SharedWorldText.WidthProbe() {
        @Override
        public int width(String text) {
            return text.length();
        }

        @Override
        public String prefixByWidth(String text, int maxWidth) {
            return text.substring(0, Math.min(text.length(), Math.max(0, maxWidth)));
        }

        @Override
        public String suffixByWidth(String text, int maxWidth) {
            return text.substring(text.length() - Math.min(text.length(), Math.max(0, maxWidth)));
        }
    };

    @Test
    void fittingTextIsUnchanged() {
        assertEquals("hello", SharedWorldText.truncate("hello", 5, PROBE));
        assertEquals("hello", SharedWorldText.truncateLeading("hello", 5, PROBE));
    }

    @Test
    void overflowingTextIsTailEllipsized() {
        assertEquals("hello w...", SharedWorldText.truncate("hello world!", 10, PROBE));
    }

    @Test
    void leadingEllipsisKeepsTheInformativeTail() {
        assertEquals("...saves/My World", SharedWorldText.truncateLeading("C:/minecraft/saves/My World", 17, PROBE));
    }

    @Test
    void degenerateWidthsNeverThrow() {
        assertEquals("...", SharedWorldText.truncate("hello", 0, PROBE));
        assertEquals("...", SharedWorldText.truncate("hello", 3, PROBE));
        assertEquals("...", SharedWorldText.truncateLeading("hello", 1, PROBE));
        assertEquals("", SharedWorldText.truncate(null, 10, PROBE));
        assertEquals("", SharedWorldText.truncateLeading(null, 10, PROBE));
    }
}
