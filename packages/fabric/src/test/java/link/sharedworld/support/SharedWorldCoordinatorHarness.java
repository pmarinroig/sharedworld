package link.sharedworld.support;

import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.api.SharedWorldModels.EnterSessionResponseDto;
import link.sharedworld.api.SharedWorldModels.ManifestFileDto;
import link.sharedworld.api.SharedWorldModels.ObserveWaitingResponseDto;
import link.sharedworld.api.SharedWorldModels.PackedManifestFileDto;
import link.sharedworld.api.SharedWorldModels.SnapshotManifestDto;
import link.sharedworld.api.SharedWorldModels.SnapshotPackDto;
import link.sharedworld.api.SharedWorldModels.StartupProgressDto;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.host.SharedWorldHostingManager;
import link.sharedworld.host.SharedWorldReleaseCoordinator;
import link.sharedworld.host.SharedWorldReleasePhase;
import link.sharedworld.host.SharedWorldReleaseStore;
import link.sharedworld.progress.SharedWorldProgressState;
import link.sharedworld.SharedWorldCoordinatorSupport;
import link.sharedworld.SharedWorldRecoveryStore;
import link.sharedworld.SharedWorldSessionCoordinator;
import link.sharedworld.host.SharedWorldHostingManager;
import link.sharedworld.sync.WorldSyncCoordinator;
import link.sharedworld.sync.WorldSyncProgressListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

public final class SharedWorldCoordinatorHarness {
    public final FakeClock clock;
    public final DeterministicAsync async;
    public final FakeClientShell clientShell;
    public final FakePlayerIdentity playerIdentity;
    public final InMemoryRecoveryStore recoveryStore;
    public final InMemoryReleaseStore releaseStore;
    public final FakeSessionBackend sessionBackend;
    public final FakeReleaseBackend releaseBackend;
    public final TempdirSnapshotDriver snapshotDriver;
    public final FakeHostControl hostControl;
    public SharedWorldHostingManager.StartupMode lastHostStartupMode = SharedWorldHostingManager.StartupMode.NORMAL;
    public final SharedWorldSessionCoordinator sessionCoordinator;
    public final SharedWorldReleaseCoordinator releaseCoordinator;

    public SharedWorldCoordinatorHarness() throws IOException {
        this(
                new FakeClock(1_700_000_000_000L),
                new DeterministicAsync(),
                new FakePlayerIdentity("player-host"),
                new InMemoryRecoveryStore(),
                new InMemoryReleaseStore(),
                new FakeSessionBackend(),
                new FakeReleaseBackend(),
                new TempdirSnapshotDriver(Files.createTempDirectory("sharedworld-harness")),
                new FakeClientShell()
        );
    }

    private SharedWorldCoordinatorHarness(
            FakeClock clock,
            DeterministicAsync async,
            FakePlayerIdentity playerIdentity,
            InMemoryRecoveryStore recoveryStore,
            InMemoryReleaseStore releaseStore,
            FakeSessionBackend sessionBackend,
            FakeReleaseBackend releaseBackend,
            TempdirSnapshotDriver snapshotDriver,
            FakeClientShell clientShell
    ) {
        this.clock = clock;
        this.async = async;
        this.playerIdentity = playerIdentity;
        this.recoveryStore = recoveryStore;
        this.releaseStore = releaseStore;
        this.sessionBackend = sessionBackend;
        this.releaseBackend = releaseBackend;
        this.snapshotDriver = snapshotDriver;
        this.clientShell = clientShell;
        this.hostControl = new FakeHostControl(snapshotDriver);
        this.sessionCoordinator = new SharedWorldSessionCoordinator(
                sessionBackend,
                recoveryStore,
                async,
                clock,
                clientShell,
                playerIdentity,
                (parent, result, startupMode) -> lastHostStartupMode = startupMode,
                new SharedWorldSessionCoordinator.SessionUi() {
                    @Override
                    public Screen joinError(Screen parent, Throwable error) {
                        clientShell.markNextScreen("join-error");
                        return null;
                    }

                    @Override
                    public Screen hostAcquired(Screen parent, EnterSessionResponseDto result) {
                        clientShell.markNextScreen("host-acquired");
                        return null;
                    }

                    @Override
                    public Screen waiting(Screen parent, String worldId, String worldName, String ownerUuid) {
                        clientShell.markNextScreen("waiting");
                        return null;
                    }

                    @Override
                    public Screen deleted(Screen parent) {
                        clientShell.markNextScreen("deleted");
                        return null;
                    }

                    @Override
                    public Screen uncleanShutdownWarning(Screen parent, String worldId, String worldName, WorldRuntimeStatusDto runtimeStatus) {
                        clientShell.markNextScreen("unclean-shutdown-warning");
                        return null;
                    }
                }
        );
        this.releaseCoordinator = new SharedWorldReleaseCoordinator(
                releaseBackend,
                hostControl,
                releaseStore,
                async,
                clock,
                clientShell,
                playerIdentity,
                worldName -> {
                    clientShell.markNextScreen("saving");
                    return null;
                }
        );
    }

