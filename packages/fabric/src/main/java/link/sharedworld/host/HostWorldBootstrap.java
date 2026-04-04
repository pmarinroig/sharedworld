package link.sharedworld.host;

import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncProgress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

final class HostWorldBootstrap {
    private final SharedWorldHostingManager.SyncAccess syncAccess;
    private final ManagedWorldStore worldStore;
    private final SharedWorldHostingManager.WorldOpenController worldOpenController;

    HostWorldBootstrap(
            SharedWorldHostingManager.SyncAccess syncAccess,
            ManagedWorldStore worldStore,
            SharedWorldHostingManager.WorldOpenController worldOpenController
    ) {
        this.syncAccess = Objects.requireNonNull(syncAccess, "syncAccess");
        this.worldStore = Objects.requireNonNull(worldStore, "worldStore");
        this.worldOpenController = Objects.requireNonNull(worldOpenController, "worldOpenController");
    }

    void prepareAndOpen(
            long startupAttemptId,
            WorldSummaryDto world,
            Supplier<String> hostPlayerUuid,
            long runtimeEpoch,
            String hostToken,
            boolean recoverLocalCrashState,
            LongPredicate isActiveStartupAttempt,
            Consumer<WorldSyncProgress> progressSink,
            Runnable onOpeningWorld
    ) throws Exception {
        if (!isActiveStartupAttempt.test(startupAttemptId)) {
            return;
        }

        String resolvedHostPlayerUuid = hostPlayerUuid.get();
        Path worldDirectory;
        if (recoverLocalCrashState) {
            worldDirectory = this.worldStore.workingCopy(world.id());
            if (!Files.exists(worldDirectory)) {
                throw new IllegalStateException("SharedWorld local crash recovery requires an existing managed working copy.");
            }
            this.syncAccess.uploadSnapshot(
                    world.id(),
                    worldDirectory,
                    resolvedHostPlayerUuid,
                    runtimeEpoch,
                    hostToken,
                    progressSink::accept
            );
        } else {
            worldDirectory = this.syncAccess.ensureSynchronizedWorkingCopy(
                    world.id(),
                    resolvedHostPlayerUuid,
                    progressSink::accept
            );
        }
        if (!isActiveStartupAttempt.test(startupAttemptId)) {
            return;
        }
        onOpeningWorld.run();
        this.worldOpenController.openExistingWorld(this.worldStore, world, worldDirectory);
    }
}
