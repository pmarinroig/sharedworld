package link.sharedworld.host;

import link.sharedworld.api.SharedWorldApiClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReleaseStartupRecoveryResolverTest {
    @Test
    void deletedWorldRecoveryClearsPersistedRecord() throws Exception {
        ReleaseStartupRecoveryResolver resolver = new ReleaseStartupRecoveryResolver();

        ReleaseStartupRecoveryResolver.Resolution resolution = resolver.resolve(
                new FailingBackend(new SharedWorldApiClient.SharedWorldApiException("world_not_found", "gone", 404)),
                record("world-1")
        );

        assertTrue(resolution.clearPersistedRecord());
    }

    @Test
    void transientRecoveryErrorKeepsPersistedRecordForRetry() throws Exception {
        ReleaseStartupRecoveryResolver resolver = new ReleaseStartupRecoveryResolver();

        ReleaseStartupRecoveryResolver.Resolution resolution = resolver.resolve(
                new FailingBackend(new IOException("network down")),
                record("world-1")
        );

        assertFalse(resolution.clearPersistedRecord());
    }

    private static SharedWorldReleaseStore.ReleaseRecord record(String worldId) {
        SharedWorldReleaseStore.ReleaseRecord record = new SharedWorldReleaseStore.ReleaseRecord();
        record.worldId = worldId;
        record.phase = SharedWorldReleasePhase.BEGINNING_BACKEND_FINALIZATION;
        return record;
    }

    private record FailingBackend(Exception failure) implements SharedWorldReleaseCoordinator.ReleaseBackend {
        @Override
        public link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto runtimeStatus(String worldId) throws Exception {
            throw this.failure;
        }

        @Override
        public void beginFinalization(String worldId, long runtimeEpoch, String hostToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void completeFinalization(String worldId, long runtimeEpoch, String hostToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseHost(String worldId, long runtimeEpoch, String hostToken, boolean graceful) {
            throw new UnsupportedOperationException();
        }
    }
}
