package link.sharedworld.mixin;

import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.SharedWorldE4mcCompatibility;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

final class E4mcSingleplayerOwnerCompatHooks {
    private E4mcSingleplayerOwnerCompatHooks() {
    }

    static void applySharedWorldOwnerCheck(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (!SharedWorldDevSessionBridge.isHostingSharedWorld()) {
            return;
        }

        String playerUuid = player == null || player.getUUID() == null ? null : player.getUUID().toString();
        cir.setReturnValue(SharedWorldE4mcCompatibility.shouldTreatPlayerAsSharedWorldOwnerForE4mc(playerUuid));
    }
}
