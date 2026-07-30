package link.sharedworld.mixin.versioned;

import link.sharedworld.SharedWorldDisconnectHook;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftDisconnectMixin {
    @Inject(method = "disconnectFromWorld", at = @At("HEAD"))
    private void sharedworld$markUserInitiatedDisconnect(Component message, CallbackInfo callbackInfo) {
        SharedWorldDisconnectHook.onDisconnect((Minecraft) (Object) this);
    }
}
