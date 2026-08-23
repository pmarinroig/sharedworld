package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import net.minecraft.network.chat.Component;

public final class SharedWorldText {
    private SharedWorldText() {
    }

    public static Component component(String key, Object... args) {
        return Component.translatable(key, args);
    }

    /**
     * EditBox hint text, styled dark grey explicitly: vanilla only started
     * auto-styling unstyled hints (EditBox.DEFAULT_HINT_STYLE) on the newest
     * versions, so older buckets would render them like typed text. Styled
     * components bypass the auto-wrap, so this looks identical everywhere.
     */
    public static Component hint(String key, Object... args) {
        return Component.translatable(key, args).withStyle(net.minecraft.ChatFormatting.DARK_GRAY);
    }

    public static String string(String key, Object... args) {
        return component(key, args).getString();
    }

    public static String defaultMotd() {
        return string("screen.sharedworld.motd_hint", SharedWorldApiClient.currentPlayerName());
    }

    public static String displayWorldName(String worldName) {
        return worldName == null || worldName.isBlank()
                ? string("screen.sharedworld.unnamed_world")
                : worldName;
    }

    public static String errorMessageOrDefault(String message) {
        return message == null || message.isBlank()
                ? string("screen.sharedworld.error_generic")
                : message;
    }

    public static Component playerCount(int current, int max) {
        return component("screen.sharedworld.player_count", current, max);
    }

    private static final String ELLIPSIS = "...";

    /** Seam over Font width/substring operations so truncation stays unit-testable. */
    public interface WidthProbe {
        int width(String text);

        /** The longest prefix of text that fits maxWidth. */
        String prefixByWidth(String text, int maxWidth);

        /** The longest suffix of text that fits maxWidth. */
        String suffixByWidth(String text, int maxWidth);
    }

    /** Tail-ellipsized text fitting maxWidth ("My Very Long Wo..."). */
    public static String truncate(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        return truncate(text, maxWidth, fontProbe(font));
    }

    /** Leading-ellipsized text fitting maxWidth ("...minecraft/saves/My World"); for paths, whose tail is the informative part. */
    public static String truncateLeading(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        return truncateLeading(text, maxWidth, fontProbe(font));
    }

    static String truncate(String text, int maxWidth, WidthProbe probe) {
        if (text == null) {
            return "";
        }
        if (probe.width(text) <= maxWidth) {
            return text;
        }
        int budget = maxWidth - probe.width(ELLIPSIS);
        if (budget <= 0) {
            return ELLIPSIS;
        }
        return probe.prefixByWidth(text, budget) + ELLIPSIS;
    }

    static String truncateLeading(String text, int maxWidth, WidthProbe probe) {
        if (text == null) {
            return "";
        }
        if (probe.width(text) <= maxWidth) {
            return text;
        }
        int budget = maxWidth - probe.width(ELLIPSIS);
        if (budget <= 0) {
            return ELLIPSIS;
        }
        return ELLIPSIS + probe.suffixByWidth(text, budget);
    }

    private static WidthProbe fontProbe(net.minecraft.client.gui.Font font) {
        return new WidthProbe() {
            @Override
            public int width(String text) {
                return font.width(text);
            }

            @Override
            public String prefixByWidth(String text, int maxWidth) {
                return font.plainSubstrByWidth(text, maxWidth);
            }

            @Override
            public String suffixByWidth(String text, int maxWidth) {
                return font.plainSubstrByWidth(text, maxWidth, true);
            }
        };
    }
}
