package link.sharedworld.versioned;

import link.sharedworld.mixin.versioned.EntityStorageAccessor;
import link.sharedworld.mixin.versioned.PersistentEntitySectionManagerAccessor;
import link.sharedworld.mixin.versioned.ServerLevelEntityManagerAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Version-specific entity/saved-data flush internals for the snapshot autosave
 * window. 1.20.x entity storage writes through an IOWorker; DimensionDataStorage
 * saves synchronously, so only the entity side contributes drains.
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
            drainFutures.add(((EntityStorageAccessor) entityStorage).sharedworld$getWorker().synchronize(false));
            ProcessorMailbox<Runnable> entityDeserializerQueue =
                    ((EntityStorageAccessor) entityStorage).sharedworld$getEntityDeserializerQueue();
            serverThreadEntityDrains.add(entityDeserializerQueue::runAll);
        } else {
            permanentStorage.flush(false);
        }
    }
}
