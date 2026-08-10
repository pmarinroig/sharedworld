package link.sharedworld;

/**
 * Coarse "is the player doing SharedWorld things" signal for the realtime
 * channel's reconnect policy: active → aggressive reconnects (must beat the
 * coordinator's 30s host-disconnect grace); idle → back way off. Screens
 * count as recent activity for a minute after they were last shown, since
 * there is no screen-close hook to key on.
 */
public final class SharedWorldActivity {
    private static final long SCREEN_RECENCY_MS = 60_000L;

    private static volatile long lastScreenSeenAt;

    private SharedWorldActivity() {
    }

    /** Called from SharedWorld screens on init and while frontmost. */
    public static void touchScreen() {
        lastScreenSeenAt = System.currentTimeMillis();
    }

    public static boolean screenRecentlyOpen() {
        return System.currentTimeMillis() - lastScreenSeenAt < SCREEN_RECENCY_MS;
    }
}
