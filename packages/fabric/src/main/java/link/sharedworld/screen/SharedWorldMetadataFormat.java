package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;

/**
 * Formatting helpers for world metadata inputs, shared by the create wizard
 * and the edit screen (extracted from a long-dead form screen).
 */
final class SharedWorldMetadataFormat {
    private SharedWorldMetadataFormat() {
    }

    static String decodeMotdInput(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\\u00A7", "§").replace("\\u00a7", "§").replace("\\n", "\n").trim();
    }

    static String effectiveMotd(String input) {
        String decoded = decodeMotdInput(input);
        return decoded == null || decoded.isBlank() ? defaultMotd() : decoded;
    }

    static String encodeMotdInput(String motd) {
        if (motd == null || motd.isBlank()) {
            return defaultMotd();
        }
        return motd.replace("§", "\\u00a7").replace("\n", "\\n");
    }

    static String defaultMotd() {
        return SharedWorldText.defaultMotd();
    }

    static String friendlyMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return SharedWorldText.string("screen.sharedworld.error_generic");
        }
        return throwable.getMessage();
    }
}
