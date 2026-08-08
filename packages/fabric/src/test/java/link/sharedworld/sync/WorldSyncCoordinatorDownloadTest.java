package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldModels.DownloadPackPlanDto;
import link.sharedworld.api.SharedWorldModels.DownloadPlanDto;
import link.sharedworld.api.SharedWorldModels.DownloadPlanEntryDto;
import link.sharedworld.api.SharedWorldModels.DownloadPlanStepDto;
import link.sharedworld.api.SharedWorldModels.LocalPackDescriptorDto;
import link.sharedworld.api.SharedWorldModels.PackedManifestFileDto;
import link.sharedworld.api.SharedWorldModels.SnapshotManifestDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldSyncCoordinatorDownloadTest {
    private static final String WORLD_ID = "world-download";
    private static final String HOST_UUID = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path tempDir;

    @Test
    void downloadFailsClosedWhenRegionDeltaBaseIsMissingOrWrong() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed"));
        Path workingCopy = worldStore.workingCopy(WORLD_ID);
        writeFile(workingCopy, "data/keep.txt", "keep-me".getBytes());

        String regionPath = "region/r.0.0.mca";
        BuiltPack baselineBundle = buildPackArtifact(
                SyncPathRules.regionBundleId(regionPath),
                Map.of(regionPath, "baseline-region".getBytes())
        );
        worldStore.updateRegionBaselines(WORLD_ID, Map.of(baselineBundle.descriptor().packId(), baselineBundle.packFile()), Map.of(baselineBundle.descriptor().packId(), baselineBundle.descriptor().hash()), "old-snapshot");

        BuiltPack targetBundle = buildPackArtifact(
                baselineBundle.descriptor().packId(),
                Map.of(regionPath, "target-region".getBytes())
        );

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.setDownloadPlan(new DownloadPlanDto(
                    WORLD_ID,
                    "new-snapshot",
                    new DownloadPlanEntryDto[0],
                    null,
                    new DownloadPackPlanDto[] {
                            new DownloadPackPlanDto(
                                    targetBundle.descriptor().packId(),
                                    targetBundle.descriptor().hash(),
                                    targetBundle.descriptor().size(),
                                    targetBundle.descriptor().files(),
                                    new DownloadPlanStepDto[] {
                                            new DownloadPlanStepDto(
                                                    "region-delta",
                                                    "region-delta-storage",
                                                    12L,
                                                    "expected-snapshot",
                                                    corruptHash(baselineBundle.descriptor().hash()),
                                                    server.downloadUrl("unused-region-delta")
                                            )
                                    }
                            )
                    },
                    new String[0],
                    SyncTestHttpServer.syncPolicy()
            ));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            IOException error = assertThrows(IOException.class, () -> coordinator.ensureSynchronizedWorkingCopy(WORLD_ID, HOST_UUID));

            assertEquals("SharedWorld grouped artifact delta base was missing.", error.getMessage());
            assertArrayEquals("keep-me".getBytes(), Files.readAllBytes(workingCopy.resolve("data").resolve("keep.txt")));
            assertEquals("old-snapshot", worldStore.regionBaselineSnapshotId(WORLD_ID));
            assertFalse(Files.exists(workingCopy.resolve("region").resolve("r.0.0.mca")));
        }
    }

    @Test
    void deltaBasedOnTheJustReportedLocalStateSucceedsWithoutCachedBaselines() throws Exception {
        // A cancelled sync can leave the working copy ahead of (or without) the
        // cached baseline artifacts. The backend plans deltas against the state the
        // client just reported, so the freshly scanned artifact must satisfy them.
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-reported-base"));
        Path workingCopy = worldStore.workingCopy(WORLD_ID);
        String regionPath = "region/r.0.0.mca";
        writeFile(workingCopy, regionPath, "local-region".getBytes());
        // Deliberately no region baselines recorded for this world.

        BuiltPack localBundle = buildPackArtifact(
                SyncPathRules.regionBundleId(regionPath),
                Map.of(regionPath, "local-region".getBytes())
        );
        BuiltPack targetBundle = buildPackArtifact(
                localBundle.descriptor().packId(),
                Map.of(regionPath, "target-region".getBytes())
        );
        Path deltaFile = Files.createTempFile(this.tempDir, "bundle-delta-", ".delta");
        ArtifactDeltaEngine.writeDelta(localBundle.packFile(), targetBundle.packFile(), deltaFile, 4096);

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.seedBlob("region-delta-blob", Files.readAllBytes(deltaFile));
            server.setDownloadPlan(new DownloadPlanDto(
                    WORLD_ID,
                    "new-snapshot",
                    new DownloadPlanEntryDto[0],
                    null,
                    new DownloadPackPlanDto[] {
                            new DownloadPackPlanDto(
                                    targetBundle.descriptor().packId(),
                                    targetBundle.descriptor().hash(),
                                    targetBundle.descriptor().size(),
                                    targetBundle.descriptor().files(),
                                    new DownloadPlanStepDto[] {
                                            new DownloadPlanStepDto(
                                                    "region-delta",
                                                    "region-delta-storage",
                                                    Files.size(deltaFile),
                                                    "base-snapshot",
                                                    localBundle.descriptor().hash(),
                                                    server.downloadUrl("region-delta-blob")
                                            )
                                    }
                            )
                    },
                    new String[0],
                    SyncTestHttpServer.syncPolicy()
            ));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            coordinator.ensureSynchronizedWorkingCopy(WORLD_ID, HOST_UUID);

            assertArrayEquals(
                    "target-region".getBytes(),
                    Files.readAllBytes(workingCopy.resolve("region").resolve("r.0.0.mca"))
            );
        }
    }

    @Test
    void downloadFailsClosedWhenReconstructedPackHashDoesNotMatchPlan() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-corrupt-pack"));
        Path workingCopy = worldStore.workingCopy(WORLD_ID);
        writeFile(workingCopy, "data/stale.txt", "stale".getBytes());

        BuiltPack pack = buildPackArtifact(null, Map.of("data/new.txt", "fresh".getBytes()));

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.seedBlob("pack-full", Files.readAllBytes(pack.packFile()));
            server.setDownloadPlan(new DownloadPlanDto(
                    WORLD_ID,
                    "snapshot-corrupt",
                    new DownloadPlanEntryDto[0],
                    new DownloadPackPlanDto(
                            pack.descriptor().packId(),
                            corruptHash(pack.descriptor().hash()),
                            pack.descriptor().size(),
                            pack.descriptor().files(),
                            new DownloadPlanStepDto[] {
                                    new DownloadPlanStepDto(
                                            "pack-full",
                                            "packs/full.pack",
                                            Files.size(pack.packFile()),
                                            null,
                                            null,
                                            server.downloadUrl("pack-full")
                                    )
                            }
                    ),
                    new DownloadPackPlanDto[0],
                    new String[0],
                    SyncTestHttpServer.syncPolicy()
            ));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            IOException error = assertThrows(IOException.class, () -> coordinator.ensureSynchronizedWorkingCopy(WORLD_ID, HOST_UUID));

            assertEquals("SharedWorld reconstructed pack hash mismatch.", error.getMessage());
            assertArrayEquals("stale".getBytes(), Files.readAllBytes(workingCopy.resolve("data").resolve("stale.txt")));
            assertFalse(Files.exists(workingCopy.resolve("data").resolve("new.txt")));
            assertEquals(null, worldStore.packBaselineSnapshotId(WORLD_ID));
            assertNoSyncTempsInWorldContainer(worldStore);
        }
    }

    @Test
    void failedDownloadPlanRequestLeavesNoTempArtifactsBehind() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-plan-fail"));
        Path workingCopy = worldStore.workingCopy(WORLD_ID);
        writeFile(workingCopy, "data/stale.txt", "stale".getBytes());
        writeFile(workingCopy, "region/r.0.0.mca", "region".getBytes());

        java.util.Set<String> tempsBefore = sharedWorldTempNames();
        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            // No download plan configured: the guest cache warmer hits this
            // failure every 30 seconds while the backend is unreachable.
            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            assertThrows(Exception.class, () -> coordinator.ensureSynchronizedWorkingCopy(WORLD_ID, HOST_UUID));
        }
        assertEquals(tempsBefore, sharedWorldTempNames());
        assertNoSyncTempsInWorldContainer(worldStore);
    }

    private static java.util.Set<String> sharedWorldTempNames() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (java.util.stream.Stream<Path> stream = Files.list(tmp)) {
            return stream
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("sharedworld-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static void assertNoSyncTempsInWorldContainer(ManagedWorldStore worldStore) throws Exception {
        Path container = worldStore.worldContainer(WORLD_ID);
        if (!Files.isDirectory(container)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(container)) {
            java.util.List<String> temps = stream
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("pack-artifact-")
                            || name.startsWith("pack-patched-")
                            || name.startsWith("pack-extract-")
                            || name.startsWith("region-bundle-extract-")
                            || (name.contains(".artifact.") && name.endsWith(".part")))
                    .toList();
            assertEquals(java.util.List.of(), temps);
        }
    }

    @Test
    void successfulDownloadAppliesAtomicallyThenPrunesStaleFiles() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-success"));
        Path workingCopy = worldStore.workingCopy(WORLD_ID);
        writeFile(workingCopy, "data/stale.txt", "old".getBytes());

        BuiltPack pack = buildPackArtifact(null, Map.of("data/new.txt", "fresh-pack".getBytes()));
        String regionPath = "region/r.0.0.mca";
        BuiltPack regionBundle = buildPackArtifact(
                SyncPathRules.regionBundleId(regionPath),
                Map.of(regionPath, "fresh-region".getBytes())
        );

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.seedBlob("pack-full-success", Files.readAllBytes(pack.packFile()));
            server.seedBlob("region-full-success", Files.readAllBytes(regionBundle.packFile()));
            server.setDownloadPlan(new DownloadPlanDto(
                    WORLD_ID,
                    "snapshot-success",
                    new DownloadPlanEntryDto[0],
                    new DownloadPackPlanDto(
                            pack.descriptor().packId(),
                            pack.descriptor().hash(),
                            pack.descriptor().size(),
                            pack.descriptor().files(),
                            new DownloadPlanStepDto[] {
                                    new DownloadPlanStepDto(
                                            "pack-full",
                                            "packs/full-success.pack",
                                            Files.size(pack.packFile()),
                                            null,
                                            null,
                                            server.downloadUrl("pack-full-success")
                                    )
                            }
                    ),
                    new DownloadPackPlanDto[] {
                            new DownloadPackPlanDto(
                                    regionBundle.descriptor().packId(),
                                    regionBundle.descriptor().hash(),
                                    regionBundle.descriptor().size(),
                                    regionBundle.descriptor().files(),
                                    new DownloadPlanStepDto[] {
                                            new DownloadPlanStepDto(
                                                    "region-full",
                                                    "region/full-success.pack",
                                                    Files.size(regionBundle.packFile()),
                                                    null,
                                                    null,
                                                    server.downloadUrl("region-full-success")
                                            )
                                    }
                            )
                    },
                    new String[0],
                    SyncTestHttpServer.syncPolicy()
            ));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            Path synchronizedWorld = coordinator.ensureSynchronizedWorkingCopy(WORLD_ID, HOST_UUID);

            assertEquals(workingCopy, synchronizedWorld);
            assertArrayEquals("fresh-pack".getBytes(), Files.readAllBytes(workingCopy.resolve("data").resolve("new.txt")));
            assertArrayEquals("fresh-region".getBytes(), Files.readAllBytes(workingCopy.resolve("region").resolve("r.0.0.mca")));
            assertFalse(Files.exists(workingCopy.resolve("data").resolve("stale.txt")));
            assertEquals("snapshot-success", worldStore.packBaselineSnapshotId(WORLD_ID));
            assertEquals("snapshot-success", worldStore.regionBaselineSnapshotId(WORLD_ID));
            assertEquals(pack.descriptor().hash(), LocalWorldHasher.hashFile(worldStore.packBaselineFile(WORLD_ID)));
            assertEquals(
                    regionBundle.descriptor().hash(),
                    LocalWorldHasher.hashFile(worldStore.regionBundleBaselineFile(WORLD_ID, regionBundle.descriptor().packId()))
            );
        }
    }

    @Test
    void shardedSnapshotWithNonRegionFilesInBundlePacksAppliesLikeAnyBundle() throws Exception {
        // A sharded snapshot carries its non-region files in
        // region-bundle:superpack:* packs and no singular pack download at all.
        // This is the exact shape a pre-sharding client also receives, so this
        // test doubles as the wire-compat proof for 0.3.0 guests.
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-sharded-dl"));
        Path workingCopy = worldStore.workingCopy(WORLD_ID);
        writeFile(workingCopy, "data/stale.txt", "old".getBytes());

        BuiltPack dataShard = buildPackArtifact(
                "region-bundle:superpack:data",
                Map.of("data/new.txt", "shard-data".getBytes())
        );
        BuiltPack rootShard = buildPackArtifact(
                "region-bundle:superpack:.",
                Map.of("icon.png", "shard-icon".getBytes())
        );

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.seedBlob("shard-data-full", Files.readAllBytes(dataShard.packFile()));
            server.seedBlob("shard-root-full", Files.readAllBytes(rootShard.packFile()));
            server.setDownloadPlan(new DownloadPlanDto(
                    WORLD_ID,
                    "snapshot-sharded",
                    new DownloadPlanEntryDto[0],
                    null,
                    new DownloadPackPlanDto[] {
                            new DownloadPackPlanDto(
                                    dataShard.descriptor().packId(),
                                    dataShard.descriptor().hash(),
                                    dataShard.descriptor().size(),
                                    dataShard.descriptor().files(),
                                    new DownloadPlanStepDto[] {
                                            new DownloadPlanStepDto(
                                                    "region-full",
                                                    "region-bundles/shard-data.bundle",
                                                    Files.size(dataShard.packFile()),
                                                    null,
                                                    null,
                                                    server.downloadUrl("shard-data-full")
                                            )
                                    }
                            ),
                            new DownloadPackPlanDto(
                                    rootShard.descriptor().packId(),
                                    rootShard.descriptor().hash(),
                                    rootShard.descriptor().size(),
                                    rootShard.descriptor().files(),
                                    new DownloadPlanStepDto[] {
                                            new DownloadPlanStepDto(
                                                    "region-full",
                                                    "region-bundles/shard-root.bundle",
                                                    Files.size(rootShard.packFile()),
                                                    null,
                                                    null,
                                                    server.downloadUrl("shard-root-full")
                                            )
                                    }
                            )
                    },
                    new String[0],
                    SyncTestHttpServer.syncPolicy()
            ));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            coordinator.ensureSynchronizedWorkingCopy(WORLD_ID, HOST_UUID);

            assertArrayEquals("shard-data".getBytes(), Files.readAllBytes(workingCopy.resolve("data").resolve("new.txt")));
            assertArrayEquals("shard-icon".getBytes(), Files.readAllBytes(workingCopy.resolve("icon.png")));
            assertFalse(Files.exists(workingCopy.resolve("data").resolve("stale.txt")));
            assertEquals(
                    dataShard.descriptor().hash(),
                    LocalWorldHasher.hashFile(worldStore.regionBundleBaselineFile(WORLD_ID, "region-bundle:superpack:data"))
            );
        }
    }

    @Test
    void appliedDownloadSeedsTheScanCacheSoTheNextPlanRequestTrustsIt() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-seeded"));
        Path workingCopy = worldStore.workingCopy(WORLD_ID);
        BuiltPack pack = buildPackArtifact(SharedWorldPack.PACK_ID, Map.of("data/icon.png", "downloaded-bytes".getBytes()));
        String downloadedFileHash = pack.descriptor().files()[0].hash();

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.seedBlob("pack-blob", Files.readAllBytes(pack.packFile()));
            server.setDownloadPlan(new DownloadPlanDto(
                    WORLD_ID,
                    "snap-1",
                    new DownloadPlanEntryDto[0],
                    new DownloadPackPlanDto(
                            pack.descriptor().packId(),
                            pack.descriptor().hash(),
                            pack.descriptor().size(),
                            pack.descriptor().files(),
                            new DownloadPlanStepDto[]{
                                    new DownloadPlanStepDto("pack-full", "packs/full.pack", pack.descriptor().size(), null, null, server.downloadUrl("pack-blob"))
                            }
                    ),
                    new DownloadPackPlanDto[0],
                    new String[0],
                    SyncTestHttpServer.syncPolicy()
            ));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            coordinator.ensureSynchronizedWorkingCopy(WORLD_ID, HOST_UUID);
            Path downloadedFile = workingCopy.resolve("data").resolve("icon.png");
            assertArrayEquals("downloaded-bytes".getBytes(), Files.readAllBytes(downloadedFile));

            // Hash-verified downloads seed the scan cache, so the next plan
            // request must report the verified hash from the cache without
            // reading contents: same size, same mtime, different bytes stays
            // invisible to it.
            var originalMtime = Files.getLastModifiedTime(downloadedFile);
            Files.write(downloadedFile, "TAMPERED-BYTES!!".getBytes());
            Files.setLastModifiedTime(downloadedFile, originalMtime);

            coordinator.ensureSynchronizedWorkingCopy(WORLD_ID, HOST_UUID);
            assertTrue(
                    server.lastDownloadPlanBody().contains(downloadedFileHash),
                    "expected the seeded hash in the plan request: " + server.lastDownloadPlanBody()
            );
        }
    }

    private BuiltPack buildPackArtifact(String packId, Map<String, byte[]> filesByPath) throws Exception {
        Path sourceRoot = Files.createTempDirectory(this.tempDir, "pack-source-");
        List<PreparedWorldFile> files = new ArrayList<>();
        for (var entry : filesByPath.entrySet()) {
            Path file = sourceRoot.resolve(entry.getKey().replace('/', java.io.File.separatorChar));
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, entry.getValue());
            files.add(new PreparedWorldFile(
                    file,
                    entry.getKey(),
                    LocalWorldHasher.hashFile(file),
                    entry.getValue().length,
                    entry.getValue().length,
                    "application/octet-stream",
                    SyncPathRules.isTerrainRegionFile(entry.getKey()),
                    null
            ));
        }
        Path packFile = Files.createTempFile(this.tempDir, "artifact-", ".pack");
        LocalPackDescriptorDto descriptor = packId == null
                ? SharedWorldPack.buildPack(files, packFile)
                : SharedWorldPack.buildPack(packId, files, packFile);
        return new BuiltPack(packFile, descriptor);
    }

    private static void writeFile(Path root, String relativePath, byte[] bytes) throws Exception {
        Path file = root.resolve(relativePath.replace('/', java.io.File.separatorChar));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, bytes);
    }

    private static String corruptHash(String hash) {
        return (hash.charAt(0) == '0' ? "1" : "0") + hash.substring(1);
    }

    @SuppressWarnings("unused")
    private static SnapshotManifestDto manifest(String worldId, String snapshotId) {
        return new SnapshotManifestDto(worldId, snapshotId, Instant.EPOCH.toString(), HOST_UUID, new link.sharedworld.api.SharedWorldModels.ManifestFileDto[0], new link.sharedworld.api.SharedWorldModels.SnapshotPackDto[0]);
    }

    private record BuiltPack(Path packFile, LocalPackDescriptorDto descriptor) {
    }

    @Test
    void transientBlobFailuresAreRetriedAndTheDownloadStillSucceeds() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-retry"));
        Path workingCopy = worldStore.workingCopy(WORLD_ID);

        BuiltPack pack = buildPackArtifact(null, Map.of("data/new.txt", "fresh-pack".getBytes()));

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.seedBlob("pack-retry", Files.readAllBytes(pack.packFile()));
            server.failBlob("pack-retry", 503, 2);
            server.setDownloadPlan(new DownloadPlanDto(
                    WORLD_ID,
                    "snapshot-retry",
                    new DownloadPlanEntryDto[0],
                    new DownloadPackPlanDto(
                            pack.descriptor().packId(),
                            pack.descriptor().hash(),
                            pack.descriptor().size(),
                            pack.descriptor().files(),
                            new DownloadPlanStepDto[] {
                                    new DownloadPlanStepDto(
                                            "pack-full",
                                            "packs/full-retry.pack",
                                            Files.size(pack.packFile()),
                                            null,
                                            null,
                                            server.downloadUrl("pack-retry")
                                    )
                            }
                    ),
                    new DownloadPackPlanDto[0],
                    new String[0],
                    SyncTestHttpServer.syncPolicy()
            ));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            coordinator.ensureSynchronizedWorkingCopy(WORLD_ID, HOST_UUID);

            assertArrayEquals("fresh-pack".getBytes(), Files.readAllBytes(workingCopy.resolve("data").resolve("new.txt")));
            assertEquals(3, server.blobRequestCount("pack-retry"), "two scripted 503s then the successful transfer");
        }
    }

    @Test
    void protocolBlobErrorsAreNotRetried() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-no-retry"));

        BuiltPack pack = buildPackArtifact(null, Map.of("data/new.txt", "fresh-pack".getBytes()));

        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            // 404: the blob is simply absent — a protocol outcome, not a
            // transport blip; retrying would only mask the real problem.
            server.setDownloadPlan(new DownloadPlanDto(
                    WORLD_ID,
                    "snapshot-no-retry",
                    new DownloadPlanEntryDto[0],
                    new DownloadPackPlanDto(
                            pack.descriptor().packId(),
                            pack.descriptor().hash(),
                            pack.descriptor().size(),
                            pack.descriptor().files(),
                            new DownloadPlanStepDto[] {
                                    new DownloadPlanStepDto(
                                            "pack-full",
                                            "packs/full-no-retry.pack",
                                            123L,
                                            null,
                                            null,
                                            server.downloadUrl("pack-missing")
                                    )
                            }
                    ),
                    new DownloadPackPlanDto[0],
                    new String[0],
                    SyncTestHttpServer.syncPolicy()
            ));

            WorldSyncCoordinator coordinator = new WorldSyncCoordinator(server.apiClient(), worldStore);
            assertThrows(IOException.class, () -> coordinator.ensureSynchronizedWorkingCopy(WORLD_ID, HOST_UUID));
            assertEquals(1, server.blobRequestCount("pack-missing"), "4xx protocol errors are never retried");
        }
    }
}
