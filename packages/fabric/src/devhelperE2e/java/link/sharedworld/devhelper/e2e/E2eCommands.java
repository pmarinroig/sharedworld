package link.sharedworld.devhelper.e2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads orchestrator commands from the file named by
 * -Dsharedworld.e2e.commandFile. The orchestrator appends one command per
 * line ("shutdown", "guest-go", "exit"); the driver consumes them in order.
 */
final class E2eCommands {
    private final Path commandFile;
    private int consumedLines;

    E2eCommands(Path commandFile) {
        this.commandFile = commandFile;
    }

    /** The next unconsumed command, or null. */
    String poll() {
        if (this.commandFile == null || !Files.exists(this.commandFile)) {
            return null;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(this.commandFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return null;
        }
        while (this.consumedLines < lines.size()) {
            String line = lines.get(this.consumedLines).trim();
            this.consumedLines += 1;
            if (!line.isEmpty()) {
                return line;
            }
        }
        return null;
    }
}
