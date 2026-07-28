package link.sharedworld.devhelper.e2e;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends machine-readable progress markers as JSON lines to the file named by
 * -Dsharedworld.e2e.markerFile. The e2e orchestrator tails this file; every
 * driver phase transition must emit exactly one marker so orchestrator
 * timeouts can name the phase that hung.
 */
final class E2eMarkers {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-e2e");
    private static final long START_MS = System.currentTimeMillis();

    private final Path markerFile;

    E2eMarkers(Path markerFile) {
        this.markerFile = markerFile;
    }

    synchronized void emit(String event, String detail) {
        String line = "{\"event\":\"" + escape(event) + "\",\"detail\":\"" + escape(detail == null ? "" : detail)
                + "\",\"tMs\":" + (System.currentTimeMillis() - START_MS) + "}\n";
        LOGGER.info("e2e marker: {} {}", event, detail == null ? "" : detail);
        try {
            Files.writeString(this.markerFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            LOGGER.error("Failed to write e2e marker {}", event, exception);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
