package link.sharedworld.mixin;

import link.sharedworld.host.SharedWorldHostServerGate;
import link.sharedworld.host.SharedWorldPublishedJoinModePolicy;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
abstract class IntegratedServerForcedGameTypeMixin {
    @Inject(method = "getForcedGameType", at = @At("RETURN"), cancellable = true)
    private void sharedworld$preserveSavedPlayerGamemode(CallbackInfoReturnable<GameType> cir) {
        cir.setReturnValue(
                SharedWorldPublishedJoinModePolicy.forcedGameMode(
                        cir.getReturnValue(),
                        SharedWorldHostServerGate.isManagedSharedWorldHost((MinecraftServer) (Object) this)
                )
        );
    }
}
