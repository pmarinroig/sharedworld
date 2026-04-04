package link.sharedworld.mixin;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldPlaySessionTracker;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftDisconnectMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-disconnect");

    @Inject(method = "disconnectFromWorld", at = @At("HEAD"))
    private void sharedworld$markUserInitiatedDisconnect(Component message, CallbackInfo callbackInfo) {
        Minecraft minecraft = (Minecraft) (Object) this;
        SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
        SharedWorldDisconnectFlow.DisconnectAction action = SharedWorldDisconnectFlow.decide(
                SharedWorldClient.releaseCoordinator().consumeDisconnectPassThrough(),
                minecraft.isLocalServer(),
                SharedWorldClient.hostingManager().activeHostSession() != null,
                session
        );
        switch (action) {
            case IGNORE_PASS_THROUGH -> LOGGER.info("Skipping SharedWorld disconnect detection because release pass-through is armed.");
            case GUEST_ONLY -> {
                LOGGER.info("Observed SharedWorld guest disconnect; marking the session as user-initiated.");
                SharedWorldClient.playSessionTracker().markUserInitiatedDisconnect();
            }
            case HOST_GRACEFUL_RELEASE -> {
                LOGGER.info("Observed SharedWorld host disconnect on a local server; starting graceful release.");
                SharedWorldClient.playSessionTracker().markUserInitiatedDisconnect();
                SharedWorldClient.releaseCoordinator().beginGracefulDisconnect(minecraft);
            }
            case NO_SHAREDWORLD_ACTION -> LOGGER.debug("Observed disconnect without an active SharedWorld host session; no graceful release needed.");
        }
    }
}
