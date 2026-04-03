package link.sharedworld.host;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;

final class ReleaseStartupRecoveryResolver {
    ReleaseStartupRecoveryResolver() {
    }

    Resolution resolve(
            SharedWorldReleaseCoordinator.ReleaseBackend backend,
            SharedWorldReleaseStore.ReleaseRecord record
    ) throws Exception {
        try {
            WorldRuntimeStatusDto runtime = backend.runtimeStatus(record.worldId);
            SharedWorldReleasePolicy.ResumeDecision decision = SharedWorldReleasePolicy.reconcile(record, runtime);
            boolean clearRecord = decision.clearBecauseObsoleteRecord()
                    || decision.terminalPhase() != null
                    || decision.backendFinalizationCompleted();
            return new Resolution(clearRecord);
        } catch (Exception exception) {
            Throwable cause = SharedWorldReleaseCoordinator.rootCause(exception);
            if (SharedWorldApiClient.isDeletedWorldError(cause) || SharedWorldApiClient.isMembershipRevokedError(cause)) {
                return new Resolution(true);
            }
            return new Resolution(false);
        }
    }

    record Resolution(boolean clearPersistedRecord) {
    }
}
