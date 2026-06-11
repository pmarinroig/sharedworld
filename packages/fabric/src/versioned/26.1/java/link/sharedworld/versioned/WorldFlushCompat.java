package link.sharedworld.versioned;

import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.CompletableFuture;

/**
 * Version-specific chunk persistence drain for Minecraft 1.21.11, where {@code ChunkMap}
 * extends {@code SimpleRegionStorage} and exposes {@code synchronize} directly.
 */
public final class WorldFlushCompat {
    private WorldFlushCompat() {
    }

    public static CompletableFuture<?> synchronizeChunks(ServerLevel level) {
        return level.getChunkSource().chunkMap.synchronize(false);
    }
}
