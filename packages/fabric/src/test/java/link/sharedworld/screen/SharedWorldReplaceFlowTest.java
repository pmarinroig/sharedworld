package link.sharedworld.screen;

import java.nio.file.Files;
import java.nio.file.Path;

import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.api.SharedWorldModels.EnterSessionResponseDto;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldReplaceFlowTest {
    @TempDir
    Path tempDir;

    @Test
    void replaceUploadsAFullSnapshotUnderTheEnterSessionLeaseAndReleasesIt() throws Exception {
        Path source = Files.createDirectories(this.tempDir.resolve("source"));
        Files.writeString(source.resolve("level.dat"), "data");
        Files.writeString(source.resolve("session.lock"), "lock");

        FakeReplaceBackend backend = new FakeReplaceBackend("host");
        FakeStore store = new FakeStore(this.tempDir.resolve("working"));
        FakeUploader uploader = new FakeUploader();
        SharedWorldReplaceFlow flow = new SharedWorldReplaceFlow(backend, store, uploader, heartbeat -> () -> {
        });

        String message = flow.replace("world-1", "Friends SMP", source, false, silentSink());

        assertEquals("screen.sharedworld.replace_done", message);
        assertEquals(1, store.resetCalls);
        assertEquals(9L, uploader.runtimeEpoch);
        assertEquals("token-9", uploader.hostToken);
        assertTrue(Files.exists(store.workingCopy("world-1").resolve("level.dat")));
        // Local-only files never enter the working copy that seeds the snapshot.
        assertFalse(Files.exists(store.workingCopy("world-1").resolve("session.lock")));
        assertEquals(1, backend.releaseCalls);
        assertFalse(backend.lastReleaseGraceful);
    }

    @Test
    void replaceRefusesWhileTheWorldIsBeingHosted() throws Exception {
        Path source = Files.createDirectories(this.tempDir.resolve("source-busy"));
        Files.writeString(source.resolve("level.dat"), "data");

        FakeReplaceBackend backend = new FakeReplaceBackend("connect");
        FakeStore store = new FakeStore(this.tempDir.resolve("working-busy"));
        FakeUploader uploader = new FakeUploader();
        SharedWorldReplaceFlow flow = new SharedWorldReplaceFlow(backend, store, uploader, heartbeat -> () -> {
        });

        assertThrows(SharedWorldReplaceFlow.WorldBusyException.class,
                () -> flow.replace("world-1", "Friends SMP", source, false, silentSink()));
        // Nothing was uploaded and the working copy was never touched.
        assertEquals(0, store.resetCalls);
        assertEquals(0, uploader.uploadCalls);
        assertEquals(0, backend.releaseCalls);
    }

    @Test
    void replaceSurfacesThePendingUncleanShutdownWarning() throws Exception {
        Path source = Files.createDirectories(this.tempDir.resolve("source-warn"));
        Files.writeString(source.resolve("level.dat"), "data");

        FakeReplaceBackend backend = new FakeReplaceBackend("warn-host");
        SharedWorldReplaceFlow flow = new SharedWorldReplaceFlow(
                backend, new FakeStore(this.tempDir.resolve("working-warn")), new FakeUploader(), heartbeat -> () -> {
                });

        assertThrows(SharedWorldReplaceFlow.UncleanShutdownPendingException.class,
                () -> flow.replace("world-1", "Friends SMP", source, false, silentSink()));

        // Acknowledging retries through the same entry point.
        backend.action = "host";
        flow.replace("world-1", "Friends SMP", source, true, silentSink());
        assertTrue(backend.lastAcknowledge);
    }

    @Test
    void aFailedUploadReleasesTheLeaseAndNeverDeletesAnything() throws Exception {
        Path source = Files.createDirectories(this.tempDir.resolve("source-fail"));
        Files.writeString(source.resolve("level.dat"), "data");

        FakeReplaceBackend backend = new FakeReplaceBackend("host");
        FakeStore store = new FakeStore(this.tempDir.resolve("working-fail"));
        FakeUploader uploader = new FakeUploader();
        uploader.failWith = new java.io.IOException("upload boom");
        SharedWorldReplaceFlow flow = new SharedWorldReplaceFlow(backend, store, uploader, heartbeat -> () -> {
        });

        java.io.IOException thrown = assertThrows(java.io.IOException.class,
                () -> flow.replace("world-1", "Friends SMP", source, false, silentSink()));
        assertEquals("upload boom", thrown.getMessage());
        assertEquals(1, backend.releaseCalls);
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

    private static final class FakeReplaceBackend implements SharedWorldReplaceFlow.ReplaceBackend {
        private String action;
        private boolean lastAcknowledge;
        private int releaseCalls;
        private boolean lastReleaseGraceful = true;

        private FakeReplaceBackend(String action) {
            this.action = action;
        }

        @Override
        public EnterSessionResponseDto enterSession(String worldId, boolean acknowledgeUncleanShutdown) {
            this.lastAcknowledge = acknowledgeUncleanShutdown;
            SharedWorldModels.HostAssignmentDto assignment = "host".equals(this.action)
                    ? new SharedWorldModels.HostAssignmentDto(worldId, "11111111111111111111111111111111", "Owner", 9L, "token-9", null)
                    : null;
            return new EnterSessionResponseDto(this.action, null, null, null, assignment, null);
        }

        @Override
        public void releaseHost(String worldId, boolean graceful, long runtimeEpoch, String hostToken) {
            this.releaseCalls += 1;
            this.lastReleaseGraceful = graceful;
            assertEquals(9L, runtimeEpoch);
            assertEquals("token-9", hostToken);
        }

        @Override
        public void heartbeatHost(String worldId, long runtimeEpoch, String hostToken) {
        }

        @Override
        public String canonicalAssignedPlayerUuidWithHyphens(String backendAssignedPlayerUuid) {
            return "11111111-1111-1111-1111-111111111111";
        }
    }

    private static final class FakeStore implements InitialSnapshotUploadPipeline.WorkingCopyStore {
        private final Path root;
        private int resetCalls;

        private FakeStore(Path root) {
            this.root = root;
        }

        @Override
        public void resetWorkingCopy(String worldId) throws java.io.IOException {
            this.resetCalls += 1;
            Files.createDirectories(this.root.resolve(worldId));
        }

        @Override
        public Path workingCopy(String worldId) {
            return this.root.resolve(worldId);
        }
    }

    private static final class FakeUploader implements InitialSnapshotUploadPipeline.SnapshotUploader {
        private long runtimeEpoch;
        private String hostToken;
        private int uploadCalls;
        private java.io.IOException failWith;

        @Override
        public void uploadSnapshot(String worldId, Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken, link.sharedworld.sync.WorldSyncProgressListener progressListener) throws java.io.IOException {
            this.uploadCalls += 1;
            this.runtimeEpoch = runtimeEpoch;
            this.hostToken = hostToken;
            if (this.failWith != null) {
                throw this.failWith;
            }
        }
    }
}
