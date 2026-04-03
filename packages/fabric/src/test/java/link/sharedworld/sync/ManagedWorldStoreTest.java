package link.sharedworld.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedWorldStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void failedSnapshotCopyDeletesPartialStagingDirectory() throws IOException {
        Path workingCopy = Files.createDirectories(this.tempDir.resolve("working-copy"));
        Path stagingDirectory = this.tempDir.resolve("staging").resolve("snapshot-1");

        IOException exception = assertThrows(IOException.class, () -> ManagedWorldStore.createSnapshotStagingCopy(
                workingCopy,
                stagingDirectory,
                (sourceRoot, targetRoot) -> {
                    Files.createDirectories(targetRoot.resolve("region"));
                    Files.writeString(targetRoot.resolve("region").resolve("r.0.0.mca"), "partial");
                    throw new IOException("copy failed");
                },
                ManagedWorldStoreTest::deleteIfExistsRecursively
        ));

        assertEquals("copy failed", exception.getMessage());
        assertFalse(Files.exists(stagingDirectory));
    }

    @Test
    void failedSnapshotCleanupIsSuppressedOntoOriginalCopyFailure() throws IOException {
        Path workingCopy = Files.createDirectories(this.tempDir.resolve("working-copy"));
        Path stagingDirectory = this.tempDir.resolve("staging").resolve("snapshot-1");

        IOException exception = assertThrows(IOException.class, () -> ManagedWorldStore.createSnapshotStagingCopy(
                workingCopy,
                stagingDirectory,
                (sourceRoot, targetRoot) -> {
                    Files.writeString(targetRoot.resolve("level.dat"), "partial");
                    throw new IOException("copy failed");
                },
                targetRoot -> {
                    throw new IOException("cleanup failed");
                }
        ));

        assertEquals("copy failed", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("cleanup failed", exception.getSuppressed()[0].getMessage());
        assertTrue(Files.exists(stagingDirectory));
    }

    private static void deleteIfExistsRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
