package link.sharedworld.mixin.versioned;

import link.sharedworld.SharedWorldDisconnectHook;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftDisconnectMixin {
    // 1.21.6-1.21.8 have no disconnectFromWorld wrapper; these two methods are
    // the intentional-leave entry points it delegates to on newer versions.
    // Hooking them (and not the raw disconnect(Screen, boolean) teardown)
    // keeps semantics identical to the 1.21.9+ hook: error/crash teardowns do
    // not fire the SharedWorld disconnect decision.
    @Inject(method = {"disconnectWithSavingScreen()V", "disconnectWithProgressScreen()V"}, at = @At("HEAD"))
    private void sharedworld$markUserInitiatedDisconnect(CallbackInfo callbackInfo) {
        SharedWorldDisconnectHook.onDisconnect((Minecraft) (Object) this);
    }
}
