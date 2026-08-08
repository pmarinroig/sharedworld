package link.sharedworld.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedWorldStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void captureMirrorRefreshCopiesChangedAddsNewAndDeletesRemovedFiles() throws IOException {
        ManagedWorldStore store = new ManagedWorldStore(this.tempDir.resolve("mirror-root"));
        Path workingCopy = Files.createDirectories(store.workingCopy("world-1").resolve("region")).getParent();
        Files.writeString(workingCopy.resolve("level.dat"), "level-v1");
        Files.writeString(workingCopy.resolve("region").resolve("r.0.0.mca"), "region-v1");
        Files.writeString(workingCopy.resolve("session.lock"), "lock");

        Path mirror = store.createSnapshotStagingCopy("world-1");
        assertEquals("level-v1", Files.readString(mirror.resolve("level.dat")));
        assertEquals("region-v1", Files.readString(mirror.resolve("region").resolve("r.0.0.mca")));
        assertFalse(Files.exists(mirror.resolve("session.lock")));

        Files.writeString(workingCopy.resolve("level.dat"), "level-v2!");
        Files.createDirectories(workingCopy.resolve("data"));
        Files.writeString(workingCopy.resolve("data").resolve("new.dat"), "new");
        Files.delete(workingCopy.resolve("region").resolve("r.0.0.mca"));

        Path refreshed = store.createSnapshotStagingCopy("world-1");
        assertEquals(mirror, refreshed);
        assertEquals("level-v2!", Files.readString(refreshed.resolve("level.dat")));
        assertEquals("new", Files.readString(refreshed.resolve("data").resolve("new.dat")));
        assertFalse(Files.exists(refreshed.resolve("region").resolve("r.0.0.mca")));
    }

    @Test
    void captureMirrorSkipsFilesWhoseSizeAndMtimeMatch() throws IOException {
        ManagedWorldStore store = new ManagedWorldStore(this.tempDir.resolve("mirror-skip-root"));
        Path workingCopy = Files.createDirectories(store.workingCopy("world-1"));
        Files.writeString(workingCopy.resolve("level.dat"), "abcdefgh");
        Path mirror = store.createSnapshotStagingCopy("world-1");

        // Same size, same mtime, different bytes: the stat compare must skip
        // the copy (that skip is the entire point of the mirror), leaving the
        // divergent mirror bytes in place.
        Files.writeString(mirror.resolve("level.dat"), "HGFEDCBA");
        Files.setLastModifiedTime(mirror.resolve("level.dat"), Files.getLastModifiedTime(workingCopy.resolve("level.dat")));

        store.createSnapshotStagingCopy("world-1");
        assertEquals("HGFEDCBA", Files.readString(mirror.resolve("level.dat")));
    }

    @Test
    void ensureRegionBaselinesKeepsMatchingCopiesChangedAndDeletesStale() throws IOException {
        ManagedWorldStore store = new ManagedWorldStore(this.tempDir.resolve("baseline-root"));
        String bundleA = "region-bundle:region:0:0";
        String bundleB = "region-bundle:region:0:1";
        Path bodyA1 = Files.writeString(this.tempDir.resolve("body-a1"), "bundle-a-v1");
        Path bodyB1 = Files.writeString(this.tempDir.resolve("body-b1"), "bundle-b-v1");

        store.ensureRegionBaselines(
                "world-1",
                java.util.Map.of(bundleA, "hash-a1", bundleB, "hash-b1"),
                packId -> packId.equals(bundleA) ? bodyA1 : bodyB1,
                "snap-1"
        );
        assertEquals("bundle-a-v1", Files.readString(store.regionBundleBaselineFile("world-1", bundleA)));
        assertEquals("bundle-b-v1", Files.readString(store.regionBundleBaselineFile("world-1", bundleB)));
        assertEquals("snap-1", store.regionBaselineSnapshotId("world-1"));

        // Unchanged hashes: the body supplier must never be consulted (a lazy
        // artifact would have to pack the world to answer it).
        store.ensureRegionBaselines(
                "world-1",
                java.util.Map.of(bundleA, "hash-a1", bundleB, "hash-b1"),
                packId -> {
                    throw new IOException("unchanged baselines must not request bodies");
                },
                "snap-2"
        );
        assertEquals("bundle-a-v1", Files.readString(store.regionBundleBaselineFile("world-1", bundleA)));
        assertEquals("snap-2", store.regionBaselineSnapshotId("world-1"));

        // A changed, B gone: A is recopied, B's baseline file is deleted.
        Path bodyA2 = Files.writeString(this.tempDir.resolve("body-a2"), "bundle-a-v2");
        store.ensureRegionBaselines("world-1", java.util.Map.of(bundleA, "hash-a2"), packId -> bodyA2, "snap-3");
        assertEquals("bundle-a-v2", Files.readString(store.regionBundleBaselineFile("world-1", bundleA)));
        assertFalse(Files.exists(store.regionBundleBaselineFile("world-1", bundleB)));
        assertEquals("snap-3", store.regionBaselineSnapshotId("world-1"));
    }

    @Test
    void deleteSnapshotStagingCopyLeavesTheMirrorButRemovesLegacyStaging() throws IOException {
        ManagedWorldStore store = new ManagedWorldStore(this.tempDir.resolve("mirror-delete-root"));
        Path workingCopy = Files.createDirectories(store.workingCopy("world-1"));
        Files.writeString(workingCopy.resolve("level.dat"), "level");
        Path mirror = store.createSnapshotStagingCopy("world-1");

        store.deleteSnapshotStagingCopy(mirror);
        assertTrue(Files.exists(mirror.resolve("level.dat")));

        Path legacyStaging = Files.createDirectories(store.stagingRoot("world-1").resolve("snapshot-123"));
        Files.writeString(legacyStaging.resolve("level.dat"), "staged");
        store.deleteSnapshotStagingCopy(legacyStaging);
        assertFalse(Files.exists(legacyStaging));
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

}
