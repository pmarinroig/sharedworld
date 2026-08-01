package link.sharedworld.mixin.versioned;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkStorage.class)
public interface ChunkStorageWorkerAccessor {
    @Accessor("worker")
    IOWorker sharedworld$getWorker();
}
