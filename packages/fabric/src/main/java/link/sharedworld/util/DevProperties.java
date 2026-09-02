package link.sharedworld.util;

/** Dev-only tuning knobs read from system properties. */
public final class DevProperties {
    private DevProperties() {
    }

    /**
     * The positive long value of a system property, or fallback when it is unset,
     * malformed, or not positive: a broken dev override must never change
     * production behavior.
     */
    public static long positiveLong(String property, long fallback) {
        String override = System.getProperty(property, "").trim();
        if (override.isEmpty()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(override);
            return parsed > 0L ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
