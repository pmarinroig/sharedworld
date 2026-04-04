package link.sharedworld.host;

import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;

final class ReleaseRuntimeReconciliation {
    private ReleaseRuntimeReconciliation() {
    }

    static Outcome reconcile(
            SharedWorldReleaseStore.ReleaseRecord record,
            WorldRuntimeStatusDto runtime,
            boolean localWorldStillPresent
    ) {
        SharedWorldReleasePolicy.ResumeDecision decision = SharedWorldReleasePolicy.reconcile(record, runtime);
        SharedWorldReleaseStore.ReleaseRecord updated = record.copy();
        updated.backendFinalizationStarted = decision.backendFinalizationStarted();
        updated.backendFinalizationCompleted = decision.backendFinalizationCompleted();
        if (!updated.localDisconnectObserved && !localWorldStillPresent) {
            updated.localDisconnectObserved = true;
        }
        if (decision.clearBecauseObsoleteRecord()) {
            return Outcome.clearPersisted(decision.obsoleteRecordMessage());
        }
        if (decision.terminalPhase() != null) {
            updated.phase = decision.terminalPhase();
            return Outcome.persist(updated);
        }
        if (decision.recoverableError() != null) {
            updated.phase = SharedWorldReleasePhase.ERROR_RECOVERABLE;
            return Outcome.recoverable(
                    updated,
                    decision.recoverableError(),
                    decision.errorKind(),
                    decision.authorityLossStage()
            );
        }
        if (updated.pendingTerminalPhase != null && updated.localDisconnectObserved) {
            updated.phase = SharedWorldReleasePolicy.shouldTransitionDirectlyToTerminal(updated)
                    ? updated.pendingTerminalPhase
                    : SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT;
            return Outcome.persist(updated);
        }
        if (updated.backendFinalizationCompleted) {
            updated.phase = updated.pendingTerminalPhase == null
                    ? SharedWorldReleasePhase.COMPLETE
                    : updated.pendingTerminalPhase;
        } else if (updated.finalUploadCompleted) {
            updated.phase = SharedWorldReleasePhase.COMPLETING_BACKEND_FINALIZATION;
        } else if (updated.backendFinalizationStarted) {
            updated.phase = updated.localDisconnectObserved
                    ? SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT
                    : SharedWorldReleasePolicy.disconnectPhaseFor(updated);
        } else {
            updated.phase = SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION;
        }
        return Outcome.persist(updated);
    }

    record Outcome(
            SharedWorldReleaseStore.ReleaseRecord updatedRecord,
            boolean clearPersistedRecord,
            String obsoleteRecordMessage,
            String recoverableError,
            SharedWorldTerminalReasonKind errorKind,
            SharedWorldReleasePolicy.ReleaseAuthorityLossStage authorityLossStage,
            boolean clearHostedSession
    ) {
        private static Outcome clearPersisted(String obsoleteRecordMessage) {
            return new Outcome(null, true, obsoleteRecordMessage, null, null, null, false);
        }

        private static Outcome persist(SharedWorldReleaseStore.ReleaseRecord updatedRecord) {
            return new Outcome(
                    updatedRecord,
                    false,
                    null,
                    null,
                    null,
                    null,
                    SharedWorldReleasePolicy.isClosedTerminal(updatedRecord.phase)
            );
        }

        private static Outcome recoverable(
                SharedWorldReleaseStore.ReleaseRecord updatedRecord,
                String recoverableError,
                SharedWorldTerminalReasonKind errorKind,
                SharedWorldReleasePolicy.ReleaseAuthorityLossStage authorityLossStage
        ) {
            return new Outcome(updatedRecord, false, null, recoverableError, errorKind, authorityLossStage, false);
        }
    }
}
