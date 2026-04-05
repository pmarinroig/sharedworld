package link.sharedworld.host;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.SharedWorldGuestCacheWarmer;
import link.sharedworld.SharedWorldPlaySessionTracker;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.HostAssignmentDto;
import link.sharedworld.api.SharedWorldModels.StartupProgressDto;
import link.sharedworld.api.SharedWorldModels.SnapshotManifestDto;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.integration.E4mcDomainTracker;
import link.sharedworld.progress.SharedWorldProgressState;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncProgress;
import link.sharedworld.sync.WorldSyncCoordinator;
import link.sharedworld.sync.WorldSyncProgressListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class SharedWorldHostingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-hosting");
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final long HEARTBEAT_RETRY_INTERVAL_MS = 1_000L;
    private static final long HOST_CONFIRM_TIMEOUT_MS = 90_000L;
    private static final long AUTOSAVE_INTERVAL_MS = 5 * 60_000L;
    private static final long JOIN_TARGET_TIMEOUT_MS = 60_000L;

    private final SharedWorldApiClient apiClient;
    private final HostStartupProgressRelayController startupProgressRelay;
    private final ManagedWorldStore worldStore;
    private final SyncAccess syncAccess;
    private final HostRecoveryPersistence hostRecoveryStore;
    private final PendingReleaseRecoveryChecker pendingReleaseRecoveryChecker;
    private final WorldSnapshotCaptureCoordinator snapshotCaptureCoordinator;
    private final WorldOpenController worldOpenController;
    private final HostWorldBootstrap worldBootstrap;
    private final Executor backgroundExecutor;
    private final Executor mainThreadExecutor;
    private final AtomicBoolean startupStarted = new AtomicBoolean();
    private final AtomicBoolean saveInFlight = new AtomicBoolean();
    private final AtomicBoolean cancelDisconnectIssued = new AtomicBoolean();
    private final AtomicBoolean heartbeatInFlight = new AtomicBoolean();
    private volatile Phase phase = Phase.IDLE;
    private volatile String statusMessage = "";
    private volatile String errorMessage;
    private volatile WorldSummaryDto world;
    private volatile SnapshotManifestDto latestManifest;
    private volatile String hostPlayerUuid;
    private volatile boolean startupCancelRequested;
    private volatile String publishedJoinTarget;
    private volatile boolean coordinatedReleaseActive;
    private volatile boolean coordinatedReleaseBackendFinalization;
    private volatile long phaseStartedAt;
    private volatile long lastHeartbeatAt;
    private volatile long lastHeartbeatAttemptAt;
    private volatile long lastAutosaveAt;
    private volatile long startupAttemptId;
    private volatile long hostSessionGeneration;
    private volatile SharedWorldProgressState progressState;
    private volatile boolean startupProgressRelayActive;
    private volatile long runtimeEpoch;
    private volatile String hostToken;
    private volatile StartupMode startupMode = StartupMode.NORMAL;
    private volatile boolean startupRecoveringLocalCrash;

    public SharedWorldHostingManager(SharedWorldApiClient apiClient) {
        this(
                apiClient,
                new ManagedWorldStore(),
                null,
                null,
                defaultStartupProgressRelay(apiClient),
                new SharedWorldHostRecoveryStore(),
                worldId -> {
                    SharedWorldReleaseCoordinator releaseCoordinator = releaseCoordinatorOrNull();
                    return releaseCoordinator != null && releaseCoordinator.hasPendingReleaseRecovery(worldId);
                },
                defaultBackgroundExecutor(),
                defaultMainThreadExecutor()
        );
    }

    SharedWorldHostingManager(
            SharedWorldApiClient apiClient,
            ManagedWorldStore worldStore,
            SyncAccess syncAccess,
            WorldOpenController worldOpenController,
            HostStartupProgressRelayController startupProgressRelay
    ) {
        this(
                apiClient,
                worldStore,
                syncAccess,
                worldOpenController,
                startupProgressRelay,
                new SharedWorldHostRecoveryStore(),
                worldId -> {
                    SharedWorldReleaseCoordinator releaseCoordinator = releaseCoordinatorOrNull();
                    return releaseCoordinator != null && releaseCoordinator.hasPendingReleaseRecovery(worldId);
                },
                defaultBackgroundExecutor(),
                defaultMainThreadExecutor()
        );
    }

    SharedWorldHostingManager(
            SharedWorldApiClient apiClient,
            ManagedWorldStore worldStore,
            SyncAccess syncAccess,
            WorldOpenController worldOpenController,
            HostStartupProgressRelayController startupProgressRelay,
            HostRecoveryPersistence hostRecoveryStore,
            PendingReleaseRecoveryChecker pendingReleaseRecoveryChecker
    ) {
        this(
                apiClient,
                worldStore,
                syncAccess,
                worldOpenController,
                startupProgressRelay,
                hostRecoveryStore,
                pendingReleaseRecoveryChecker,
                defaultBackgroundExecutor(),
                defaultMainThreadExecutor()
        );
    }

    SharedWorldHostingManager(
            SharedWorldApiClient apiClient,
            ManagedWorldStore worldStore,
            SyncAccess syncAccess,
            WorldOpenController worldOpenController,
            HostStartupProgressRelayController startupProgressRelay,
            HostRecoveryPersistence hostRecoveryStore,
            PendingReleaseRecoveryChecker pendingReleaseRecoveryChecker,
            Executor backgroundExecutor,
            Executor mainThreadExecutor
    ) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.startupProgressRelay = Objects.requireNonNull(startupProgressRelay, "startupProgressRelay");
        this.worldStore = Objects.requireNonNull(worldStore, "worldStore");
        this.hostRecoveryStore = Objects.requireNonNull(hostRecoveryStore, "hostRecoveryStore");
        this.pendingReleaseRecoveryChecker = Objects.requireNonNull(pendingReleaseRecoveryChecker, "pendingReleaseRecoveryChecker");
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        this.syncAccess = syncAccess != null
                ? syncAccess
                : new WorldSyncAdapter(new WorldSyncCoordinator(apiClient, this.worldStore));
        this.snapshotCaptureCoordinator = new WorldSnapshotCaptureCoordinator(this.worldStore);
        this.worldOpenController = worldOpenController != null
                ? worldOpenController
                : new MinecraftWorldOpenController();
        this.worldBootstrap = new HostWorldBootstrap(this.syncAccess, this.worldStore, this.worldOpenController);
    }

    private static HostStartupProgressRelayController defaultStartupProgressRelay(SharedWorldApiClient apiClient) {
        return new HostStartupProgressRelayController(
                apiClient::setHostStartupProgress,
                defaultBackgroundExecutor(),
                System::currentTimeMillis
        );
    }

    private static Executor defaultBackgroundExecutor() {
        try {
            return SharedWorldClient.ioExecutor();
        } catch (ExceptionInInitializerError | NoClassDefFoundError ignored) {
            return Runnable::run;
        }
    }

    private static Executor defaultMainThreadExecutor() {
        return runnable -> {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null) {
                    minecraft.execute(runnable);
                    return;
                }
            } catch (ExceptionInInitializerError | NoClassDefFoundError ignored) {
            }
            runnable.run();
        };
    }

    private static void refreshHostedPermissionLevels() {
        Minecraft minecraft;
        try {
            minecraft = Minecraft.getInstance();
        } catch (ExceptionInInitializerError | NoClassDefFoundError ignored) {
            return;
        }
        if (minecraft == null) {
            return;
        }
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }

        var playerList = server.getPlayerList();
        if (playerList == null) {
            return;
        }

        for (var serverPlayer : playerList.getPlayers()) {
            playerList.sendPlayerPermissionLevel(serverPlayer);
        }
    }

    private static void withGuestCacheWarmer(Consumer<SharedWorldGuestCacheWarmer> action) {
        try {
            SharedWorldGuestCacheWarmer guestCacheWarmer = SharedWorldClient.guestCacheWarmer();
            if (guestCacheWarmer != null) {
                action.accept(guestCacheWarmer);
            }
        } catch (ExceptionInInitializerError | NoClassDefFoundError ignored) {
        }
    }

    private static void withPlaySessionTracker(Consumer<SharedWorldPlaySessionTracker> action) {
        try {
            action.accept(SharedWorldClient.playSessionTracker());
        } catch (ExceptionInInitializerError | NoClassDefFoundError ignored) {
        }
    }

    private static SharedWorldReleaseCoordinator releaseCoordinatorOrNull() {
        try {
            return SharedWorldClient.releaseCoordinator();
        } catch (ExceptionInInitializerError | NoClassDefFoundError ignored) {
            return null;
        }
    }

    /**
     * Responsibility:
     * Start a single local host attempt for the backend-assigned runtime epoch/token.
     *
     * Preconditions:
     * The backend already elected this player as host and supplied the current runtime epoch/token.
     *
     * Postconditions:
     * The manager owns one startup attempt that either becomes RUNNING, is canceled, or fails.
     *
     * Stale-work rule:
     * Async work from older host attempts must be ignored once startupAttemptId or hostSessionGeneration changes.
     *
     * Authority source:
     * Backend host assignment for the current runtime epoch/token.
     */
    public void beginHosting(Screen launchingScreen, WorldSummaryDto world, SnapshotManifestDto latestManifest, HostAssignmentDto assignment) {
        beginHosting(launchingScreen, world, latestManifest, assignment, StartupMode.NORMAL);
    }

    public void beginHosting(Screen launchingScreen, WorldSummaryDto world, SnapshotManifestDto latestManifest, HostAssignmentDto assignment, StartupMode startupMode) {
        if (this.startupStarted.get() && this.world != null && this.world.id().equals(world.id())) {
            return;
        }
        if (assignment == null) {
            throw new IllegalStateException("SharedWorld host startup requires a backend host assignment.");
        }
        if (latestManifest == null) {
            throw new IllegalStateException("SharedWorld host startup requires a finalized snapshot manifest. Fresh-world startup is no longer supported.");
        }

        this.world = world;
        this.latestManifest = latestManifest;
        this.runtimeEpoch = assignment.runtimeEpoch();
        this.hostToken = assignment.hostToken();
        this.hostPlayerUuid = this.apiClient.canonicalAssignedPlayerUuidWithHyphens(assignment.playerUuid());
        this.startupMode = startupMode == null ? StartupMode.NORMAL : startupMode;
        this.startupRecoveringLocalCrash = false;
        this.hostSessionGeneration += 1L;
        this.publishedJoinTarget = null;
        this.coordinatedReleaseActive = false;
        this.coordinatedReleaseBackendFinalization = false;
        this.errorMessage = null;
        this.lastHeartbeatAt = 0L;
        this.lastHeartbeatAttemptAt = 0L;
        this.lastAutosaveAt = 0L;
        this.startupProgressRelayActive = false;
        this.startupStarted.set(true);
        this.saveInFlight.set(false);
        this.heartbeatInFlight.set(false);
        this.startupCancelRequested = false;
        this.cancelDisconnectIssued.set(false);
        this.startupProgressRelay.reset();
        long startupAttemptId = this.startupAttemptId + 1L;
        this.startupAttemptId = startupAttemptId;
        withGuestCacheWarmer(guestCacheWarmer -> guestCacheWarmer.pauseWorld(world.id()));
        E4mcDomainTracker.clear();
        setPhase(Phase.PREPARING, SharedWorldText.string("screen.sharedworld.hosting_syncing_snapshot"));

        CompletableFuture.runAsync(() -> prepareAndOpen(startupAttemptId), this.backgroundExecutor)
                .whenComplete((unused, error) -> {
                    if (!isActiveStartupAttempt(startupAttemptId)) {
                        return;
                    }
                    if (error != null) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        fail(SharedWorldText.string("screen.sharedworld.hosting_prepare_failed"), cause);
                    }
                });
    }

    /**
     * Responsibility:
     * Drive the authoritative host lifecycle loop for the active local host attempt.
     *
     * Preconditions:
     * If startupStarted is true, this manager is the sole owner of the local host execution state.
     *
     * Postconditions:
     * Exactly one phase-specific driver runs per tick, and stale async work cannot re-enter the loop.
     *
     * Stale-work rule:
     * Async callbacks are validated against HostAttemptContext before they mutate state.
     *
     * Authority source:
     * Backend runtime authority plus local host execution state.
     */
    public void tick(Minecraft minecraft) {
        this.startupProgressRelay.tick();
        if (!this.startupStarted.get() || this.world == null || this.phase == Phase.IDLE || this.phase == Phase.ERROR) {
            return;
        }

        if (driveStartupCancellationIfRequested(minecraft)) {
            return;
        }

        long now = System.currentTimeMillis();
        driveStartupHeartbeat(now);
        if (driveLocalWorldPublish(minecraft)) {
            return;
        }
        if (driveJoinTargetAcquisition()) {
            return;
        }
        if (driveHostConfirmation(now)) {
            return;
        }
        driveLiveLease(now);
        driveRunningLoop(now);
    }

    private boolean driveStartupCancellationIfRequested(Minecraft minecraft) {
        if (!this.startupCancelRequested) {
            return false;
        }
        if ((minecraft.hasSingleplayerServer() || minecraft.level != null || minecraft.getConnection() != null)
                && this.cancelDisconnectIssued.compareAndSet(false, true)) {
            minecraft.disconnectFromWorld(null);
        }
        return true;
    }

    private void driveStartupHeartbeat(long now) {
        // host-starting must stay alive even before we have a join target, so startup heartbeats
        // are driven independently from the publish/confirm sub-phases.
        if (shouldSendStartupHeartbeat() && shouldAttemptHeartbeat(now)) {
            heartbeat(null);
        }
    }

    private boolean driveLocalWorldPublish(Minecraft minecraft) {
        if (this.phase != Phase.OPENING_WORLD || !minecraft.hasSingleplayerServer()) {
            return false;
        }
        if (!isClientReadyForPublish(minecraft)) {
            this.statusMessage = SharedWorldText.string("screen.sharedworld.hosting_joining_local");
            return true;
        }
        publishIfNeeded(minecraft.getSingleplayerServer());
        return true;
    }

    private boolean driveJoinTargetAcquisition() {
        if (this.phase != Phase.WAITING_FOR_E4MC) {
            return false;
        }
        String joinTarget = E4mcDomainTracker.currentJoinTarget();
        if (joinTarget != null && !joinTarget.isBlank()) {
            this.publishedJoinTarget = joinTarget;
            this.lastHeartbeatAt = 0L;
            this.lastHeartbeatAttemptAt = 0L;
            setPhase(Phase.CONFIRMING_HOST, SharedWorldText.string("screen.sharedworld.hosting_confirming_host"));
            confirmHostSession(joinTarget);
            return true;
        }
        if (System.currentTimeMillis() - this.phaseStartedAt > JOIN_TARGET_TIMEOUT_MS) {
            fail(SharedWorldText.string("screen.sharedworld.hosting_join_target_timeout"), null);
        }
        return true;
    }

    private boolean driveHostConfirmation(long now) {
        if (this.phase != Phase.CONFIRMING_HOST) {
            return false;
        }
        String joinTarget = this.publishedJoinTarget;
        if (joinTarget == null || joinTarget.isBlank()) {
            fail(SharedWorldText.string("screen.sharedworld.hosting_lost_join_target"), null);
            return true;
        }
        if (now - this.phaseStartedAt > HOST_CONFIRM_TIMEOUT_MS) {
            fail(SharedWorldText.string("screen.sharedworld.hosting_confirm_timeout"), null);
            return true;
        }
        if (shouldAttemptHeartbeat(now)) {
            confirmHostSession(joinTarget);
        }
        return true;
    }

    private void driveLiveLease(long now) {
        if (!HostLifecyclePolicy.shouldMaintainLiveLease(this.phase)) {
            return;
        }
        if (this.coordinatedReleaseActive) {
            if (!this.coordinatedReleaseBackendFinalization && shouldAttemptHeartbeat(now)) {
                heartbeat(this.publishedJoinTarget, this.phase == Phase.SAVING);
            }
            return;
        }
        if (shouldAttemptHeartbeat(now)) {
            heartbeat(this.publishedJoinTarget, this.phase == Phase.SAVING);
        }
    }

    private void driveRunningLoop(long now) {
        if (this.phase != Phase.RUNNING || this.coordinatedReleaseActive) {
            return;
        }
        if (now - this.lastAutosaveAt >= AUTOSAVE_INTERVAL_MS && this.saveInFlight.compareAndSet(false, true)) {
            uploadSnapshot(false);
        }
    }

    public boolean isStartupCancelable() {
        return this.startupStarted.get()
                && !this.startupCancelRequested
                && this.phase != Phase.IDLE
                && this.phase != Phase.ERROR
                && this.phase != Phase.RUNNING
                && this.phase != Phase.SAVING
                && this.phase != Phase.RELEASING;
    }

    /**
     * Responsibility:
     * Expose the current startup state to passive UI code without giving the UI ownership.
     *
     * Preconditions:
     * None.
     *
     * Postconditions:
     * The returned view mirrors current startup progress, cancelability, and error state only.
     *
     * Stale-work rule:
     * Consumers must treat the view as read-only and use manager intents for any mutation.
     *
     * Authority source:
     * Local host execution state owned by this manager.
     */
    public StartupView startupView() {
        return new StartupView(
                this.phase != Phase.IDLE,
                this.phase == Phase.ERROR,
                this.phase == Phase.IDLE,
                isStartupCancelable(),
                this.progressState,
                this.errorMessage
        );
    }

    public boolean isSavingOrReleasing() {
        return this.phase == Phase.SAVING || this.phase == Phase.RELEASING || this.phase == Phase.CANCELLING;
    }

    public boolean isReleaseComplete() {
        return this.phase == Phase.IDLE;
    }

    public ActiveHostSession activeHostSession() {
        if (!this.startupStarted.get() || this.world == null || this.phase == Phase.IDLE || this.phase == Phase.ERROR) {
            return null;
        }
        return new ActiveHostSession(
                this.world.id(),
                this.world.name(),
                this.runtimeEpoch,
                this.hostToken,
                this.publishedJoinTarget
        );
    }

    public boolean isBackgroundSaveInFlight() {
        return this.saveInFlight.get();
    }

    public void beginCoordinatedRelease() {
        this.coordinatedReleaseActive = true;
        this.coordinatedReleaseBackendFinalization = false;
    }

    public void markCoordinatedBackendFinalizationStarted() {
        this.coordinatedReleaseActive = true;
        this.coordinatedReleaseBackendFinalization = true;
        if (this.phase != Phase.IDLE && this.phase != Phase.ERROR) {
            setPhase(Phase.RELEASING, SharedWorldText.string("screen.sharedworld.progress.finishing_up"));
            return;
        }
        relayStartupProgressIfNeeded();
    }

    public Path finalReleaseWorldDirectory(String worldId) {
        return this.worldStore.workingCopy(worldId);
    }

    public SnapshotManifestDto uploadFinalReleaseSnapshot(
            String worldId,
            Path worldDirectory,
            String hostPlayerUuid,
            long runtimeEpoch,
            String hostToken,
            WorldSyncProgressListener progressListener
    ) throws IOException, InterruptedException {
        return this.syncAccess.uploadSnapshot(
                worldId,
                worldDirectory,
                hostPlayerUuid,
                runtimeEpoch,
                hostToken,
                progressListener
        );
    }

    public void clearHostedSessionAfterCoordinatedRelease() {
        this.hostRecoveryStore.clear();
        resetState();
    }

    /**
     * Responsibility:
     * Tear down local host execution after the terminal-flow owner has decided this host session must end.
     *
     * Preconditions:
     * The release coordinator already owns disconnect/UI sequencing for this terminal exit.
     *
     * Postconditions:
     * Local hosting state is cleared without performing another disconnect side effect.
     *
     * Stale-work rule:
     * This method only clears current local state; it must not revive or mutate an older host attempt.
     *
     * Authority source:
     * SharedWorldReleaseCoordinator terminal flow.
     */
    public void clearHostedSessionAfterTerminalExit() {
        resetState();
    }

    public boolean hasRecoverableLocalCrashState(String worldId, String hostPlayerUuid, long previousRuntimeEpoch) {
        return evaluateRecoveryEligibility(worldId, hostPlayerUuid, previousRuntimeEpoch).outcome() == RecoveryEligibilityOutcome.RECOVER_LOCAL;
    }

    public String activeWorldName() {
        return this.world == null ? "" : this.world.name();
    }

    public String activeWorldId() {
        return this.world == null ? "" : this.world.id();
    }

    public void cancelStartup() {
        if (!isStartupCancelable()) {
            return;
        }
        HostAttemptContext context = currentAttemptContext();
        this.startupCancelRequested = true;
        this.cancelDisconnectIssued.set(false);
        this.startupAttemptId += 1L;
        invalidateAsyncOperations();
        setPhase(Phase.CANCELLING, SharedWorldText.string("screen.sharedworld.hosting_canceling"));
        releaseHostLeaseAfterStartupCancel(context, false);

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.hasSingleplayerServer() || minecraft.level != null || minecraft.getConnection() != null) {
            minecraft.execute(() -> minecraft.disconnectFromWorld(null));
            return;
        }

        releaseHostLeaseAfterStartupCancel(context, true);
    }

    public Phase phase() {
        return this.phase;
    }

    public String statusMessage() {
        return this.statusMessage;
    }

    public String errorMessage() {
        return this.errorMessage;
    }

    public SharedWorldProgressState progressState() {
        return this.progressState;
    }

    public boolean hasError() {
        return this.phase == Phase.ERROR;
    }

    private void prepareAndOpen(long startupAttemptId) {
        try {
            HostRecoveryRecord recoveryRecord = startupRecoveryRecord();
            this.startupRecoveringLocalCrash = recoveryRecord != null;
            this.worldBootstrap.prepareAndOpen(
                    startupAttemptId,
                    this.world,
                    this::requireHostPlayerUuid,
                    this.runtimeEpoch,
                    this.hostToken,
                    recoveryRecord != null,
                    this::isActiveStartupAttempt,
                    progress -> applyStartupSyncProgress(startupAttemptId, progress),
                    () -> setPhase(Phase.OPENING_WORLD, SharedWorldText.string("screen.sharedworld.hosting_opening_world"))
            );
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private void publishIfNeeded(IntegratedServer server) {
        if (server == null) {
            return;
        }
        if (!server.isPublished()) {
            setPhase(Phase.PUBLISHING, SharedWorldText.string("screen.sharedworld.hosting_opening_to_friends"));
            int port = HttpUtil.getAvailablePort();
            // Shared World synchronizes playerdata, so late joiners must keep their stored
            // gamemode instead of inheriting a forced LAN publish mode.
            if (!server.publishServer(SharedWorldPublishedJoinModePolicy.publishGameMode(), false, port)) {
                fail(SharedWorldText.string("screen.sharedworld.hosting_publish_failed"), null);
                return;
            }
        }
        setPhase(Phase.WAITING_FOR_E4MC, SharedWorldText.string("screen.sharedworld.hosting_waiting_for_e4mc"));
    }

    private boolean isClientReadyForPublish(Minecraft minecraft) {
        return minecraft.player != null && minecraft.level != null && minecraft.getConnection() != null;
    }

    /**
     * Responsibility:
     * Send the next authoritative heartbeat for the current host attempt.
     *
     * Preconditions:
     * The current HostAttemptContext still matches the active host epoch/token.
     *
     * Postconditions:
     * Success refreshes local liveness bookkeeping; failure is classified by authority/error type.
     *
     * Stale-work rule:
     * Completion is ignored unless the callback still matches the current HostAttemptContext.
     *
     * Authority source:
     * Backend runtime authority for the current host epoch/token.
     */
    private void heartbeat(String joinTarget) {
        heartbeat(joinTarget, false);
    }

    private void heartbeat(String joinTarget, boolean duringSnapshotUpload) {
        HostAttemptContext context = currentAttemptContext();
        if (context == null || !this.heartbeatInFlight.compareAndSet(false, true)) {
            return;
        }
        this.lastHeartbeatAttemptAt = System.currentTimeMillis();
        String heartbeatJoinTarget = joinTarget == null || joinTarget.isBlank() ? null : joinTarget;
        CompletableFuture.runAsync(() -> {
            try {
                WorldRuntimeStatusDto runtime = this.apiClient.heartbeatHost(
                        context.worldId(),
                        context.runtimeEpoch(),
                        context.hostToken(),
                        heartbeatJoinTarget
                );
                dispatchToMainThread(() -> onHeartbeatSucceeded(context, runtime, heartbeatJoinTarget, duringSnapshotUpload));
            } catch (Exception exception) {
                dispatchToMainThread(() -> handleHeartbeatFailure(context, exception, duringSnapshotUpload));
            }
        }, this.backgroundExecutor).whenComplete((unused, error) -> dispatchToMainThread(() -> clearHeartbeatInFlight(context)));
    }

    private void confirmHostSession(String joinTarget) {
        heartbeat(joinTarget);
    }

    private boolean shouldAttemptHeartbeat(long now) {
        return HostLifecyclePolicy.shouldAttemptHeartbeat(
                now,
                this.lastHeartbeatAt,
                this.lastHeartbeatAttemptAt,
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_RETRY_INTERVAL_MS
        );
    }

    private boolean shouldSendStartupHeartbeat() {
        return this.world != null
                && HostLifecyclePolicy.shouldSendStartupHeartbeat(this.phase);
    }

    private void onHeartbeatSucceeded(
            HostAttemptContext context,
            WorldRuntimeStatusDto runtime,
            String joinTarget,
            boolean duringSnapshotUpload
    ) {
        if (!isCurrentAttempt(context)) {
            return;
        }
        if (runtime == null || runtime.runtimeEpoch() != context.runtimeEpoch()) {
            LOGGER.warn(
                    "SharedWorld heartbeat returned unexpected runtime for {} (phase={}, epoch={}, releaseActive={})",
                    context.worldId(),
                    runtime == null ? null : runtime.phase(),
                    runtime == null ? null : runtime.runtimeEpoch(),
                    this.coordinatedReleaseActive
            );
            handleHostAuthorityLost(heartbeatAuthorityLossMessage(duringSnapshotUpload));
            return;
        }
        if ("host-finalizing".equals(runtime.phase())) {
            if (this.coordinatedReleaseActive) {
                return;
            }
            LOGGER.warn(
                    "SharedWorld heartbeat unexpectedly reported host-finalizing without coordinated release for {}",
                    context.worldId()
            );
            handleHostAuthorityLost(heartbeatAuthorityLossMessage(duringSnapshotUpload));
            return;
        }
        if (!"host-starting".equals(runtime.phase()) && !"host-live".equals(runtime.phase())) {
            LOGGER.warn(
                    "SharedWorld heartbeat returned unexpected phase {} for {}",
                    runtime.phase(),
                    context.worldId()
            );
            handleHostAuthorityLost(heartbeatAuthorityLossMessage(duringSnapshotUpload));
            return;
        }
        this.lastHeartbeatAt = System.currentTimeMillis();
        String confirmedJoinTarget = runtime.joinTarget() == null || runtime.joinTarget().isBlank()
                ? joinTarget
                : runtime.joinTarget();
        if (this.phase == Phase.CONFIRMING_HOST
                && this.world != null
                && "host-live".equals(runtime.phase())
                && confirmedJoinTarget != null
                && confirmedJoinTarget.equals(this.publishedJoinTarget)) {
            this.lastAutosaveAt = this.lastHeartbeatAt;
            saveHostRecoveryMarker();
            withPlaySessionTracker(playSessionTracker -> playSessionTracker.beginHostSession(this.world.id(), this.world.name()));
            SharedWorldDevSessionBridge.setHostingSharedWorld(true, this.world.ownerUuid());
            refreshHostedPermissionLevels();
            setPhase(Phase.RUNNING, SharedWorldText.string("screen.sharedworld.hosting_live_at", confirmedJoinTarget));
        }
    }

    private void handleHeartbeatFailure(HostAttemptContext context, Exception exception, boolean duringSnapshotUpload) {
        if (!isCurrentAttempt(context)) {
            return;
        }
        if (SharedWorldApiClient.isDeletedWorldError(exception)) {
            SharedWorldReleaseCoordinator releaseCoordinator = releaseCoordinatorOrNull();
            if (releaseCoordinator != null) {
                releaseCoordinator.onWorldDeleted();
            }
            return;
        }
        if (SharedWorldApiClient.isMembershipRevokedError(exception)) {
            SharedWorldReleaseCoordinator releaseCoordinator = releaseCoordinatorOrNull();
            if (releaseCoordinator != null) {
                releaseCoordinator.onMembershipRevoked();
            }
            return;
        }
        if (SharedWorldApiClient.isHostNotActiveError(exception)) {
            handleHostAuthorityLost(heartbeatAuthorityLossMessage(duringSnapshotUpload));
            return;
        }
        LOGGER.warn(duringSnapshotUpload ? "SharedWorld snapshot upload heartbeat failed" : "SharedWorld heartbeat failed", exception);
    }

    private String heartbeatAuthorityLossMessage(boolean duringSnapshotUpload) {
        if (duringSnapshotUpload) {
            return SharedWorldText.string("screen.sharedworld.hosting_lost_authority_upload");
        }
        return this.phase == Phase.CONFIRMING_HOST
                ? SharedWorldText.string("screen.sharedworld.hosting_lost_authority_confirm")
                : SharedWorldText.string("screen.sharedworld.hosting_lost_authority_live");
    }

    private void handleHostAuthorityLost(String message) {
        ActiveHostSession session = activeHostSession();
        SharedWorldReleaseCoordinator.HostAuthorityLossStage stage = HostLifecyclePolicy.authorityLossStage(this.phase);
        this.errorMessage = message;
        invalidateAsyncOperations();
        setPhase(Phase.ERROR, message);
        SharedWorldReleaseCoordinator releaseCoordinator = releaseCoordinatorOrNull();
        if (releaseCoordinator != null) {
            releaseCoordinator.onHostAuthorityLost(session, stage, message);
        }
    }

    /**
     * Responsibility:
     * Capture and publish an autosave or initial snapshot for the current host attempt.
     *
     * Preconditions:
     * The current HostAttemptContext still owns the active hosted world.
     *
     * Postconditions:
     * The snapshot is uploaded or the failure is classified without letting stale work mutate state.
     *
     * Stale-work rule:
     * Upload completions, progress, and cleanup only apply if the HostAttemptContext is still current.
     *
     * Authority source:
     * Current HostAttemptContext plus backend upload authorization.
     */
    private void uploadSnapshot(boolean initialSnapshot) {
        HostAttemptContext context = currentAttemptContext();
        if (context == null) {
            this.saveInFlight.set(false);
            return;
        }
        setPhase(Phase.SAVING, SharedWorldText.string("screen.sharedworld.hosting_saving_snapshot"));
        CompletableFuture.runAsync(() -> {
            Path stagingDirectory = null;
            try {
                Minecraft minecraft = Minecraft.getInstance();
                IntegratedServer server = minecraft.getSingleplayerServer();
                WorldSnapshotCaptureCoordinator.CaptureMode captureMode = initialSnapshot
                        ? WorldSnapshotCaptureCoordinator.CaptureMode.FINALIZATION_FLUSH
                        : WorldSnapshotCaptureCoordinator.CaptureMode.AUTOSAVE_WINDOW;
                stagingDirectory = this.snapshotCaptureCoordinator.capture(context.worldId(), server, captureMode);
                SnapshotManifestDto uploadedManifest = this.syncAccess.uploadSnapshot(
                        context.worldId(),
                        stagingDirectory,
                        requireHostPlayerUuid(),
                        context.runtimeEpoch(),
                        context.hostToken(),
                        progress -> applySaveSyncProgress(context, progress, false)
                );
                dispatchToMainThread(() -> {
                    if (!isCurrentAttempt(context)) {
                        return;
                    }
                    this.latestManifest = uploadedManifest;
                    this.lastAutosaveAt = System.currentTimeMillis();
                    setPhase(Phase.RUNNING, HostLifecyclePolicy.runningStatusMessage(this.publishedJoinTarget));
                });
            } catch (Exception exception) {
                if (!isCurrentAttempt(context)) {
                    return;
                }
                if (SharedWorldApiClient.isDeletedWorldError(exception)) {
                    dispatchToMainThread(() -> {
                        if (isCurrentAttempt(context)) {
                            SharedWorldReleaseCoordinator releaseCoordinator = releaseCoordinatorOrNull();
                            if (releaseCoordinator != null) {
                                releaseCoordinator.onWorldDeleted();
                            }
                        }
                    });
                    return;
                }
                if (SharedWorldApiClient.isMembershipRevokedError(exception)) {
                    dispatchToMainThread(() -> {
                        if (isCurrentAttempt(context)) {
                            SharedWorldReleaseCoordinator releaseCoordinator = releaseCoordinatorOrNull();
                            if (releaseCoordinator != null) {
                                releaseCoordinator.onMembershipRevoked();
                            }
                        }
                    });
                    return;
                }
                if (SharedWorldApiClient.isHostNotActiveError(exception)) {
                    dispatchToMainThread(() -> {
                        if (isCurrentAttempt(context)) {
                            handleHostAuthorityLost(SharedWorldText.string("screen.sharedworld.hosting_lost_authority_upload"));
                        }
                    });
                    return;
                }
                LOGGER.warn("SharedWorld autosave failed", exception);
                dispatchToMainThread(() -> {
                    if (isCurrentAttempt(context)) {
                        setPhase(Phase.RUNNING, HostLifecyclePolicy.runningStatusMessage(this.publishedJoinTarget));
                    }
                });
            } finally {
                if (stagingDirectory != null) {
                    try {
                        this.worldStore.deleteSnapshotStagingCopy(stagingDirectory);
                    } catch (Exception cleanupException) {
                        LOGGER.warn("SharedWorld failed to clean up snapshot staging copy", cleanupException);
                    }
                }
                dispatchToMainThread(() -> clearSaveInFlight(context));
            }
        }, this.backgroundExecutor);
    }

    private void fail(String message, Throwable throwable) {
        if (this.startupCancelRequested) {
            return;
        }
        HostAttemptContext context = currentAttemptContext();
        this.errorMessage = throwable == null ? message : message + " " + throwable.getMessage();
        invalidateAsyncOperations();
        setPhase(Phase.ERROR, this.errorMessage);
        CompletableFuture.runAsync(() -> {
            try {
                if (context != null) {
                    this.apiClient.releaseHost(context.worldId(), false, context.runtimeEpoch(), context.hostToken());
                }
            } catch (Exception exception) {
                LOGGER.warn("SharedWorld failed to release lease after startup error", exception);
            }
        }, this.backgroundExecutor);
    }

    private String requireHostPlayerUuid() {
        if (this.hostPlayerUuid == null || this.hostPlayerUuid.isBlank()) {
            throw new IllegalStateException("SharedWorld host startup is missing the canonical host player UUID.");
        }
        return this.hostPlayerUuid;
    }

    private HostRecoveryRecord startupRecoveryRecord() {
        if (this.startupMode != StartupMode.ACKNOWLEDGED_UNCLEAN_SHUTDOWN || this.world == null) {
            return null;
        }
        RecoveryEligibility eligibility = evaluateRecoveryEligibility(this.world.id(), requireHostPlayerUuid(), this.runtimeEpoch - 1L);
        if (eligibility.outcome() != RecoveryEligibilityOutcome.RECOVER_LOCAL) {
            return null;
        }
        return eligibility.record();
    }

    private RecoveryEligibility evaluateRecoveryEligibility(String worldId, String hostPlayerUuid, long previousRuntimeEpoch) {
        if (worldId == null || worldId.isBlank() || hostPlayerUuid == null || hostPlayerUuid.isBlank()) {
            return new RecoveryEligibility(RecoveryEligibilityOutcome.FALLBACK_NO_MARKER, null);
        }
        if (this.pendingReleaseRecoveryChecker.hasPendingReleaseRecovery(worldId)) {
            return new RecoveryEligibility(RecoveryEligibilityOutcome.FALLBACK_PENDING_RELEASE, null);
        }
        HostRecoveryRecord record = this.hostRecoveryStore.load();
        if (record == null) {
            return new RecoveryEligibility(RecoveryEligibilityOutcome.FALLBACK_NO_MARKER, null);
        }
        if (!worldId.equals(record.worldId())) {
            return new RecoveryEligibility(RecoveryEligibilityOutcome.FALLBACK_NO_MARKER, null);
        }
        if (!hostPlayerUuid.equalsIgnoreCase(record.hostUuid())) {
            return new RecoveryEligibility(RecoveryEligibilityOutcome.FALLBACK_NO_MARKER, null);
        }
        if (!Files.exists(this.worldStore.workingCopy(worldId))) {
            return new RecoveryEligibility(RecoveryEligibilityOutcome.FALLBACK_NO_WORKING_COPY, record);
        }
        if (record.runtimeEpoch() != previousRuntimeEpoch) {
            return new RecoveryEligibility(RecoveryEligibilityOutcome.FALLBACK_STALE_EPOCH, record);
        }
        return new RecoveryEligibility(RecoveryEligibilityOutcome.RECOVER_LOCAL, record);
    }

    private void saveHostRecoveryMarker() {
        if (this.world == null || this.hostPlayerUuid == null || this.hostPlayerUuid.isBlank()) {
            return;
        }
        try {
            this.hostRecoveryStore.save(new HostRecoveryRecord(
                    this.world.id(),
                    this.world.name(),
                    this.hostPlayerUuid,
                    this.runtimeEpoch,
                    Instant.ofEpochMilli(this.lastHeartbeatAt == 0L ? System.currentTimeMillis() : this.lastHeartbeatAt).toString()
            ));
        } catch (Exception ignored) {
        }
    }

    private void setPhase(Phase phase, String statusMessage) {
        this.phase = phase;
        this.statusMessage = statusMessage;
        this.phaseStartedAt = System.currentTimeMillis();
        this.progressState = switch (phase) {
            case PREPARING -> HostProgressStateFactory.startupIndeterminate("preparing_world", Component.translatable("screen.sharedworld.progress.preparing_world"), this.progressState);
            case OPENING_WORLD -> HostProgressStateFactory.startupIndeterminate("finishing_up", Component.translatable("screen.sharedworld.progress.finishing_up"), this.progressState);
            case PUBLISHING -> HostProgressStateFactory.startupIndeterminate("becoming_host", Component.translatable("screen.sharedworld.progress.becoming_host"), this.progressState);
            case WAITING_FOR_E4MC -> HostProgressStateFactory.startupIndeterminate("connecting", Component.translatable("screen.sharedworld.progress.connecting"), this.progressState);
            case CONFIRMING_HOST -> HostProgressStateFactory.startupIndeterminate("connecting", Component.translatable("screen.sharedworld.progress.connecting"), this.progressState);
            case RUNNING -> null;
            case CANCELLING -> HostProgressStateFactory.startupIndeterminate("finishing_up", Component.translatable("screen.sharedworld.progress.finishing_up"), this.progressState);
            case SAVING -> HostProgressStateFactory.savingIndeterminate("saving_world", Component.translatable("screen.sharedworld.progress.saving_world"), this.progressState);
            case RELEASING -> releasingProgressState();
            case ERROR -> null;
            case IDLE -> null;
        };
        relayStartupProgressIfNeeded();
    }

    private void releaseHostLeaseAfterStartupCancel(HostAttemptContext context, boolean resetAfterRelease) {
        String worldId = context == null ? null : context.worldId();
        if (worldId == null) {
            if (resetAfterRelease) {
                dispatchToMainThread(this::resetState);
            }
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                this.apiClient.releaseHost(worldId, false, context.runtimeEpoch(), context.hostToken());
            } catch (Exception exception) {
                LOGGER.warn("SharedWorld failed to release host after startup cancel", exception);
            }
        }, this.backgroundExecutor).whenComplete((unused, error) -> {
            if (resetAfterRelease) {
                dispatchToMainThread(this::resetState);
            }
        });
    }

    private HostAttemptContext currentAttemptContext() {
        if (!this.startupStarted.get() || this.world == null || this.phase == Phase.IDLE) {
            return null;
        }
        return new HostAttemptContext(
                this.hostSessionGeneration,
                this.startupAttemptId,
                this.world.id(),
                this.runtimeEpoch,
                this.hostToken
        );
    }

    private boolean isCurrentAttempt(HostAttemptContext context) {
        return context != null
                && this.startupStarted.get()
                && this.world != null
                && this.hostSessionGeneration == context.generation()
                && this.startupAttemptId == context.startupAttemptId()
                && this.world.id().equals(context.worldId())
                && this.runtimeEpoch == context.runtimeEpoch()
                && Objects.equals(this.hostToken, context.hostToken());
    }

    private void invalidateAsyncOperations() {
        this.hostSessionGeneration += 1L;
        this.heartbeatInFlight.set(false);
        this.saveInFlight.set(false);
    }

    private void clearHeartbeatInFlight(HostAttemptContext context) {
        if (isCurrentAttempt(context)) {
            this.heartbeatInFlight.set(false);
        }
    }

    private void clearSaveInFlight(HostAttemptContext context) {
        if (isCurrentAttempt(context)) {
            this.saveInFlight.set(false);
        }
    }

    private void dispatchToMainThread(Runnable runnable) {
        this.mainThreadExecutor.execute(runnable);
    }

    private void resetState() {
        if (this.world != null) {
            withGuestCacheWarmer(guestCacheWarmer -> guestCacheWarmer.resumeWorld(this.world.id()));
        }
        withPlaySessionTracker(SharedWorldPlaySessionTracker::clear);
        this.phase = Phase.IDLE;
        this.statusMessage = "";
        this.errorMessage = null;
        this.world = null;
        this.latestManifest = null;
        this.hostPlayerUuid = null;
        this.coordinatedReleaseActive = false;
        this.coordinatedReleaseBackendFinalization = false;
        this.startupCancelRequested = false;
        this.publishedJoinTarget = null;
        this.lastHeartbeatAt = 0L;
        this.lastHeartbeatAttemptAt = 0L;
        this.lastAutosaveAt = 0L;
        this.startupStarted.set(false);
        this.saveInFlight.set(false);
        this.heartbeatInFlight.set(false);
        this.cancelDisconnectIssued.set(false);
        this.progressState = null;
        this.startupProgressRelayActive = false;
        this.startupProgressRelay.reset();
        this.runtimeEpoch = 0L;
        this.hostToken = null;
        this.startupMode = StartupMode.NORMAL;
        this.startupRecoveringLocalCrash = false;
        E4mcDomainTracker.clear();
        SharedWorldDevSessionBridge.clear();
        refreshHostedPermissionLevels();
    }

    private void applyStartupSyncProgress(long startupAttemptId, WorldSyncProgress progress) {
        if (!isActiveStartupAttempt(startupAttemptId)) {
            return;
        }
        this.progressState = switch (progress.stage()) {
            case WorldSyncCoordinator.STAGE_UPLOADING_CHANGED_FILES -> this.startupRecoveringLocalCrash
                    ? HostProgressStateFactory.startupDeterminate(
                    "recovering_local_world",
                    Component.translatable("screen.sharedworld.progress.recovering_local_world"),
                    progress.fraction(),
                    this.progressState,
                    progress.bytesDone(),
                    progress.bytesTotal()
            )
                    : HostProgressStateFactory.startupIndeterminate(
                    "preparing_world",
                    Component.translatable("screen.sharedworld.progress.preparing_world"),
                    this.progressState
            );
            case WorldSyncCoordinator.STAGE_FINALIZING_SNAPSHOT -> this.startupRecoveringLocalCrash
                    ? HostProgressStateFactory.startupIndeterminate(
                    "recovering_local_world",
                    Component.translatable("screen.sharedworld.progress.recovering_local_world"),
                    this.progressState
            )
                    : HostProgressStateFactory.startupIndeterminate(
                    "preparing_world",
                    Component.translatable("screen.sharedworld.progress.preparing_world"),
                    this.progressState
            );
            case WorldSyncCoordinator.STAGE_DOWNLOADING_CHANGED_FILES -> HostProgressStateFactory.startupDeterminate(
                    "syncing_world",
                    Component.translatable("screen.sharedworld.progress.syncing_world"),
                    progress.fraction(),
                    this.progressState,
                    progress.bytesDone(),
                    progress.bytesTotal()
            );
            case WorldSyncCoordinator.STAGE_APPLYING_WORLD_UPDATE -> HostProgressStateFactory.startupIndeterminate(
                    "finishing_up",
                    Component.translatable("screen.sharedworld.progress.finishing_up"),
                    this.progressState
            );
            default -> HostProgressStateFactory.startupIndeterminate(
                    "preparing_world",
                    Component.translatable("screen.sharedworld.progress.preparing_world"),
                    this.progressState
            );
        };
        this.statusMessage = this.progressState.label().getString();
        relayStartupProgressIfNeeded();
    }

    private void applySaveSyncProgress(HostAttemptContext context, WorldSyncProgress progress, boolean releasingAfterUpload) {
        if (!isCurrentAttempt(context)) {
            return;
        }
        this.progressState = switch (progress.stage()) {
            case WorldSyncCoordinator.STAGE_UPLOADING_CHANGED_FILES -> HostProgressStateFactory.savingDeterminate(
                    "saving_world",
                    Component.translatable("screen.sharedworld.progress.saving_world"),
                    progress.fraction(),
                    this.progressState,
                    progress.bytesDone(),
                    progress.bytesTotal()
            );
            case WorldSyncCoordinator.STAGE_FINALIZING_SNAPSHOT -> HostProgressStateFactory.savingIndeterminate(
                    releasingAfterUpload ? "finishing_up" : "finishing_up",
                    Component.translatable("screen.sharedworld.progress.finishing_up"),
                    this.progressState
            );
            default -> HostProgressStateFactory.savingIndeterminate(
                    "saving_world",
                    Component.translatable("screen.sharedworld.progress.saving_world"),
                    this.progressState
            );
        };
        this.statusMessage = this.progressState.label().getString();
        relayStartupProgressIfNeeded();
    }

    private SharedWorldProgressState releasingProgressState() {
        return HostProgressStateFactory.releasingState(
                this.coordinatedReleaseBackendFinalization,
                this.progressState
        );
    }

    private void relayStartupProgressIfNeeded() {
        HostAttemptContext context = currentAttemptContext();
        if (context == null) {
            return;
        }
        SharedWorldProgressState state = this.progressState;
        boolean shouldRelay = state != null
                && this.phase != Phase.RUNNING
                && this.phase != Phase.SAVING
                && this.phase != Phase.ERROR
                && this.phase != Phase.IDLE
                && (this.phase != Phase.RELEASING || this.coordinatedReleaseBackendFinalization);
        if (!shouldRelay) {
            if (this.startupProgressRelayActive) {
                this.startupProgressRelay.clear(progressRelayAuthority(context));
                this.startupProgressRelayActive = false;
            }
            return;
        }

        Double fraction = state.mode() == SharedWorldProgressState.ProgressMode.DETERMINATE ? state.targetFraction() : null;
        this.startupProgressRelayActive = true;
        this.startupProgressRelay.relay(
                progressRelayAuthority(context),
                new StartupProgressDto(
                        state.label().getString(),
                        state.mode() == SharedWorldProgressState.ProgressMode.DETERMINATE ? "determinate" : "indeterminate",
                        fraction,
                        null
                )
        );
    }

    public void relayCoordinatedReleaseProgress(SharedWorldProgressState progressState) {
        if (!this.coordinatedReleaseActive || progressState == null) {
            return;
        }
        this.progressState = progressState;
        this.statusMessage = progressState.label().getString();
        relayStartupProgressIfNeeded();
    }

    public void clearCoordinatedReleaseProgress() {
        if (!this.coordinatedReleaseActive && this.phase != Phase.RELEASING && this.phase != Phase.ERROR) {
            return;
        }
        HostAttemptContext context = currentAttemptContext();
        if (context == null || !this.startupProgressRelayActive) {
            return;
        }
        this.startupProgressRelay.clear(progressRelayAuthority(context));
        this.startupProgressRelayActive = false;
    }

    private boolean isActiveStartupAttempt(long startupAttemptId) {
        return this.startupStarted.get() && !this.startupCancelRequested && this.startupAttemptId == startupAttemptId;
    }

    private HostStartupProgressRelayController.AuthorityContext progressRelayAuthority(HostAttemptContext context) {
        return new HostStartupProgressRelayController.AuthorityContext(
                context.worldId(),
                context.runtimeEpoch(),
                context.hostToken(),
                context.generation()
        );
    }

    public enum Phase {
        IDLE,
        PREPARING,
        OPENING_WORLD,
        PUBLISHING,
        WAITING_FOR_E4MC,
        CONFIRMING_HOST,
        RUNNING,
        CANCELLING,
        SAVING,
        RELEASING,
        ERROR
    }

    public enum StartupMode {
        NORMAL,
        ACKNOWLEDGED_UNCLEAN_SHUTDOWN
    }

    public record ActiveHostSession(
            String worldId,
            String worldName,
            long runtimeEpoch,
            String hostToken,
            String joinTarget
    ) {
    }

    public record StartupView(
            boolean active,
            boolean hasError,
            boolean complete,
            boolean canCancel,
            SharedWorldProgressState progressState,
            String errorMessage
    ) {
    }

    public record HostRecoveryRecord(
            String worldId,
            String worldName,
            String hostUuid,
            long runtimeEpoch,
            String updatedAt
    ) {
    }

    private record RecoveryEligibility(
            RecoveryEligibilityOutcome outcome,
            HostRecoveryRecord record
    ) {
    }

    private enum RecoveryEligibilityOutcome {
        RECOVER_LOCAL,
        FALLBACK_NO_MARKER,
        FALLBACK_NO_WORKING_COPY,
        FALLBACK_PENDING_RELEASE,
        FALLBACK_STALE_EPOCH
    }

    interface SyncAccess {
        Path ensureSynchronizedWorkingCopy(String worldId, String hostPlayerUuid, WorldSyncProgressListener progressListener) throws IOException, InterruptedException;

        SnapshotManifestDto uploadSnapshot(
                String worldId,
                Path worldDirectory,
                String hostPlayerUuid,
                long runtimeEpoch,
                String hostToken,
                WorldSyncProgressListener progressListener
        ) throws IOException, InterruptedException;
    }

    public interface HostRecoveryPersistence {
        HostRecoveryRecord load();

        void save(HostRecoveryRecord record) throws Exception;

        void clear();
    }

    interface PendingReleaseRecoveryChecker {
        boolean hasPendingReleaseRecovery(String worldId);
    }

    interface WorldOpenController {
        void openExistingWorld(ManagedWorldStore worldStore, WorldSummaryDto world, Path worldDirectory);
    }

    private static final class WorldSyncAdapter implements SyncAccess {
        private final WorldSyncCoordinator coordinator;

        private WorldSyncAdapter(WorldSyncCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        public Path ensureSynchronizedWorkingCopy(String worldId, String hostPlayerUuid, WorldSyncProgressListener progressListener) throws IOException, InterruptedException {
            return this.coordinator.ensureSynchronizedWorkingCopy(worldId, hostPlayerUuid, progressListener);
        }

        @Override
        public SnapshotManifestDto uploadSnapshot(
                String worldId,
                Path worldDirectory,
                String hostPlayerUuid,
                long runtimeEpoch,
                String hostToken,
                WorldSyncProgressListener progressListener
        ) throws IOException, InterruptedException {
            return this.coordinator.uploadSnapshot(worldId, worldDirectory, hostPlayerUuid, runtimeEpoch, hostToken, progressListener);
        }
    }

    private static final class MinecraftWorldOpenController implements WorldOpenController {
        @Override
        public void openExistingWorld(ManagedWorldStore worldStore, WorldSummaryDto world, Path worldDirectory) {
            Minecraft.getInstance().execute(() -> {
                WorldOpenFlows flows = new WorldOpenFlows(Minecraft.getInstance(), worldStore.levelSource(world.id()));
                flows.openWorld(ManagedWorldStore.LEVEL_ID, () -> {
                });
            });
        }
    }

    private record HostAttemptContext(
            long generation,
            long startupAttemptId,
            String worldId,
            long runtimeEpoch,
            String hostToken
    ) {
    }
}
