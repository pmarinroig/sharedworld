package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.EnterSessionResponseDto;
import link.sharedworld.api.SharedWorldModels.FinalizationActionResultDto;
import link.sharedworld.api.SharedWorldModels.ObserveWaitingResponseDto;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.progress.SharedWorldProgressState;
import link.sharedworld.screen.HandoffWaitingScreen;
import link.sharedworld.screen.HostAcquiredScreen;
import link.sharedworld.screen.SharedWorldErrorScreen;
import link.sharedworld.host.SharedWorldHostingManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SharedWorldSessionCoordinator {
    private static final long POLL_INTERVAL_MS = 1_000L;

    private final SessionBackend backend;
    private final RecoveryPersistence recoveryStore;
    private final SharedWorldCoordinatorSupport.AsyncBridge asyncBridge;
    private final SharedWorldCoordinatorSupport.Clock clock;
    private final SharedWorldCoordinatorSupport.ClientShell clientShell;
    private final SharedWorldCoordinatorSupport.PlayerIdentity playerIdentity;
    private final HostStartupOwner hostStartupOwner;
    private final SessionUi sessionUi;
    private WaitingFlowState waitingState;
    private ConnectedRuntimeContext pendingConnectedRuntime;
    private RecoveryFingerprint suppressedRecoveryFingerprint;
    private RecoveryFingerprint pendingRecoveredConnectFingerprint;
    private boolean autoResumeChecked;
    private long joinAttemptCounter;
    private PendingJoinAttempt pendingJoinAttempt;

    public SharedWorldSessionCoordinator(SharedWorldApiClient apiClient) {
        this(
                new SessionBackend() {
                    @Override
                    public EnterSessionResponseDto enterSession(String worldId, String waiterSessionId, boolean acknowledgeUncleanShutdown) throws Exception {
                        return apiClient.enterSession(worldId, waiterSessionId, acknowledgeUncleanShutdown);
                    }

                    @Override
                    public ObserveWaitingResponseDto observeWaiting(String worldId, String waiterSessionId) throws Exception {
                        return apiClient.observeWaiting(worldId, waiterSessionId);
                    }

                    @Override
                    public WorldRuntimeStatusDto cancelWaiting(String worldId, String waiterSessionId) throws Exception {
                        return apiClient.cancelWaiting(worldId, waiterSessionId);
                    }

                    @Override
                    public FinalizationActionResultDto abandonFinalization(String worldId) throws Exception {
                        return apiClient.abandonFinalization(worldId);
                    }
                },
                new SharedWorldRecoveryStore(),
                SharedWorldCoordinatorSupport.asyncBridge(SharedWorldClient.ioExecutor(), runnable -> Minecraft.getInstance().execute(runnable)),
                SharedWorldCoordinatorSupport.systemClock(),
                SharedWorldCoordinatorSupport.liveClientShell(),
                SharedWorldCoordinatorSupport.currentPlayerIdentity(),
                (parent, result, startupMode) -> SharedWorldClient.hostingManager().beginHosting(parent, result.world(), result.latestManifest(), result.assignment(), startupMode),
                new SessionUi() {
                    @Override
                    public Screen joinError(Screen parent, Throwable error) {
                        return new SharedWorldErrorScreen(
                                parent,
                                Component.translatable("screen.sharedworld.error_join_title"),
                                Component.literal(SharedWorldText.errorMessageOrDefault(SharedWorldApiClient.friendlyErrorMessage(error)))
                        );
                    }

                    @Override
                    public Screen hostAcquired(Screen parent, EnterSessionResponseDto result) {
                        return new HostAcquiredScreen(parent, result.world(), result.latestManifest(), result.assignment());
                    }

                    @Override
                    public Screen waiting(Screen parent, String worldId, String worldName, String ownerUuid) {
                        return new HandoffWaitingScreen(parent, worldId, worldName, ownerUuid);
                    }

                    @Override
                    public Screen deleted(Screen parent) {
                        return new SharedWorldErrorScreen(
                                parent,
                                Component.translatable("screen.sharedworld.deleted_title"),
                                Component.translatable("screen.sharedworld.deleted_detail")
                        );
                    }

                    @Override
                    public Screen uncleanShutdownWarning(Screen parent, String worldId, String worldName, WorldRuntimeStatusDto runtimeStatus) {
                        return new link.sharedworld.screen.UncleanShutdownWarningScreen(parent, worldId, worldName, runtimeStatus);
                    }
                }
        );
    }

    public SharedWorldSessionCoordinator(
            SessionBackend backend,
            RecoveryPersistence recoveryStore,
            SharedWorldCoordinatorSupport.AsyncBridge asyncBridge,
            SharedWorldCoordinatorSupport.Clock clock,
            SharedWorldCoordinatorSupport.ClientShell clientShell,
            SharedWorldCoordinatorSupport.PlayerIdentity playerIdentity,
            HostStartupOwner hostStartupOwner,
            SessionUi sessionUi
    ) {
        this.backend = backend;
        this.recoveryStore = recoveryStore;
        this.asyncBridge = asyncBridge;
        this.clock = clock;
        this.clientShell = clientShell;
        this.playerIdentity = playerIdentity;
        this.hostStartupOwner = hostStartupOwner;
        this.sessionUi = sessionUi;
    }

    /**
     * Responsibility:
     * Start a fresh join attempt and hand off the result to the waiting/host/connect flow.
     *
     * Preconditions:
     * The caller selected a world and the coordinator is free to begin a new session entry.
     *
     * Postconditions:
     * The client either connects immediately, enters the waiting flow, or opens host startup.
     *
     * Stale-work rule:
     * The backend decides connect/wait/host; the client never infers host eligibility locally.
     *
     * Authority source:
     * The backend enter-session response.
     */
    public void beginJoin(Screen parent, WorldSummaryDto world) {
        beginJoinAttempt(parent, world.id(), displayName(world), world.ownerUuid(), null, false, false, false, null, SharedWorldHostingManager.StartupMode.NORMAL);
    }

    public void acknowledgeUncleanShutdown(Screen parent, String worldId, String worldName) {
        beginJoinAttempt(parent, worldId, worldName, null, null, false, false, true, null, SharedWorldHostingManager.StartupMode.ACKNOWLEDGED_UNCLEAN_SHUTDOWN);
    }

    /**
     * Responsibility:
     * Start a fresh backend-owned session entry attempt, including restarts from waiting recovery.
     *
     * Preconditions:
     * The caller already chose the world and wants the backend to resolve connect / wait / host.
     *
     * Postconditions:
     * Exactly one of connect, host-acquired, waiting, or explicit error occurs for this attempt.
     *
     * Stale-work rule:
     * Only the newest pending join attempt may update coordinator state or UI.
     *
     * Authority source:
     * The backend enter-session response.
     */
    private void beginJoinAttempt(Screen parent, String worldId, String worldName, String ownerUuid, String previousJoinTarget, boolean hostChangeFlow, boolean returnToSharedWorldMenu, boolean acknowledgeUncleanShutdown, RecoveryFingerprint resumedRecoveryFingerprint, SharedWorldHostingManager.StartupMode startupMode) {
        PendingJoinAttempt attempt = new PendingJoinAttempt(
                ++this.joinAttemptCounter,
                worldId,
                resumedRecoveryFingerprint,
                startupMode == null ? SharedWorldHostingManager.StartupMode.NORMAL : startupMode
        );
        this.pendingJoinAttempt = attempt;
        this.asyncBridge.supply(
                () -> this.backend.enterSession(worldId, null, acknowledgeUncleanShutdown),
                (result, error) -> {
                    if (!matchesPendingJoinAttempt(attempt)) {
                        return;
                    }
                    this.pendingJoinAttempt = null;
                    if (error != null) {
                        Throwable cause = rootCause(error);
                        if (SharedWorldApiClient.isDeletedWorldError(cause)) {
                            clearPersistedRecoveryIfMatches(attempt.recoveryFingerprint());
                            this.clientShell.setScreen(this.sessionUi.deleted(parent));
                            return;
                        }
                        if (SharedWorldApiClient.isMembershipRevokedError(cause)) {
                            clearPersistedRecoveryIfMatches(attempt.recoveryFingerprint());
                            this.clientShell.openMembershipRevokedScreen(parent);
                            return;
                        }
                        if (parent != null) {
                            parent.clearFocus();
                        }
                        this.clientShell.setScreen(this.sessionUi.joinError(parent, cause));
                        return;
                    }
                    this.handleEnterSession(parent, ownerUuid, worldName, previousJoinTarget, result, hostChangeFlow, returnToSharedWorldMenu, attempt.recoveryFingerprint(), attempt.startupMode());
                }
        );
    }

    /**
     * Responsibility:
     * Drive the waiting flow poll loop and recovery auto-resume.
     *
     * Preconditions:
     * Waiting state, if present, is the sole active join/wait coordinator state.
     *
     * Postconditions:
     * Runtime observations either keep waiting, connect, or escalate to assigned-host claim.
     *
     * Stale-work rule:
     * Poll results are ignored once the waiting state changed or transitioned.
     *
     * Authority source:
     * The backend runtime status and waiting heartbeat response.
     */
    public void tick(Minecraft client) {
        if (!this.autoResumeChecked && !this.clientShell.hasLevel() && !this.clientShell.hasSingleplayerServer()) {
            this.autoResumeChecked = true;
            maybeResumePersistedRecovery();
        }
        WaitingFlowState state = this.waitingState;
        if (state == null || state.requestInFlight || state.transitionStarted || state.cancelInFlight || state.discardInFlight) {
            return;
        }
        long now = this.clock.nowMillis();
        if (now - state.lastPollAt >= POLL_INTERVAL_MS) {
            poll(state, now);
        }
    }

    public WaitingView waitingView() {
        WaitingFlowState state = this.waitingState;
        if (state == null) {
            return null;
        }
        return new WaitingView(
                state.worldId,
                state.worldName,
                state.ownerUuid,
                state.hostChangeFlow,
                state.returnToSharedWorldMenu,
                state.statusMessage,
                state.progressState,
                state.lastRuntimeStatus,
                state.cancelInFlight,
                state.discardInFlight,
                state.discardErrorMessage,
                SharedWorldWaitingFlowLogic.canDiscardPendingFinalization(
                        state.ownerUuid,
                        this.playerIdentity.currentPlayerUuid(),
                        state.lastRuntimeStatus,
                        state.transitionStarted,
                        state.cancelInFlight || state.discardInFlight,
                        this.clock.nowMillis()
                ),
                state.transitionStarted,
                state.parent
        );
    }

    public void cancelWaiting() {
        WaitingFlowState state = this.waitingState;
        if (state == null || state.cancelInFlight || state.transitionStarted || state.discardInFlight) {
            return;
        }
        state.cancelInFlight = true;
        state.statusMessage = Component.translatable(state.hostChangeFlow
                ? "screen.sharedworld.host_change_canceling"
                : "screen.sharedworld.joining_canceling").getString();
        state.progressState = progress(state.hostChangeFlow, Component.translatable("screen.sharedworld.progress.finishing_up"), state.progressState);
        if (state.waiterSessionId == null || state.waiterSessionId.isBlank()) {
            this.waitingState = null;
            clearPersistedRecovery();
            this.clientShell.clearPlaySession();
            if (state.returnToSharedWorldMenu) {
                this.clientShell.openMainScreen(state.parent);
            } else {
                this.clientShell.setScreen(state.parent);
            }
            return;
        }
        this.asyncBridge.run(() -> {
            this.backend.cancelWaiting(state.worldId, state.waiterSessionId);
        }, error -> {
            if (this.waitingState != state) {
                return;
            }
            if (error != null) {
                state.cancelInFlight = false;
                state.statusMessage = Component.translatable("screen.sharedworld.waiting").getString();
                state.progressState = progress(state.hostChangeFlow, Component.translatable("screen.sharedworld.progress.waiting_for_host"), state.progressState);
                state.discardErrorMessage = SharedWorldApiClient.friendlyErrorMessage(rootCause(error));
                return;
            }
            this.waitingState = null;
            clearPersistedRecovery();
            this.clientShell.clearPlaySession();
            if (state.returnToSharedWorldMenu) {
                this.clientShell.openMainScreen(state.parent);
            } else {
                this.clientShell.setScreen(state.parent);
            }
        });
    }

    public void resumeAfterHostStartupCancel() {
        WaitingFlowState state = this.waitingState;
        if (state == null) {
            return;
        }
        state.transitionStarted = false;
        state.requestInFlight = false;
        state.statusMessage = Component.translatable("screen.sharedworld.waiting").getString();
        state.progressState = progress(state.hostChangeFlow, Component.translatable("screen.sharedworld.progress.waiting_for_host"), state.progressState);
        state.lastPollAt = 0L;
    }

    /**
     * Responsibility:
     * Request owner-only abandonment of a stranded previous-host finalization from the waiting flow.
     *
     * Preconditions:
     * The current waiting flow explicitly allows the owner to discard stalled finalization state.
     *
     * Postconditions:
     * The discard request either completes, routes to a terminal deleted/revoked outcome, or records
     * a visible waiting-flow error without bypassing coordinator ownership.
     *
     * Stale-work rule:
     * Completion is ignored once the active waiting flow changed.
     *
     * Authority source:
     * Backend finalization abandonment response, not the confirmation screen.
     */
    public boolean requestDiscardPendingFinalization() {
        WaitingFlowState state = this.waitingState;
        if (state == null
                || state.discardInFlight
                || state.cancelInFlight
                || state.transitionStarted
                || !SharedWorldWaitingFlowLogic.canDiscardPendingFinalization(
                        state.ownerUuid,
                        this.playerIdentity.currentPlayerUuid(),
                        state.lastRuntimeStatus,
                        state.transitionStarted,
                        state.cancelInFlight || state.discardInFlight,
                        this.clock.nowMillis()
                )) {
            return false;
        }
        state.discardInFlight = true;
        state.discardErrorMessage = null;
        this.asyncBridge.run(() -> this.backend.abandonFinalization(state.worldId), error -> {
            if (this.waitingState != state) {
                return;
            }
            state.discardInFlight = false;
            if (error != null) {
                Throwable cause = rootCause(error);
                if (SharedWorldApiClient.isDeletedWorldError(cause)) {
                    this.waitingState = null;
                    clearPersistedRecovery();
                    this.clientShell.setScreen(this.sessionUi.deleted(state.parent));
                    return;
                }
                if (SharedWorldApiClient.isMembershipRevokedError(cause)) {
                    this.waitingState = null;
                    clearPersistedRecovery();
                    this.clientShell.openMembershipRevokedScreen(state.parent);
                    return;
                }
                state.discardErrorMessage = SharedWorldApiClient.friendlyErrorMessage(cause);
                return;
            }
            state.discardErrorMessage = null;
            state.lastPollAt = 0L;
        });
        return true;
    }

    public void refreshWaitingNow() {
        WaitingFlowState state = this.waitingState;
        if (state != null) {
            state.lastPollAt = 0L;
        }
    }

    public void onUnexpectedGuestDisconnect(SharedWorldPlaySessionTracker.RecoverySession recovery) {
        if (recovery == null) {
            return;
        }
        long runtimeEpoch = 0L;
        ConnectedRuntimeContext context = this.pendingConnectedRuntime;
        if (context != null && context.worldId.equals(recovery.worldId())) {
            runtimeEpoch = context.runtimeEpoch;
        }
        try {
            savePersistedRecovery(new SharedWorldRecoveryStore.RecoveryRecord(
                    recovery.worldId(),
                    recovery.worldName(),
                    runtimeEpoch,
                    "disconnect-recovery",
                    recovery.previousJoinTarget(),
                    null
            ));
        } catch (Exception ignored) {
        }
    }

    public boolean openRecoveryScreenIfPresent(Screen fallbackParent) {
        SharedWorldRecoveryStore.RecoveryRecord record = this.recoveryStore.load();
        if (record == null || isRecoverySuppressed(record)) {
            return false;
        }
        resumePersistedRecovery(record, fallbackParent);
        return true;
    }

    public void onGuestSessionJoined(SharedWorldPlaySessionTracker.ActiveWorldSession session) {
        if (session == null || session.role() != SharedWorldPlaySessionTracker.SessionRole.GUEST) {
            return;
        }
        RecoveryFingerprint fingerprint = this.pendingRecoveredConnectFingerprint;
        if (fingerprint == null || !fingerprint.worldId().equals(session.worldId())) {
            return;
        }
        clearPersistedRecoveryIfMatches(fingerprint);
    }

    public void rememberConnectedRuntime(String worldId, String worldName, long runtimeEpoch, String previousJoinTarget) {
        this.pendingConnectedRuntime = new ConnectedRuntimeContext(worldId, worldName, runtimeEpoch, previousJoinTarget);
    }

    private void clearPendingConnectedRuntime(String worldId, String joinTarget) {
        ConnectedRuntimeContext context = this.pendingConnectedRuntime;
        if (context == null) {
            return;
        }
        if (!context.worldId.equals(worldId)) {
            return;
        }
        if (!sameJoinTarget(context.previousJoinTarget, joinTarget)) {
            return;
        }
        this.pendingConnectedRuntime = null;
    }

    private void maybeResumePersistedRecovery() {
        SharedWorldRecoveryStore.RecoveryRecord record = this.recoveryStore.load();
        if (record == null || this.waitingState != null) {
            return;
        }
        resumePersistedRecovery(record, null);
    }

    private void resumePersistedRecovery(SharedWorldRecoveryStore.RecoveryRecord record, Screen parent) {
        RecoveryFingerprint fingerprint = fingerprint(record);
        this.suppressedRecoveryFingerprint = fingerprint;
        if (!resumesThroughWaiting(record)) {
            beginJoinAttempt(
                    parent,
                    record.worldId(),
                    record.worldName(),
                    null,
                    record.previousJoinTarget(),
                    true,
                    true,
                    false,
                    fingerprint,
                    SharedWorldHostingManager.StartupMode.NORMAL
            );
            return;
        }
        startWaitingFlow(
                parent,
                record.worldId(),
                record.worldName(),
                null,
                record.previousJoinTarget(),
                record.waiterSessionId(),
                true,
                true
        );
    }

    private boolean resumesThroughWaiting(SharedWorldRecoveryStore.RecoveryRecord record) {
        String flowKind = normalizeRecoveryValue(record.flowKind());
        if ("waiting".equals(flowKind)) {
            return true;
        }
        if ("disconnect-recovery".equals(flowKind)) {
            return false;
        }
        return record.waiterSessionId() != null && !record.waiterSessionId().isBlank();
    }

    /**
     * Responsibility:
     * Interpret an enter-session result without mixing it with the waiting flow poll logic.
     *
     * Preconditions:
     * The result came from the backend and belongs to the current user request.
     *
     * Postconditions:
     * Exactly one of connect, host-acquired, or waiting flow activation occurs.
     *
     * Stale-work rule:
     * Runtime data is only used to connect immediately when the backend already exposed a join target.
     *
     * Authority source:
     * The backend enter-session response.
     */
    private void handleEnterSession(Screen parent, String ownerUuid, String worldName, String previousJoinTarget, EnterSessionResponseDto result, boolean hostChangeFlow, boolean returnToSharedWorldMenu, RecoveryFingerprint resumedRecoveryFingerprint, SharedWorldHostingManager.StartupMode startupMode) {
        if ("connect".equals(result.action())) {
            String target = result.runtime() != null ? result.runtime().joinTarget() : null;
            if (target != null && !target.isBlank()) {
                beginConnectHandoff(parent, result.world().id(), worldName, result.runtime().runtimeEpoch(), target, resumedRecoveryFingerprint);
                return;
            }
        }
        if ("host".equals(result.action()) && result.assignment() != null) {
            clearPersistedRecoveryIfMatches(resumedRecoveryFingerprint);
            this.hostStartupOwner.beginHosting(parent, result, startupMode);
            this.clientShell.setScreen(this.sessionUi.hostAcquired(parent, result));
            return;
        }
        if ("warn-host".equals(result.action())) {
            clearPersistedRecoveryIfMatches(resumedRecoveryFingerprint);
            this.clientShell.setScreen(this.sessionUi.uncleanShutdownWarning(parent, result.world().id(), worldName, result.runtime()));
            return;
        }
        if (!"wait".equals(result.action()) || result.waiterSessionId() == null || result.waiterSessionId().isBlank()) {
            this.clientShell.setScreen(this.sessionUi.joinError(
                    parent,
                    new IllegalStateException(SharedWorldText.string("screen.sharedworld.waiting_error_invalid_response"))
            ));
            return;
        }
        startWaitingFlow(parent, result.world().id(), worldName, ownerUuid, previousJoinTarget, result.waiterSessionId(), hostChangeFlow, returnToSharedWorldMenu);
    }

    private void startWaitingFlow(Screen parent, String worldId, String worldName, String ownerUuid, String previousJoinTarget, String waiterSessionId, boolean hostChangeFlow, boolean returnToSharedWorldMenu) {
        this.waitingState = new WaitingFlowState(++this.joinAttemptCounter, parent, worldId, worldName, ownerUuid, previousJoinTarget, waiterSessionId, hostChangeFlow, returnToSharedWorldMenu);
        this.waitingState.progressState = progress(hostChangeFlow, Component.translatable("screen.sharedworld.progress.waiting_for_host"), null);
        if (waiterSessionId != null && !waiterSessionId.isBlank()) {
            try {
                savePersistedRecovery(new SharedWorldRecoveryStore.RecoveryRecord(
                        worldId,
                        worldName,
                        0L,
                        "waiting",
                        previousJoinTarget,
                        waiterSessionId
                ));
            } catch (Exception ignored) {
            }
        }
        this.clientShell.setScreen(this.sessionUi.waiting(parent, worldId, worldName, ownerUuid));
    }

    /**
     * Responsibility:
     * Observe the authoritative waiting/runtime state and decide whether to keep waiting,
     * connect, or claim an assigned host slot.
     *
     * Preconditions:
     * The provided state is still the active waiting flow owned by this coordinator.
     *
     * Postconditions:
     * The waiting UI is updated, or the flow transitions to connect / host-acquired.
     *
     * Stale-work rule:
     * Async completions are ignored once waitingState no longer points at this flow.
     *
     * Authority source:
     * The backend waiting heartbeat or runtime status response.
     */
    private void poll(WaitingFlowState state, long now) {
        if (state.waiterSessionId == null || state.waiterSessionId.isBlank()) {
            restartWaitingAttempt(state);
            return;
        }
        state.requestInFlight = true;
        state.lastPollAt = now;
        final long attemptId = state.attemptId;
        this.asyncBridge.supply(() -> this.backend.observeWaiting(state.worldId, state.waiterSessionId), (observation, error) -> {
            if (this.waitingState != state || state.attemptId != attemptId) {
                return;
            }
            state.requestInFlight = false;
            if (error != null) {
                Throwable cause = rootCause(error);
                if (SharedWorldApiClient.isDeletedWorldError(cause)) {
                    this.waitingState = null;
                    clearPersistedRecovery();
                    this.clientShell.setScreen(this.sessionUi.deleted(state.parent));
                    return;
                }
                if (SharedWorldApiClient.isMembershipRevokedError(cause)) {
                    this.waitingState = null;
                    clearPersistedRecovery();
                    this.clientShell.openMembershipRevokedScreen(state.parent);
                }
                return;
            }
            SharedWorldWaitingFlowLogic.PollDecision decision = SharedWorldWaitingFlowLogic.evaluateObservation(
                    new SharedWorldWaitingFlowLogic.WaitingContext(
                            state.worldId,
                            state.worldName,
                            state.previousJoinTarget,
                            state.hostChangeFlow
                    ),
                    observation
            );
            applyPollDecision(state, decision);
        });
    }

    private void applyPollDecision(WaitingFlowState state, SharedWorldWaitingFlowLogic.PollDecision decision) {
        switch (decision.outcome()) {
            case CONNECT -> {
                this.waitingState = null;
                clearPersistedRecovery();
                beginConnectHandoff(state.parent, state.worldId, state.worldName, decision.runtimeEpoch(), decision.connectTarget(), null);
            }
            case RESTART -> restartWaitingAttempt(state);
            case STAY_WAITING -> {
                state.waiterSessionId = decision.waiterSessionId();
                state.lastRuntimeStatus = decision.runtimeStatus();
                state.statusMessage = decision.statusMessage();
                state.progressState = progress(state.hostChangeFlow, decision.progressLabel(), state.progressState);
                persistWaitingRecovery(state);
            }
            case ERROR -> {
                this.waitingState = null;
                clearPersistedRecovery();
                this.clientShell.setScreen(this.sessionUi.joinError(
                        state.parent,
                        new IllegalStateException(decision.errorMessage())
                ));
            }
        }
    }

    private void restartWaitingAttempt(WaitingFlowState state) {
        if (this.waitingState != state || state.transitionStarted) {
            return;
        }
        this.waitingState = null;
        clearPersistedRecovery();
        beginJoinAttempt(
                state.parent,
                state.worldId,
                state.worldName,
                state.ownerUuid,
                state.previousJoinTarget,
                state.hostChangeFlow,
                state.returnToSharedWorldMenu,
                false,
                null,
                SharedWorldHostingManager.StartupMode.NORMAL
        );
    }

    private void persistWaitingRecovery(WaitingFlowState state) {
        if (state.waiterSessionId == null || state.waiterSessionId.isBlank()) {
            clearPersistedRecovery();
            return;
        }
        try {
            savePersistedRecovery(new SharedWorldRecoveryStore.RecoveryRecord(
                    state.worldId,
                    state.worldName,
                    0L,
                    "waiting",
                    state.previousJoinTarget,
                    state.waiterSessionId
            ));
        } catch (Exception ignored) {
        }
    }

    private void savePersistedRecovery(SharedWorldRecoveryStore.RecoveryRecord record) throws Exception {
        this.recoveryStore.save(record);
        this.suppressedRecoveryFingerprint = null;
        this.pendingRecoveredConnectFingerprint = null;
    }

    private void clearPersistedRecovery() {
        this.recoveryStore.clear();
        this.suppressedRecoveryFingerprint = null;
        this.pendingRecoveredConnectFingerprint = null;
    }

    private void clearPersistedRecoveryIfMatches(RecoveryFingerprint fingerprint) {
        if (fingerprint == null) {
            return;
        }
        SharedWorldRecoveryStore.RecoveryRecord current = this.recoveryStore.load();
        if (current != null && fingerprint.equals(fingerprint(current))) {
            clearPersistedRecovery();
            return;
        }
        if (fingerprint.equals(this.suppressedRecoveryFingerprint)) {
            this.suppressedRecoveryFingerprint = null;
        }
        if (fingerprint.equals(this.pendingRecoveredConnectFingerprint)) {
            this.pendingRecoveredConnectFingerprint = null;
        }
    }

    private boolean isRecoverySuppressed(SharedWorldRecoveryStore.RecoveryRecord record) {
        return this.suppressedRecoveryFingerprint != null
                && this.suppressedRecoveryFingerprint.equals(fingerprint(record));
    }

    private static RecoveryFingerprint fingerprint(SharedWorldRecoveryStore.RecoveryRecord record) {
        return new RecoveryFingerprint(
                record.worldId(),
                record.worldName(),
                record.runtimeEpoch(),
                normalizeRecoveryValue(record.flowKind()),
                normalizeRecoveryValue(record.previousJoinTarget()),
                normalizeRecoveryValue(record.waiterSessionId())
        );
    }

    private static String normalizeRecoveryValue(String value) {
        return value == null ? "" : value;
    }

    private static SharedWorldProgressState progress(boolean hostChangeFlow, Component label, SharedWorldProgressState previous) {
        return SharedWorldProgressState.indeterminate(
                Component.translatable(hostChangeFlow ? "screen.sharedworld.host_change" : "screen.sharedworld.joining_title"),
                label,
                "handoff_wait",
                previous
        );
    }

    private void beginConnectHandoff(Screen parent, String worldId, String worldName, long runtimeEpoch, String joinTarget, RecoveryFingerprint resumedRecoveryFingerprint) {
        rememberConnectedRuntime(worldId, worldName, runtimeEpoch, joinTarget);
        this.pendingRecoveredConnectFingerprint = resumedRecoveryFingerprint;
        this.clientShell.connect(parent, joinTarget, worldId, worldName, error -> handleConnectHandoffFailure(parent, worldId, joinTarget, resumedRecoveryFingerprint, error));
    }

    private void handleConnectHandoffFailure(Screen parent, String worldId, String joinTarget, RecoveryFingerprint resumedRecoveryFingerprint, Throwable error) {
        clearPendingConnectedRuntime(worldId, joinTarget);
        if (resumedRecoveryFingerprint != null && resumedRecoveryFingerprint.equals(this.pendingRecoveredConnectFingerprint)) {
            this.pendingRecoveredConnectFingerprint = null;
        }
        if (parent != null) {
            parent.clearFocus();
        }
        this.clientShell.setScreen(this.sessionUi.joinError(
                parent,
                new IllegalStateException(SharedWorldText.string("screen.sharedworld.join_connect_failed"))
        ));
    }

    private static boolean sameJoinTarget(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private static String displayName(WorldSummaryDto world) {
        return SharedWorldText.displayWorldName(world.name());
    }

    private boolean matchesPendingJoinAttempt(PendingJoinAttempt attempt) {
        return this.pendingJoinAttempt != null
                && this.pendingJoinAttempt.attemptId == attempt.attemptId
                && this.pendingJoinAttempt.worldId.equals(attempt.worldId);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    public record WaitingView(
            String worldId,
            String worldName,
            String ownerUuid,
            boolean hostChangeFlow,
            boolean returnToSharedWorldMenu,
            String statusMessage,
            SharedWorldProgressState progressState,
            WorldRuntimeStatusDto runtimeStatus,
            boolean cancelInFlight,
            boolean discardInFlight,
            String discardErrorMessage,
            boolean canDiscardPendingFinalization,
            boolean transitionStarted,
            Screen parent
    ) {
    }

    private static final class WaitingFlowState {
        private final long attemptId;
        private final Screen parent;
        private final String worldId;
        private final String worldName;
        private final String ownerUuid;
        private final String previousJoinTarget;
        private String waiterSessionId;
        private final boolean hostChangeFlow;
        private final boolean returnToSharedWorldMenu;
        private long lastPollAt;
        private boolean requestInFlight;
        private boolean transitionStarted;
        private boolean cancelInFlight;
        private boolean discardInFlight;
        private String discardErrorMessage;
        private String statusMessage = Component.translatable("screen.sharedworld.waiting").getString();
        private SharedWorldProgressState progressState;
        private WorldRuntimeStatusDto lastRuntimeStatus;

        private WaitingFlowState(long attemptId, Screen parent, String worldId, String worldName, String ownerUuid, String previousJoinTarget, String waiterSessionId, boolean hostChangeFlow, boolean returnToSharedWorldMenu) {
            this.attemptId = attemptId;
            this.parent = parent;
            this.worldId = worldId;
            this.worldName = worldName;
            this.ownerUuid = ownerUuid;
            this.previousJoinTarget = previousJoinTarget;
            this.waiterSessionId = waiterSessionId;
            this.hostChangeFlow = hostChangeFlow;
            this.returnToSharedWorldMenu = returnToSharedWorldMenu;
        }
    }

    private record ConnectedRuntimeContext(String worldId, String worldName, long runtimeEpoch, String previousJoinTarget) {
    }

    private record PendingJoinAttempt(long attemptId, String worldId, RecoveryFingerprint recoveryFingerprint, SharedWorldHostingManager.StartupMode startupMode) {
    }

    private record RecoveryFingerprint(
            String worldId,
            String worldName,
            long runtimeEpoch,
            String flowKind,
            String previousJoinTarget,
            String waiterSessionId
    ) {
    }

    public interface SessionBackend {
        EnterSessionResponseDto enterSession(String worldId, String waiterSessionId, boolean acknowledgeUncleanShutdown) throws Exception;

        ObserveWaitingResponseDto observeWaiting(String worldId, String waiterSessionId) throws Exception;

        WorldRuntimeStatusDto cancelWaiting(String worldId, String waiterSessionId) throws Exception;

        FinalizationActionResultDto abandonFinalization(String worldId) throws Exception;
    }

    public interface RecoveryPersistence {
        SharedWorldRecoveryStore.RecoveryRecord load();

        void save(SharedWorldRecoveryStore.RecoveryRecord record) throws Exception;

        void clear();
    }

    public interface SessionUi {
        Screen joinError(Screen parent, Throwable error);

        Screen hostAcquired(Screen parent, EnterSessionResponseDto result);

        Screen waiting(Screen parent, String worldId, String worldName, String ownerUuid);

        Screen deleted(Screen parent);

        Screen uncleanShutdownWarning(Screen parent, String worldId, String worldName, WorldRuntimeStatusDto runtimeStatus);
    }

    @FunctionalInterface
    public interface HostStartupOwner {
        void beginHosting(Screen parent, EnterSessionResponseDto result, SharedWorldHostingManager.StartupMode startupMode);
    }
}