    public SharedWorldCoordinatorHarness restart() {
        return new SharedWorldCoordinatorHarness(
                this.clock,
                new DeterministicAsync(),
                this.playerIdentity,
                this.recoveryStore,
                this.releaseStore,
                this.sessionBackend,
                this.releaseBackend,
                this.snapshotDriver,
                new FakeClientShell()
        );
    }

    public Screen parentScreen() {
        return null;
    }

    public void tickSession() {
        this.sessionCoordinator.tick(null);
    }

    public void tickRelease() {
        this.releaseCoordinator.tick(null);
    }

    public void advanceTime(long millis) {
        this.clock.advance(millis);
    }

    public void runNextAsync() {
        this.async.runNextBackground();
    }

    public void runLatestAsync() {
        this.async.runLatestBackground();
    }

    public void flushMainThread() {
        this.async.flushMainThread();
        this.clientShell.flushRenderThreadTasks();
    }

    public void runUntilIdle() {
        this.async.runUntilIdle();
    }

    public boolean hasPendingWork() {
        return this.async.hasPendingWork();
    }

    public void close() throws IOException {
        this.snapshotDriver.close();
    }

    public static WorldSummaryDto world(String id, String name, String ownerUuid) {
        return new WorldSummaryDto(id, id, name, ownerUuid, "", null, null, 1, "idle", null, null, null, null, null, 0, new String[0], "google-drive", true, null);
    }

    public static WorldRuntimeStatusDto runtime(String worldId, String phase, long epoch, String candidateUuid, String joinTarget) {
        return new WorldRuntimeStatusDto(worldId, phase, epoch, "player-host", "Host", candidateUuid, candidateUuid == null ? null : "Candidate", joinTarget, null, null, null, null);
    }

    public static EnterSessionResponseDto connectResponse(WorldSummaryDto world, long runtimeEpoch, String joinTarget) {
        return new EnterSessionResponseDto(
                "connect",
                world,
                null,
                new WorldRuntimeStatusDto(world.id(), "host-live", runtimeEpoch, "player-host", "Host", null, null, joinTarget, null, null, null, null),
                null,
                null
        );
    }

    public static EnterSessionResponseDto waitResponse(WorldSummaryDto world) {
        return new EnterSessionResponseDto("wait", world, null, null, null, "wait-test");
    }

    public static ObserveWaitingResponseDto observeWait(String worldId, String waiterSessionId, WorldRuntimeStatusDto runtime) {
        return new ObserveWaitingResponseDto("wait", runtime, null, waiterSessionId);
    }

    public static ObserveWaitingResponseDto observeConnect(String worldId, long runtimeEpoch, String joinTarget) {
        return new ObserveWaitingResponseDto(
                "connect",
                new WorldRuntimeStatusDto(worldId, "host-live", runtimeEpoch, "player-host", "Host", null, null, joinTarget, null, null, null, null),
                null,
                null
        );
    }

    public static ObserveWaitingResponseDto observeRestart(String worldId, WorldRuntimeStatusDto runtime) {
        return new ObserveWaitingResponseDto("restart", runtime, null, null);
    }

    public static EnterSessionResponseDto hostResponse(WorldSummaryDto world, long epoch, String token) {
        return new EnterSessionResponseDto(
                "host",
                world,
                null,
                null,
                new SharedWorldModels.HostAssignmentDto(world.id(), "player-host", "Host", epoch, token, Instant.EPOCH.toString()),
                null
        );
    }

