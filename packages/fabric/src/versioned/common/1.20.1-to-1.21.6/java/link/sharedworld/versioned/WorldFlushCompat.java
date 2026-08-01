package link.sharedworld.versioned;

import link.sharedworld.mixin.versioned.ChunkStorageWorkerAccessor;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.CompletableFuture;

/**
 * Version-specific chunk persistence controls for Minecraft 1.21.6-1.21.8, where
 * {@code ChunkMap} extends the old {@code ChunkStorage} (blocking {@code flushWorker()}
 * only, so the drain reaches the underlying {@code IOWorker} through an accessor) and
 * the integrated server has no autosave toggle: per-level {@code noSave} is the switch
 * vanilla itself used for save suppression in this era.
 */
public final class WorldFlushCompat {
    private WorldFlushCompat() {
    }

    public static CompletableFuture<?> synchronizeChunks(ServerLevel level) {
        return ((ChunkStorageWorkerAccessor) level.getChunkSource().chunkMap).sharedworld$getWorker().synchronize(false);
    }

    public static boolean isAutoSave(IntegratedServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            return !level.noSave;
        }
        return true;
    }

    public static void setAutoSave(IntegratedServer server, boolean enabled) {
        for (ServerLevel level : server.getAllLevels()) {
            level.noSave = !enabled;
        }
    }
}
