package link.sharedworld.screen;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import link.sharedworld.integration.support.SharedWorldIntegrationFixtures;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncCoordinator;
import link.sharedworld.versioned.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replace against the real backend wire: the enterSession host lease seeds a
 * fresh full snapshot that becomes the latest, and a live host blocks the
 * replace with connect/wait instead of a lease.
 */
@Tag("integration")
final class BackendModReplaceFlowIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void replaceAdvancesTheLatestSnapshotThroughTheEnterSessionLease() throws Exception {
        SharedWorldIntegrationFixtures.ReleasedWorld released = SharedWorldIntegrationFixtures.createReleasedWorld(
                "Integration Replace",
                SharedWorldIntegrationBackend.OWNER
        );
        SharedWorldApiClient owner = released.ownerClient();
        String worldId = released.world().id();
        String snapshotBefore = owner.getWorld(worldId).lastSnapshotId();

        Path root = Files.createTempDirectory("sharedworld-replace-integration");
        try {
            Path source = replacementSave(root);
            ManagedWorldStore managedWorldStore = new ManagedWorldStore(root.resolve("managed"));
            WorldSyncCoordinator syncCoordinator = new WorldSyncCoordinator(owner, managedWorldStore);
            SharedWorldReplaceFlow flow = replaceFlow(owner, managedWorldStore, syncCoordinator);

            String message = flow.replace(worldId, released.world().name(), source, false, silentSink());

            assertEquals("screen.sharedworld.replace_done", message);
            String snapshotAfter = owner.getWorld(worldId).lastSnapshotId();
            assertTrue(snapshotAfter != null && !snapshotAfter.isBlank());
            assertNotEquals(snapshotBefore, snapshotAfter);
            // The lease was released: the runtime is idle again and a normal host can start.
            SharedWorldModels.EnterSessionResponseDto reentered = owner.enterSession(worldId);
            assertEquals("host", reentered.action());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void replaceIsRefusedWhileAnotherMemberHosts() throws Exception {
        SharedWorldIntegrationFixtures.HostedWorld hosted = SharedWorldIntegrationFixtures.createHostedWorld(
                "Integration Replace Busy",
                SharedWorldIntegrationBackend.OWNER,
                SharedWorldIntegrationBackend.HOST
        );
        SharedWorldApiClient owner = hosted.ownerClient();
        String worldId = hosted.world().id();

        Path root = Files.createTempDirectory("sharedworld-replace-busy-integration");
        try {
            Path source = replacementSave(root);
            ManagedWorldStore managedWorldStore = new ManagedWorldStore(root.resolve("managed"));
            WorldSyncCoordinator syncCoordinator = new WorldSyncCoordinator(owner, managedWorldStore);
            SharedWorldReplaceFlow flow = replaceFlow(owner, managedWorldStore, syncCoordinator);

            assertThrows(SharedWorldReplaceFlow.WorldBusyException.class,
                    () -> flow.replace(worldId, hosted.world().name(), source, false, silentSink()));
        } finally {
            deleteTree(root);
        }
    }

    private static Path replacementSave(Path root) throws Exception {
        Path source = root.resolve("replacement");
        Files.createDirectories(source.resolve("region"));
        CompoundTag levelTag = new CompoundTag();
        levelTag.put("Data", new CompoundTag());
        NbtCompat.writeCompressed(levelTag, source.resolve("level.dat"));
        Files.writeString(source.resolve("region").resolve("notes.txt"), "replacement world");
        return source;
    }

    private static SharedWorldReplaceFlow replaceFlow(
            SharedWorldApiClient client,
            ManagedWorldStore managedWorldStore,
            WorldSyncCoordinator syncCoordinator
    ) {
        return new SharedWorldReplaceFlow(
                new SharedWorldReplaceFlow.ReplaceBackend() {
                    @Override
                    public SharedWorldModels.EnterSessionResponseDto enterSession(String worldId, boolean acknowledgeUncleanShutdown) throws java.io.IOException, InterruptedException {
                        return client.enterSession(worldId, null, acknowledgeUncleanShutdown);
                    }

                    @Override
                    public void releaseHost(String worldId, boolean graceful, long runtimeEpoch, String hostToken) throws java.io.IOException, InterruptedException {
                        client.releaseHost(worldId, graceful, runtimeEpoch, hostToken);
                    }

                    @Override
                    public void heartbeatHost(String worldId, long runtimeEpoch, String hostToken) throws java.io.IOException, InterruptedException {
                        client.heartbeatHost(worldId, runtimeEpoch, hostToken, null);
                    }

                    @Override
                    public String canonicalAssignedPlayerUuidWithHyphens(String backendAssignedPlayerUuid) {
                        return client.canonicalAssignedPlayerUuidWithHyphens(backendAssignedPlayerUuid);
                    }
                },
                new InitialSnapshotUploadPipeline.WorkingCopyStore() {
                    @Override
                    public void resetWorkingCopy(String worldId) throws java.io.IOException {
                        managedWorldStore.resetWorkingCopy(worldId);
                    }

                    @Override
                    public Path workingCopy(String worldId) {
                        return managedWorldStore.workingCopy(worldId);
                    }
                },
                syncCoordinator::uploadSnapshot,
                heartbeat -> {
                    heartbeat.run();
                    return () -> {
                    };
                }
        );
    }

    private static InitialSnapshotUploadPipeline.ProgressSink silentSink() {
        return new InitialSnapshotUploadPipeline.ProgressSink() {
            @Override
            public void updateDeterminate(Component label, String phase, double targetFraction, Long bytesDone, Long bytesTotal) {
            }

            @Override
            public void updateIndeterminate(Component label, String phase) {
            }
        };
    }

    private static void deleteTree(Path root) throws Exception {
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
