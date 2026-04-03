package link.sharedworld.screen;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncCoordinator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class BackendModCreateFlowIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void createFlowSeedsSnapshotAgainstRealBackendWithoutMinecraftWindows() throws Exception {
        SharedWorldApiClient hostClient = SharedWorldIntegrationBackend.apiClient(SharedWorldIntegrationBackend.HOST);
        SharedWorldModels.StorageLinkSessionDto storageLink = SharedWorldIntegrationBackend.linkStorage(hostClient);
        Path root = Files.createTempDirectory("sharedworld-create-flow-integration");
        try {
            Path source = root.resolve("source");
            Files.createDirectories(source.resolve("data"));
            CompoundTag levelTag = new CompoundTag();
            levelTag.put("Data", new CompoundTag());
            NbtIo.writeCompressed(levelTag, source.resolve("level.dat"));
            Files.writeString(source.resolve("data").resolve("notes.txt"), "hello");

            ManagedWorldStore managedWorldStore = new ManagedWorldStore(root.resolve("managed"));
            WorldSyncCoordinator syncCoordinator = new WorldSyncCoordinator(hostClient, managedWorldStore);
            List<String> progressEvents = new ArrayList<>();
            SharedWorldCreateFlow flow = new SharedWorldCreateFlow(
                    new SharedWorldCreateFlow.CreateBackend() {
                        @Override
                        public SharedWorldModels.CreateWorldResultDto createWorld(String name, String motdLine1, String customIconPngBase64, SharedWorldModels.ImportedWorldSourceDto importSource, String storageLinkSessionId) throws java.io.IOException, InterruptedException {
                            return hostClient.createWorld(name, motdLine1, null, customIconPngBase64, importSource, storageLinkSessionId);
                        }

                        @Override
                        public void releaseHost(String worldId, boolean graceful, long runtimeEpoch, String hostToken) throws java.io.IOException, InterruptedException {
                            hostClient.releaseHost(worldId, graceful, runtimeEpoch, hostToken);
                        }

                        @Override
                        public String canonicalAssignedPlayerUuidWithHyphens(String backendAssignedPlayerUuid) {
                            return hostClient.canonicalAssignedPlayerUuidWithHyphens(backendAssignedPlayerUuid);
                        }
                    },
                    path -> null,
                    new SharedWorldCreateFlow.WorkingCopyStore() {
                        @Override
                        public void resetWorkingCopy(String worldId) throws java.io.IOException {
                            managedWorldStore.resetWorkingCopy(worldId);
                        }

                        @Override
                        public Path workingCopy(String worldId) {
                            return managedWorldStore.workingCopy(worldId);
                        }
                    },
                    (worldId, worldDirectory, hostPlayerUuid, runtimeEpoch, hostToken, progressListener) ->
                            syncCoordinator.uploadSnapshot(worldId, worldDirectory, hostPlayerUuid, runtimeEpoch, hostToken, progressListener)
            );

            String worldName = SharedWorldIntegrationBackend.uniqueName("Integration Create");
            String message = flow.create(
                    new CreateSharedWorldScreen.CreateRequest(
                            new LocalSaveCatalog.LocalSaveOption("save-1", "Save", source, 0L, null, Component.empty()),
                            storageLink,
                            worldName,
                            "MOTD",
                            null,
                            false
                    ),
                    new SharedWorldCreateFlow.ProgressSink() {
                        @Override
                        public void updateDeterminate(Component label, String phase, double targetFraction, Long bytesDone, Long bytesTotal) {
                            progressEvents.add("determinate:" + phase);
                        }

                        @Override
                        public void updateIndeterminate(Component label, String phase) {
                            progressEvents.add("indeterminate:" + phase);
                        }
                    }
            );

            SharedWorldModels.WorldSummaryDto createdWorld = hostClient.listWorlds().getFirst();
            SharedWorldIntegrationBackend.StorageSnapshot storage = SharedWorldIntegrationBackend.storageSnapshot();

            assertEquals("screen.sharedworld.operation_created_world", message);
            assertTrue(progressEvents.contains("indeterminate:create_upload_prepare"));
            assertEquals("google-drive", createdWorld.storageProvider());
            assertTrue(createdWorld.storageLinked());
            assertTrue(createdWorld.lastSnapshotId() != null && !createdWorld.lastSnapshotId().isBlank());
            assertEquals("google-drive", storage.provider());
            assertTrue(storage.objects().length > 0);
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }
}
