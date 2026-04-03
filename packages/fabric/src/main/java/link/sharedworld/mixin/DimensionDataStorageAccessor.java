package link.sharedworld.mixin;

import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

@Mixin(DimensionDataStorage.class)
public interface DimensionDataStorageAccessor {
    @Accessor("pendingWriteFuture")
    CompletableFuture<?> sharedworld$getPendingWriteFuture();
}
