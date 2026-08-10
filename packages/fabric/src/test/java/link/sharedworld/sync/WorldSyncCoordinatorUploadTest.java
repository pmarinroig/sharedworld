package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldModels.LocalPackDescriptorDto;
import link.sharedworld.api.SharedWorldModels.ManifestFileDto;
import link.sharedworld.api.SharedWorldModels.SnapshotManifestDto;
import link.sharedworld.api.SharedWorldModels.SnapshotPackDto;
import link.sharedworld.api.SharedWorldModels.UploadPackPlanDto;
import link.sharedworld.api.SharedWorldModels.UploadPlanDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldSyncCoordinatorUploadTest {
    private static final String WORLD_ID = "world-upload";
    private static final String HOST_UUID = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path tempDir;

    @Test
    void uploadFallsBackToFullWhenLocalBaselineSnapshotOrHashDoesNotMatch() throws Exception {
        Path worldDirectory = Files.createDirectories(this.tempDir.resolve("world-full"));
        writeFile(worldDirectory, "data/foo.dat", repeated('A', 8192));

        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-full"));
        BuiltPack baselinePack = buildPackFromWorld(writeWorldDirectory(Map.of("data/foo.dat", repeated('B', 8192))));
        worldStore.refreshPackBaseline(WORLD_ID, baselinePack.packFile(), baselinePack.descriptor().hash(), "old-snapshot");
        BuiltPack currentPack = buildPackFromWorld(worldDirectory);

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.setUploadPlan(new UploadPlanDto(
                    WORLD_ID,
                    "base-snapshot",
                    new link.sharedworld.api.SharedWorldModels.UploadPlanEntryDto[0],
                    new UploadPackPlanDto(
                            currentPack.descriptor(),
                            false,
                            null,
                            null,
                            null,
                            "packs/full.pack",
                            server.uploadUrl("pack-full"),
                            "packs/delta.pack",
                            server.uploadUrl("pack-delta"),
                            "expected-snapshot",
                            baselinePack.descriptor().hash(),
                            0
                    ),
                    new UploadPackPlanDto[0],
                    SyncTestHttpServer.syncPolicy()
            ));
            server.setFinalizeManifest(manifest("snapshot-full"));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            coordinator.uploadSnapshot(WORLD_ID, worldDirectory, HOST_UUID, 7L, "token-7");

            assertNotNull(server.uploadedBlobBody("pack-full"));
            assertNull(server.uploadedBlobBody("pack-delta"));
            assertTrue(server.lastFinalizeSnapshotBody().contains("\"transferMode\":\"pack-full\""));
            assertTrue(server.lastFinalizeSnapshotBody().contains("\"storageKey\":\"packs/full.pack\""));
        }
    }

    @Test
    void uploadUsesDeltaWhenBaselineMatchesAndDeltaIsSmaller() throws Exception {
        Path baselineWorld = writeWorldDirectory(Map.of("data/foo.dat", repeated('A', 256 * 1024)));
        Path currentWorld = writeWorldDirectory(Map.of("data/foo.dat", mostlySameBytes(256 * 1024)));

        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-delta"));
        BuiltPack baselinePack = buildPackFromWorld(baselineWorld);
        BuiltPack currentPack = buildPackFromWorld(currentWorld);
        worldStore.refreshPackBaseline(WORLD_ID, baselinePack.packFile(), baselinePack.descriptor().hash(), "base-snapshot");

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.setUploadPlan(new UploadPlanDto(
                    WORLD_ID,
                    "base-snapshot",
                    new link.sharedworld.api.SharedWorldModels.UploadPlanEntryDto[0],
                    new UploadPackPlanDto(
                            currentPack.descriptor(),
                            false,
                            null,
                            null,
                            null,
                            "packs/full.pack",
                            server.uploadUrl("pack-full"),
                            "packs/delta.pack",
                            server.uploadUrl("pack-delta"),
                            "base-snapshot",
                            baselinePack.descriptor().hash(),
                            0,
                            2
                    ),
                    new UploadPackPlanDto[0],
                    SyncTestHttpServer.syncPolicy()
            ));
            server.setFinalizeManifest(manifest("snapshot-delta"));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            coordinator.uploadSnapshot(WORLD_ID, currentWorld, HOST_UUID, 8L, "token-8");

            assertNull(server.uploadedBlobBody("pack-full"));
            assertNotNull(server.uploadedBlobBody("pack-delta"));
            assertTrue(server.uploadedBlobBody("pack-delta").length < currentPack.descriptor().size());
            assertTrue(server.lastFinalizeSnapshotBody().contains("\"transferMode\":\"pack-delta\""));
            assertTrue(server.lastFinalizeSnapshotBody().contains("\"baseSnapshotId\":\"base-snapshot\""));
            assertTrue(server.lastFinalizeSnapshotBody().contains("\"deltaFormatVersion\":2"), server.lastFinalizeSnapshotBody());
            assertTrue(server.lastFinalizeSnapshotBody().contains("\"deltaBlobSize\":"), server.lastFinalizeSnapshotBody());
        }
    }

    @Test
    void finalizeFailureDoesNotRefreshBaselines() throws Exception {
        Path baselineWorld = writeWorldDirectory(Map.of("data/foo.dat", repeated('C', 4096)));
        Path currentWorld = writeWorldDirectory(Map.of("data/foo.dat", repeated('D', 4096)));

        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-finalize-failure"));
        BuiltPack baselinePack = buildPackFromWorld(baselineWorld);
        BuiltPack currentPack = buildPackFromWorld(currentWorld);
        worldStore.refreshPackBaseline(WORLD_ID, baselinePack.packFile(), baselinePack.descriptor().hash(), "old-snapshot");
        String oldBaselineHash = LocalWorldHasher.hashFile(worldStore.packBaselineFile(WORLD_ID));

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.setUploadPlan(new UploadPlanDto(
                    WORLD_ID,
                    "old-snapshot",
                    new link.sharedworld.api.SharedWorldModels.UploadPlanEntryDto[0],
                    new UploadPackPlanDto(
                            currentPack.descriptor(),
                            false,
                            null,
                            null,
                            null,
                            "packs/failure-full.pack",
                            server.uploadUrl("pack-full-failure"),
                            "packs/failure-delta.pack",
                            server.uploadUrl("pack-delta-failure"),
                            "mismatched-snapshot",
                            baselinePack.descriptor().hash(),
                            0
                    ),
                    new UploadPackPlanDto[0],
                    SyncTestHttpServer.syncPolicy()
            ));
            server.failFinalize("finalize_failed", "Finalize failed.", 500);

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            IOException error = assertThrows(IOException.class, () -> coordinator.uploadSnapshot(WORLD_ID, currentWorld, HOST_UUID, 9L, "token-9"));

            assertEquals("Finalize failed.", error.getMessage());
            assertNotNull(server.uploadedBlobBody("pack-full-failure"));
            assertEquals("old-snapshot", worldStore.packBaselineSnapshotId(WORLD_ID));
            assertEquals(oldBaselineHash, LocalWorldHasher.hashFile(worldStore.packBaselineFile(WORLD_ID)));
        }
    }

    @Test
    void unchangedSnapshotSkipsFinalizeAndConvergesBaselines() throws Exception {
        Path worldDirectory = writeWorldDirectory(Map.of("data/foo.dat", repeated('A', 4096)));
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-skip"));
        BuiltPack currentPack = buildPackFromWorld(worldDirectory);

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            // Backend proof of "nothing changed": the only local pack is already
            // present and the latest snapshot holds exactly that pack id. No
            // finalize manifest is stubbed, so reaching finalize would fail loudly.
            server.setUploadPlan(new UploadPlanDto(
                    WORLD_ID,
                    "base-snapshot",
                    new link.sharedworld.api.SharedWorldModels.UploadPlanEntryDto[0],
                    new UploadPackPlanDto(
                            currentPack.descriptor(),
                            true,
                            "packs/existing.pack",
                            "pack-full",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                    ),
                    new UploadPackPlanDto[0],
                    SyncTestHttpServer.syncPolicy(),
                    new String[]{SharedWorldPack.PACK_ID}
            ));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            SnapshotManifestDto manifest = coordinator.uploadSnapshot(WORLD_ID, worldDirectory, HOST_UUID, 7L, "token-7");

            assertNull(manifest);
            assertNull(server.lastFinalizeSnapshotBody());
            // Baselines still converge on the latest snapshot for the next delta plan.
            assertEquals("base-snapshot", worldStore.packBaselineSnapshotId(WORLD_ID));
        }
    }

    @Test
    void removedPackStillFinalizesEvenWhenEverythingElseIsUnchanged() throws Exception {
        Path worldDirectory = writeWorldDirectory(Map.of("data/foo.dat", repeated('A', 4096)));
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-removed-pack"));
        BuiltPack currentPack = buildPackFromWorld(worldDirectory);

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            // The latest snapshot also holds a region bundle that no longer
            // exists locally: the pack id sets differ, so the manifest must be
            // republished to record the removal.
            server.setUploadPlan(new UploadPlanDto(
                    WORLD_ID,
                    "base-snapshot",
                    new link.sharedworld.api.SharedWorldModels.UploadPlanEntryDto[0],
                    new UploadPackPlanDto(
                            currentPack.descriptor(),
                            true,
                            "packs/existing.pack",
                            "pack-full",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                    ),
                    new UploadPackPlanDto[0],
                    SyncTestHttpServer.syncPolicy(),
                    new String[]{SharedWorldPack.PACK_ID, "region-bundle:region:0:0"}
            ));
            server.setFinalizeManifest(manifest("snapshot-after-removal"));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            SnapshotManifestDto manifest = coordinator.uploadSnapshot(WORLD_ID, worldDirectory, HOST_UUID, 7L, "token-7");

            assertNotNull(manifest);
            assertNotNull(server.lastFinalizeSnapshotBody());
        }
    }

    @Test
    void worldOverTheShardCapUploadsNonRegionFilesAsShardPacks() throws Exception {
        System.setProperty("sharedworld.dev.superpackShardMaxBytes", "1024");
        try {
            Path worldDirectory = writeWorldDirectory(Map.of(
                    "data/foo.dat", repeated('A', 8192),
                    "entities/r.0.0.mca", repeated('E', 8192)
            ));
            ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-sharded"));

            List<SyncPathRules.RegionBundleGroup> shards = SyncPathRules.groupSuperpackFiles(
                    WorldCanonicalizer.scanCanonical(worldDirectory, HOST_UUID).stream()
                            .filter(file -> SyncPathRules.belongsInSuperpack(file.relativePath()))
                            .toList()
            );
            assertEquals(
                    List.of("region-bundle:superpack:data", "region-bundle:superpack:entities"),
                    shards.stream().map(SyncPathRules.RegionBundleGroup::bundleId).toList()
            );

            try (SyncTestHttpServer server = new SyncTestHttpServer()) {
                UploadPackPlanDto[] shardUploads = new UploadPackPlanDto[shards.size()];
                for (int i = 0; i < shards.size(); i++) {
                    Path shardPack = Files.createTempFile(this.tempDir, "shard-", ".bundle");
                    LocalPackDescriptorDto descriptor = SharedWorldPack.buildPack(shards.get(i).bundleId(), shards.get(i).files(), shardPack);
                    shardUploads[i] = new UploadPackPlanDto(
                            descriptor,
                            false,
                            null,
                            null,
                            null,
                            "region-bundles/full/shard-" + i + ".bundle",
                            server.uploadUrl("shard-" + i),
                            null,
                            null,
                            null,
                            null,
                            null
                    );
                }
                server.setUploadPlan(new UploadPlanDto(
                        WORLD_ID,
                        null,
                        new link.sharedworld.api.SharedWorldModels.UploadPlanEntryDto[0],
                        null,
                        shardUploads,
                        SyncTestHttpServer.syncPolicy()
                ));
                server.setFinalizeManifest(manifest("snapshot-sharded"));

                WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
                coordinator.uploadSnapshot(WORLD_ID, worldDirectory, HOST_UUID, 7L, "token-7");

                // The wire request must carry no singular superpack: the shard
                // packs travel in the regionBundles array a 0.3.0 backend and
                // client already understand.
                assertTrue(!server.lastPrepareUploadsBody().contains("\"nonRegionPack\""));
                assertTrue(server.lastPrepareUploadsBody().contains("region-bundle:superpack:data"));
                assertNotNull(server.uploadedBlobBody("shard-0"));
                assertNotNull(server.uploadedBlobBody("shard-1"));
                assertTrue(server.lastFinalizeSnapshotBody().contains("region-bundle:superpack:entities"));
                // Shard baselines land in the per-id bundle store, not the
                // singular pack baseline.
                assertNull(worldStore.packBaselineSnapshotId(WORLD_ID));
                assertTrue(Files.exists(worldStore.regionBundleBaselineFile(WORLD_ID, "region-bundle:superpack:data")));
            }
        } finally {
            System.clearProperty("sharedworld.dev.superpackShardMaxBytes");
        }
    }

    @Test
    void singleFileOverTheAdvertisedBodyLimitFailsNamingTheFile() throws Exception {
        Path worldDirectory = writeWorldDirectory(Map.of("data/huge.bin", repeated('H', 8192)));
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-oversized"));
        BuiltPack currentPack = buildPackFromWorld(worldDirectory);

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.setUploadPlan(new UploadPlanDto(
                    WORLD_ID,
                    null,
                    new link.sharedworld.api.SharedWorldModels.UploadPlanEntryDto[0],
                    new UploadPackPlanDto(
                            currentPack.descriptor(),
                            false,
                            null,
                            null,
                            null,
                            "packs/full.pack",
                            server.uploadUrl("pack-full-oversized"),
                            null,
                            null,
                            null,
                            null,
                            null
                    ),
                    new UploadPackPlanDto[0],
                    new link.sharedworld.api.SharedWorldModels.SyncPolicyDto(4, 2, 2, 10, 25, 250, 1024L)
            ));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            IOException error = assertThrows(IOException.class, () -> coordinator.uploadSnapshot(WORLD_ID, worldDirectory, HOST_UUID, 7L, "token-7"));

            assertTrue(error.getMessage().contains("data/huge.bin"), error.getMessage());
            assertTrue(error.getMessage().contains("relay transfer path is limited"), error.getMessage());
            // The doomed upload never started.
            assertNull(server.uploadedBlobBody("pack-full-oversized"));
        }
    }

    @Test
    void warmCacheAnswersThePlanWithoutRereadingFileContentsOrRepackingTheWorld() throws Exception {
        Path worldDirectory = writeWorldDirectory(Map.of("data/foo.dat", repeated('A', 4096)));
        Path fooFile = worldDirectory.resolve("data").resolve("foo.dat");
        // Old mtimes so the racy-mtime rule lets the cache trust the entries.
        Files.setLastModifiedTime(fooFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000L));
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-cache"));
        BuiltPack originalPack = buildPackFromWorld(worldDirectory);
        UploadPlanDto unchangedPlan = new UploadPlanDto(
                WORLD_ID,
                "base-snapshot",
                new link.sharedworld.api.SharedWorldModels.UploadPlanEntryDto[0],
                new UploadPackPlanDto(
                        originalPack.descriptor(),
                        true,
                        "packs/existing.pack",
                        "pack-full",
                        null, null, null, null, null, null, null, null
                ),
                new UploadPackPlanDto[0],
                SyncTestHttpServer.syncPolicy(),
                new String[]{SharedWorldPack.PACK_ID}
        );

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.setUploadPlan(unchangedPlan);
            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            assertNull(coordinator.uploadSnapshot(WORLD_ID, worldDirectory, HOST_UUID, 7L, "token-7"));

            // Same size, same mtime, different bytes: a scan that trusts the
            // cache reports the original hashes without reading contents, and a
            // sync that never rebuilds the pack cannot notice either. (A
            // rebuild would produce a different pack hash and fail loudly, so
            // plain success here is the laziness proof.)
            java.nio.file.attribute.FileTime originalMtime = Files.getLastModifiedTime(fooFile);
            Files.write(fooFile, repeated('Z', 4096));
            Files.setLastModifiedTime(fooFile, originalMtime);

            assertNull(coordinator.uploadSnapshot(WORLD_ID, worldDirectory, HOST_UUID, 8L, "token-8"));
            assertTrue(
                    server.lastPrepareUploadsBody().contains(originalPack.descriptor().hash()),
                    "expected the cached pack hash in the plan request: " + server.lastPrepareUploadsBody()
            );
        }
    }

    @Test
    void unchangedSyncLeavesMatchingBaselinesUntouched() throws Exception {
        Path worldDirectory = writeWorldDirectory(Map.of("data/foo.dat", repeated('A', 4096)));
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-baseline-skip"));
        BuiltPack currentPack = buildPackFromWorld(worldDirectory);
        UploadPlanDto unchangedPlan = new UploadPlanDto(
                WORLD_ID,
                "base-snapshot",
                new link.sharedworld.api.SharedWorldModels.UploadPlanEntryDto[0],
                new UploadPackPlanDto(
                        currentPack.descriptor(),
                        true,
                        "packs/existing.pack",
                        "pack-full",
                        null, null, null, null, null, null, null, null
                ),
                new UploadPackPlanDto[0],
                SyncTestHttpServer.syncPolicy(),
                new String[]{SharedWorldPack.PACK_ID}
        );

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.setUploadPlan(unchangedPlan);
            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            assertNull(coordinator.uploadSnapshot(WORLD_ID, worldDirectory, HOST_UUID, 7L, "token-7"));
            assertTrue(Files.exists(worldStore.packBaselineFile(WORLD_ID)));

            // A sentinel in the baseline file: if the next unchanged sync
            // rewrote baselines (the old delete-and-recopy behavior), the
            // sentinel would be replaced by real pack bytes. Consumers re-hash
            // baselines before trusting them, so this is safe to skip.
            Files.write(worldStore.packBaselineFile(WORLD_ID), "sentinel".getBytes());
            assertNull(coordinator.uploadSnapshot(WORLD_ID, worldDirectory, HOST_UUID, 8L, "token-8"));

            assertEquals("sentinel", Files.readString(worldStore.packBaselineFile(WORLD_ID)));
            assertEquals("base-snapshot", worldStore.packBaselineSnapshotId(WORLD_ID));
        }
    }

    private BuiltPack buildPackFromWorld(Path worldDirectory) throws Exception {
        Path packFile = Files.createTempFile(this.tempDir, "non-region-", ".pack");
        LocalPackDescriptorDto descriptor = SharedWorldPack.buildPack(
                WorldCanonicalizer.scanCanonical(worldDirectory, HOST_UUID).stream()
                        .filter(file -> SyncPathRules.belongsInSuperpack(file.relativePath()))
                        .toList(),
                packFile
        );
        return new BuiltPack(packFile, descriptor);
    }

    private Path writeWorldDirectory(Map<String, byte[]> files) throws Exception {
        Path root = Files.createTempDirectory(this.tempDir, "world-");
        for (var entry : files.entrySet()) {
            writeFile(root, entry.getKey(), entry.getValue());
        }
        return root;
    }

    private static void writeFile(Path root, String relativePath, byte[] bytes) throws Exception {
        Path file = root.resolve(relativePath.replace('/', java.io.File.separatorChar));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, bytes);
    }

    @Test
    void failedUploadPlanRequestLeavesNoTempArtifactsBehind() throws Exception {
        Path worldDirectory = Files.createDirectories(this.tempDir.resolve("world-plan-fail"));
        writeFile(worldDirectory, "data/foo.dat", repeated('A', 8192));
        writeFile(worldDirectory, "region/r.0.0.mca", repeated('R', 8192));
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-plan-fail"));

        java.util.Set<String> tempsBefore = sharedWorldTempNames();
        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            // No upload plan configured: the plan request fails like a dead
            // backend does, which is exactly when autosaves retry every five
            // minutes and temp leaks would compound.
            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            assertThrows(Exception.class, () -> coordinator.uploadSnapshot(WORLD_ID, worldDirectory, HOST_UUID, 7L, "token-7", (WorldSyncProgressListener) null));
        }
        assertEquals(tempsBefore, sharedWorldTempNames());
    }

    private static java.util.Set<String> sharedWorldTempNames() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var stream = Files.list(tmp)) {
            return stream
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("sharedworld-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static byte[] repeated(char value, int count) {
        byte[] bytes = new byte[count];
        for (int index = 0; index < count; index++) {
            bytes[index] = (byte) value;
        }
        return bytes;
    }

    private static byte[] mostlySameBytes(int count) {
        byte[] bytes = repeated('A', count);
        bytes[bytes.length - 1] = 'B';
        return bytes;
    }

    private static SnapshotManifestDto manifest(String snapshotId) {
        return new SnapshotManifestDto(
                WORLD_ID,
                snapshotId,
                Instant.EPOCH.toString(),
                HOST_UUID,
                new ManifestFileDto[0],
                new SnapshotPackDto[0]
        );
    }

    private record BuiltPack(Path packFile, LocalPackDescriptorDto descriptor) {
    }
}
