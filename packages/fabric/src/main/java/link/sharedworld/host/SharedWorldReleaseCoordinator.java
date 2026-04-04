package link.sharedworld.host;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldCoordinatorSupport;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.SnapshotManifestDto;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import link.sharedworld.progress.SharedWorldProgressState;
import link.sharedworld.screen.SharedWorldErrorScreen;
import link.sharedworld.sync.WorldSyncCoordinator;
import link.sharedworld.sync.WorldSyncProgress;
import link.sharedworld.sync.WorldSyncProgressListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class SharedWorldReleaseCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-release");

    private final ReleaseBackend backend;
    private final HostControl hostControl;
    private final ReleasePersistence releaseStore;
    private final SharedWorldCoordinatorSupport.AsyncBridge asyncBridge;
    private final SharedWorldCoordinatorSupport.Clock clock;
    private final SharedWorldCoordinatorSupport.ClientShell clientShell;
    private final SharedWorldCoordinatorSupport.PlayerIdentity playerIdentity;
    private final ReleaseUi releaseUi;
    private final ReleaseStartupRecoveryResolver startupRecoveryResolver;
    private ReleaseState state;
    private TerminalState terminalState;
    private boolean disconnectPassThroughArmed;
    private boolean startupCleanupChecked;

    public SharedWorldReleaseCoordinator(SharedWorldApiClient apiClient, SharedWorldHostingManager hostingManager) {
        this(
                new ReleaseBackend() {
                    @Override
                    public WorldRuntimeStatusDto runtimeStatus(String worldId) throws Exception {
                        return apiClient.runtimeStatus(worldId);
                    }

                    @Override
                    public void beginFinalization(String worldId, long runtimeEpoch, String hostToken) throws Exception {
                        apiClient.beginFinalization(worldId, runtimeEpoch, hostToken);
                    }

                    @Override
                    public void completeFinalization(String worldId, long runtimeEpoch, String hostToken) throws Exception {
                        apiClient.completeFinalization(worldId, runtimeEpoch, hostToken);
                    }

                    @Override
                    public void releaseHost(String worldId, long runtimeEpoch, String hostToken, boolean graceful) throws Exception {
                        apiClient.releaseHost(worldId, graceful, runtimeEpoch, hostToken);
                    }
                },
                new HostControl() {
                    @Override
                    public SharedWorldHostingManager.ActiveHostSession activeHostSession() {
                        return hostingManager.activeHostSession();
                    }

                    @Override
                    public boolean isBackgroundSaveInFlight() {
                        return hostingManager.isBackgroundSaveInFlight();
                    }

                    @Override
                    public void beginCoordinatedRelease() {
                        hostingManager.beginCoordinatedRelease();
                    }

                    @Override
                    public void markCoordinatedBackendFinalizationStarted() {
                        hostingManager.markCoordinatedBackendFinalizationStarted();
                    }

                    @Override
                    public Path finalReleaseWorldDirectory(String worldId) {
                        return hostingManager.finalReleaseWorldDirectory(worldId);
                    }

                    @Override
                    public SnapshotManifestDto uploadFinalReleaseSnapshot(String worldId, Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken, WorldSyncProgressListener progressListener) throws Exception {
                        return hostingManager.uploadFinalReleaseSnapshot(worldId, worldDirectory, hostPlayerUuid, runtimeEpoch, hostToken, progressListener);
                    }

                    @Override
                    public void clearHostedSessionAfterCoordinatedRelease() {
                        hostingManager.clearHostedSessionAfterCoordinatedRelease();
                    }

                    @Override
                    public void clearHostedSessionAfterTerminalExit() {
                        hostingManager.clearHostedSessionAfterTerminalExit();
                    }

                    @Override
                    public boolean isStartupCancelable() {
                        return hostingManager.isStartupCancelable();
                    }
                },
                new SharedWorldReleaseStore(),
                SharedWorldCoordinatorSupport.asyncBridge(SharedWorldClient.ioExecutor(), runnable -> Minecraft.getInstance().execute(runnable)),
                SharedWorldCoordinatorSupport.systemClock(),
                SharedWorldCoordinatorSupport.liveClientShell(),
                SharedWorldCoordinatorSupport.currentPlayerIdentity(),
                worldName -> new link.sharedworld.screen.SharedWorldSavingScreen(
                        new JoinMultiplayerScreen(new TitleScreen()),
                        worldName
                )
        );
    }

    public SharedWorldReleaseCoordinator(
            ReleaseBackend backend,
            HostControl hostControl,
            ReleasePersistence releaseStore,
            SharedWorldCoordinatorSupport.AsyncBridge asyncBridge,
            SharedWorldCoordinatorSupport.Clock clock,
            SharedWorldCoordinatorSupport.ClientShell clientShell,
            SharedWorldCoordinatorSupport.PlayerIdentity playerIdentity,
            ReleaseUi releaseUi
    ) {
        this.backend = backend;
        this.hostControl = hostControl;
        this.releaseStore = releaseStore;
        this.asyncBridge = asyncBridge;
        this.clock = clock;
        this.clientShell = clientShell;
        this.playerIdentity = playerIdentity;
        this.releaseUi = releaseUi;
        this.startupRecoveryResolver = new ReleaseStartupRecoveryResolver();
    }

    /**
     * Responsibility:
     * Drive the single authoritative lifecycle-exit flow for the current client.
     *
     * Preconditions:
     * At most one graceful release or forced terminal exit is active at a time.
     *
     * Postconditions:
     * At most one phase effect is in flight, and lifecycle state advances toward a terminal or
     * explicitly recoverable outcome without raw disconnect side paths.
     *
     * Stale-work rule:
     * Async completions are keyed by releaseAttemptId and ignored once the active attempt changes.
     *
     * Authority source:
     * Persisted release state plus coordinator-owned forced-exit state reconciled against backend runtime status.
     */
    public void tick(Minecraft minecraft) {
        ensurePersistedReleaseRecoveryStarted();
        TerminalState forced = this.terminalState;
        if (forced != null) {
            driveForcedExit(forced);
            return;
        }
        ReleaseState current = this.state;
        if (current == null) {
            return;
        }

        if (shouldMarkLocalDisconnectObserved(current.record)
                && !this.clientShell.hasSingleplayerServer()
                && !this.clientShell.hasLevel()) {
            markLocalDisconnectObserved(current.record);
            return;
        }

        if (current.taskInFlight || SharedWorldReleasePolicy.isClosedTerminal(current.record.phase) || current.record.phase == SharedWorldReleasePhase.ERROR_RECOVERABLE) {
            return;
        }

        switch (current.record.phase) {
            case BEGINNING_BACKEND_FINALIZATION -> scheduleBeginFinalization(current.record.releaseAttemptId);
            case BACKEND_FINALIZING -> transitionPhase(current.record.localDisconnectObserved
                    ? SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT
                    : SharedWorldReleasePolicy.disconnectPhaseFor(current.record));
            case WAITING_FOR_VANILLA_DISCONNECT -> {
            }
            case DISCONNECTING_LOCAL_WORLD -> requestLocalDisconnect(current.record.releaseAttemptId);
            case UPLOADING_FINAL_SNAPSHOT -> {
                if (this.hostControl.isBackgroundSaveInFlight()) {
                    setProgress(SharedWorldReleasePolicy.waitingForSaveProgress());
                    return;
                }
                scheduleUpload(current.record.releaseAttemptId);
            }
            case COMPLETING_BACKEND_FINALIZATION -> scheduleCompleteFinalization(current.record.releaseAttemptId);
            default -> {
            }
        }
    }

    /**
     * Responsibility:
     * Start the durable release protocol for the currently hosted world.
     *
     * Preconditions:
     * The client is the current local host and no other release attempt is active.
     *
     * Postconditions:
     * A persisted release record exists and the blocking saving flow becomes authoritative.
     *
     * Stale-work rule:
     * If a release already exists, it remains authoritative instead of starting a parallel one.
     *
     * Authority source:
     * Active local host session plus persisted release state.
     */
    public ReleaseDisplay beginGracefulDisconnect(Minecraft minecraft) {
        SharedWorldHostingManager.ActiveHostSession session = this.hostControl.activeHostSession();
        LOGGER.info(
                "SharedWorld release diagnostics [beginGracefulDisconnect]: hasSession={}, localServer={}, currentPhase={}",
                session != null,
                this.clientShell.isLocalServer(),
                this.state == null ? (this.terminalState == null ? "none" : this.terminalState.phase) : this.state.record.phase
        );
        if (session == null || !this.clientShell.isLocalServer()) {
            return null;
        }
        if (this.state != null && SharedWorldReleasePolicy.isClosedTerminal(this.state.record.phase)) {
            LOGGER.info(
                    "SharedWorld release diagnostics [beginGracefulDisconnect]: auto-clearing stale closed terminal state {} for {}",
                    this.state.record.phase,
                    this.state.record.worldId
            );
            acknowledgeTerminal();
        }
        if (this.terminalState != null && !this.terminalState.blocking) {
            LOGGER.info(
                    "SharedWorld release diagnostics [beginGracefulDisconnect]: auto-clearing stale terminal notice {} for {}",
                    this.terminalState.phase,
                    this.terminalState.worldId
            );
            acknowledgeTerminal();
        }
        if (this.state != null) {
            LOGGER.info(
                    "SharedWorld release diagnostics [beginGracefulDisconnect]: short-circuiting because active release state {} already exists for {}",
                    this.state.record.phase,
                    this.state.record.worldId
            );
            return new ReleaseDisplay(this.state.record.worldId, this.state.record.worldName);
        }
        if (this.terminalState != null) {
            LOGGER.info(
                    "SharedWorld release diagnostics [beginGracefulDisconnect]: short-circuiting because terminal state {} already exists for {}",
                    this.terminalState.phase,
                    this.terminalState.worldId
            );
            return new ReleaseDisplay(this.terminalState.worldId, this.terminalState.worldName);
        }

        this.hostControl.beginCoordinatedRelease();
        SharedWorldReleaseStore.ReleaseRecord record = newRecordForSession(session, SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION);
        record.vanillaDisconnectExpected = true;
        this.terminalState = null;
        this.state = new ReleaseState(record, SharedWorldReleasePolicy.blockingProgress(Component.translatable("screen.sharedworld.progress.uploading_world")), null);
        persistAndApply(record);
        scheduleBeginFinalization(record.releaseAttemptId);
        return new ReleaseDisplay(record.worldId, record.worldName);
    }

    public boolean consumeDisconnectPassThrough() {
        if (!this.disconnectPassThroughArmed) {
            return false;
        }
        this.disconnectPassThroughArmed = false;
        return true;
    }

    public void onClientDisconnect(Minecraft minecraft) {
        if (this.terminalState != null) {
            observeForcedDisconnect();
            if (shouldKeepSavingScreenVisible()
                    && !(this.clientShell.currentScreen() instanceof link.sharedworld.screen.SharedWorldSavingScreen)
                    && !(this.clientShell.currentScreen() instanceof SharedWorldErrorScreen)) {
                this.clientShell.setScreen(this.releaseUi.savingScreen(this.terminalState.worldName));
            }
            return;
        }
        ReleaseState current = this.state;
        if (current == null) {
            return;
        }
        if (shouldMarkLocalDisconnectObserved(current.record)
                && !this.clientShell.hasSingleplayerServer()
                && !this.clientShell.hasLevel()) {
            markLocalDisconnectObserved(current.record);
            return;
        }
        if (shouldKeepSavingScreenVisible()
                && !(this.clientShell.currentScreen() instanceof link.sharedworld.screen.SharedWorldSavingScreen)
                && !(this.clientShell.currentScreen() instanceof SharedWorldErrorScreen)) {
            this.clientShell.setScreen(this.releaseUi.savingScreen(current.record.worldName));
        }
    }

    public void onClientStopping(Minecraft minecraft) {
        if (this.state != null || this.terminalState != null) {
            return;
        }
        if (this.hostControl.isStartupCancelable()) {
            return;
        }
        beginGracefulDisconnect(minecraft);
    }

    public void onWorldDeleted() {
        ReleaseState current = this.state;
        if (current != null) {
            startTerminalHostExit(SharedWorldReleasePhase.TERMINATED_DELETED);
            return;
        }
        SharedWorldHostingManager.ActiveHostSession hostSession = this.hostControl.activeHostSession();
        if (hostSession != null) {
            beginForcedExit(
                    ForcedExitReason.WORLD_DELETED,
                    hostSession.worldId(),
                    hostSession.worldName(),
                    hostSession.runtimeEpoch(),
                    hostSession.hostToken(),
                    SharedWorldText.string("screen.sharedworld.deleted_detail"),
                    true
            );
            return;
        }
        beginForcedGuestExit(SharedWorldTerminalReasonKind.TERMINATED_DELETED, SharedWorldText.string("screen.sharedworld.deleted_detail"));
    }

    public void onMembershipRevoked() {
        ReleaseState current = this.state;
        if (current != null) {
            SharedWorldReleaseStore.ReleaseRecord updated = current.record.copy();
            updated.pendingTerminalPhase = SharedWorldReleasePhase.TERMINATED_REVOKED;
            persistAndApply(updated);
            return;
        }
        SharedWorldHostingManager.ActiveHostSession hostSession = this.hostControl.activeHostSession();
        if (hostSession != null) {
            beginRevokedHostRelease(hostSession);
            return;
        }
        beginForcedGuestExit(SharedWorldTerminalReasonKind.TERMINATED_REVOKED, SharedWorldText.string("screen.sharedworld.revoked_detail"));
    }

    public boolean shouldKeepSavingScreenVisible() {
        if (this.terminalState != null) {
            return this.terminalState.blocking;
        }
        return this.state != null
                && this.state.record.phase != SharedWorldReleasePhase.COMPLETE
                && this.state.record.phase != SharedWorldReleasePhase.TERMINATED_DELETED
                && this.state.record.phase != SharedWorldReleasePhase.TERMINATED_REVOKED;
    }

    public boolean isActive() {
        return this.state != null || this.terminalState != null;
    }

    public String activeWorldName() {
        if (this.terminalState != null) {
            return this.terminalState.worldName == null ? "" : this.terminalState.worldName;
        }
        ReleaseState current = this.state;
        return current == null || current.record.worldName == null ? "" : current.record.worldName;
    }

    public ReleaseDisplay onClientDisconnectReturnDisplay(Minecraft minecraft) {
        onClientDisconnect(minecraft);
        if (this.terminalState != null) {
            return new ReleaseDisplay(this.terminalState.worldId, this.terminalState.worldName);
        }
        ReleaseState current = this.state;
        return current == null ? null : new ReleaseDisplay(current.record.worldId, current.record.worldName);
    }

    public ReleaseView view() {
        TerminalState forced = this.terminalState;
        if (forced != null) {
            return new ReleaseView(
                    forced.worldId,
                    forced.worldName,
                    forced.phase,
                    forced.progressState,
                    forced.message,
                    forced.reasonKind,
                    forced.reasonKind == SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE,
                    !forced.blocking && canDiscardLocalReleaseState(),
                    forced.blocking
            );
        }
        ReleaseState current = this.state;
        if (current == null) {
            return null;
        }
        SharedWorldTerminalReasonKind reasonKind = current.errorKind != null
                ? current.errorKind
                : ReleaseTerminalStateSupport.reasonKindForPhase(current.record.phase);
        return new ReleaseView(
                current.record.worldId,
                current.record.worldName,
                current.record.phase,
                current.progressState,
                current.errorMessage,
                reasonKind,
                current.record.phase == SharedWorldReleasePhase.ERROR_RECOVERABLE && reasonKind == SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE,
                canDiscardLocalReleaseState(),
                current.record.phase != SharedWorldReleasePhase.ERROR_RECOVERABLE
                        && !SharedWorldReleasePolicy.isClosedTerminal(current.record.phase)
        );
    }

    public boolean retry() {
        if (this.terminalState != null) {
            return false;
        }
        ReleaseState current = this.state;
        if (current == null
                || current.record.phase != SharedWorldReleasePhase.ERROR_RECOVERABLE
                || current.errorKind != SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE) {
            return false;
        }
        current.errorMessage = null;
        current.errorKind = null;
        current.progressState = SharedWorldReleasePolicy.blockingProgress(Component.translatable("screen.sharedworld.progress.uploading_world"));
        SharedWorldReleaseStore.ReleaseRecord updated = current.record.copy();
        updated.phase = SharedWorldReleasePolicy.resumePhaseForRetry(updated);
        persistAndApply(updated);
        scheduleRuntimeReconciliation(current.record.releaseAttemptId);
        return true;
    }

    public boolean canDiscardLocalReleaseState() {
        return !this.clientShell.hasLevel() && !this.clientShell.hasSingleplayerServer();
    }

    public boolean discardLocalReleaseState() {
        if (!canDiscardLocalReleaseState()) {
            return false;
        }
        this.releaseStore.clear();
        this.state = null;
        this.terminalState = null;
        return true;
    }

    public boolean discardPendingReleaseIfMatches(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            return false;
        }
        ensurePersistedReleaseRecoveryStarted();
        if (this.terminalState != null) {
            return false;
        }
        ReleaseState current = this.state;
        if (current != null) {
            if (!equalsIgnoreCase(current.record.worldId, worldId)) {
                return false;
            }
            return discardLocalReleaseState();
        }
        ensurePersistedReleaseRecoveryStarted();
        SharedWorldReleaseStore.ReleaseRecord record = this.releaseStore.loadFor(worldId, this.playerIdentity.currentPlayerUuid());
        if (record == null || !canDiscardLocalReleaseState()) {
            return false;
        }
        return discardLocalReleaseState();
    }

    public boolean abandonPendingReleaseIfMatches(String worldId) throws Exception {
        if (worldId == null || worldId.isBlank()) {
            return false;
        }
        ensurePersistedReleaseRecoveryStarted();
        if (this.terminalState != null || !canDiscardLocalReleaseState()) {
            return false;
        }
        SharedWorldReleaseStore.ReleaseRecord record = pendingReleaseRecordFor(worldId);
        if (record == null) {
            return false;
        }
        try {
            if (record.runtimeEpoch > 0L && record.hostToken != null && !record.hostToken.isBlank()) {
                this.backend.releaseHost(record.worldId, record.runtimeEpoch, record.hostToken, false);
            }
        } catch (Exception exception) {
            Throwable cause = rootCause(exception);
            if (!isSafePendingReleaseDiscardError(cause)) {
                throw exception;
            }
        }
        boolean cleared = discardLocalReleaseState();
        if (cleared && this.hostControl.activeHostSession() != null) {
            this.hostControl.clearHostedSessionAfterTerminalExit();
        }
        return cleared;
    }

    /**
     * Responsibility:
     * Enter the single forced-exit flow after host authority can no longer be trusted.
     *
     * Preconditions:
     * The hosting manager already invalidated its local async work for this host attempt.
     *
     * Postconditions:
     * Disconnect, reconciliation, and terminal UI are now owned by this coordinator alone.
     *
     * Stale-work rule:
     * A newer forced-exit attempt supersedes any older authority-loss reconciliation work.
     *
     * Authority source:
     * Backend runtime reconciliation for the reported host epoch/token.
     */
    public void onHostAuthorityLost(
            SharedWorldHostingManager.ActiveHostSession session,
            HostAuthorityLossStage stage,
            String message
    ) {
        if (this.state != null) {
            failRecoverable(message, SharedWorldTerminalReasonKind.AUTHORITATIVE_LOSS);
            return;
        }
        if (session == null) {
            beginTerminalNotice(null, null, SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE, message);
            return;
        }
        beginForcedExit(
                stage == HostAuthorityLossStage.STARTUP
                        ? ForcedExitReason.HOST_AUTHORITY_LOST_STARTUP
                        : stage == HostAuthorityLossStage.SNAPSHOT_UPLOAD
                        ? ForcedExitReason.HOST_AUTHORITY_LOST_SNAPSHOT_UPLOAD
                        : ForcedExitReason.HOST_AUTHORITY_LOST_LIVE,
                session.worldId(),
                session.worldName(),
                session.runtimeEpoch(),
                session.hostToken(),
                message,
                true
        );
    }

    public void acknowledgeTerminal() {
        if (this.terminalState != null && !this.terminalState.blocking) {
            this.terminalState = null;
            return;
        }
        ReleaseState current = this.state;
        if (current == null || !SharedWorldReleasePolicy.isClosedTerminal(current.record.phase)) {
            return;
        }
        this.state = null;
    }

    public void beginForcedGuestExit(SharedWorldTerminalReasonKind reasonKind, String message) {
        link.sharedworld.SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
        beginForcedGuestExit(
                session == null ? null : session.worldId(),
                session == null ? null : session.worldName(),
                reasonKind,
                message
        );
    }

    public void beginForcedGuestExit(
            String worldId,
            String worldName,
            SharedWorldTerminalReasonKind reasonKind,
            String message
    ) {
        ForcedExitReason reason = switch (reasonKind) {
            case TERMINATED_DELETED -> ForcedExitReason.WORLD_DELETED;
            case TERMINATED_REVOKED -> ForcedExitReason.MEMBERSHIP_REVOKED;
            default -> ForcedExitReason.OBSOLETE_LOCAL_STATE;
        };
        beginForcedExit(reason, worldId, worldName, 0L, null, message, false);
    }

    private void ensurePersistedReleaseRecoveryStarted() {
        if (this.startupCleanupChecked || this.clientShell.hasLevel() || this.clientShell.hasSingleplayerServer()) {
            return;
        }
        this.startupCleanupChecked = true;
        if (this.state != null || this.terminalState != null) {
            return;
        }
        SharedWorldReleaseStore.ReleaseRecord record = this.releaseStore.load();
        if (record == null) {
            return;
        }
        this.asyncBridge.supply(
                () -> new StartupRecoveryResolution(this.startupRecoveryResolver.resolve(this.backend, record).clearPersistedRecord()),
                (resolution, error) -> {
                    if (this.state != null || this.terminalState != null) {
                        return;
                    }
                    SharedWorldReleaseStore.ReleaseRecord latest = this.releaseStore.loadFor(record.worldId, this.playerIdentity.currentPlayerUuid());
                    if (latest == null || latest.releaseAttemptId != record.releaseAttemptId) {
                        return;
                    }
                    if (resolution != null && resolution.clearPersistedRecord()) {
                        this.releaseStore.clear();
                        return;
                    }
                    activatePersistedRecord(latest);
                }
        );
    }

    private void activatePersistedRecord(SharedWorldReleaseStore.ReleaseRecord record) {
        this.terminalState = null;
        this.state = new ReleaseState(
                record.copy(),
                SharedWorldReleasePolicy.blockingProgress(Component.translatable("screen.sharedworld.progress.uploading_world")),
                null
        );
        scheduleRuntimeReconciliation(this.state.record.releaseAttemptId);
    }

    /**
     * Responsibility:
     * Own every forced lifecycle exit once graceful release is not the active protocol.
     *
     * Preconditions:
     * The caller has already classified the trigger and will no longer disconnect the client directly.
     *
     * Postconditions:
     * Disconnect, reconciliation, and terminal UI now flow through this coordinator only.
     *
     * Stale-work rule:
     * A newer forced-exit attempt supersedes older forced-exit callbacks and classification work.
     *
     * Authority source:
     * Coordinator-owned terminal state plus backend runtime reconciliation when authority is in doubt.
     */
    private void beginForcedExit(
            ForcedExitReason reason,
            String worldId,
            String worldName,
            long runtimeEpoch,
            String hostToken,
            String message,
            boolean hostSessionOwnedLocally
    ) {
        if (this.state != null && reason != ForcedExitReason.WORLD_DELETED && reason != ForcedExitReason.MEMBERSHIP_REVOKED) {
            return;
        }
        this.releaseStore.clear();
        this.state = null;
        this.clientShell.clearPlaySession();
        long attemptId = nextTerminalAttemptId();
        this.terminalState = new TerminalState(
                attemptId,
                worldId,
                worldName,
                SharedWorldReleasePhase.FORCED_DISCONNECTING,
                SharedWorldReleasePolicy.blockingProgress(Component.translatable("screen.sharedworld.progress.uploading_world")),
                message,
                null,
                false,
                true,
                false,
                runtimeEpoch,
                hostToken,
                hostSessionOwnedLocally
        );
        if (reason.requiresRuntimeConfirmation()) {
            scheduleForcedExitReconciliation(attemptId, reason, message);
        } else {
            applyForcedExitResolution(attemptId, ReleaseTerminalStateSupport.resolutionFor(reason, message));
        }
        driveForcedExit(this.terminalState);
    }

    private void beginTerminalNotice(
            String worldId,
            String worldName,
            SharedWorldTerminalReasonKind reasonKind,
            String message
    ) {
        this.releaseStore.clear();
        this.state = null;
        this.clientShell.clearPlaySession();
        this.terminalState = new TerminalState(
                nextTerminalAttemptId(),
                worldId,
                worldName,
                ReleaseTerminalStateSupport.phaseFor(reasonKind),
                SharedWorldReleasePolicy.blockingProgress(Component.translatable("screen.sharedworld.progress.uploading_world")),
                message,
                reasonKind,
                true,
                false,
                false,
                0L,
                null,
                false
        );
    }

    private void driveForcedExit(TerminalState current) {
        if (current.phase == SharedWorldReleasePhase.FORCED_DISCONNECTING
                && !current.localDisconnectObserved
                && !this.clientShell.hasSingleplayerServer()
                && !this.clientShell.hasLevel()) {
            observeForcedDisconnect();
            return;
        }
        if (current.phase == SharedWorldReleasePhase.FORCED_DISCONNECTING && !current.disconnectRequested) {
            requestForcedDisconnect(current.attemptId);
        }
    }

    private void requestForcedDisconnect(long attemptId) {
        TerminalState current = this.terminalState;
        if (current == null || current.attemptId != attemptId || current.disconnectRequested) {
            return;
        }
        current.disconnectRequested = true;
        if (!this.clientShell.hasSingleplayerServer() && !this.clientShell.hasLevel()) {
            observeForcedDisconnect();
            return;
        }
        this.disconnectPassThroughArmed = true;
        this.clientShell.disconnectFromWorld();
        if (!this.clientShell.hasSingleplayerServer() && !this.clientShell.hasLevel()) {
            observeForcedDisconnect();
        }
    }

    private void observeForcedDisconnect() {
        TerminalState current = this.terminalState;
        if (current == null || current.localDisconnectObserved) {
            return;
        }
        current.localDisconnectObserved = true;
        clearTerminalOwnedHostSession(current);
        if (current.reasonResolved) {
            current.blocking = false;
            current.phase = ReleaseTerminalStateSupport.phaseFor(current.reasonKind);
        }
    }

    private void scheduleForcedExitReconciliation(long attemptId, ForcedExitReason reason, String fallbackMessage) {
        TerminalState current = this.terminalState;
        if (current == null || current.attemptId != attemptId || current.taskInFlight) {
            return;
        }
        current.taskInFlight = true;
        this.asyncBridge.supply(
                () -> this.backend.runtimeStatus(current.worldId),
                (runtime, error) -> {
                    TerminalState latest = this.terminalState;
                    if (latest == null || latest.attemptId != attemptId) {
                        return;
                    }
                    latest.taskInFlight = false;
                    if (error != null) {
                        Throwable cause = rootCause(error);
                        if (SharedWorldApiClient.isDeletedWorldError(cause)) {
                            applyForcedExitResolution(attemptId, new ReleaseTerminalStateSupport.Resolution(
                                    SharedWorldTerminalReasonKind.TERMINATED_DELETED,
                                    SharedWorldText.string("screen.sharedworld.deleted_detail")
                            ));
                            return;
                        }
                        if (SharedWorldApiClient.isMembershipRevokedError(cause)) {
                            applyForcedExitResolution(attemptId, new ReleaseTerminalStateSupport.Resolution(
                                    SharedWorldTerminalReasonKind.TERMINATED_REVOKED,
                                    SharedWorldText.string("screen.sharedworld.revoked_detail")
                            ));
                            return;
                        }
                        applyForcedExitResolution(attemptId, new ReleaseTerminalStateSupport.Resolution(
                                SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE,
                                fallbackMessage == null || fallbackMessage.isBlank()
                                        ? SharedWorldText.string("screen.sharedworld.release_backend_authority_unverified")
                                        : fallbackMessage
                        ));
                        return;
                    }
                    SharedWorldTerminalReasonKind kind = ReleaseTerminalStateSupport.isRuntimeStillOwnedByHost(
                            this.playerIdentity.currentPlayerUuid(),
                            latest.runtimeEpoch,
                            runtime
                    )
                            ? SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE
                            : SharedWorldTerminalReasonKind.AUTHORITATIVE_LOSS;
                    applyForcedExitResolution(attemptId, new ReleaseTerminalStateSupport.Resolution(kind, fallbackMessage));
                }
        );
    }

    private void applyForcedExitResolution(long attemptId, ReleaseTerminalStateSupport.Resolution resolution) {
        TerminalState current = this.terminalState;
        if (current == null || current.attemptId != attemptId) {
            return;
        }
        current.reasonKind = resolution.reasonKind();
        current.message = resolution.message();
        current.reasonResolved = true;
        if (current.localDisconnectObserved || (!this.clientShell.hasSingleplayerServer() && !this.clientShell.hasLevel())) {
            current.blocking = false;
            current.phase = ReleaseTerminalStateSupport.phaseFor(resolution.reasonKind());
            clearTerminalOwnedHostSession(current);
        }
    }

    private void clearTerminalOwnedHostSession(TerminalState current) {
        if (!current.hostSessionOwnedLocally || current.hostSessionCleared) {
            return;
        }
        current.hostSessionCleared = true;
        this.hostControl.clearHostedSessionAfterTerminalExit();
    }

    private void startTerminalHostExit(SharedWorldReleasePhase terminalPhase) {
        ReleaseState current = this.state;
        if (current != null) {
            SharedWorldReleaseStore.ReleaseRecord updated = current.record.copy();
            updated.releaseAttemptId = nextAttemptId(updated.releaseAttemptId);
            updated.pendingTerminalPhase = terminalPhase;
            if (updated.localDisconnectObserved) {
                updated.phase = terminalPhase;
            } else {
                updated.phase = SharedWorldReleasePolicy.disconnectPhaseFor(updated);
            }
            persistAndApply(updated);
            if (updated.phase == SharedWorldReleasePhase.DISCONNECTING_LOCAL_WORLD) {
                requestLocalDisconnect(updated.releaseAttemptId);
            }
            return;
        }

        SharedWorldHostingManager.ActiveHostSession session = this.hostControl.activeHostSession();
        if (session == null) {
            return;
        }
        this.hostControl.beginCoordinatedRelease();
        SharedWorldReleaseStore.ReleaseRecord record = newRecordForSession(session, SharedWorldReleasePhase.DISCONNECTING_LOCAL_WORLD);
        record.pendingTerminalPhase = terminalPhase;
        this.state = new ReleaseState(record, SharedWorldReleasePolicy.blockingProgress(Component.translatable("screen.sharedworld.progress.uploading_world"), "release_finishing"), null);
        persistAndApply(record);
        requestLocalDisconnect(record.releaseAttemptId);
    }

    private void beginRevokedHostRelease(SharedWorldHostingManager.ActiveHostSession session) {
        this.hostControl.beginCoordinatedRelease();
        SharedWorldReleaseStore.ReleaseRecord record = newRecordForSession(session, SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION);
        record.pendingTerminalPhase = SharedWorldReleasePhase.TERMINATED_REVOKED;
        this.terminalState = null;
        this.state = new ReleaseState(record, SharedWorldReleasePolicy.blockingProgress(Component.translatable("screen.sharedworld.progress.uploading_world")), null);
        persistAndApply(record);
        scheduleBeginFinalization(record.releaseAttemptId);
    }

    private void markLocalDisconnectObserved(SharedWorldReleaseStore.ReleaseRecord record) {
        SharedWorldReleaseStore.ReleaseRecord updated = record.copy();
        updated.localDisconnectObserved = true;
        updated.phase = updated.pendingTerminalPhase != null && SharedWorldReleasePolicy.shouldTransitionDirectlyToTerminal(updated)
                ? updated.pendingTerminalPhase
                : updated.backendFinalizationStarted
                ? SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT
                : SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION;
        if (updated.phase == SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT) {
            LOGGER.info(
                    "SharedWorld release diagnostics [transition]: moving {} to {} after local disconnect",
                    updated.worldId,
                    updated.phase
            );
        }
        persistAndApply(updated);
        if (SharedWorldReleasePolicy.isClosedTerminal(updated.phase)) {
            this.hostControl.clearHostedSessionAfterCoordinatedRelease();
        }
    }

    private SharedWorldReleaseStore.ReleaseRecord newRecordForSession(
            SharedWorldHostingManager.ActiveHostSession session,
            SharedWorldReleasePhase phase
    ) {
        SharedWorldReleaseStore.ReleaseRecord record = new SharedWorldReleaseStore.ReleaseRecord();
        record.worldId = session.worldId();
        record.worldName = session.worldName();
        record.hostUuid = this.playerIdentity.currentPlayerUuid();
        record.runtimeEpoch = session.runtimeEpoch();
        record.hostToken = session.hostToken();
        record.releaseAttemptId = this.clock.nowMillis();
        record.phase = phase;
        record.backendFinalizationStarted = false;
        record.localDisconnectObserved = false;
        record.vanillaDisconnectExpected = false;
        record.finalUploadCompleted = false;
        record.backendFinalizationCompleted = false;
        record.pendingTerminalPhase = null;
        record.createdAt = Instant.ofEpochMilli(this.clock.nowMillis()).toString();
        record.updatedAt = record.createdAt;
        return record;
    }

    private void scheduleRuntimeReconciliation(long attemptId) {
        ReleaseState current = this.state;
        if (current == null || current.taskInFlight) {
            return;
        }
        current.taskInFlight = true;
        this.asyncBridge.supply(
                () -> this.backend.runtimeStatus(current.record.worldId),
                (runtime, error) -> {
                    ReleaseState latest = this.state;
                    if (shouldIgnoreAsyncCompletion(latest, attemptId)) {
                        return;
                    }
                    latest.taskInFlight = false;
                    if (error != null) {
                        Throwable cause = rootCause(error);
                        if (SharedWorldApiClient.isDeletedWorldError(cause)) {
                            transitionTerminal(SharedWorldReleasePhase.TERMINATED_DELETED, null);
                            return;
                        }
                        failRecoverable(SharedWorldText.string("screen.sharedworld.release_resume_failed", SharedWorldApiClient.friendlyErrorMessage(cause)), SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE);
                        return;
                    }
                    applyRuntimeReconciliation(latest.record, runtime);
                }
        );
    }

    /**
     * Responsibility:
     * Reconcile persisted release state against the authoritative backend runtime.
     *
     * Preconditions:
     * The provided record is the current persisted release attempt.
     *
     * Postconditions:
     * The release becomes resumable, terminal, obsolete, or recoverably blocked with no ambiguous state.
     *
     * Stale-work rule:
     * Reconciliation may discard obsolete local state, but it never revives an older release attempt.
     *
     * Authority source:
     * Backend runtime status, not local assumptions.
     */
    private void applyRuntimeReconciliation(SharedWorldReleaseStore.ReleaseRecord record, WorldRuntimeStatusDto runtime) {
        ReleaseRuntimeReconciliation.Outcome outcome = ReleaseRuntimeReconciliation.reconcile(
                record,
                runtime,
                this.clientShell.hasLevel() || this.clientShell.hasSingleplayerServer()
        );
        if (outcome.clearPersistedRecord()) {
            this.releaseStore.clear();
            this.state = null;
            if (outcome.obsoleteRecordMessage() != null && !outcome.obsoleteRecordMessage().isBlank()) {
                beginTerminalNotice(record.worldId, record.worldName, SharedWorldTerminalReasonKind.OBSOLETE_LOCAL_STATE, outcome.obsoleteRecordMessage());
            }
            return;
        }
        persistAndApply(outcome.updatedRecord());
        if (outcome.recoverableError() != null) {
            if (outcome.errorKind() == SharedWorldTerminalReasonKind.AUTHORITATIVE_LOSS && runtime != null) {
                LOGGER.warn(
                        "SharedWorld release diagnostics [reconcile]: backend moved on during release for {} (runtimePhase={}, runtimeEpoch={}, backendFinalizationStarted={}, localDisconnectObserved={}, finalUploadCompleted={}, authorityLossStage={})",
                        record.worldId,
                        runtime.phase(),
                        runtime.runtimeEpoch(),
                        record.backendFinalizationStarted,
                        record.localDisconnectObserved,
                        record.finalUploadCompleted,
                        outcome.authorityLossStage()
                );
            }
            this.state.errorMessage = outcome.recoverableError();
            this.state.errorKind = outcome.errorKind();
            return;
        }
        if (outcome.clearHostedSession()) {
            this.hostControl.clearHostedSessionAfterCoordinatedRelease();
        }
    }

    private void scheduleBeginFinalization(long attemptId) {
        ReleaseState current = this.state;
        if (current == null || current.taskInFlight) {
            return;
        }
        current.taskInFlight = true;
        this.asyncBridge.supply(() -> {
            WorldRuntimeStatusDto runtime = this.backend.runtimeStatus(current.record.worldId);
            if (runtime != null
                    && "host-finalizing".equals(runtime.phase())
                    && runtime.runtimeEpoch() == current.record.runtimeEpoch) {
                return runtime;
            }
            this.backend.beginFinalization(current.record.worldId, current.record.runtimeEpoch, current.record.hostToken);
            return this.backend.runtimeStatus(current.record.worldId);
        }, (runtime, error) -> {
            ReleaseState latest = this.state;
            if (shouldIgnoreAsyncCompletion(latest, attemptId)) {
                return;
            }
            latest.taskInFlight = false;
            if (error != null) {
                Throwable cause = rootCause(error);
                if (SharedWorldApiClient.isDeletedWorldError(cause)) {
                    transitionTerminal(SharedWorldReleasePhase.TERMINATED_DELETED, null);
                    return;
                }
                if (SharedWorldApiClient.isHostNotActiveError(cause)) {
                    failRecoverable(SharedWorldText.string("screen.sharedworld.release_lost_authority_begin"), SharedWorldTerminalReasonKind.AUTHORITATIVE_LOSS);
                    return;
                }
                failRecoverable(SharedWorldText.string("screen.sharedworld.release_begin_finalization_failed", SharedWorldApiClient.friendlyErrorMessage(cause)), SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE);
                return;
            }
            this.hostControl.markCoordinatedBackendFinalizationStarted();
            SharedWorldReleaseStore.ReleaseRecord updated = latest.record.copy();
            updated.backendFinalizationStarted = true;
            updated.phase = updated.localDisconnectObserved
                    ? SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT
                    : SharedWorldReleasePhase.BACKEND_FINALIZING;
            if (updated.phase == SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT) {
                LOGGER.info(
                        "SharedWorld release diagnostics [transition]: moving {} to {} after backend finalization start",
                        updated.worldId,
                        updated.phase
                );
            }
            persistAndApply(updated);
            applyRuntimeReconciliation(updated, runtime);
        });
    }

    private void requestLocalDisconnect(long attemptId) {
        ReleaseState current = this.state;
        if (current == null || current.localDisconnectRequested) {
            return;
        }
        current.localDisconnectRequested = true;
        if (!this.clientShell.hasSingleplayerServer() && !this.clientShell.hasLevel()) {
            markLocalDisconnectObserved(current.record);
            return;
        }
        this.disconnectPassThroughArmed = true;
        this.clientShell.disconnectFromWorld();
        if (!this.clientShell.hasSingleplayerServer() && !this.clientShell.hasLevel()) {
            ReleaseState latest = this.state;
            if (latest != null && latest.record.releaseAttemptId == attemptId && latest.record.phase == SharedWorldReleasePhase.DISCONNECTING_LOCAL_WORLD) {
                markLocalDisconnectObserved(latest.record);
            }
        }
    }

    private void scheduleUpload(long attemptId) {
        ReleaseState current = this.state;
        if (current == null || current.taskInFlight) {
            return;
        }
        Path uploadDirectory = uploadPathFor(current.record);
        if (uploadDirectory == null || !Files.exists(uploadDirectory)) {
            failRecoverable(SharedWorldText.string("screen.sharedworld.release_missing_staging_data"), SharedWorldTerminalReasonKind.UNEXPECTED_LOCAL_INVARIANT_BREACH);
            return;
        }
        current.taskInFlight = true;
        this.asyncBridge.supply(
                () -> this.hostControl.uploadFinalReleaseSnapshot(
                        current.record.worldId,
                        uploadDirectory,
                        canonicalUploadHostUuid(current.record.hostUuid),
                        current.record.runtimeEpoch,
                        current.record.hostToken,
                        this::applyUploadProgress
                ),
                (manifest, error) -> {
                    ReleaseState latest = this.state;
                    if (shouldIgnoreAsyncCompletion(latest, attemptId)) {
                        return;
                    }
                    latest.taskInFlight = false;
                    if (error != null) {
                        Throwable cause = rootCause(error);
                        if (SharedWorldApiClient.isDeletedWorldError(cause)) {
                            transitionTerminal(SharedWorldReleasePhase.TERMINATED_DELETED, null);
                            return;
                        }
                        if (SharedWorldApiClient.isMembershipRevokedError(cause)) {
                            transitionTerminal(SharedWorldReleasePhase.TERMINATED_REVOKED, null);
                            return;
                        }
                        failRecoverable(SharedWorldText.string("screen.sharedworld.release_upload_snapshot_failed", SharedWorldApiClient.friendlyErrorMessage(cause)), SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE);
                        return;
                    }
                    SharedWorldReleaseStore.ReleaseRecord updated = latest.record.copy();
                    updated.finalUploadCompleted = true;
                    updated.phase = SharedWorldReleasePhase.COMPLETING_BACKEND_FINALIZATION;
                    persistAndApply(updated);
                }
        );
    }

    private void scheduleCompleteFinalization(long attemptId) {
        ReleaseState current = this.state;
        if (current == null || current.taskInFlight) {
            return;
        }
        current.taskInFlight = true;
        this.asyncBridge.run(() -> {
            WorldRuntimeStatusDto runtime = this.backend.runtimeStatus(current.record.worldId);
            SharedWorldReleasePolicy.ResumeDecision decision = SharedWorldReleasePolicy.reconcile(current.record, runtime);
            if (decision.backendFinalizationCompleted()) {
                return;
            }
            this.backend.completeFinalization(current.record.worldId, current.record.runtimeEpoch, current.record.hostToken);
        }, error -> {
            ReleaseState latest = this.state;
            if (shouldIgnoreAsyncCompletion(latest, attemptId)) {
                return;
            }
            latest.taskInFlight = false;
            if (error != null) {
                Throwable cause = rootCause(error);
                if (SharedWorldApiClient.isDeletedWorldError(cause)) {
                    transitionTerminal(SharedWorldReleasePhase.TERMINATED_DELETED, null);
                    return;
                }
                if (SharedWorldApiClient.isHostNotActiveError(cause)) {
                    failRecoverable(SharedWorldText.string("screen.sharedworld.release_lost_authority_complete"), SharedWorldTerminalReasonKind.AUTHORITATIVE_LOSS);
                    return;
                }
                failRecoverable(SharedWorldText.string("screen.sharedworld.release_complete_finalization_failed", SharedWorldApiClient.friendlyErrorMessage(cause)), SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE);
                return;
            }
            SharedWorldReleaseStore.ReleaseRecord updated = latest.record.copy();
            updated.backendFinalizationCompleted = true;
            updated.phase = updated.pendingTerminalPhase == null
                    ? SharedWorldReleasePhase.COMPLETE
                    : updated.pendingTerminalPhase;
            persistAndApply(updated);
            if (SharedWorldReleasePolicy.isClosedTerminal(updated.phase)) {
                this.hostControl.clearHostedSessionAfterCoordinatedRelease();
            }
        });
    }

    private void transitionPhase(SharedWorldReleasePhase phase) {
        ReleaseState current = this.state;
        if (current == null) {
            return;
        }
        SharedWorldReleaseStore.ReleaseRecord updated = current.record.copy();
        updated.phase = phase;
        persistAndApply(updated);
    }

    private void transitionTerminal(SharedWorldReleasePhase phase, String errorMessage) {
        ReleaseState current = this.state;
        if (current == null) {
            return;
        }
        SharedWorldReleaseStore.ReleaseRecord updated = current.record.copy();
        updated.phase = phase;
        updated.pendingTerminalPhase = null;
        current.record = updated;
        current.errorMessage = errorMessage;
        current.errorKind = null;
        current.progressState = SharedWorldReleasePolicy.blockingProgress(Component.translatable("screen.sharedworld.progress.uploading_world"), "release_finishing");
        this.releaseStore.clear();
        this.hostControl.clearHostedSessionAfterCoordinatedRelease();
    }

    private void failRecoverable(String errorMessage, SharedWorldTerminalReasonKind errorKind) {
        ReleaseState current = this.state;
        if (current == null) {
            return;
        }
        SharedWorldReleaseStore.ReleaseRecord updated = current.record.copy();
        updated.phase = SharedWorldReleasePhase.ERROR_RECOVERABLE;
        persistAndApply(updated);
        current.errorMessage = errorMessage;
        current.errorKind = errorKind;
    }

    private void persistAndApply(SharedWorldReleaseStore.ReleaseRecord updated) {
        ReleaseState current = this.state;
        if (current == null) {
            return;
        }
        updated.updatedAt = Instant.ofEpochMilli(this.clock.nowMillis()).toString();
        current.record = updated;
        current.progressState = SharedWorldReleasePolicy.progressFor(updated.phase, current.progressState);
        try {
            if (SharedWorldReleasePolicy.isClosedTerminal(updated.phase)) {
                this.releaseStore.clear();
            } else {
                this.releaseStore.save(updated);
            }
        } catch (IOException exception) {
            current.errorMessage = SharedWorldText.string("screen.sharedworld.release_persist_failed", exception.getMessage());
            current.errorKind = SharedWorldTerminalReasonKind.UNEXPECTED_LOCAL_INVARIANT_BREACH;
            current.record.phase = SharedWorldReleasePhase.ERROR_RECOVERABLE;
        }
    }

    private long nextAttemptId(long currentAttemptId) {
        return Math.max(this.clock.nowMillis(), currentAttemptId + 1L);
    }

    private long nextTerminalAttemptId() {
        long currentAttemptId = 0L;
        if (this.state != null) {
            currentAttemptId = Math.max(currentAttemptId, this.state.record.releaseAttemptId);
        }
        if (this.terminalState != null) {
            currentAttemptId = Math.max(currentAttemptId, this.terminalState.attemptId);
        }
        return nextAttemptId(currentAttemptId);
    }

    private void setProgress(SharedWorldProgressState progressState) {
        if (this.state != null) {
            this.state.progressState = progressState;
        }
    }

    private void applyUploadProgress(WorldSyncProgress progress) {
        ReleaseState current = this.state;
        if (current == null) {
            return;
        }
        current.progressState = switch (progress.stage()) {
            case WorldSyncCoordinator.STAGE_UPLOADING_CHANGED_FILES -> SharedWorldProgressState.determinate(
                    Component.translatable("screen.sharedworld.saving_title"),
                    Component.translatable("screen.sharedworld.progress.uploading_world"),
                    "release_uploading",
                    progress.fraction(),
                    current.progressState,
                    progress.bytesDone(),
                    progress.bytesTotal()
            );
            case WorldSyncCoordinator.STAGE_FINALIZING_SNAPSHOT -> SharedWorldReleasePolicy.blockingProgress(Component.translatable("screen.sharedworld.progress.uploading_world"), "release_finishing");
            default -> SharedWorldReleasePolicy.blockingProgress(Component.translatable("screen.sharedworld.progress.uploading_world"), "release_preparing");
        };
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static boolean shouldMarkLocalDisconnectObserved(SharedWorldReleaseStore.ReleaseRecord record) {
        return !record.localDisconnectObserved
                && record.phase != SharedWorldReleasePhase.ERROR_RECOVERABLE
                && !SharedWorldReleasePolicy.isClosedTerminal(record.phase);
    }

    private Path uploadPathFor(SharedWorldReleaseStore.ReleaseRecord record) {
        return this.hostControl.finalReleaseWorldDirectory(record.worldId);
    }

    private static String canonicalUploadHostUuid(String hostUuid) {
        if (hostUuid == null || hostUuid.isBlank() || hostUuid.indexOf('-') >= 0 || hostUuid.length() != 32) {
            return hostUuid;
        }
        return hostUuid.substring(0, 8)
                + "-"
                + hostUuid.substring(8, 12)
                + "-"
                + hostUuid.substring(12, 16)
                + "-"
                + hostUuid.substring(16, 20)
                + "-"
                + hostUuid.substring(20);
    }

    private static boolean shouldIgnoreAsyncCompletion(ReleaseState latest, long attemptId) {
        return latest == null
                || latest.record.releaseAttemptId != attemptId
                || SharedWorldReleasePolicy.isClosedTerminal(latest.record.phase)
                || latest.record.phase == SharedWorldReleasePhase.ERROR_RECOVERABLE;
    }

    private SharedWorldReleaseStore.ReleaseRecord pendingReleaseRecordFor(String worldId) {
        ReleaseState current = this.state;
        if (current != null) {
            return equalsIgnoreCase(current.record.worldId, worldId) ? current.record.copy() : null;
        }
        return this.releaseStore.loadFor(worldId, this.playerIdentity.currentPlayerUuid());
    }

    private static boolean isSafePendingReleaseDiscardError(Throwable error) {
        return SharedWorldApiClient.isDeletedWorldError(error)
                || SharedWorldApiClient.isMembershipRevokedError(error)
                || SharedWorldApiClient.isHostNotActiveError(error)
                || "not_finalizing".equals(SharedWorldApiClient.errorCode(error));
    }

    static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    public record ReleaseDisplay(String worldId, String worldName) {
    }

    public record ReleaseView(
            String worldId,
            String worldName,
            SharedWorldReleasePhase phase,
            SharedWorldProgressState progressState,
            String errorMessage,
            SharedWorldTerminalReasonKind errorKind,
            boolean canRetry,
            boolean canDiscardLocalState,
            boolean blocking
    ) {
    }

    private record StartupRecoveryResolution(boolean clearPersistedRecord) {
    }

    private static final class ReleaseState {
        private SharedWorldReleaseStore.ReleaseRecord record;
        private SharedWorldProgressState progressState;
        private String errorMessage;
        private SharedWorldTerminalReasonKind errorKind;
        private boolean taskInFlight;
        private boolean localDisconnectRequested;

        private ReleaseState(
                SharedWorldReleaseStore.ReleaseRecord record,
                SharedWorldProgressState progressState,
                String errorMessage
        ) {
            this.record = record;
            this.progressState = progressState;
            this.errorMessage = errorMessage;
        }
    }

    private static final class TerminalState {
        private final long attemptId;
        private final String worldId;
        private final String worldName;
        private SharedWorldReleasePhase phase;
        private SharedWorldProgressState progressState;
        private String message;
        private SharedWorldTerminalReasonKind reasonKind;
        private boolean reasonResolved;
        private boolean blocking;
        private boolean localDisconnectObserved;
        private boolean disconnectRequested;
        private boolean taskInFlight;
        private final long runtimeEpoch;
        private final String hostToken;
        private final boolean hostSessionOwnedLocally;
        private boolean hostSessionCleared;

        private TerminalState(
                long attemptId,
                String worldId,
                String worldName,
                SharedWorldReleasePhase phase,
                SharedWorldProgressState progressState,
                String message,
                SharedWorldTerminalReasonKind reasonKind,
                boolean reasonResolved,
                boolean blocking,
                boolean localDisconnectObserved,
                long runtimeEpoch,
                String hostToken,
                boolean hostSessionOwnedLocally
        ) {
            this.attemptId = attemptId;
            this.worldId = worldId;
            this.worldName = worldName;
            this.phase = phase;
            this.progressState = progressState;
            this.message = message;
            this.reasonKind = reasonKind;
            this.reasonResolved = reasonResolved;
            this.blocking = blocking;
            this.localDisconnectObserved = localDisconnectObserved;
            this.runtimeEpoch = runtimeEpoch;
            this.hostToken = hostToken;
            this.hostSessionOwnedLocally = hostSessionOwnedLocally;
        }
    }

    enum ForcedExitReason {
        HOST_AUTHORITY_LOST_STARTUP,
        HOST_AUTHORITY_LOST_LIVE,
        HOST_AUTHORITY_LOST_SNAPSHOT_UPLOAD,
        MEMBERSHIP_REVOKED,
        WORLD_DELETED,
        OBSOLETE_LOCAL_STATE;

        private boolean requiresRuntimeConfirmation() {
            return this == HOST_AUTHORITY_LOST_STARTUP
                    || this == HOST_AUTHORITY_LOST_LIVE
                    || this == HOST_AUTHORITY_LOST_SNAPSHOT_UPLOAD;
        }
    }

    public enum HostAuthorityLossStage {
        STARTUP,
        LIVE,
        SNAPSHOT_UPLOAD
    }

    public interface ReleaseBackend {
        WorldRuntimeStatusDto runtimeStatus(String worldId) throws Exception;

        void beginFinalization(String worldId, long runtimeEpoch, String hostToken) throws Exception;

        void completeFinalization(String worldId, long runtimeEpoch, String hostToken) throws Exception;

        void releaseHost(String worldId, long runtimeEpoch, String hostToken, boolean graceful) throws Exception;
    }

    public interface HostControl {
        SharedWorldHostingManager.ActiveHostSession activeHostSession();

        boolean isBackgroundSaveInFlight();

        void beginCoordinatedRelease();

        void markCoordinatedBackendFinalizationStarted();

        Path finalReleaseWorldDirectory(String worldId);

        SnapshotManifestDto uploadFinalReleaseSnapshot(
                String worldId,
                Path worldDirectory,
                String hostPlayerUuid,
                long runtimeEpoch,
                String hostToken,
                WorldSyncProgressListener progressListener
        ) throws Exception;

        void clearHostedSessionAfterCoordinatedRelease();

        void clearHostedSessionAfterTerminalExit();

        boolean isStartupCancelable();
    }

    public interface ReleasePersistence {
        SharedWorldReleaseStore.ReleaseRecord load();

        SharedWorldReleaseStore.ReleaseRecord loadFor(String worldId, String hostUuid);

        void save(SharedWorldReleaseStore.ReleaseRecord record) throws IOException;

        void clear();
    }

    @FunctionalInterface
    public interface ReleaseUi {
        net.minecraft.client.gui.screens.Screen savingScreen(String worldName);
    }
}