    public static final class DeterministicAsync implements SharedWorldCoordinatorSupport.AsyncBridge {
        private final Deque<Runnable> background = new ArrayDeque<>();
        private final Deque<Runnable> mainThread = new ArrayDeque<>();

        @Override
        public <T> void supply(SharedWorldCoordinatorSupport.ThrowingSupplier<T> supplier, java.util.function.BiConsumer<T, Throwable> completion) {
            this.background.add(() -> {
                T result = null;
                Throwable error = null;
                try {
                    result = supplier.get();
                } catch (Throwable throwable) {
                    error = throwable;
                }
                T completionResult = result;
                Throwable completionError = error;
                this.mainThread.add(() -> completion.accept(completionResult, completionError));
            });
        }

        @Override
        public void run(SharedWorldCoordinatorSupport.ThrowingRunnable runnable, java.util.function.Consumer<Throwable> completion) {
            this.background.add(() -> {
                Throwable error = null;
                try {
                    runnable.run();
                } catch (Throwable throwable) {
                    error = throwable;
                }
                Throwable completionError = error;
                this.mainThread.add(() -> completion.accept(completionError));
            });
        }

        public void runNextBackground() {
            Runnable runnable = this.background.pollFirst();
            if (runnable != null) {
                runnable.run();
            }
        }

        public void runLatestBackground() {
            Runnable runnable = this.background.pollLast();
            if (runnable != null) {
                runnable.run();
            }
        }

        public void flushMainThread() {
            while (!this.mainThread.isEmpty()) {
                this.mainThread.pollFirst().run();
            }
        }

        public void runUntilIdle() {
            while (!this.background.isEmpty() || !this.mainThread.isEmpty()) {
                runNextBackground();
                flushMainThread();
            }
        }

        public boolean hasPendingWork() {
            return !this.background.isEmpty() || !this.mainThread.isEmpty();
        }
    }

    public static final class FakeClock implements SharedWorldCoordinatorSupport.Clock {
        private long nowMillis;

        public FakeClock(long nowMillis) {
            this.nowMillis = nowMillis;
        }

        @Override
        public long nowMillis() {
            return this.nowMillis;
        }

        public void advance(long millis) {
            this.nowMillis += millis;
        }
    }

    public static final class FakePlayerIdentity implements SharedWorldCoordinatorSupport.PlayerIdentity {
        private final String currentPlayerUuid;

        public FakePlayerIdentity(String currentPlayerUuid) {
            this.currentPlayerUuid = currentPlayerUuid;
        }

        @Override
        public String currentPlayerUuid() {
            return this.currentPlayerUuid;
        }
    }

    public static final class FakeClientShell implements SharedWorldCoordinatorSupport.ClientShell {
        private boolean hasSingleplayerServer = true;
        private boolean hasLevel = true;
        private boolean localServer = true;
        private boolean renderThread = true;
        private Screen currentScreen;
        private final List<String> actions = new ArrayList<>();
        private final Deque<Runnable> renderThreadTasks = new ArrayDeque<>();
        private int disconnectCalls;
        private String pendingScreenTag;
        private Throwable nextConnectFailure;

        @Override
        public boolean hasSingleplayerServer() {
            return this.hasSingleplayerServer;
        }

        @Override
        public boolean hasLevel() {
            return this.hasLevel;
        }

        @Override
        public boolean isLocalServer() {
            return this.localServer;
        }

        @Override
        public Screen currentScreen() {
            return this.currentScreen;
        }

        @Override
        public void setScreen(Screen screen) {
            if (!this.renderThread) {
                this.renderThreadTasks.addLast(() -> setScreen(screen));
                return;
            }
            this.currentScreen = screen;
            String tag = this.pendingScreenTag != null ? this.pendingScreenTag : (screen == null ? "null" : screen.getClass().getSimpleName());
            this.pendingScreenTag = null;
            this.actions.add("setScreen:" + tag);
        }

        @Override
        public void disconnectFromWorld() {
            this.disconnectCalls += 1;
            this.actions.add("disconnect");
            this.hasSingleplayerServer = false;
            this.hasLevel = false;
        }

        @Override
        public void openMainScreen(Screen parent) {
            if (!this.renderThread) {
                this.renderThreadTasks.addLast(() -> openMainScreen(parent));
                return;
            }
            this.currentScreen = null;
            this.actions.add("openMain");
        }

