package link.sharedworld.util;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Crash-safe JSON file writes: a sibling temp file is written in full, then moved into place. */
public final class AtomicJsonFile {
    private AtomicJsonFile() {
    }

    public static void write(Path file, Gson gson, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tempFile)) {
            gson.toJson(value, writer);
        }
        try {
            Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteQuietly(tempFile);
        }
    }

    public static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
