package link.sharedworld.host;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SharedWorldHostingManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void handoffStartupSyncsExistingWorldWithAssignmentIdentity() throws Exception {
        RecordingSyncAccess syncAccess = new RecordingSyncAccess(this.tempDir.resolve("prepared-world"));
        RecordingWorldOpenController worldOpenController = new RecordingWorldOpenController();
        SharedWorldHostingManager manager = new SharedWorldHostingManager(
                new SharedWorldApiClient(
                        "http://127.0.0.1:1",
                        () -> new SharedWorldApiClient.SessionIdentity("backend-player", "Tester", "dev:test")
                ),
                new ManagedWorldStore(this.tempDir.resolve("managed")),
                syncAccess,
                worldOpenController,
                new HostStartupProgressRelayController(
                        (worldId, runtimeEpoch, hostToken, progress) -> {
                        },
                        Runnable::run,
                        () -> 0L
                )
        );

        setField(manager, "world", world("world-1", "Handoff World"));
        setField(manager, "latestManifest", latestManifest("world-1"));
        setField(manager, "hostPlayerUuid", "22222222-2222-2222-2222-222222222222");
        setField(manager, "runtimeEpoch", 7L);
        setField(manager, "hostToken", "token-7");
        setField(manager, "startupAttemptId", 7L);
        AtomicBoolean startupStarted = (AtomicBoolean) getField(manager, "startupStarted");
        startupStarted.set(true);

        Method prepareAndOpen = SharedWorldHostingManager.class.getDeclaredMethod("prepareAndOpen", long.class);
        prepareAndOpen.setAccessible(true);
        prepareAndOpen.invoke(manager, 7L);

        assertEquals(1, syncAccess.ensureCalls);
        assertEquals("world-1", syncAccess.worldId);
        assertEquals("22222222-2222-2222-2222-222222222222", syncAccess.hostPlayerUuid);
        assertEquals(1, worldOpenController.openExistingCalls);
        assertEquals(syncAccess.preparedWorldDirectory, worldOpenController.openedWorldDirectory);
    }

    @Test
    void beginHostingFailsClosedWhenManifestIsMissing() {
        SharedWorldHostingManager manager = new SharedWorldHostingManager(
                new SharedWorldApiClient(
                        "http://127.0.0.1:1",
                        () -> new SharedWorldApiClient.SessionIdentity("backend-player", "Tester", "dev:test")
                ),
                new ManagedWorldStore(this.tempDir.resolve("managed-fail-closed")),
                null,
                new RecordingWorldOpenController(),
                new HostStartupProgressRelayController(
                        (worldId, runtimeEpoch, hostToken, progress) -> {
                        },
                        Runnable::run,
                        () -> 0L
                )
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> manager.beginHosting(
                        null,
                        world("world-1", "Handoff World"),
                        null,
                        new SharedWorldModels.HostAssignmentDto("world-1", "22222222222222222222222222222222", "Guest", 7L, "token-7", Instant.EPOCH.toString())
                )
        );

        assertEquals(
                "SharedWorld host startup requires a finalized snapshot manifest. Fresh-world startup is no longer supported.",
                error.getMessage()
        );
    }

    @Test
    void backendFinalizationMarksHostManagerAsReleasing() throws Exception {
        AtomicReference<SharedWorldModels.StartupProgressDto> relayedProgress = new AtomicReference<>();
        SharedWorldHostingManager manager = new SharedWorldHostingManager(
                new SharedWorldApiClient(
                        "http://127.0.0.1:1",
                        () -> new SharedWorldApiClient.SessionIdentity("backend-player", "Tester", "dev:test")
                ),
                new ManagedWorldStore(this.tempDir.resolve("managed-release")),
                null,
                new RecordingWorldOpenController(),
                new HostStartupProgressRelayController(
                        (worldId, runtimeEpoch, hostToken, progress) -> relayedProgress.set(progress),
                        Runnable::run,
                        () -> 0L
                )
        );

        setField(manager, "world", world("world-1", "Handoff World"));
        setField(manager, "hostPlayerUuid", "22222222-2222-2222-2222-222222222222");
        setField(manager, "runtimeEpoch", 7L);
        setField(manager, "hostToken", "token-7");
        setField(manager, "hostSessionGeneration", 1L);
        setField(manager, "startupAttemptId", 7L);
        setField(manager, "phase", SharedWorldHostingManager.Phase.RUNNING);
        AtomicBoolean startupStarted = (AtomicBoolean) getField(manager, "startupStarted");
        startupStarted.set(true);

        manager.markCoordinatedBackendFinalizationStarted();

        assertEquals(SharedWorldHostingManager.Phase.RELEASING, manager.phase());
        assertEquals("indeterminate", relayedProgress.get().mode());
    }

    private static SharedWorldModels.WorldSummaryDto world(String worldId, String worldName) {
        return new SharedWorldModels.WorldSummaryDto(
                worldId,
                "world",
                worldName,
                "player-owner",
                null,
                null,
                null,
                1,
                "handoff",
                "snapshot-1",
                Instant.EPOCH.toString(),
                null,
                null,
                null,
                0,
                new String[0],
                "google-drive",
                true,
                null
        );
    }

    private static SharedWorldModels.SnapshotManifestDto latestManifest(String worldId) {
        return new SharedWorldModels.SnapshotManifestDto(
                worldId,
                "snapshot-1",
                Instant.EPOCH.toString(),
                "player-host",
                new SharedWorldModels.ManifestFileDto[0],
                new SharedWorldModels.SnapshotPackDto[0]
        );
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = SharedWorldHostingManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = SharedWorldHostingManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class RecordingSyncAccess implements SharedWorldHostingManager.SyncAccess {
        private final Path preparedWorldDirectory;
        private int ensureCalls;
        private String worldId;
        private String hostPlayerUuid;

        private RecordingSyncAccess(Path preparedWorldDirectory) {
            this.preparedWorldDirectory = preparedWorldDirectory;
        }

        @Override
        public Path ensureSynchronizedWorkingCopy(String worldId, String hostPlayerUuid, WorldSyncProgressListener progressListener) {
            this.ensureCalls += 1;
            this.worldId = worldId;
            this.hostPlayerUuid = hostPlayerUuid;
            return this.preparedWorldDirectory;
        }

        @Override
        public SharedWorldModels.SnapshotManifestDto uploadSnapshot(String worldId, Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken, WorldSyncProgressListener progressListener) {
            throw new UnsupportedOperationException("uploadSnapshot should not run during startup sync");
        }
    }

    private static final class RecordingWorldOpenController implements SharedWorldHostingManager.WorldOpenController {
        private int openExistingCalls;
        private Path openedWorldDirectory;

        @Override
        public void openExistingWorld(ManagedWorldStore worldStore, SharedWorldModels.WorldSummaryDto world, Path worldDirectory) {
            this.openExistingCalls += 1;
            this.openedWorldDirectory = worldDirectory;
        }
    }
}
