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
        worldStore.updateRegionBaselines(WORLD_ID, Map.of(baselineBundle.descriptor().packId(), baselineBundle.packFile()), "old-snapshot");

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
}