        @Override
        public void openMembershipRevokedScreen(Screen parent) {
            if (!this.renderThread) {
                this.renderThreadTasks.addLast(() -> openMembershipRevokedScreen(parent));
                return;
            }
            this.currentScreen = null;
            this.actions.add("openRevoked");
        }

        @Override
        public void connect(Screen parent, String joinTarget, String worldId, String worldName, Consumer<Throwable> failureHandler) {
            if (!this.renderThread) {
                Throwable failure = this.nextConnectFailure;
                this.nextConnectFailure = null;
                this.renderThreadTasks.addLast(() -> completeConnect(parent, joinTarget, failureHandler, failure));
                return;
            }
            Throwable failure = this.nextConnectFailure;
            this.nextConnectFailure = null;
            completeConnect(parent, joinTarget, failureHandler, failure);
        }

        private void completeConnect(Screen parent, String joinTarget, Consumer<Throwable> failureHandler, Throwable failure) {
            if (failure != null) {
                this.actions.add("connectFailed:" + joinTarget);
                failureHandler.accept(failure);
                return;
            }
            this.currentScreen = null;
            this.actions.add("connect:" + joinTarget);
        }

        @Override
        public void clearPlaySession() {
            this.actions.add("clearPlaySession");
        }

        public void setLocalServerState(boolean hasSingleplayerServer, boolean hasLevel, boolean localServer) {
            this.hasSingleplayerServer = hasSingleplayerServer;
            this.hasLevel = hasLevel;
            this.localServer = localServer;
        }

        public void setRenderThread(boolean renderThread) {
            this.renderThread = renderThread;
        }

        public void flushRenderThreadTasks() {
            boolean wasRenderThread = this.renderThread;
            this.renderThread = true;
            while (!this.renderThreadTasks.isEmpty()) {
                this.renderThreadTasks.removeFirst().run();
            }
            this.renderThread = wasRenderThread;
        }

        public int disconnectCalls() {
            return this.disconnectCalls;
        }

        public List<String> actions() {
            return this.actions;
        }

        public void markNextScreen(String pendingScreenTag) {
            this.pendingScreenTag = pendingScreenTag;
        }

        public void failNextConnect(Throwable failure) {
            this.nextConnectFailure = failure;
        }
    }

    public static final class InMemoryRecoveryStore implements SharedWorldSessionCoordinator.RecoveryPersistence {
        private SharedWorldRecoveryStore.RecoveryRecord record;

        @Override
        public SharedWorldRecoveryStore.RecoveryRecord load() {
            return this.record;
        }

        @Override
        public void save(SharedWorldRecoveryStore.RecoveryRecord record) {
            this.record = record;
        }

        @Override
        public void clear() {
            this.record = null;
        }
    }

    public static final class InMemoryReleaseStore implements SharedWorldReleaseCoordinator.ReleasePersistence {
        private SharedWorldReleaseStore.ReleaseRecord record;

        @Override
        public SharedWorldReleaseStore.ReleaseRecord load() {
            return this.record == null ? null : this.record.copy();
        }

        @Override
        public SharedWorldReleaseStore.ReleaseRecord loadFor(String worldId, String hostUuid) {
            if (this.record == null) {
                return null;
            }
            if (!worldId.equalsIgnoreCase(this.record.worldId) || !hostUuid.equalsIgnoreCase(this.record.hostUuid)) {
                return null;
            }
            return this.record.copy();
        }

        @Override
        public void save(SharedWorldReleaseStore.ReleaseRecord record) {
            this.record = record.copy();
        }

        @Override
        public void clear() {
            this.record = null;
        }
    }

    public static final class FakeSessionBackend implements SharedWorldSessionCoordinator.SessionBackend {
        private final ScriptedFailures failures = new ScriptedFailures();
        private final Deque<EnterSessionResponseDto> enterResponses = new ArrayDeque<>();
        private final Deque<ObserveWaitingResponseDto> observeResponses = new ArrayDeque<>();
        private final java.util.List<String> enterWaiterSessionIds = new java.util.ArrayList<>();
        private final java.util.List<Boolean> enterAcknowledgeFlags = new java.util.ArrayList<>();
        private ObserveWaitingResponseDto currentObservation;
        private int enterCalls;
        private int waitingCalls;
        private int abandonFinalizationCalls;

