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

    @org.junit.jupiter.api.Test
    void pruneTransientArtifactsRemovesLeftoversWithoutTouchingWorldData() throws IOException {
        Path root = this.tempDir.resolve("prune-root");
        ManagedWorldStore store = new ManagedWorldStore(root);
        Path container = store.worldContainer("world-1");
        Path workingCopy = store.workingCopy("world-1");
        Files.createDirectories(workingCopy.resolve("region"));

        // Real world data and baselines that must survive.
        Files.writeString(workingCopy.resolve("level.dat"), "level");
        Files.writeString(workingCopy.resolve("region").resolve("r.0.0.mca"), "region");
        Files.createDirectories(store.regionBaselineRoot("world-1"));
        Files.writeString(store.regionBaselineRoot("world-1").resolve("bundle.bundle"), "baseline");
        Files.writeString(store.packBaselineFile("world-1"), "pack-baseline");
        Files.writeString(store.regionBaselineSnapshotFile("world-1"), "snapshot-1");

        // Leftovers from a killed client that must be reclaimed.
        Files.createDirectories(store.stagingRoot("world-1").resolve("snapshot-123"));
        Files.writeString(store.stagingRoot("world-1").resolve("snapshot-123").resolve("level.dat"), "staged");
        Files.createDirectories(container.resolve("pack-extract-abc"));
        Files.createDirectories(container.resolve("region-bundle-extract-def"));
        Files.writeString(container.resolve("pack-artifact-123.part"), "partial");
        Files.writeString(container.resolve("pack-patched-456.pack"), "patched");
        Files.writeString(workingCopy.resolve("region").resolve("r.0.0.mca.artifact.789.part"), "partial-download");

        store.pruneTransientArtifacts();

        assertTrue(Files.exists(workingCopy.resolve("level.dat")));
        assertTrue(Files.exists(workingCopy.resolve("region").resolve("r.0.0.mca")));
        assertTrue(Files.exists(store.regionBaselineRoot("world-1").resolve("bundle.bundle")));
        assertTrue(Files.exists(store.packBaselineFile("world-1")));
        assertTrue(Files.exists(store.regionBaselineSnapshotFile("world-1")));

        assertTrue(Files.notExists(store.stagingRoot("world-1")));
        assertTrue(Files.notExists(container.resolve("pack-extract-abc")));
        assertTrue(Files.notExists(container.resolve("region-bundle-extract-def")));
        assertTrue(Files.notExists(container.resolve("pack-artifact-123.part")));
        assertTrue(Files.notExists(container.resolve("pack-patched-456.pack")));
        assertTrue(Files.notExists(workingCopy.resolve("region").resolve("r.0.0.mca.artifact.789.part")));
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
