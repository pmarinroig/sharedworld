package link.sharedworld.mixin;

import link.sharedworld.SharedWorldE4mcOwnerCompatHooks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "link.e4mc.Mirror")
abstract class E4mcSingleplayerOwnerCompatNamedMixin {
    @Inject(
            method = "isSingleplayerOwner(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void sharedworld$applySharedWorldOwnerCheck(
            MinecraftServer server,
            ServerPlayer player,
            CallbackInfoReturnable<Boolean> cir
    ) {
        SharedWorldE4mcOwnerCompatHooks.applySharedWorldOwnerCheck(player, cir);
    }
}
