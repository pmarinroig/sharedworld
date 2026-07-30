package link.sharedworld.host;

import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.api.SharedWorldModels.HostAssignmentDto;
import link.sharedworld.api.SharedWorldModels.HostHeartbeatResponseDto;
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
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class SharedWorldHostingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-hosting");
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final long HEARTBEAT_RETRY_INTERVAL_MS = 1_000L;
    private static final int HEARTBEAT_FAILURES_BEFORE_WARNING = 3;
    private static final long HOST_CONFIRM_TIMEOUT_MS = 90_000L;
    private static final long AUTOSAVE_INTERVAL_MS = 5 * 60_000L;
    private static final long JOIN_TARGET_TIMEOUT_MS = 60_000L;

    private final SharedWorldApiClient apiClient;
    private final HostStartupProgressRelayController startupProgressRelay;
    private final ManagedWorldStore worldStore;
    private final SyncAccess syncAccess;
    private final HostRecoveryPersistence hostRecoveryStore;
    private final HostingEvents events;
    private final WorldSnapshotCaptureCoordinator snapshotCaptureCoordinator;
    private final WorldOpenController worldOpenController;
    private final HostWorldBootstrap worldBootstrap;
    private final Executor backgroundExecutor;
    private final Executor mainThreadExecutor;
    private final ClientWorldGate clientWorldGate;
    private final AtomicBoolean startupStarted = new AtomicBoolean();
    // In-flight guards hold the hostSessionGeneration of the operation that
    // claimed them (0 = idle). A completion can only release its own claim, so
    // a stale completion can never wedge or free a newer attempt's slot.
    private final AtomicLong saveInFlight = new AtomicLong();
    private final AtomicBoolean cancelDisconnectIssued = new AtomicBoolean();
    private final AtomicLong heartbeatInFlight = new AtomicLong();
    private volatile Phase phase = Phase.IDLE;
    private volatile String statusMessage = "";
    private volatile String errorMessage;
    private volatile WorldSummaryDto world;
    private volatile SnapshotManifestDto latestManifest;
    private volatile String hostPlayerUuid;
    private volatile boolean startupCancelRequested;
    private volatile boolean cancelLeaseReleaseSettled;
    private volatile String publishedJoinTarget;
    private volatile CoordinatedRelease coordinatedRelease = CoordinatedRelease.NONE;
    private volatile long phaseStartedAt;
    private volatile long lastHeartbeatAt;
    private volatile long lastHeartbeatAttemptAt;
    private volatile int consecutiveHeartbeatFailures;
    private volatile long lastAutosaveAt;
    /** Last settings revision pushed to the live server; -1 = none this session. */
    private volatile long appliedSettingsRevision = -1;
    private volatile long startupAttemptId;
    private volatile long hostSessionGeneration;
    private volatile SharedWorldProgressState progressState;
    private volatile boolean startupProgressRelayActive;
    private volatile long runtimeEpoch;
    private volatile String hostToken;
    private volatile StartupMode startupMode = StartupMode.NORMAL;
    private volatile boolean startupRecoveringLocalCrash;

    public SharedWorldHostingManager(
            SharedWorldApiClient apiClient,
            HostingEvents events,
            Executor backgroundExecutor,
            Executor mainThreadExecutor
    ) {
        this(
                apiClient,
                new ManagedWorldStore(),
                null,
                null,
                new HostStartupProgressRelayController(
                        apiClient::setHostStartupProgress,
                        backgroundExecutor,
                        System::currentTimeMillis
                ),
                new SharedWorldHostRecoveryStore(),
                events,
                backgroundExecutor,
                mainThreadExecutor
        );
    }

    SharedWorldHostingManager(
            SharedWorldApiClient apiClient,
            ManagedWorldStore worldStore,
            SyncAccess syncAccess,
            WorldOpenController worldOpenController,
            HostStartupProgressRelayController startupProgressRelay,
            HostRecoveryPersistence hostRecoveryStore,
            HostingEvents events,
            Executor backgroundExecutor,
            Executor mainThreadExecutor
    ) {
        this(apiClient, worldStore, syncAccess, worldOpenController, startupProgressRelay, hostRecoveryStore, events, backgroundExecutor, mainThreadExecutor, null);
    }

    SharedWorldHostingManager(
            SharedWorldApiClient apiClient,
            ManagedWorldStore worldStore,
            SyncAccess syncAccess,
            WorldOpenController worldOpenController,
            HostStartupProgressRelayController startupProgressRelay,
            HostRecoveryPersistence hostRecoveryStore,
            HostingEvents events,
            Executor backgroundExecutor,
            Executor mainThreadExecutor,
            ClientWorldGate clientWorldGate
    ) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.startupProgressRelay = Objects.requireNonNull(startupProgressRelay, "startupProgressRelay");
        this.worldStore = Objects.requireNonNull(worldStore, "worldStore");
        this.hostRecoveryStore = Objects.requireNonNull(hostRecoveryStore, "hostRecoveryStore");
        this.events = Objects.requireNonNull(events, "events");
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        this.syncAccess = syncAccess != null
                ? syncAccess
                : new WorldSyncAdapter(new WorldSyncCoordinator(apiClient, this.worldStore));
        this.snapshotCaptureCoordinator = new WorldSnapshotCaptureCoordinator(this.worldStore);
        this.worldOpenController = worldOpenController != null
                ? worldOpenController
                : new MinecraftWorldOpenController();
        this.clientWorldGate = clientWorldGate != null
                ? clientWorldGate
                : new MinecraftClientWorldGate();
        this.worldBootstrap = new HostWorldBootstrap(this.syncAccess, this.worldStore, this.worldOpenController);
    }

    /**
     * Seam over the client's "is any world or connection open" state and the
     * forced-disconnect side effect, so cancellation logic stays unit-testable.
     */
    interface ClientWorldGate {
        boolean isWorldOpen();

        /**
         * Disconnecting while the integrated server is still starting (spawn
         * preparation, level not yet attached) can deadlock the client; the
         * cancel tick waits for this before issuing the forced disconnect.
         */
        boolean isSafeToDisconnect();

        void requestDisconnect();

        /**
         * Whether a singleplayer server is open that is NOT the given managed working copy.
         * Guards publish against attaching to a foreign world (e.g. a vanilla singleplayer world
         * the player opened while a stuck attempt was still in OPENING_WORLD). False when no
         * server is open at all — a world that is still opening is not foreign.
         */
        default boolean isForeignServerOpen(Path expectedWorkingCopy) {
            return false;
        }
    }

    private static final class MinecraftClientWorldGate implements ClientWorldGate {
        @Override
        public boolean isWorldOpen() {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.hasSingleplayerServer() || minecraft.level != null || minecraft.getConnection() != null;
        }

        @Override
        public boolean isSafeToDisconnect() {
            return Minecraft.getInstance().level != null;
        }

        @Override
        public void requestDisconnect() {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> link.sharedworld.versioned.ClientCompat.disconnectFromWorld(minecraft));
        }

        @Override
        public boolean isForeignServerOpen(Path expectedWorkingCopy) {
            var server = Minecraft.getInstance().getSingleplayerServer();
            return server != null && !SharedWorldServerIdentity.isServerForWorkingCopy(server, expectedWorkingCopy);
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
            // Re-entry into an attempt that is already running. If the backend
            // just issued a fresh assignment (a new epoch), release it instead
            // of leaking a lease no local attempt will ever heartbeat.
            if (assignment != null
                    && (assignment.runtimeEpoch() != this.runtimeEpoch || !Objects.equals(assignment.hostToken(), this.hostToken))) {
                String staleWorldId = world.id();
                CompletableFuture.runAsync(() -> {
                    try {
                        this.apiClient.releaseHost(staleWorldId, false, assignment.runtimeEpoch(), assignment.hostToken());
                    } catch (Exception exception) {
                        LOGGER.warn("SharedWorld failed to release a duplicate host assignment", exception);
                    }
                }, this.backgroundExecutor);
            }
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
        this.coordinatedRelease = CoordinatedRelease.NONE;
        this.errorMessage = null;
        this.lastHeartbeatAt = 0L;
        this.lastHeartbeatAttemptAt = 0L;
        this.consecutiveHeartbeatFailures = 0;
        this.lastAutosaveAt = 0L;
        this.appliedSettingsRevision = -1;
        this.startupProgressRelayActive = false;
        this.startupStarted.set(true);
        this.saveInFlight.set(0L);
        this.heartbeatInFlight.set(0L);
        this.startupCancelRequested = false;
        this.cancelDisconnectIssued.set(false);
        this.startupProgressRelay.reset();
        long startupAttemptId = this.startupAttemptId + 1L;
        this.startupAttemptId = startupAttemptId;
        this.events.onHostStartupBegan(world.id());
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
        if (this.clientWorldGate.isWorldOpen()) {
            // Disconnecting mid-world-open deadlocks the client; wait for the
            // level to attach before forcing the disconnect.
            if (this.clientWorldGate.isSafeToDisconnect() && this.cancelDisconnectIssued.compareAndSet(false, true)) {
                this.clientWorldGate.requestDisconnect();
            }
            return true;
        }
        if (this.cancelLeaseReleaseSettled) {
            // No world remains and the lease release settled: cancellation is
            // complete. Waiting for the release also stops an immediate re-host
            // from reusing a lease whose release is still in flight.
            resetState();
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
        if (this.phase != Phase.OPENING_WORLD) {
            return false;
        }
        if (this.clientWorldGate.isForeignServerOpen(this.worldStore.workingCopy(this.world.id()))) {
            // The open integrated server is not this attempt's managed working copy — the player's
            // own world must never be published or otherwise touched; fail the attempt instead.
            fail(SharedWorldText.string("screen.sharedworld.hosting_foreign_world_open"), null);
            return true;
        }
        if (!minecraft.hasSingleplayerServer()) {
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
        if (this.coordinatedRelease != CoordinatedRelease.NONE) {
            // While the backend owns finalization, host heartbeats must stop refreshing the lease.
            if (this.coordinatedRelease == CoordinatedRelease.ACTIVE && shouldAttemptHeartbeat(now)) {
                heartbeat(this.publishedJoinTarget, this.phase == Phase.SAVING);
            }
            return;
        }
        if (shouldAttemptHeartbeat(now)) {
            heartbeat(this.publishedJoinTarget, this.phase == Phase.SAVING);
        }
    }

    private void driveRunningLoop(long now) {
        if (this.phase != Phase.RUNNING || this.coordinatedRelease != CoordinatedRelease.NONE) {
            return;
        }
        if (now - this.lastAutosaveAt >= AUTOSAVE_INTERVAL_MS && this.saveInFlight.compareAndSet(0L, this.hostSessionGeneration)) {
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
        // A cancelled startup is no longer an active host session: the lease is
        // already being released, so the cancellation's forced disconnect must
        // not be classified as a graceful host release against it.
        if (this.startupCancelRequested || this.phase == Phase.CANCELLING) {
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
        return this.saveInFlight.get() != 0L;
    }

    /** Canonical UUID of the local host player while a host attempt is active; null otherwise. */
    public String activeHostPlayerUuid() {
        return activeHostSession() == null ? null : this.hostPlayerUuid;
    }

    /**
     * Owner-hosting shortcut: when the owner toggles a member's command permission
     * while hosting that world themselves, apply it to the live server immediately
     * instead of waiting for the next heartbeat to echo it back.
     */
    public void applyLocalMemberPermissionChange(String worldId, String playerUuid, String playerName, boolean canUseCommands) {
        ActiveHostSession session = activeHostSession();
        if (session == null
                || worldId == null
                || !worldId.equals(session.worldId())
                || !SharedWorldDevSessionBridge.isHostingSharedWorld()
                || playerUuid == null
                || playerUuid.isBlank()) {
            return;
        }
        Map<String, MemberCommandGrant> grants = new LinkedHashMap<>(SharedWorldDevSessionBridge.hostedMemberGrants());
        grants.put(
                SharedWorldHostPermissionPolicy.commandGrantKey(playerUuid),
                new MemberCommandGrant(playerUuid, playerName, canUseCommands)
        );
        SharedWorldDevSessionBridge.setHostedMemberGrants(grants);
        this.events.onHostedMemberPermissionsChanged();
    }

    /**
     * Owner-hosting shortcut: when the owner saves world settings while hosting
     * that world themselves, apply them to the live server immediately. The
     * next heartbeat may re-apply the same values, which is harmless.
     */
    public void applyLocalWorldSettingsChange(String worldId, SharedWorldModels.WorldSettingsDto settings) {
        ActiveHostSession session = activeHostSession();
        if (session == null
                || worldId == null
                || !worldId.equals(session.worldId())
                || !SharedWorldDevSessionBridge.isHostingSharedWorld()
                || settings == null) {
            return;
        }
        this.events.onWorldSettingsChanged(settings);
    }

    public void beginCoordinatedRelease() {
        this.coordinatedRelease = CoordinatedRelease.ACTIVE;
    }

    public void markCoordinatedBackendFinalizationStarted() {
        this.coordinatedRelease = CoordinatedRelease.BACKEND_FINALIZING;
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

        // Exactly one lease release. The tick loop finishes the cancellation:
        // it forces the disconnect once that is safe and resets to IDLE only
        // after the world is gone and the release settled.
        this.cancelLeaseReleaseSettled = false;
        releaseHostLeaseAfterStartupCancel(context);
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
            if (!link.sharedworld.versioned.ServerPublishCompat.publish(server, SharedWorldPublishedJoinModePolicy.publishGameMode(), port)) {
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
        if (context == null || !this.heartbeatInFlight.compareAndSet(0L, context.generation())) {
            return;
        }
        this.lastHeartbeatAttemptAt = System.currentTimeMillis();
        String heartbeatJoinTarget = joinTarget == null || joinTarget.isBlank() ? null : joinTarget;
        CompletableFuture.runAsync(() -> {
            try {
                HostHeartbeatResponseDto response = this.apiClient.heartbeatHost(
                        context.worldId(),
                        context.runtimeEpoch(),
                        context.hostToken(),
                        heartbeatJoinTarget
                );
                dispatchToMainThread(() -> onHeartbeatSucceeded(context, response, heartbeatJoinTarget, duringSnapshotUpload));
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
            HostHeartbeatResponseDto runtime,
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
                    this.coordinatedRelease != CoordinatedRelease.NONE
            );
            handleHostAuthorityLost(heartbeatAuthorityLossMessage(duringSnapshotUpload));
            return;
        }
        if ("host-finalizing".equals(runtime.phase())) {
            if (this.coordinatedRelease != CoordinatedRelease.NONE) {
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
        if (this.consecutiveHeartbeatFailures > HEARTBEAT_FAILURES_BEFORE_WARNING && this.phase == Phase.RUNNING) {
            this.statusMessage = HostLifecyclePolicy.runningStatusMessage(this.publishedJoinTarget);
        }
        this.consecutiveHeartbeatFailures = 0;
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
            this.appliedSettingsRevision = -1;
            SharedWorldDevSessionBridge.setHostingSharedWorld(true, this.world.ownerUuid());
            this.events.onHostSessionLive(this.world.id(), this.world.name());
            setPhase(Phase.RUNNING, SharedWorldText.string("screen.sharedworld.hosting_live_at", confirmedJoinTarget));
        }
        applyHeartbeatMemberships(runtime.memberships());
        applyHeartbeatSettings(runtime.settings(), runtime.settingsRevision());
    }

    /**
     * Apply owner-chosen world settings carried by the heartbeat. The revision
     * starts unapplied on every host session, so the first live heartbeat
     * configures the freshly booted server and later bumps reach it within one
     * heartbeat interval.
     */
    private void applyHeartbeatSettings(SharedWorldModels.WorldSettingsDto settings, Long settingsRevision) {
        if (settings == null || settingsRevision == null || !SharedWorldDevSessionBridge.isHostingSharedWorld()) {
            return;
        }
        if (settingsRevision == this.appliedSettingsRevision) {
            return;
        }
        this.appliedSettingsRevision = settingsRevision;
        this.events.onWorldSettingsChanged(settings);
    }

    /**
     * Keep the bridge's member command grants in sync with the heartbeat's
     * membership list, so owner-side permission toggles reach a live host within
     * one heartbeat interval. Guests can only connect after the first live
     * heartbeat published the join target, so seeding here is early enough.
     */
    private void applyHeartbeatMemberships(SharedWorldModels.HostHeartbeatMembershipDto[] memberships) {
        if (memberships == null || !SharedWorldDevSessionBridge.isHostingSharedWorld()) {
            return;
        }
        Map<String, MemberCommandGrant> grants = new LinkedHashMap<>();
        for (SharedWorldModels.HostHeartbeatMembershipDto membership : memberships) {
            if (membership == null || membership.playerUuid() == null || membership.playerUuid().isBlank()) {
                continue;
            }
            grants.put(
                    SharedWorldHostPermissionPolicy.commandGrantKey(membership.playerUuid()),
                    new MemberCommandGrant(membership.playerUuid(), membership.playerName(), membership.canUseCommands())
            );
        }
        if (!grants.equals(SharedWorldDevSessionBridge.hostedMemberGrants())) {
            SharedWorldDevSessionBridge.setHostedMemberGrants(grants);
            this.events.onHostedMemberPermissionsChanged();
        }
    }

    private void handleHeartbeatFailure(HostAttemptContext context, Exception exception, boolean duringSnapshotUpload) {
        if (!isCurrentAttempt(context)) {
            return;
        }
        if (SharedWorldApiClient.isDeletedWorldError(exception)) {
            this.events.onWorldDeleted();
            return;
        }
        if (SharedWorldApiClient.isMembershipRevokedError(exception)) {
            this.events.onMembershipRevoked();
            return;
        }
        if (SharedWorldApiClient.isHostNotActiveError(exception)) {
            handleHostAuthorityLost(heartbeatAuthorityLossMessage(duringSnapshotUpload));
            return;
        }
        this.consecutiveHeartbeatFailures += 1;
        LOGGER.warn(duringSnapshotUpload ? "SharedWorld snapshot upload heartbeat failed" : "SharedWorld heartbeat failed", exception);
        // The lease survives short gaps (90s vs 30s interval), but the host
        // must see that the backend is unreachable instead of a stale
        // "hosting live" message.
        if (this.consecutiveHeartbeatFailures > HEARTBEAT_FAILURES_BEFORE_WARNING && this.phase == Phase.RUNNING) {
            this.statusMessage = SharedWorldText.string("screen.sharedworld.hosting_backend_reconnecting");
        }
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
        this.events.onHostAuthorityLost(session, stage, message);
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
            this.saveInFlight.set(0L);
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
                    // A release that began while this save was in flight owns the
                    // phase now; stomping RELEASING back to RUNNING would tear
                    // down the finalization progress guests are watching.
                    if (this.coordinatedRelease == CoordinatedRelease.NONE) {
                        setPhase(Phase.RUNNING, HostLifecyclePolicy.runningStatusMessage(this.publishedJoinTarget));
                    }
                });
            } catch (Exception exception) {
                if (!isCurrentAttempt(context)) {
                    return;
                }
                if (SharedWorldApiClient.isDeletedWorldError(exception)) {
                    dispatchToMainThread(() -> {
                        if (isCurrentAttempt(context)) {
                            this.events.onWorldDeleted();
                        }
                    });
                    return;
                }
                if (SharedWorldApiClient.isMembershipRevokedError(exception)) {
                    dispatchToMainThread(() -> {
                        if (isCurrentAttempt(context)) {
                            this.events.onMembershipRevoked();
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
                    if (isCurrentAttempt(context) && this.coordinatedRelease == CoordinatedRelease.NONE) {
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
        if (this.events.hasPendingReleaseRecovery(worldId)) {
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

    private void releaseHostLeaseAfterStartupCancel(HostAttemptContext context) {
        String worldId = context == null ? null : context.worldId();
        if (worldId == null) {
            this.cancelLeaseReleaseSettled = true;
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                this.apiClient.releaseHost(worldId, false, context.runtimeEpoch(), context.hostToken());
            } catch (Exception exception) {
                LOGGER.warn("SharedWorld failed to release host after startup cancel", exception);
            }
        }, this.backgroundExecutor).whenComplete((unused, error) ->
                dispatchToMainThread(() -> this.cancelLeaseReleaseSettled = true));
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
        this.heartbeatInFlight.set(0L);
        this.saveInFlight.set(0L);
    }

    private void clearHeartbeatInFlight(HostAttemptContext context) {
        if (context != null) {
            this.heartbeatInFlight.compareAndSet(context.generation(), 0L);
        }
    }

    private void clearSaveInFlight(HostAttemptContext context) {
        if (context != null) {
            this.saveInFlight.compareAndSet(context.generation(), 0L);
        }
    }

    private void dispatchToMainThread(Runnable runnable) {
        this.mainThreadExecutor.execute(runnable);
    }

    private void resetState() {
        String clearedWorldId = this.world == null ? null : this.world.id();
        this.phase = Phase.IDLE;
        this.statusMessage = "";
        this.errorMessage = null;
        this.world = null;
        this.latestManifest = null;
        this.hostPlayerUuid = null;
        this.coordinatedRelease = CoordinatedRelease.NONE;
        this.startupCancelRequested = false;
        this.cancelLeaseReleaseSettled = false;
        this.publishedJoinTarget = null;
        this.lastHeartbeatAt = 0L;
        this.lastHeartbeatAttemptAt = 0L;
        this.consecutiveHeartbeatFailures = 0;
        this.lastAutosaveAt = 0L;
        this.startupStarted.set(false);
        this.saveInFlight.set(0L);
        this.heartbeatInFlight.set(0L);
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
        this.events.onHostStateCleared(clearedWorldId);
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
                this.coordinatedRelease == CoordinatedRelease.BACKEND_FINALIZING,
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
                && (this.phase != Phase.RELEASING || this.coordinatedRelease == CoordinatedRelease.BACKEND_FINALIZING);
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
        if (this.coordinatedRelease == CoordinatedRelease.NONE || progressState == null) {
            return;
        }
        this.progressState = progressState;
        this.statusMessage = progressState.label().getString();
        relayStartupProgressIfNeeded();
    }

    public void clearCoordinatedReleaseProgress() {
        if (this.coordinatedRelease == CoordinatedRelease.NONE && this.phase != Phase.RELEASING && this.phase != Phase.ERROR) {
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

    /**
     * Whether the release coordinator currently owns this host session's shutdown,
     * and whether backend finalization has started. Once BACKEND_FINALIZING is
     * reached, heartbeats stop refreshing the host lease.
     */
    enum CoordinatedRelease {
        NONE,
        ACTIVE,
        BACKEND_FINALIZING
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
            Minecraft.getInstance().execute(() -> link.sharedworld.versioned.WorldOpenCompat.openExistingWorld(
                    Minecraft.getInstance(), worldStore.levelSource(world.id()), ManagedWorldStore.LEVEL_ID));
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
