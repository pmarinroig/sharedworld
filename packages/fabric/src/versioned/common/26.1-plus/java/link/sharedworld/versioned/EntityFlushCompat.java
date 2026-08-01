package link.sharedworld.versioned;

import link.sharedworld.mixin.versioned.DimensionDataStorageAccessor;
import link.sharedworld.mixin.versioned.EntityStorageAccessor;
import link.sharedworld.mixin.versioned.PersistentEntitySectionManagerAccessor;
import link.sharedworld.mixin.versioned.ServerLevelEntityManagerAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Version-specific entity/saved-data flush internals for the snapshot autosave
 * window. Collects the async write futures to await off-thread plus the
 * deserializer-queue drains that must run on the server thread.
 */
public final class EntityFlushCompat {
    private EntityFlushCompat() {
    }

    public static void collectDrains(
            ServerLevel level,
            List<CompletableFuture<?>> drainFutures,
            List<Runnable> serverThreadEntityDrains
    ) {
        PersistentEntitySectionManager<?> entityManager =
                ((ServerLevelEntityManagerAccessor) level).sharedworld$getEntityManager();
        EntityPersistentStorage<?> permanentStorage =
                ((PersistentEntitySectionManagerAccessor) entityManager).sharedworld$getPermanentStorage();
        if (permanentStorage instanceof EntityStorage entityStorage) {
            drainFutures.add(((EntityStorageAccessor) entityStorage).sharedworld$getSimpleRegionStorage().synchronize(false));
            ConsecutiveExecutor entityDeserializerQueue =
                    ((EntityStorageAccessor) entityStorage).sharedworld$getEntityDeserializerQueue();
            serverThreadEntityDrains.add(entityDeserializerQueue::runAll);
        } else {
            permanentStorage.flush(false);
        }

        SavedDataStorage dataStorage = level.getDataStorage();
        CompletableFuture<?> pendingWriteFuture =
                ((DimensionDataStorageAccessor) dataStorage).sharedworld$getPendingWriteFuture();
        if (pendingWriteFuture != null) {
            drainFutures.add(pendingWriteFuture);
        }
    }
}