        @Override
        public EnterSessionResponseDto enterSession(String worldId, String waiterSessionId, boolean acknowledgeUncleanShutdown) throws Exception {
            this.enterCalls += 1;
            this.enterWaiterSessionIds.add(waiterSessionId);
            this.enterAcknowledgeFlags.add(acknowledgeUncleanShutdown);
            this.failures.throwIfNeeded("enterSession");
            return this.enterResponses.isEmpty() ? null : this.enterResponses.removeFirst();
        }

        @Override
        public ObserveWaitingResponseDto observeWaiting(String worldId, String waiterSessionId) throws Exception {
            this.waitingCalls += 1;
            this.failures.throwIfNeeded("observeWaiting");
            return nextObservation();
        }

        @Override
        public WorldRuntimeStatusDto cancelWaiting(String worldId, String waiterSessionId) throws Exception {
            this.waitingCalls += 1;
            this.failures.throwIfNeeded("setSessionWaitingFalse");
            return this.currentObservation == null ? null : this.currentObservation.runtime();
        }

        @Override
        public SharedWorldModels.FinalizationActionResultDto abandonFinalization(String worldId) throws Exception {
            this.abandonFinalizationCalls += 1;
            this.failures.throwIfNeeded("abandonFinalization");
            return new SharedWorldModels.FinalizationActionResultDto(worldId, null, null, "idle");
        }

        public void enqueueEnterResponse(EnterSessionResponseDto response) {
            this.enterResponses.addLast(response);
        }

        public void enqueueObserveResponse(ObserveWaitingResponseDto response) {
            this.observeResponses.addLast(response);
        }

        public void setCurrentObserve(ObserveWaitingResponseDto response) {
            this.currentObservation = response;
        }

        public ScriptedFailures failures() {
            return this.failures;
        }

        public int enterCalls() {
            return this.enterCalls;
        }

        public java.util.List<String> enterWaiterSessionIds() {
            return this.enterWaiterSessionIds;
        }

        public java.util.List<Boolean> enterAcknowledgeFlags() {
            return this.enterAcknowledgeFlags;
        }

        public int waitingCalls() {
            return this.waitingCalls;
        }

        public int abandonFinalizationCalls() {
            return this.abandonFinalizationCalls;
        }

        private ObserveWaitingResponseDto nextObservation() {
            if (!this.observeResponses.isEmpty()) {
                this.currentObservation = this.observeResponses.removeFirst();
            }
            return this.currentObservation;
        }
    }

    public static final class FakeReleaseBackend implements SharedWorldReleaseCoordinator.ReleaseBackend {
        private final ScriptedFailures failures = new ScriptedFailures();
        private WorldRuntimeStatusDto runtime;
        private WorldRuntimeStatusDto runtimeAfterBegin;
        private int beginCalls;
        private int completeCalls;
        private int releaseCalls;
        private WorldRuntimeStatusDto runtimeAfterComplete;

        @Override
        public WorldRuntimeStatusDto runtimeStatus(String worldId) throws Exception {
            this.failures.throwIfNeeded("runtimeStatus");
            return this.runtime;
        }

        @Override
        public void beginFinalization(String worldId, long runtimeEpoch, String hostToken) throws Exception {
            this.beginCalls += 1;
            this.failures.throwIfNeeded("beginFinalization");
            this.runtime = this.runtimeAfterBegin == null
                    ? new WorldRuntimeStatusDto(worldId, "host-finalizing", runtimeEpoch, "player-host", "Host", null, null, null, null, null, null, null)
                    : this.runtimeAfterBegin;
        }

        @Override
        public void completeFinalization(String worldId, long runtimeEpoch, String hostToken) throws Exception {
            this.completeCalls += 1;
            this.failures.throwIfNeeded("completeFinalization");
            this.runtime = this.runtimeAfterComplete == null
                    ? new WorldRuntimeStatusDto(worldId, "idle", runtimeEpoch + 1L, null, null, null, null, null, null, null, null, null)
                    : this.runtimeAfterComplete;
        }

        @Override
        public void releaseHost(String worldId, long runtimeEpoch, String hostToken, boolean graceful) throws Exception {
            this.releaseCalls += 1;
            this.failures.throwIfNeeded("releaseHost");
            this.runtime = new WorldRuntimeStatusDto(worldId, "idle", runtimeEpoch + 1L, null, null, null, null, null, null, null, null, null);
        }

