package link.sharedworld.mixin.versioned;

import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

@Mixin(SavedDataStorage.class)
public interface DimensionDataStorageAccessor {
    @Accessor("pendingWriteFuture")
    CompletableFuture<?> sharedworld$getPendingWriteFuture();
}
