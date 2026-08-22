package link.sharedworld.util;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Opens a URL in the system browser. AWT's {@link Desktop#browse} deadlocks or
 * silently no-ops on macOS under GLFW (the AWT event loop never runs), so the
 * primary path is the same OS command vanilla uses; Desktop is the fallback
 * for platforms without one.
 */
public final class BrowserOpener {
    private BrowserOpener() {
    }

    /** Returns true when a browser open was successfully started. */
    public static boolean open(String url) {
        String[] command = platformCommand(url);
        if (command != null) {
            try {
                new ProcessBuilder(command).start();
                return true;
            } catch (IOException ignored) {
                // Fall through to Desktop.
            }
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return false;
    }

    private static String[] platformCommand(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return new String[] {"open", url};
        }
        if (os.contains("win")) {
            return new String[] {"rundll32", "url.dll,FileProtocolHandler", url};
        }
        if (os.contains("nix") || os.contains("nux") || os.contains("bsd")) {
            return new String[] {"xdg-open", url};
        }
        return null;
    }
}
