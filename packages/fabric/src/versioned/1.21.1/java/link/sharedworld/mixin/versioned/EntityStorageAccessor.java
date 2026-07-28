package link.sharedworld.mixin.versioned;

import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {
    @Accessor("simpleRegionStorage")
    SimpleRegionStorage sharedworld$getSimpleRegionStorage();

    @Accessor("entityDeserializerQueue")
    ProcessorMailbox<Runnable> sharedworld$getEntityDeserializerQueue();
}
