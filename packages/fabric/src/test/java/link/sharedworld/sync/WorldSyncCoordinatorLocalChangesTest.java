package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The no-silent-rollback guard's local half: does the working copy differ
 * from the hashes it last synced/uploaded at? Answered from the baseline
 * sidecar + a (cached) scan; no backend, no bodies for unchanged packs.
 */
final class WorldSyncCoordinatorLocalChangesTest {
    private static final String WORLD_ID = "world-1";
    private static final String HOST_UUID = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path tempDir;

    private WorldSyncCoordinator coordinator(ManagedWorldStore store) {
        // The API client is never contacted by this path; an unreachable base URL proves it.
        return new WorldSyncCoordinator(new SharedWorldApiClient("http://127.0.0.1:1"), store);
    }

    private static Path seedWorld(ManagedWorldStore store) throws Exception {
        Path workingCopy = store.workingCopy(WORLD_ID);
        Files.createDirectories(workingCopy);
        Files.createDirectories(workingCopy.resolve("data"));
        Files.writeString(workingCopy.resolve("data/scoreboard.dat"), "data-v1");
        Files.createDirectories(workingCopy.resolve("region"));
        Files.writeString(workingCopy.resolve("region/r.0.0.mca"), "region-v1");
        return workingCopy;
    }

    /** Records the current pack hashes as the baseline, the way a sync or upload would. */
    private static void baselineFromCurrent(ManagedWorldStore store, Path workingCopy) throws Exception {
        List<PreparedWorldFile> files = WorldCanonicalizer.scanCanonical(workingCopy, HOST_UUID);
        List<PreparedWorldFile> regionFiles = files.stream().filter(file -> SyncPathRules.isTerrainRegionFile(file.relativePath())).toList();
        List<PreparedWorldFile> nonRegionFiles = files.stream().filter(file -> SyncPathRules.belongsInSuperpack(file.relativePath())).toList();
        java.util.Map<String, String> bundleHashes = new java.util.HashMap<>();
        java.util.Map<String, Path> bundleBodies = new java.util.HashMap<>();
        for (WorldSyncSupport.LazyArtifact bundle : WorldSyncSupport.lazyRegionBundleArtifacts(regionFiles, null)) {
            bundleHashes.put(bundle.packId(), bundle.descriptor().hash());
            bundleBodies.put(bundle.packId(), bundle.body());
        }
        store.ensureRegionBaselines(WORLD_ID, bundleHashes, bundleBodies::get, "snapshot-1");
        WorldSyncSupport.LazyArtifact pack = new WorldSyncSupport.LazyArtifact(SharedWorldPack.PACK_ID, nonRegionFiles, null);
        store.ensurePackBaseline(WORLD_ID, pack.descriptor().hash(), packId -> pack.body(), "snapshot-1");
    }

    @Test
    void noBaselineSidecarCountsAsChanged() throws Exception {
        ManagedWorldStore store = new ManagedWorldStore(this.tempDir.resolve("managed"));
        Path workingCopy = seedWorld(store);
        assertTrue(coordinator(store).hasLocalChangesSinceBaseline(WORLD_ID, workingCopy, HOST_UUID));
    }

    @Test
    void anUnchangedWorkingCopyMatchesItsBaseline() throws Exception {
        ManagedWorldStore store = new ManagedWorldStore(this.tempDir.resolve("managed"));
        Path workingCopy = seedWorld(store);
        baselineFromCurrent(store, workingCopy);
        assertFalse(coordinator(store).hasLocalChangesSinceBaseline(WORLD_ID, workingCopy, HOST_UUID));
        assertNotNull(store.baselineSnapshotId(WORLD_ID));
    }

    @Test
    void editingARegionOrANonRegionFileIsAChange() throws Exception {
        ManagedWorldStore store = new ManagedWorldStore(this.tempDir.resolve("managed"));
        Path workingCopy = seedWorld(store);
        baselineFromCurrent(store, workingCopy);

        Files.writeString(workingCopy.resolve("region/r.0.0.mca"), "region-v2");
        assertTrue(coordinator(store).hasLocalChangesSinceBaseline(WORLD_ID, workingCopy, HOST_UUID));

        Files.writeString(workingCopy.resolve("region/r.0.0.mca"), "region-v1");
        assertFalse(coordinator(store).hasLocalChangesSinceBaseline(WORLD_ID, workingCopy, HOST_UUID));
        Files.writeString(workingCopy.resolve("data/scoreboard.dat"), "data-v2");
        assertTrue(coordinator(store).hasLocalChangesSinceBaseline(WORLD_ID, workingCopy, HOST_UUID));
    }

    @Test
    void aRegionThatVanishedSinceTheBaselineIsAChange() throws Exception {
        ManagedWorldStore store = new ManagedWorldStore(this.tempDir.resolve("managed"));
        Path workingCopy = seedWorld(store);
        baselineFromCurrent(store, workingCopy);
        Files.delete(workingCopy.resolve("region/r.0.0.mca"));
        assertTrue(coordinator(store).hasLocalChangesSinceBaseline(WORLD_ID, workingCopy, HOST_UUID));
    }

    @Test
    void theLocalChangesMarkerRoundTripsAndKeepsItsFirstTimestamp() throws Exception {
        ManagedWorldStore store = new ManagedWorldStore(this.tempDir.resolve("managed"));
        assertNull(store.localChanges(WORLD_ID));
        store.markLocalChanges(WORLD_ID, HOST_UUID, "2026-08-17T10:00:00Z");
        store.markLocalChanges(WORLD_ID, HOST_UUID, "2026-08-17T11:00:00Z");
        ManagedWorldStore.LocalChangesMarker marker = store.localChanges(WORLD_ID);
        assertNotNull(marker);
        assertTrue(marker.since().startsWith("2026-08-17T10"));
        // Never inside the working copy (which is scanned and uploaded whole).
        assertFalse(store.localChangesFile(WORLD_ID).startsWith(store.workingCopy(WORLD_ID)));
        store.clearLocalChanges(WORLD_ID);
        assertNull(store.localChanges(WORLD_ID));
        // Unreadable degrades to "no claim".
        Files.writeString(store.localChangesFile(WORLD_ID), "{not json");
        assertNull(store.localChanges(WORLD_ID));
        // A reset working copy carries no claim either.
        store.markLocalChanges(WORLD_ID, HOST_UUID, "2026-08-17T10:00:00Z");
        store.resetWorkingCopy(WORLD_ID);
        assertNull(store.localChanges(WORLD_ID));
    }
}