        public void setRuntime(WorldRuntimeStatusDto runtime) {
            this.runtime = runtime;
        }

        public void setRuntimeAfterBegin(WorldRuntimeStatusDto runtimeAfterBegin) {
            this.runtimeAfterBegin = runtimeAfterBegin;
        }

        public void setRuntimeAfterComplete(WorldRuntimeStatusDto runtimeAfterComplete) {
            this.runtimeAfterComplete = runtimeAfterComplete;
        }

        public int beginCalls() {
            return this.beginCalls;
        }

        public int completeCalls() {
            return this.completeCalls;
        }

        public int releaseCalls() {
            return this.releaseCalls;
        }

        public ScriptedFailures failures() {
            return this.failures;
        }
    }

    public static final class FakeHostControl implements SharedWorldReleaseCoordinator.HostControl {
        private final TempdirSnapshotDriver snapshotDriver;
        private final ScriptedFailures failures = new ScriptedFailures();
        private SharedWorldHostingManager.ActiveHostSession activeHostSession;
        private boolean backgroundSaveInFlight;
        private boolean coordinatedReleaseStarted;
        private boolean backendFinalizationStarted;
        private int clearProgressCalls;
        private int clearCalls;
        private final List<SharedWorldProgressState> relayedProgress = new ArrayList<>();

        private FakeHostControl(TempdirSnapshotDriver snapshotDriver) {
            this.snapshotDriver = snapshotDriver;
        }

        @Override
        public SharedWorldHostingManager.ActiveHostSession activeHostSession() {
            return this.activeHostSession;
        }

        @Override
        public boolean isBackgroundSaveInFlight() {
            return this.backgroundSaveInFlight;
        }

        @Override
        public void beginCoordinatedRelease() {
            this.coordinatedReleaseStarted = true;
        }

        @Override
        public void markCoordinatedBackendFinalizationStarted() {
            this.backendFinalizationStarted = true;
        }

        @Override
        public Path finalReleaseWorldDirectory(String worldId) {
            return this.snapshotDriver.workingCopy(worldId);
        }

        @Override
        public SnapshotManifestDto uploadFinalReleaseSnapshot(String worldId, Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken, WorldSyncProgressListener progressListener) throws Exception {
            this.failures.throwIfNeeded("upload");
            return this.snapshotDriver.upload(worldId, worldDirectory, runtimeEpoch, hostToken, progressListener);
        }

        @Override
        public void clearHostedSessionAfterCoordinatedRelease() {
            this.clearCalls += 1;
            this.activeHostSession = null;
        }

        @Override
        public void relayCoordinatedReleaseProgress(SharedWorldProgressState progressState) {
            if (progressState != null) {
                this.relayedProgress.add(progressState);
            }
        }

        @Override
        public void clearCoordinatedReleaseProgress() {
            this.clearProgressCalls += 1;
        }

        @Override
        public void clearHostedSessionAfterTerminalExit() {
            this.clearCalls += 1;
            this.activeHostSession = null;
        }

        @Override
        public boolean isStartupCancelable() {
            return false;
        }

        public void setActiveHostSession(String worldId, String worldName, long runtimeEpoch, String hostToken, String joinTarget) {
            this.activeHostSession = new SharedWorldHostingManager.ActiveHostSession(worldId, worldName, runtimeEpoch, hostToken, joinTarget);
            this.snapshotDriver.setAcceptedHost(runtimeEpoch, hostToken);
            this.snapshotDriver.ensureWorkingCopy(worldId);
        }

        public void setBackgroundSaveInFlight(boolean backgroundSaveInFlight) {
            this.backgroundSaveInFlight = backgroundSaveInFlight;
        }

        public boolean coordinatedReleaseStarted() {
            return this.coordinatedReleaseStarted;
        }

        public boolean backendFinalizationStarted() {
            return this.backendFinalizationStarted;
        }

        public int clearCalls() {
            return this.clearCalls;
        }

        public int clearProgressCalls() {
            return this.clearProgressCalls;
        }

        public List<SharedWorldProgressState> relayedProgress() {
            return this.relayedProgress;
        }

        public ScriptedFailures failures() {
            return this.failures;
        }
    }

