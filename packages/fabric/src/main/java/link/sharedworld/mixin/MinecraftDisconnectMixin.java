package link.sharedworld.mixin;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldPlaySessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftDisconnectMixin {
    @Inject(method = "disconnectFromWorld", at = @At("HEAD"))
    private void sharedworld$markUserInitiatedDisconnect(Component message, CallbackInfo callbackInfo) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (SharedWorldClient.releaseCoordinator().consumeDisconnectPassThrough()) {
            return;
        }
        if (minecraft.screen instanceof PauseScreen) {
            SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
            if (session != null && session.role() == SharedWorldPlaySessionTracker.SessionRole.GUEST) {
                SharedWorldClient.presenceManager().onDisconnect(session);
            }
            SharedWorldClient.playSessionTracker().markUserInitiatedDisconnect();
            SharedWorldClient.releaseCoordinator().beginGracefulDisconnect(minecraft);
        }
    }

}
