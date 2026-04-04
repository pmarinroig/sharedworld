package link.sharedworld.host;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldHostingManagerTest {
    private static final String HOST_UUID = "22222222-2222-2222-2222-222222222222";

    @TempDir
    Path tempDir;

    @Test
    void handoffStartupSyncsExistingWorldWithAssignmentIdentity() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed"));
        RecordingSyncAccess syncAccess = new RecordingSyncAccess(this.tempDir.resolve("prepared-world"));
        RecordingWorldOpenController worldOpenController = new RecordingWorldOpenController();
        SharedWorldHostingManager manager = manager(worldStore, syncAccess, worldOpenController, new InMemoryHostRecoveryStore(), worldId -> false);

        primeStartup(manager, world("world-1", "Handoff World"), 7L, SharedWorldHostingManager.StartupMode.NORMAL);
        invokePrepareAndOpen(manager, 7L);

        assertEquals(1, syncAccess.ensureCalls);
        assertEquals(0, syncAccess.uploadCalls);
        assertEquals("world-1", syncAccess.worldId);
        assertEquals(HOST_UUID, syncAccess.hostPlayerUuid);
        assertEquals(1, worldOpenController.openExistingCalls);
        assertEquals(syncAccess.preparedWorldDirectory, worldOpenController.openedWorldDirectory);
    }

    @Test
    void acknowledgedUncleanShutdownWithMatchingCrashMarkerUploadsLocalWorldBeforeOpening() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-recovery"));
        Path workingCopy = worldStore.workingCopy("world-1");
        Files.createDirectories(workingCopy);
        RecordingSyncAccess syncAccess = new RecordingSyncAccess(this.tempDir.resolve("prepared-world"));
        RecordingWorldOpenController worldOpenController = new RecordingWorldOpenController();
        InMemoryHostRecoveryStore recoveryStore = new InMemoryHostRecoveryStore();
        recoveryStore.record = new SharedWorldHostingManager.HostRecoveryRecord("world-1", "Handoff World", HOST_UUID, 6L, Instant.EPOCH.toString());
        SharedWorldHostingManager manager = manager(worldStore, syncAccess, worldOpenController, recoveryStore, worldId -> false);

        primeStartup(manager, world("world-1", "Handoff World"), 7L, SharedWorldHostingManager.StartupMode.ACKNOWLEDGED_UNCLEAN_SHUTDOWN);
        invokePrepareAndOpen(manager, 7L);

        assertEquals(0, syncAccess.ensureCalls);
        assertEquals(1, syncAccess.uploadCalls);
        assertEquals(workingCopy, syncAccess.uploadedWorldDirectory);
        assertEquals(workingCopy, worldOpenController.openedWorldDirectory);
    }

    @Test
    void acknowledgedUncleanShutdownWithoutCrashMarkerFallsBackToDownloadStartup() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-no-marker"));
        RecordingSyncAccess syncAccess = new RecordingSyncAccess(this.tempDir.resolve("prepared-world"));
        RecordingWorldOpenController worldOpenController = new RecordingWorldOpenController();
        SharedWorldHostingManager manager = manager(worldStore, syncAccess, worldOpenController, new InMemoryHostRecoveryStore(), worldId -> false);

        primeStartup(manager, world("world-1", "Handoff World"), 7L, SharedWorldHostingManager.StartupMode.ACKNOWLEDGED_UNCLEAN_SHUTDOWN);
        invokePrepareAndOpen(manager, 7L);

        assertEquals(1, syncAccess.ensureCalls);
        assertEquals(0, syncAccess.uploadCalls);
    }

    @Test
    void staleCrashMarkerWithMismatchedRuntimeEpochFallsBackToDownloadStartup() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-stale-marker"));
        Files.createDirectories(worldStore.workingCopy("world-1"));
        RecordingSyncAccess syncAccess = new RecordingSyncAccess(this.tempDir.resolve("prepared-world"));
        RecordingWorldOpenController worldOpenController = new RecordingWorldOpenController();
        InMemoryHostRecoveryStore recoveryStore = new InMemoryHostRecoveryStore();
        recoveryStore.record = new SharedWorldHostingManager.HostRecoveryRecord("world-1", "Handoff World", HOST_UUID, 5L, Instant.EPOCH.toString());
        SharedWorldHostingManager manager = manager(worldStore, syncAccess, worldOpenController, recoveryStore, worldId -> false);

        primeStartup(manager, world("world-1", "Handoff World"), 7L, SharedWorldHostingManager.StartupMode.ACKNOWLEDGED_UNCLEAN_SHUTDOWN);
        invokePrepareAndOpen(manager, 7L);

        assertEquals(1, syncAccess.ensureCalls);
        assertEquals(0, syncAccess.uploadCalls);
    }

    @Test
    void recoveryUploadFailureStopsStartupAndPreservesMarkerWithoutDownloadFallback() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-failed-recovery"));
        Files.createDirectories(worldStore.workingCopy("world-1"));
        RecordingSyncAccess syncAccess = new RecordingSyncAccess(this.tempDir.resolve("prepared-world"));
        syncAccess.failUpload = true;
        InMemoryHostRecoveryStore recoveryStore = new InMemoryHostRecoveryStore();
        recoveryStore.record = new SharedWorldHostingManager.HostRecoveryRecord("world-1", "Handoff World", HOST_UUID, 6L, Instant.EPOCH.toString());
        SharedWorldHostingManager manager = manager(worldStore, syncAccess, new RecordingWorldOpenController(), recoveryStore, worldId -> false);

        primeStartup(manager, world("world-1", "Handoff World"), 7L, SharedWorldHostingManager.StartupMode.ACKNOWLEDGED_UNCLEAN_SHUTDOWN);

        InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> invokePrepareAndOpen(manager, 7L));
        assertNotNull(error.getCause());
        assertEquals(0, syncAccess.ensureCalls);
        assertEquals(1, syncAccess.uploadCalls);
        assertNotNull(recoveryStore.record);
    }

    @Test
    void pendingReleaseRecoveryBlocksCrashLocalRecovery() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-pending-release"));
        Files.createDirectories(worldStore.workingCopy("world-1"));
        RecordingSyncAccess syncAccess = new RecordingSyncAccess(this.tempDir.resolve("prepared-world"));
        InMemoryHostRecoveryStore recoveryStore = new InMemoryHostRecoveryStore();
        recoveryStore.record = new SharedWorldHostingManager.HostRecoveryRecord("world-1", "Handoff World", HOST_UUID, 6L, Instant.EPOCH.toString());
        SharedWorldHostingManager manager = manager(worldStore, syncAccess, new RecordingWorldOpenController(), recoveryStore, worldId -> true);

        primeStartup(manager, world("world-1", "Handoff World"), 7L, SharedWorldHostingManager.StartupMode.ACKNOWLEDGED_UNCLEAN_SHUTDOWN);
        invokePrepareAndOpen(manager, 7L);

        assertEquals(1, syncAccess.ensureCalls);
        assertEquals(0, syncAccess.uploadCalls);
    }

    @Test
    void warningAvailabilityRequiresMatchingPreviousRuntimeEpoch() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-warning-epoch"));
        Files.createDirectories(worldStore.workingCopy("world-1"));
        InMemoryHostRecoveryStore recoveryStore = new InMemoryHostRecoveryStore();
        recoveryStore.record = new SharedWorldHostingManager.HostRecoveryRecord("world-1", "Handoff World", HOST_UUID, 6L, Instant.EPOCH.toString());
        SharedWorldHostingManager manager = manager(worldStore, new RecordingSyncAccess(this.tempDir.resolve("prepared-world")), new RecordingWorldOpenController(), recoveryStore, worldId -> false);

        assertTrue(manager.hasRecoverableLocalCrashState("world-1", HOST_UUID, 6L));
        assertFalse(manager.hasRecoverableLocalCrashState("world-1", HOST_UUID, 5L));
    }

    @Test
    void warningAvailabilityIsBlockedByPendingReleaseRecovery() throws Exception {
        ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-warning-pending"));
        Files.createDirectories(worldStore.workingCopy("world-1"));
        InMemoryHostRecoveryStore recoveryStore = new InMemoryHostRecoveryStore();
        recoveryStore.record = new SharedWorldHostingManager.HostRecoveryRecord("world-1", "Handoff World", HOST_UUID, 6L, Instant.EPOCH.toString());
        SharedWorldHostingManager manager = manager(worldStore, new RecordingSyncAccess(this.tempDir.resolve("prepared-world")), new RecordingWorldOpenController(), recoveryStore, worldId -> true);

        assertFalse(manager.hasRecoverableLocalCrashState("world-1", HOST_UUID, 6L));
    }

    @Test
    void heartbeatPromotionWritesHostRecoveryMarker() throws Exception {
        InMemoryHostRecoveryStore recoveryStore = new InMemoryHostRecoveryStore();
        SharedWorldHostingManager manager = manager(
                new ManagedWorldStore(this.tempDir.resolve("managed-live-marker")),
                new RecordingSyncAccess(this.tempDir.resolve("prepared-world")),
                new RecordingWorldOpenController(),
                recoveryStore,
                worldId -> false
        );

        setField(manager, "world", world("world-1", "Handoff World"));
        setField(manager, "hostPlayerUuid", HOST_UUID);
        setField(manager, "runtimeEpoch", 7L);
        setField(manager, "hostToken", "token-7");
        setField(manager, "hostSessionGeneration", 1L);
        setField(manager, "startupAttemptId", 7L);
        setField(manager, "publishedJoinTarget", "join.example");
        setField(manager, "phase", SharedWorldHostingManager.Phase.CONFIRMING_HOST);
        ((AtomicBoolean) getField(manager, "startupStarted")).set(true);

        Method onHeartbeatSucceeded = SharedWorldHostingManager.class.getDeclaredMethod(
                "onHeartbeatSucceeded",
                Class.forName("link.sharedworld.host.SharedWorldHostingManager$HostAttemptContext"),
                String.class
        );
        onHeartbeatSucceeded.setAccessible(true);
        onHeartbeatSucceeded.invoke(manager, hostAttemptContext(1L, 7L, "world-1", 7L, "token-7"), "join.example");

        assertNotNull(recoveryStore.record);
        assertEquals("world-1", recoveryStore.record.worldId());
        assertEquals(HOST_UUID, recoveryStore.record.hostUuid());
        assertEquals(7L, recoveryStore.record.runtimeEpoch());
    }

    @Test
    void successfulCoordinatedReleaseClearsRecoveryMarker() {
        InMemoryHostRecoveryStore recoveryStore = new InMemoryHostRecoveryStore();
        recoveryStore.record = new SharedWorldHostingManager.HostRecoveryRecord("world-1", "Handoff World", HOST_UUID, 7L, Instant.EPOCH.toString());
        SharedWorldHostingManager manager = manager(
                new ManagedWorldStore(this.tempDir.resolve("managed-clear-marker")),
                new RecordingSyncAccess(this.tempDir.resolve("prepared-world")),
                new RecordingWorldOpenController(),
                recoveryStore,
                worldId -> false
        );

        manager.clearHostedSessionAfterCoordinatedRelease();

        assertNull(recoveryStore.record);
    }

    @Test
    void beginHostingFailsClosedWhenManifestIsMissing() {
        SharedWorldHostingManager manager = manager(
                new ManagedWorldStore(this.tempDir.resolve("managed-fail-closed")),
                new RecordingSyncAccess(this.tempDir.resolve("prepared-world")),
                new RecordingWorldOpenController(),
                new InMemoryHostRecoveryStore(),
                worldId -> false
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
                apiClient(),
                new ManagedWorldStore(this.tempDir.resolve("managed-release")),
                null,
                new RecordingWorldOpenController(),
                new HostStartupProgressRelayController(
                        (worldId, runtimeEpoch, hostToken, progress) -> relayedProgress.set(progress),
                        Runnable::run,
                        () -> 0L
                ),
                new InMemoryHostRecoveryStore(),
                worldId -> false
        );

        setField(manager, "world", world("world-1", "Handoff World"));
        setField(manager, "hostPlayerUuid", HOST_UUID);
        setField(manager, "runtimeEpoch", 7L);
        setField(manager, "hostToken", "token-7");
        setField(manager, "hostSessionGeneration", 1L);
        setField(manager, "startupAttemptId", 7L);
        setField(manager, "phase", SharedWorldHostingManager.Phase.RUNNING);
        ((AtomicBoolean) getField(manager, "startupStarted")).set(true);

        manager.markCoordinatedBackendFinalizationStarted();

        assertEquals(SharedWorldHostingManager.Phase.RELEASING, manager.phase());
        assertEquals("indeterminate", relayedProgress.get().mode());
    }

    private SharedWorldHostingManager manager(
            ManagedWorldStore worldStore,
            RecordingSyncAccess syncAccess,
            RecordingWorldOpenController worldOpenController,
            InMemoryHostRecoveryStore recoveryStore,
            SharedWorldHostingManager.PendingReleaseRecoveryChecker pendingReleaseRecoveryChecker
    ) {
        return new SharedWorldHostingManager(
                apiClient(),
                worldStore,
                syncAccess,
                worldOpenController,
                new HostStartupProgressRelayController(
                        (worldId, runtimeEpoch, hostToken, progress) -> {
                        },
                        Runnable::run,
                        () -> 0L
                ),
                recoveryStore,
                pendingReleaseRecoveryChecker
        );
    }

    private SharedWorldApiClient apiClient() {
        return new SharedWorldApiClient(
                "http://127.0.0.1:1",
                () -> new SharedWorldApiClient.SessionIdentity("backend-player", "Tester", "dev:test")
        );
    }

    private static void primeStartup(SharedWorldHostingManager manager, SharedWorldModels.WorldSummaryDto world, long runtimeEpoch, SharedWorldHostingManager.StartupMode startupMode) throws Exception {
        setField(manager, "world", world);
        setField(manager, "latestManifest", latestManifest(world.id()));
        setField(manager, "hostPlayerUuid", HOST_UUID);
        setField(manager, "runtimeEpoch", runtimeEpoch);
        setField(manager, "hostToken", "token-" + runtimeEpoch);
        setField(manager, "startupAttemptId", runtimeEpoch);
        setField(manager, "startupMode", startupMode);
        ((AtomicBoolean) getField(manager, "startupStarted")).set(true);
    }

    private static void invokePrepareAndOpen(SharedWorldHostingManager manager, long startupAttemptId) throws Exception {
        Method prepareAndOpen = SharedWorldHostingManager.class.getDeclaredMethod("prepareAndOpen", long.class);
        prepareAndOpen.setAccessible(true);
        prepareAndOpen.invoke(manager, startupAttemptId);
    }

    private static Object hostAttemptContext(long generation, long startupAttemptId, String worldId, long runtimeEpoch, String hostToken) throws Exception {
        Class<?> contextClass = Class.forName("link.sharedworld.host.SharedWorldHostingManager$HostAttemptContext");
        var constructor = contextClass.getDeclaredConstructor(long.class, long.class, String.class, long.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(generation, startupAttemptId, worldId, runtimeEpoch, hostToken);
    }

    private static SharedWorldModels.WorldSummaryDto world(String worldId, String worldName) {
        return new SharedWorldModels.WorldSummaryDto(
                worldId,
                "world",
                worldName,
                "11111111-1111-1111-1111-111111111111",
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

    private static final class InMemoryHostRecoveryStore implements SharedWorldHostingManager.HostRecoveryPersistence {
        private SharedWorldHostingManager.HostRecoveryRecord record;

        @Override
        public SharedWorldHostingManager.HostRecoveryRecord load() {
            return this.record;
        }

        @Override
        public void save(SharedWorldHostingManager.HostRecoveryRecord record) {
            this.record = record;
        }

        @Override
        public void clear() {
            this.record = null;
        }
    }

    private static final class RecordingSyncAccess implements SharedWorldHostingManager.SyncAccess {
        private final Path preparedWorldDirectory;
        private int ensureCalls;
        private int uploadCalls;
        private boolean failUpload;
        private String worldId;
        private String hostPlayerUuid;
        private Path uploadedWorldDirectory;

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
        public SharedWorldModels.SnapshotManifestDto uploadSnapshot(String worldId, Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken, WorldSyncProgressListener progressListener) throws java.io.IOException {
            this.uploadCalls += 1;
            this.worldId = worldId;
            this.hostPlayerUuid = hostPlayerUuid;
            this.uploadedWorldDirectory = worldDirectory;
            if (this.failUpload) {
                throw new java.io.IOException("simulated upload failure");
            }
            return latestManifest(worldId);
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
