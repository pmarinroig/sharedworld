package link.sharedworld.versioned;

import link.sharedworld.mixin.versioned.ChunkStorageWorkerAccessor;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.CompletableFuture;

/**
 * Version-specific chunk persistence drain for Minecraft 1.21.9 and 1.21.10, where
 * {@code ChunkMap} extends the old {@code ChunkStorage}. Its only public flush,
 * {@code flushWorker()}, blocks the calling thread, so SharedWorld reaches the underlying
 * {@code IOWorker} through an accessor to keep the drain asynchronous like newer versions.
 */
public final class WorldFlushCompat {
    private WorldFlushCompat() {
    }

    public static CompletableFuture<?> synchronizeChunks(ServerLevel level) {
        return ((ChunkStorageWorkerAccessor) level.getChunkSource().chunkMap).sharedworld$getWorker().synchronize(false);
    }
}
