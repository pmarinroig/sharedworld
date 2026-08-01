package link.sharedworld.mixin.versioned;

import link.sharedworld.integration.E4mcDomainTracker;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
abstract class ChatComponentMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void sharedworld$trackClipboardTargets(Component message, CallbackInfo callbackInfo) {
        if (E4mcDomainTracker.interceptMessage(message)) {
            callbackInfo.cancel();
        }
    }
}
