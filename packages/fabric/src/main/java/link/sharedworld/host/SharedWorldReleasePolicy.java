package link.sharedworld.host;

import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import link.sharedworld.progress.SharedWorldProgressState;
import net.minecraft.network.chat.Component;

final class SharedWorldReleasePolicy {
    private SharedWorldReleasePolicy() {
    }

    static SharedWorldReleasePhase resumePhaseForRetry(SharedWorldReleaseStore.ReleaseRecord record) {
        if (record.pendingTerminalPhase != null && record.localDisconnectObserved && shouldTransitionDirectlyToTerminal(record)) {
            return record.pendingTerminalPhase;
        }
        if (record.backendFinalizationCompleted) {
            return record.pendingTerminalPhase == null
                    ? SharedWorldReleasePhase.COMPLETE
                    : record.pendingTerminalPhase;
        }
        if (record.finalUploadCompleted) {
            return SharedWorldReleasePhase.COMPLETING_BACKEND_FINALIZATION;
        }
        if (record.backendFinalizationStarted) {
            return record.localDisconnectObserved
                    ? SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT
                    : disconnectPhaseFor(record);
        }
        return SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION;
    }

    static boolean shouldTransitionDirectlyToTerminal(SharedWorldReleaseStore.ReleaseRecord record) {
        return record.pendingTerminalPhase != SharedWorldReleasePhase.TERMINATED_REVOKED
                || !record.backendFinalizationStarted;
    }

    static ResumeDecision reconcile(SharedWorldReleaseStore.ReleaseRecord record, WorldRuntimeStatusDto runtime) {
        boolean backendStarted = record.backendFinalizationStarted;
        boolean backendCompleted = record.backendFinalizationCompleted;
        if (runtime == null) {
            return new ResumeDecision(backendStarted, backendCompleted, null, SharedWorldText.string("screen.sharedworld.release_runtime_status_unavailable"), false, null);
        }
        boolean sameEpoch = runtime.runtimeEpoch() == record.runtimeEpoch;
        if ("host-finalizing".equals(runtime.phase()) && sameEpoch) {
            return new ResumeDecision(true, false, null, null, false, null);
        }
        if (("host-live".equals(runtime.phase()) || "host-starting".equals(runtime.phase())) && sameEpoch) {
            return new ResumeDecision(false, false, null, null, false, null);
        }
        if (!record.backendFinalizationStarted
                && ("handoff-waiting".equals(runtime.phase()) || "idle".equals(runtime.phase()) || !sameEpoch)) {
            return new ResumeDecision(
                    false,
                    false,
                    null,
                    null,
                    true,
                    SharedWorldText.string("screen.sharedworld.release_cleared_stale_state")
            );
        }
        if (record.backendFinalizationStarted && ("handoff-waiting".equals(runtime.phase()) || "idle".equals(runtime.phase()) || !sameEpoch)) {
            return new ResumeDecision(true, true, null, null, false, null);
        }
        return new ResumeDecision(backendStarted, backendCompleted, null, SharedWorldText.string("screen.sharedworld.release_runtime_state_mismatch"), false, null);
    }

    static SharedWorldProgressState progressFor(SharedWorldReleasePhase phase, SharedWorldProgressState previous) {
        return switch (phase) {
            case BEGINNING_BACKEND_FINALIZATION, BACKEND_FINALIZING, WAITING_FOR_VANILLA_DISCONNECT, DISCONNECTING_LOCAL_WORLD, FORCED_DISCONNECTING, UPLOADING_FINAL_SNAPSHOT ->
                    progress("screen.sharedworld.progress.uploading_world", "release_preparing", previous);
            case COMPLETING_BACKEND_FINALIZATION ->
                    progress("screen.sharedworld.progress.uploading_world", "release_finishing", previous);
            default -> progress("screen.sharedworld.progress.uploading_world", "release_preparing", previous);
        };
    }

    static SharedWorldProgressState blockingProgress(Component label) {
        return blockingProgress(label, "release_preparing");
    }

    static SharedWorldProgressState blockingProgress(Component label, String phase) {
        return SharedWorldProgressState.indeterminate(
                Component.translatable("screen.sharedworld.saving_title"),
                label,
                phase,
                null
        );
    }

    static SharedWorldProgressState waitingForSaveProgress() {
        return progress("screen.sharedworld.progress.uploading_world", "release_preparing", null);
    }

    static boolean isClosedTerminal(SharedWorldReleasePhase phase) {
        return phase == SharedWorldReleasePhase.COMPLETE
                || phase == SharedWorldReleasePhase.TERMINATED_DELETED
                || phase == SharedWorldReleasePhase.TERMINATED_REVOKED;
    }

    static SharedWorldReleasePhase disconnectPhaseFor(SharedWorldReleaseStore.ReleaseRecord record) {
        return record.vanillaDisconnectExpected
                ? SharedWorldReleasePhase.WAITING_FOR_VANILLA_DISCONNECT
                : SharedWorldReleasePhase.DISCONNECTING_LOCAL_WORLD;
    }

    private static SharedWorldProgressState progress(String labelKey, String phase, SharedWorldProgressState previous) {
        return SharedWorldProgressState.indeterminate(
                Component.translatable("screen.sharedworld.saving_title"),
                Component.translatable(labelKey),
                phase,
                previous
        );
    }

    record ResumeDecision(
            boolean backendFinalizationStarted,
            boolean backendFinalizationCompleted,
            SharedWorldReleasePhase terminalPhase,
            String recoverableError,
            boolean clearBecauseObsoleteRecord,
            String obsoleteRecordMessage
    ) {
    }
}
