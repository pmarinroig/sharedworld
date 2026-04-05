package link.sharedworld.host;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.progress.SharedWorldProgressState;
import link.sharedworld.support.SharedWorldCoordinatorHarness;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncProgressListener;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
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
    private static final Gson GSON = new Gson();

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
                SharedWorldModels.WorldRuntimeStatusDto.class,
                String.class,
                boolean.class
        );
        onHeartbeatSucceeded.setAccessible(true);
        onHeartbeatSucceeded.invoke(
                manager,
                hostAttemptContext(1L, 7L, "world-1", 7L, "token-7"),
                runtimeStatus("world-1", "host-live", 7L, "join.example"),
                "join.example",
                false
        );

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

    @Test
    void coordinatedReleaseProgressIsRelayedAndCleared() throws Exception {
        List<String> relayed = new ArrayList<>();
        SharedWorldHostingManager manager = new SharedWorldHostingManager(
                apiClient(),
                new ManagedWorldStore(this.tempDir.resolve("managed-release-progress")),
                null,
                new RecordingWorldOpenController(),
                new HostStartupProgressRelayController(
                        (worldId, runtimeEpoch, hostToken, progress) -> relayed.add(progress == null ? "<clear>" : progress.label() + ":" + progress.mode() + ":" + progress.fraction()),
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
        setField(manager, "phase", SharedWorldHostingManager.Phase.RELEASING);
        setField(manager, "coordinatedReleaseActive", true);
        setField(manager, "coordinatedReleaseBackendFinalization", true);
        ((AtomicBoolean) getField(manager, "startupStarted")).set(true);

        manager.relayCoordinatedReleaseProgress(SharedWorldProgressState.determinate(
                Component.literal("Saving"),
                Component.literal("Uploading world"),
                "release_uploading",
                0.5D,
                null,
                50L,
                100L
        ));
        manager.clearCoordinatedReleaseProgress();

        assertEquals(List.of(
                "Uploading world:determinate:0.5",
                "<clear>"
        ), relayed);
    }

    @Test
    void savePhaseHeartbeatAcceptsHostFinalizingDuringCoordinatedRelease() throws Exception {
        LeaseRuntimeState leaseState = new LeaseRuntimeState("world-1", 7L, "token-7", 90_000L);
        try (HeartbeatTestServer server = new HeartbeatTestServer(leaseState)) {
            ManualExecutor background = new ManualExecutor();
            ManualExecutor mainThread = new ManualExecutor();
            SharedWorldHostingManager manager = manager(
                    apiClient(server.baseUrl()),
                    new ManagedWorldStore(this.tempDir.resolve("managed-finalizing-heartbeat")),
                    new RecordingSyncAccess(this.tempDir.resolve("prepared-world")),
                    new RecordingWorldOpenController(),
                    new InMemoryHostRecoveryStore(),
                    worldId -> false,
                    background,
                    mainThread
            );

            primeStartup(manager, world("world-1", "Handoff World"), 7L, SharedWorldHostingManager.StartupMode.NORMAL);
            setField(manager, "hostSessionGeneration", 1L);
            setField(manager, "publishedJoinTarget", "join.example");
            setField(manager, "phase", SharedWorldHostingManager.Phase.SAVING);
            long staleHeartbeatAt = System.currentTimeMillis() - 31_000L;
            setField(manager, "lastHeartbeatAt", staleHeartbeatAt);
            setField(manager, "lastHeartbeatAttemptAt", 0L);
            ((AtomicBoolean) getField(manager, "saveInFlight")).set(true);

            manager.beginCoordinatedRelease();
            manager.tick(null);
            assertEquals(1, background.size());

            leaseState.beginFinalization("world-1", 7L, "token-7");
            background.runNext();
            mainThread.runAll();

            assertEquals("host-finalizing", leaseState.phase());
            assertEquals(0, leaseState.heartbeatCount());
            assertEquals(staleHeartbeatAt, getField(manager, "lastHeartbeatAt"));
            assertEquals(SharedWorldHostingManager.Phase.SAVING, manager.phase());
            assertNull(manager.errorMessage());
        }
    }

    @Test
    void savingPhaseContinuesLiveHeartbeats() throws Exception {
        LeaseRuntimeState leaseState = new LeaseRuntimeState("world-1", 7L, "token-7", 90_000L);
        try (HeartbeatTestServer server = new HeartbeatTestServer(leaseState)) {
            SharedWorldHostingManager manager = manager(
                    apiClient(server.baseUrl()),
                    new ManagedWorldStore(this.tempDir.resolve("managed-saving-heartbeat")),
                    new RecordingSyncAccess(this.tempDir.resolve("prepared-world")),
                    new RecordingWorldOpenController(),
                    new InMemoryHostRecoveryStore(),
                    worldId -> false
            );

            primeStartup(manager, world("world-1", "Handoff World"), 7L, SharedWorldHostingManager.StartupMode.NORMAL);
            setField(manager, "hostSessionGeneration", 1L);
            setField(manager, "publishedJoinTarget", "join.example");
            setField(manager, "phase", SharedWorldHostingManager.Phase.SAVING);
            setField(manager, "lastHeartbeatAt", System.currentTimeMillis() - 31_000L);
            setField(manager, "lastHeartbeatAttemptAt", 0L);
            ((AtomicBoolean) getField(manager, "saveInFlight")).set(true);

            leaseState.advance(30_001L);
            manager.tick(null);

            assertEquals(1, leaseState.heartbeatCount());
            assertEquals("join.example", leaseState.lastJoinTarget());
            assertEquals("host-live", leaseState.phase());
        }
    }

    @Test
    void staleSavePhaseHeartbeatDoesNotAbortGracefulReleaseAfterBackendFinalizationStarts() throws Exception {
        LeaseRuntimeState leaseState = new LeaseRuntimeState("world-1", 7L, "token-7", 90_000L);
        try (HeartbeatTestServer server = new HeartbeatTestServer(leaseState)) {
            ManualExecutor background = new ManualExecutor();
            ManualExecutor mainThread = new ManualExecutor();
            ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-late-release-heartbeat"));
            SharedWorldHostingManager manager = manager(
                    apiClient(server.baseUrl()),
                    worldStore,
                    new RecordingSyncAccess(this.tempDir.resolve("prepared-world")),
                    new RecordingWorldOpenController(),
                    new InMemoryHostRecoveryStore(),
                    worldId -> false,
                    background,
                    mainThread
            );

            primeStartup(manager, world("world-1", "Handoff World"), 7L, SharedWorldHostingManager.StartupMode.NORMAL);
            setField(manager, "hostSessionGeneration", 1L);
            setField(manager, "publishedJoinTarget", "join.example");
            setField(manager, "phase", SharedWorldHostingManager.Phase.SAVING);
            setField(manager, "lastHeartbeatAt", System.currentTimeMillis() - 31_000L);
            setField(manager, "lastHeartbeatAttemptAt", 0L);
            ((AtomicBoolean) getField(manager, "saveInFlight")).set(true);

            manager.tick(null);
            assertEquals(1, background.size());

            SharedWorldCoordinatorHarness.DeterministicAsync async = new SharedWorldCoordinatorHarness.DeterministicAsync();
            SharedWorldCoordinatorHarness.FakeClock clock = new SharedWorldCoordinatorHarness.FakeClock(1_900_000_000_000L);
            SharedWorldCoordinatorHarness.FakeClientShell clientShell = new SharedWorldCoordinatorHarness.FakeClientShell();
            clientShell.setLocalServerState(true, true, true);
            SharedWorldReleaseCoordinator coordinator = new SharedWorldReleaseCoordinator(
                    new LeaseAwareReleaseBackend(leaseState),
                    hostControl(manager),
                    new SharedWorldCoordinatorHarness.InMemoryReleaseStore(),
                    async,
                    clock,
                    clientShell,
                    new SharedWorldCoordinatorHarness.FakePlayerIdentity("player-host"),
                    worldName -> null
            );

            assertNotNull(coordinator.beginGracefulDisconnect(null));
            coordinator.tick(null);
            async.runNextBackground();
            async.flushMainThread();
            coordinator.tick(null);

            assertEquals("host-finalizing", leaseState.phase());
            background.runNext();
            mainThread.runAll();

            assertFalse(manager.hasError());
            assertEquals(SharedWorldHostingManager.Phase.RELEASING, manager.phase());
            assertEquals(0, leaseState.heartbeatCount());
            assertNotNull(coordinator.view());
            assertEquals(SharedWorldReleasePhase.WAITING_FOR_VANILLA_DISCONNECT, coordinator.view().phase());
            assertNull(coordinator.view().errorMessage());
        }
    }

    @Test
    void gracefulQuitDuringSlowAutosaveBeginsFinalizationBeforeAuthorityExpires() throws Exception {
        LeaseRuntimeState leaseState = new LeaseRuntimeState("world-1", 7L, "token-7", 90_000L);
        try (HeartbeatTestServer server = new HeartbeatTestServer(leaseState)) {
            ManagedWorldStore worldStore = new ManagedWorldStore(this.tempDir.resolve("managed-release-heartbeat"));
            SharedWorldHostingManager manager = manager(
                    apiClient(server.baseUrl()),
                    worldStore,
                    new RecordingSyncAccess(this.tempDir.resolve("prepared-world")),
                    new RecordingWorldOpenController(),
                    new InMemoryHostRecoveryStore(),
                    worldId -> false
            );

            primeStartup(manager, world("world-1", "Handoff World"), 7L, SharedWorldHostingManager.StartupMode.NORMAL);
            setField(manager, "hostSessionGeneration", 1L);
            setField(manager, "publishedJoinTarget", "join.example");
            setField(manager, "phase", SharedWorldHostingManager.Phase.SAVING);
            ((AtomicBoolean) getField(manager, "saveInFlight")).set(true);

            long staleHeartbeatAt = System.currentTimeMillis() - 31_000L;
            for (int attempt = 0; attempt < 4; attempt++) {
                leaseState.advance(30_001L);
                setField(manager, "lastHeartbeatAt", staleHeartbeatAt);
                setField(manager, "lastHeartbeatAttemptAt", 0L);
                manager.tick(null);
            }

            assertTrue(leaseState.exceededOriginalLeaseWindow());
            assertEquals(4, leaseState.heartbeatCount());

            SharedWorldCoordinatorHarness.DeterministicAsync async = new SharedWorldCoordinatorHarness.DeterministicAsync();
            SharedWorldCoordinatorHarness.FakeClock clock = new SharedWorldCoordinatorHarness.FakeClock(1_900_000_000_000L);
            SharedWorldCoordinatorHarness.FakeClientShell clientShell = new SharedWorldCoordinatorHarness.FakeClientShell();
            clientShell.setLocalServerState(true, true, true);
            SharedWorldReleaseCoordinator coordinator = new SharedWorldReleaseCoordinator(
                    new LeaseAwareReleaseBackend(leaseState),
                    hostControl(manager),
                    new SharedWorldCoordinatorHarness.InMemoryReleaseStore(),
                    async,
                    clock,
                    clientShell,
                    new SharedWorldCoordinatorHarness.FakePlayerIdentity("player-host"),
                    worldName -> null
            );

            assertNotNull(coordinator.beginGracefulDisconnect(null));

            coordinator.tick(null);
            async.runNextBackground();
            async.flushMainThread();
            coordinator.tick(null);

            assertNotNull(coordinator.view());
            assertEquals("host-finalizing", leaseState.phase());
            assertEquals(SharedWorldReleasePhase.WAITING_FOR_VANILLA_DISCONNECT, coordinator.view().phase());
            assertNull(coordinator.view().errorMessage());
        }
    }

    private SharedWorldHostingManager manager(
            SharedWorldApiClient apiClient,
            ManagedWorldStore worldStore,
            RecordingSyncAccess syncAccess,
            RecordingWorldOpenController worldOpenController,
            InMemoryHostRecoveryStore recoveryStore,
            SharedWorldHostingManager.PendingReleaseRecoveryChecker pendingReleaseRecoveryChecker
    ) {
        return manager(
                apiClient,
                worldStore,
                syncAccess,
                worldOpenController,
                recoveryStore,
                pendingReleaseRecoveryChecker,
                Runnable::run,
                Runnable::run
        );
    }

    private SharedWorldHostingManager manager(
            SharedWorldApiClient apiClient,
            ManagedWorldStore worldStore,
            RecordingSyncAccess syncAccess,
            RecordingWorldOpenController worldOpenController,
            InMemoryHostRecoveryStore recoveryStore,
            SharedWorldHostingManager.PendingReleaseRecoveryChecker pendingReleaseRecoveryChecker,
            Executor backgroundExecutor,
            Executor mainThreadExecutor
    ) {
        return new SharedWorldHostingManager(
                apiClient,
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
                pendingReleaseRecoveryChecker,
                backgroundExecutor,
                mainThreadExecutor
        );
    }

    private SharedWorldHostingManager manager(
            ManagedWorldStore worldStore,
            RecordingSyncAccess syncAccess,
            RecordingWorldOpenController worldOpenController,
            InMemoryHostRecoveryStore recoveryStore,
            SharedWorldHostingManager.PendingReleaseRecoveryChecker pendingReleaseRecoveryChecker
    ) {
        return manager(apiClient(), worldStore, syncAccess, worldOpenController, recoveryStore, pendingReleaseRecoveryChecker);
    }

    private SharedWorldApiClient apiClient() {
        return apiClient("http://127.0.0.1:1");
    }

    private SharedWorldApiClient apiClient(String baseUrl) {
        return new SharedWorldApiClient(
                baseUrl,
                HttpClient.newHttpClient(),
                () -> new SharedWorldApiClient.SessionIdentity("backend-player", "Tester", "dev:test")
        );
    }

    private static SharedWorldReleaseCoordinator.HostControl hostControl(SharedWorldHostingManager manager) {
        return new SharedWorldReleaseCoordinator.HostControl() {
            @Override
            public SharedWorldHostingManager.ActiveHostSession activeHostSession() {
                return manager.activeHostSession();
            }

            @Override
            public boolean isBackgroundSaveInFlight() {
                return manager.isBackgroundSaveInFlight();
            }

            @Override
            public void beginCoordinatedRelease() {
                manager.beginCoordinatedRelease();
            }

            @Override
            public void markCoordinatedBackendFinalizationStarted() {
                manager.markCoordinatedBackendFinalizationStarted();
            }

            @Override
            public Path finalReleaseWorldDirectory(String worldId) {
                return manager.finalReleaseWorldDirectory(worldId);
            }

            @Override
            public SharedWorldModels.SnapshotManifestDto uploadFinalReleaseSnapshot(
                    String worldId,
                    Path worldDirectory,
                    String hostPlayerUuid,
                    long runtimeEpoch,
                    String hostToken,
                    WorldSyncProgressListener progressListener
            ) {
                return latestManifest(worldId);
            }

            @Override
            public void clearHostedSessionAfterCoordinatedRelease() {
                manager.clearHostedSessionAfterCoordinatedRelease();
            }

            @Override
            public void relayCoordinatedReleaseProgress(SharedWorldProgressState progressState) {
                manager.relayCoordinatedReleaseProgress(progressState);
            }

            @Override
            public void clearCoordinatedReleaseProgress() {
                manager.clearCoordinatedReleaseProgress();
            }

            @Override
            public void clearHostedSessionAfterTerminalExit() {
                manager.clearHostedSessionAfterTerminalExit();
            }

            @Override
            public boolean isStartupCancelable() {
                return manager.isStartupCancelable();
            }
        };
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

    private static SharedWorldModels.WorldRuntimeStatusDto runtimeStatus(String worldId, String phase, long runtimeEpoch, String joinTarget) {
        return new SharedWorldModels.WorldRuntimeStatusDto(
                worldId,
                phase,
                runtimeEpoch,
                "player-host",
                "Host",
                null,
                null,
                joinTarget,
                null,
                null,
                null,
                null,
                null,
                null
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

    private static final class LeaseAwareReleaseBackend implements SharedWorldReleaseCoordinator.ReleaseBackend {
        private final LeaseRuntimeState leaseState;

        private LeaseAwareReleaseBackend(LeaseRuntimeState leaseState) {
            this.leaseState = leaseState;
        }

        @Override
        public SharedWorldModels.WorldRuntimeStatusDto runtimeStatus(String worldId) {
            return this.leaseState.runtimeStatus(worldId);
        }

        @Override
        public void beginFinalization(String worldId, long runtimeEpoch, String hostToken) throws Exception {
            this.leaseState.beginFinalization(worldId, runtimeEpoch, hostToken);
        }

        @Override
        public void completeFinalization(String worldId, long runtimeEpoch, String hostToken) {
            this.leaseState.completeFinalization(worldId, runtimeEpoch, hostToken);
        }

        @Override
        public void releaseHost(String worldId, long runtimeEpoch, String hostToken, boolean graceful) {
            this.leaseState.releaseHost(worldId, runtimeEpoch, hostToken);
        }
    }

    private static final class LeaseRuntimeState {
        private final String worldId;
        private final long runtimeEpoch;
        private final String hostToken;
        private final long leaseTimeoutMs;
        private long nowMs;
        private long lastActivityAtMs;
        private String phase = "host-live";
        private String joinTarget = "join.example";
        private int heartbeatCount;

        private LeaseRuntimeState(String worldId, long runtimeEpoch, String hostToken, long leaseTimeoutMs) {
            this.worldId = worldId;
            this.runtimeEpoch = runtimeEpoch;
            this.hostToken = hostToken;
            this.leaseTimeoutMs = leaseTimeoutMs;
        }

        synchronized void advance(long millis) {
            this.nowMs += millis;
        }

        synchronized int heartbeatCount() {
            return this.heartbeatCount;
        }

        synchronized String lastJoinTarget() {
            return this.joinTarget;
        }

        synchronized String phase() {
            return this.phase;
        }

        synchronized boolean exceededOriginalLeaseWindow() {
            return this.nowMs > this.leaseTimeoutMs;
        }

        synchronized SharedWorldModels.WorldRuntimeStatusDto recordHeartbeat(String worldId, long runtimeEpoch, String hostToken, String joinTarget) throws SharedWorldApiClient.SharedWorldApiException {
            verifyWorldAndAuthority(worldId, runtimeEpoch, hostToken);
            if (leaseExpired()) {
                throw hostNotActive();
            }
            if ("host-finalizing".equals(this.phase)) {
                return runtimeStatus(worldId);
            }
            if (!"host-live".equals(this.phase) && !"host-starting".equals(this.phase)) {
                throw hostNotActive();
            }
            this.lastActivityAtMs = this.nowMs;
            this.heartbeatCount += 1;
            if (joinTarget != null && !joinTarget.isBlank()) {
                this.joinTarget = joinTarget;
                this.phase = "host-live";
            }
            return runtimeStatus(worldId);
        }

        synchronized void beginFinalization(String worldId, long runtimeEpoch, String hostToken) throws SharedWorldApiClient.SharedWorldApiException {
            verifyWorldAndAuthority(worldId, runtimeEpoch, hostToken);
            if ((!"host-live".equals(this.phase) && !"host-starting".equals(this.phase)) || leaseExpired()) {
                throw hostNotActive();
            }
            this.phase = "host-finalizing";
            this.lastActivityAtMs = this.nowMs;
        }

        synchronized void completeFinalization(String worldId, long runtimeEpoch, String hostToken) {
            verifyWorldAndAuthority(worldId, runtimeEpoch, hostToken);
            this.phase = "idle";
        }

        synchronized void releaseHost(String worldId, long runtimeEpoch, String hostToken) {
            verifyWorldAndAuthority(worldId, runtimeEpoch, hostToken);
            this.phase = "idle";
        }

        synchronized SharedWorldModels.WorldRuntimeStatusDto runtimeStatus(String worldId) {
            if (!this.worldId.equals(worldId)) {
                return null;
            }
            return switch (this.phase) {
                case "host-starting" -> new SharedWorldModels.WorldRuntimeStatusDto(
                        worldId,
                        "host-starting",
                        this.runtimeEpoch,
                        "player-host",
                        "Host",
                        "player-host",
                        "Host",
                        null,
                        null,
                        null,
                        null,
                        null
                );
                case "host-finalizing" -> new SharedWorldModels.WorldRuntimeStatusDto(
                        worldId,
                        "host-finalizing",
                        this.runtimeEpoch,
                        "player-host",
                        "Host",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );
                case "host-live" -> new SharedWorldModels.WorldRuntimeStatusDto(
                        worldId,
                        "host-live",
                        this.runtimeEpoch,
                        "player-host",
                        "Host",
                        null,
                        null,
                        leaseExpired() ? null : this.joinTarget,
                        null,
                        null,
                        null,
                        null
                );
                default -> new SharedWorldModels.WorldRuntimeStatusDto(
                        worldId,
                        "idle",
                        this.runtimeEpoch + 1L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );
            };
        }

        private void verifyWorldAndAuthority(String worldId, long runtimeEpoch, String hostToken) {
            if (!this.worldId.equals(worldId)
                    || this.runtimeEpoch != runtimeEpoch
                    || !this.hostToken.equals(hostToken)) {
                throw new IllegalArgumentException("unexpected host authority");
            }
        }

        private boolean leaseExpired() {
            return this.nowMs - this.lastActivityAtMs >= this.leaseTimeoutMs;
        }

        private SharedWorldApiClient.SharedWorldApiException hostNotActive() {
            return new SharedWorldApiClient.SharedWorldApiException("host_not_active", "SharedWorld host lease is no longer active for snapshot upload.", 409);
        }
    }

    private static final class HeartbeatTestServer implements AutoCloseable {
        private final HttpServer server;
        private final LeaseRuntimeState leaseState;

        private HeartbeatTestServer(LeaseRuntimeState leaseState) throws IOException {
            this.leaseState = leaseState;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", this::handle);
            this.server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + this.server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())
                        && "/auth/dev-complete".equals(exchange.getRequestURI().getPath())) {
                    writeJson(exchange, 200, Map.of(
                            "token", "session-dev",
                            "playerUuid", HOST_UUID.replace("-", ""),
                            "playerName", "Tester",
                            "expiresAt", "2099-01-01T00:00:00.000Z",
                            "allowInsecureE4mc", true
                    ));
                    return;
                }
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())
                        && exchange.getRequestURI().getPath().equals("/worlds/" + this.leaseState.worldId + "/heartbeat")) {
                    handleHeartbeat(exchange);
                    return;
                }
                writeJson(exchange, 404, Map.of("error", "not_found", "message", "No test route matched.", "status", 404));
            } finally {
                exchange.close();
            }
        }

        private void handleHeartbeat(HttpExchange exchange) throws IOException {
            Map<String, Object> body = readJson(exchange);
            try {
                SharedWorldModels.WorldRuntimeStatusDto runtime = this.leaseState.recordHeartbeat(
                        this.leaseState.worldId,
                        ((Number) body.get("runtimeEpoch")).longValue(),
                        (String) body.get("hostToken"),
                        (String) body.get("joinTarget")
                );
                writeJson(exchange, 200, runtime);
            } catch (SharedWorldApiClient.SharedWorldApiException exception) {
                writeJson(exchange, exception.status(), Map.of(
                        "error", exception.error(),
                        "message", exception.getMessage(),
                        "status", exception.status()
                ));
            }
        }

        private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
            try (InputStream stream = exchange.getRequestBody()) {
                String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                Map<?, ?> parsed = GSON.fromJson(body, Map.class);
                Map<String, Object> normalized = new LinkedHashMap<>();
                if (parsed != null) {
                    for (Map.Entry<?, ?> entry : parsed.entrySet()) {
                        normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return normalized;
            }
        }

        private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            this.server.stop(0);
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

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            this.tasks.add(command);
        }

        private int size() {
            return this.tasks.size();
        }

        private void runNext() {
            this.tasks.remove().run();
        }

        private void runAll() {
            while (!this.tasks.isEmpty()) {
                runNext();
            }
        }
    }
}
