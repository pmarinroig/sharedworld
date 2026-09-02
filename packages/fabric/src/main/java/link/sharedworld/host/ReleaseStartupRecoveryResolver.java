package link.sharedworld.host;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;

final class ReleaseStartupRecoveryResolver {
    ReleaseStartupRecoveryResolver() {
    }

    /** Whether the persisted release record is obsolete and should be cleared instead of resumed. */
    boolean shouldClearPersistedRecord(
            SharedWorldReleaseCoordinator.ReleaseBackend backend,
            SharedWorldReleaseStore.ReleaseRecord record
    ) {
        try {
            WorldRuntimeStatusDto runtime = backend.runtimeStatus(record.worldId);
            SharedWorldReleasePolicy.ResumeDecision decision = SharedWorldReleasePolicy.reconcile(record, runtime);
            return decision.clearBecauseObsoleteRecord() || decision.backendFinalizationCompleted();
        } catch (Exception exception) {
            return SharedWorldApiClient.isDeletedWorldError(exception) || SharedWorldApiClient.isMembershipRevokedError(exception);
        }
    }
}
