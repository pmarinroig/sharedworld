package link.sharedworld.util;

/**
 * Monotonic wall-independent clock for UI animation and refresh scheduling.
 *
 * <p>Mirrors vanilla {@code Util.getMillis()} without binding to a Minecraft class whose
 * package moved between versions; SharedWorld compiles one source tree against several
 * Minecraft versions and time arithmetic must not depend on any of them.</p>
 */
public final class MonotonicClock {
    private MonotonicClock() {
    }

    public static long millis() {
        return System.nanoTime() / 1_000_000L;
    }
}