    public static final class TempdirSnapshotDriver implements AutoCloseable {
        private final Path root;
        private final List<UploadRecord> uploads = new ArrayList<>();
        private long acceptedRuntimeEpoch;
        private String acceptedHostToken;

        private TempdirSnapshotDriver(Path root) {
            this.root = root;
        }

        public void setAcceptedHost(long runtimeEpoch, String hostToken) {
            this.acceptedRuntimeEpoch = runtimeEpoch;
            this.acceptedHostToken = hostToken;
        }

        public Path workingCopy(String worldId) {
            return this.root.resolve("working-" + worldId);
        }

        public void ensureWorkingCopy(String worldId) {
            try {
                Path workingCopy = workingCopy(worldId);
                Files.createDirectories(workingCopy);
                Files.writeString(workingCopy.resolve(worldId + ".txt"), "working-copy");
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }

        public SnapshotManifestDto upload(String worldId, Path stagingDirectory, long runtimeEpoch, String hostToken, WorldSyncProgressListener progressListener) throws IOException, InterruptedException {
            if (runtimeEpoch != this.acceptedRuntimeEpoch || (this.acceptedHostToken != null && !this.acceptedHostToken.equals(hostToken))) {
                throw new IOException("stale epoch/token");
            }
            if (progressListener != null) {
                progressListener.onProgress(new link.sharedworld.sync.WorldSyncProgress(
                        WorldSyncCoordinator.STAGE_UPLOADING_CHANGED_FILES,
                        0.5D,
                        50L,
                        100L,
                        "uploading"
                ));
                progressListener.onProgress(new link.sharedworld.sync.WorldSyncProgress(
                        WorldSyncCoordinator.STAGE_FINALIZING_SNAPSHOT,
                        1.0D,
                        100L,
                        100L,
                        "finalizing"
                ));
            }
            Path uploadDir = this.root.resolve("uploaded-" + (this.uploads.size() + 1));
            copyDirectory(stagingDirectory, uploadDir);
            this.uploads.add(new UploadRecord(worldId, runtimeEpoch, hostToken, uploadDir));
            return new SnapshotManifestDto(
                    worldId,
                    "snapshot-" + this.uploads.size(),
                    Instant.now().toString(),
                    "player-host",
                    new ManifestFileDto[0],
                    new SnapshotPackDto[]{
                            new SnapshotPackDto("pack", "hash", 1L, "storage-key", "full", null, null, null, new PackedManifestFileDto[0])
                    }
            );
        }

        public List<UploadRecord> uploads() {
            return this.uploads;
        }

        @Override
        public void close() throws IOException {
            deleteIfExists(this.root);
        }

        private static void copyDirectory(Path source, Path target) throws IOException {
            Files.walk(source).forEach(path -> {
                try {
                    Path relative = source.relativize(path);
                    Path targetPath = target.resolve(relative);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(path, targetPath);
                    }
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }

        private static void deleteIfExists(Path path) throws IOException {
            if (!Files.exists(path)) {
                return;
            }
            try (var walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                    try {
                        Files.deleteIfExists(entry);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
    }

    public static final class UploadRecord {
        private final String worldId;
        private final long runtimeEpoch;
        private final String hostToken;
        private final Path uploadDir;

        private UploadRecord(String worldId, long runtimeEpoch, String hostToken, Path uploadDir) {
            this.worldId = worldId;
            this.runtimeEpoch = runtimeEpoch;
            this.hostToken = hostToken;
            this.uploadDir = uploadDir;
        }

        public String worldId() {
            return this.worldId;
        }

        public long runtimeEpoch() {
            return this.runtimeEpoch;
        }

        public String hostToken() {
            return this.hostToken;
        }

        public Path uploadDir() {
            return this.uploadDir;
        }
    }

    public static final class ScriptedFailures {
        private final java.util.Map<String, Deque<Exception>> failures = new java.util.HashMap<>();

        public void add(String step, Exception exception) {
            this.failures.computeIfAbsent(step, ignored -> new ArrayDeque<>()).addLast(exception);
        }

        public void throwIfNeeded(String step) throws Exception {
            Deque<Exception> queue = this.failures.get(step);
            if (queue == null || queue.isEmpty()) {
                return;
            }
            throw queue.removeFirst();
        }
    }
}
