package link.sharedworld.util;

/**
 * Responsibility:
 * Apply a server-suggested loop interval (remote throttle lever) under strict
 * local safety rules.
 *
 * Postconditions:
 * The server can only slow a loop down, never speed it up past the client's
 * built-in default, and never slow it beyond the local cap that protects
 * liveness timeouts (presence expiry, host lease).
 *
 * Authority source:
 * The client's compiled-in default and cap; the server value is advisory.
 */
public final class ServerPacing {
    private ServerPacing() {
    }

    public static long clampSuggestedInterval(Long suggestedMs, long defaultMs, long maxMs) {
        if (suggestedMs == null || suggestedMs <= 0) {
            return defaultMs;
        }
        return Math.max(defaultMs, Math.min(maxMs, suggestedMs));
    }
}
