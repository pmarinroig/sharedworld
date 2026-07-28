package link.sharedworld.mixin.versioned;

import link.sharedworld.SharedWorldDisconnectHook;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftDisconnectMixin {
    // 1.21/1.21.1 intentional-leave entry points are the disconnect() and
    // disconnect(Screen) wrappers (PauseScreen quit uses them); error/transfer
    // teardowns call the raw disconnect(Screen, boolean) directly, so hooking
    // only the wrappers keeps semantics identical to the newer buckets.
    @Inject(method = {"disconnect()V", "disconnect(Lnet/minecraft/client/gui/screens/Screen;)V"}, at = @At("HEAD"))
    private void sharedworld$markUserInitiatedDisconnect(CallbackInfo callbackInfo) {
        SharedWorldDisconnectHook.onDisconnect((Minecraft) (Object) this);
    }
}
