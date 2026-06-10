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
            FakeLeaseKeepAlive keepAlive = new FakeLeaseKeepAlive();
            List<String> progressEvents = new ArrayList<>();
            SharedWorldCreateFlow flow = new SharedWorldCreateFlow(
                    backend,
                    path -> null,
                    workingCopyStore,
                    uploader,
                    keepAlive
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
            assertEquals(0, backend.deleteCalls);
            assertEquals(2, backend.heartbeatCalls);
            assertEquals(7L, backend.lastHeartbeatEpoch);
            assertEquals("token-7", backend.lastHeartbeatToken);
            assertEquals(1, keepAlive.started);
            assertEquals(1, keepAlive.closed);
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
                    uploader,
                    new FakeLeaseKeepAlive()
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

    @Test
    void createFlowDeletesTheGhostWorldWhenTheSeedUploadFails() throws Exception {
        Path root = Files.createTempDirectory("sharedworld-create-flow-failure");
        try {
            Path source = root.resolve("source");
            Files.createDirectories(source);
            Files.writeString(source.resolve("level.dat"), "data");

            FakeBackend backend = new FakeBackend();
            FakeWorkingCopyStore workingCopyStore = new FakeWorkingCopyStore(root.resolve("working"));
            FakeSnapshotUploader uploader = new FakeSnapshotUploader();
            uploader.failWith = new java.io.IOException("upload boom");
            FakeLeaseKeepAlive keepAlive = new FakeLeaseKeepAlive();
            SharedWorldCreateFlow flow = new SharedWorldCreateFlow(
                    backend,
                    path -> null,
                    workingCopyStore,
                    uploader,
                    keepAlive
            );

            java.io.IOException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    java.io.IOException.class,
                    () -> flow.create(request(source), silentProgressSink())
            );

            assertEquals("upload boom", thrown.getMessage());
            assertEquals(1, backend.releaseCalls);
            assertEquals(false, backend.lastReleaseGraceful);
            assertEquals(1, backend.deleteCalls);
            assertEquals("world-1", backend.deletedWorldId);
            assertEquals(1, keepAlive.closed);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void createFlowSucceedsWhenTheSeedLeaseReleaseFailsAfterAGoodUpload() throws Exception {
        Path root = Files.createTempDirectory("sharedworld-create-flow-release");
        try {
            Path source = root.resolve("source");
            Files.createDirectories(source);
            Files.writeString(source.resolve("level.dat"), "data");

            FakeBackend backend = new FakeBackend();
            backend.releaseThrows = true;
            FakeWorkingCopyStore workingCopyStore = new FakeWorkingCopyStore(root.resolve("working"));
            FakeSnapshotUploader uploader = new FakeSnapshotUploader();
            SharedWorldCreateFlow flow = new SharedWorldCreateFlow(
                    backend,
                    path -> null,
                    workingCopyStore,
                    uploader,
                    new FakeLeaseKeepAlive()
            );

            String message = flow.create(request(source), silentProgressSink());

            // The snapshot is committed, so a failed lease release must not turn a good create into
            // an error, and it must not delete the freshly created world.
            assertEquals("screen.sharedworld.operation_created_world", message);
            assertEquals(1, backend.releaseCalls);
            assertEquals(0, backend.deleteCalls);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void createFlowSucceedsEvenWhenKeepAliveHeartbeatsFail() throws Exception {
        Path root = Files.createTempDirectory("sharedworld-create-flow-heartbeat");
        try {
            Path source = root.resolve("source");
            Files.createDirectories(source);
            Files.writeString(source.resolve("level.dat"), "data");

            FakeBackend backend = new FakeBackend();
            backend.heartbeatThrows = true;
            FakeWorkingCopyStore workingCopyStore = new FakeWorkingCopyStore(root.resolve("working"));
            FakeSnapshotUploader uploader = new FakeSnapshotUploader();
            SharedWorldCreateFlow flow = new SharedWorldCreateFlow(
                    backend,
                    path -> null,
                    workingCopyStore,
                    uploader,
                    new FakeLeaseKeepAlive()
            );

            String message = flow.create(request(source), silentProgressSink());

            assertEquals("screen.sharedworld.operation_created_world", message);
            assertEquals(0, backend.deleteCalls);
            assertEquals(1, backend.releaseCalls);
        } finally {
            deleteTree(root);
        }
    }

    private static CreateSharedWorldScreen.CreateRequest request(Path source) {
        return new CreateSharedWorldScreen.CreateRequest(
                new LocalSaveCatalog.LocalSaveOption("save-1", "Save", source, 0L, null, Component.empty()),
                new StorageLinkSessionDto("storage-1", "google-drive", "linked", null, Instant.EPOCH.toString(), null, null, null),
                "World",
                "MOTD",
                null,
                false
        );
    }

    private static SharedWorldCreateFlow.ProgressSink silentProgressSink() {
        return new SharedWorldCreateFlow.ProgressSink() {
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

    private static final class FakeBackend implements SharedWorldCreateFlow.CreateBackend {
        private int releaseCalls;
        private String releasedWorldId;
        private boolean lastReleaseGraceful = true;
        private int heartbeatCalls;
        private long lastHeartbeatEpoch;
        private String lastHeartbeatToken;
        private int deleteCalls;
        private String deletedWorldId;
        private boolean heartbeatThrows;
        private boolean releaseThrows;

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
        public void releaseHost(String worldId, boolean graceful, long runtimeEpoch, String hostToken) throws java.io.IOException {
            this.releaseCalls += 1;
            this.releasedWorldId = worldId;
            this.lastReleaseGraceful = graceful;
            assertEquals(7L, runtimeEpoch);
            assertEquals("token-7", hostToken);
            if (this.releaseThrows) {
                throw new java.io.IOException("release boom");
            }
        }

        @Override
        public void heartbeatHost(String worldId, long runtimeEpoch, String hostToken) throws java.io.IOException {
            this.heartbeatCalls += 1;
            this.lastHeartbeatEpoch = runtimeEpoch;
            this.lastHeartbeatToken = hostToken;
            if (this.heartbeatThrows) {
                throw new java.io.IOException("heartbeat boom");
            }
        }

        @Override
        public void deleteWorld(String worldId) {
            this.deleteCalls += 1;
            this.deletedWorldId = worldId;
        }

        @Override
        public String canonicalAssignedPlayerUuidWithHyphens(String backendAssignedPlayerUuid) {
            return "11111111-1111-1111-1111-111111111111";
        }
    }

    /**
     * Fires the heartbeat synchronously a couple of times on start (simulating keep-alive ticks
     * across the copy/upload) and records that the handle was closed.
     */
    private static final class FakeLeaseKeepAlive implements SharedWorldCreateFlow.LeaseKeepAlive {
        private int started;
        private int closed;

        @Override
        public AutoCloseable start(Runnable heartbeat) {
            this.started += 1;
            heartbeat.run();
            heartbeat.run();
            return () -> this.closed += 1;
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
        private java.io.IOException failWith;

        @Override
        public void uploadSnapshot(String worldId, Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken, link.sharedworld.sync.WorldSyncProgressListener progressListener) throws java.io.IOException {
            this.runtimeEpoch = runtimeEpoch;
            this.hostToken = hostToken;
            assertTrue(Files.exists(worldDirectory.resolve("level.dat")));
            if (this.failWith != null) {
                throw this.failWith;
            }
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
