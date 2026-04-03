package link.sharedworld.screen;

import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.api.SharedWorldModels.CreateWorldResultDto;
import link.sharedworld.api.SharedWorldModels.StorageLinkSessionDto;
import link.sharedworld.api.SharedWorldModels.WorldDetailsDto;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldCreateFlowTest {
    @Test
    void createFlowSeedsInitialSnapshotWithTemporaryHostAssignmentAndReleasesIt() throws Exception {
        Path root = Files.createTempDirectory("sharedworld-create-flow");
        try {
            Path source = root.resolve("source");
            Files.createDirectories(source);
            Files.writeString(source.resolve("level.dat"), "data");

            FakeBackend backend = new FakeBackend();
            FakeWorkingCopyStore workingCopyStore = new FakeWorkingCopyStore(root.resolve("working"));
            FakeSnapshotUploader uploader = new FakeSnapshotUploader();
            List<String> progressEvents = new ArrayList<>();
            SharedWorldCreateFlow flow = new SharedWorldCreateFlow(
                    backend,
                    path -> null,
                    workingCopyStore,
                    uploader
            );

            String message = flow.create(
                    new CreateSharedWorldScreen.CreateRequest(
                            new LocalSaveCatalog.LocalSaveOption("save-1", "Save", source, 0L, null, Component.empty()),
                            new StorageLinkSessionDto("storage-1", "google-drive", "linked", null, Instant.EPOCH.toString(), null, null, null),
                            "World",
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

            assertEquals("screen.sharedworld.operation_created_world", message);
            assertEquals(7L, uploader.runtimeEpoch);
            assertEquals("token-7", uploader.hostToken);
            assertEquals(1, backend.releaseCalls);
            assertEquals("world-1", backend.releasedWorldId);
            assertTrue(progressEvents.contains("indeterminate:create_upload_prepare"));
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

    @Test
    void createFlowUsesCreateSpecificLocalizedProgressLabels() throws Exception {
        Path root = Files.createTempDirectory("sharedworld-create-flow-progress");
        try {
            Path source = root.resolve("source");
            Files.createDirectories(source);
            Files.writeString(source.resolve("level.dat"), "data");

            FakeBackend backend = new FakeBackend();
            FakeWorkingCopyStore workingCopyStore = new FakeWorkingCopyStore(root.resolve("working"));
            FakeSnapshotUploader uploader = new FakeSnapshotUploader();
            List<String> progressEvents = new ArrayList<>();
            SharedWorldCreateFlow flow = new SharedWorldCreateFlow(
                    backend,
                    path -> null,
                    workingCopyStore,
                    uploader
            );

            flow.create(
                    new CreateSharedWorldScreen.CreateRequest(
                            new LocalSaveCatalog.LocalSaveOption("save-1", "Save", source, 0L, null, Component.empty()),
                            new StorageLinkSessionDto("storage-1", "google-drive", "linked", null, Instant.EPOCH.toString(), null, null, null),
                            "World",
                            "MOTD",
                            null,
                            false
                    ),
                    new SharedWorldCreateFlow.ProgressSink() {
                        @Override
                        public void updateDeterminate(Component label, String phase, double targetFraction, Long bytesDone, Long bytesTotal) {
                            progressEvents.add("determinate:" + phase + ":" + label.getString());
                        }

                        @Override
                        public void updateIndeterminate(Component label, String phase) {
                            progressEvents.add("indeterminate:" + phase + ":" + label.getString());
                        }
                    }
            );

            assertTrue(progressEvents.contains("indeterminate:create_prepare:screen.sharedworld.create_progress_preparing"));
            assertTrue(progressEvents.contains("determinate:create_copy:screen.sharedworld.create_progress_copying"));
            assertTrue(progressEvents.contains("indeterminate:create_upload_prepare:screen.sharedworld.create_progress_uploading"));
            assertTrue(progressEvents.contains("determinate:create_upload:screen.sharedworld.create_progress_uploading"));
            assertTrue(progressEvents.contains("indeterminate:create_finish:screen.sharedworld.create_progress_finishing"));
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

    private static final class FakeBackend implements SharedWorldCreateFlow.CreateBackend {
        private int releaseCalls;
        private String releasedWorldId;

        @Override
        public CreateWorldResultDto createWorld(String name, String motdLine1, String customIconPngBase64, SharedWorldModels.ImportedWorldSourceDto importSource, String storageLinkSessionId) {
            return new CreateWorldResultDto(
                    new WorldDetailsDto(
                            "world-1",
                            "world-1",
                            name,
                            "player-host",
                            motdLine1,
                            null,
                            null,
                            1,
                            "idle",
                            null,
                            null,
                            null,
                            null,
                            null,
                            0,
                            new String[0],
                            "google-drive",
                            true,
                            null,
                            null,
                            new SharedWorldModels.WorldMembershipDto[0],
                            null,
                            null
                    ),
                    new SharedWorldModels.HostAssignmentDto("world-1", "11111111111111111111111111111111", "Host", 7L, "token-7", Instant.EPOCH.toString())
            );
        }

        @Override
        public void releaseHost(String worldId, boolean graceful, long runtimeEpoch, String hostToken) {
            this.releaseCalls += 1;
            this.releasedWorldId = worldId;
            assertEquals(7L, runtimeEpoch);
            assertEquals("token-7", hostToken);
        }

        @Override
        public String canonicalAssignedPlayerUuidWithHyphens(String backendAssignedPlayerUuid) {
            return "11111111-1111-1111-1111-111111111111";
        }
    }

    private static final class FakeWorkingCopyStore implements SharedWorldCreateFlow.WorkingCopyStore {
        private final Path root;

        private FakeWorkingCopyStore(Path root) {
            this.root = root;
        }

        @Override
        public void resetWorkingCopy(String worldId) throws java.io.IOException {
            Files.createDirectories(this.root.resolve(worldId));
        }

        @Override
        public Path workingCopy(String worldId) {
            return this.root.resolve(worldId);
        }
    }

    private static final class FakeSnapshotUploader implements SharedWorldCreateFlow.SnapshotUploader {
        private long runtimeEpoch;
        private String hostToken;

        @Override
        public void uploadSnapshot(String worldId, Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken, link.sharedworld.sync.WorldSyncProgressListener progressListener) throws java.io.IOException {
            this.runtimeEpoch = runtimeEpoch;
            this.hostToken = hostToken;
            assertTrue(Files.exists(worldDirectory.resolve("level.dat")));
            if (progressListener != null) {
                progressListener.onProgress(new link.sharedworld.sync.WorldSyncProgress(
                        link.sharedworld.sync.WorldSyncCoordinator.STAGE_UPLOADING_CHANGED_FILES,
                        0.5D,
                        5L,
                        10L,
                        "uploading"
                ));
            }
        }
    }
}
