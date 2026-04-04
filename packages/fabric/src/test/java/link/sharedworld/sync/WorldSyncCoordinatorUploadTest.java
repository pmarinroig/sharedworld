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
        worldStore.refreshPackBaseline(WORLD_ID, baselinePack.packFile(), "old-snapshot");
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
        worldStore.refreshPackBaseline(WORLD_ID, baselinePack.packFile(), "base-snapshot");

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
                            0
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
        }
    }

    @Test
    void finalizeFailureDoesNotRefreshBaselines() throws Exception {
        Path baselineWorld = writeWorldDirectory(Map.of("data/foo.dat", repeated('C', 4096)));
        Path currentWorld = writeWorldDirectory(Map.of("data/foo.dat", repeated('D', 4096)));

        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-finalize-failure"));
        BuiltPack baselinePack = buildPackFromWorld(baselineWorld);
        BuiltPack currentPack = buildPackFromWorld(currentWorld);
        worldStore.refreshPackBaseline(WORLD_ID, baselinePack.packFile(), "old-snapshot");
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
