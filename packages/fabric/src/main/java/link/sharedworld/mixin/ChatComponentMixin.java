package link.sharedworld.mixin;

import link.sharedworld.integration.E4mcDomainTracker;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void sharedworld$trackClipboardTargets(Component message, CallbackInfo callbackInfo) {
        if (E4mcDomainTracker.shouldSuppressMessage(message)) {
            callbackInfo.cancel();
            return;
        }
        E4mcDomainTracker.observeMessage(message);
    }
}
