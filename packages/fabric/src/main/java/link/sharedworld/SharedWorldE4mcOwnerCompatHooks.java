package link.sharedworld;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class SharedWorldE4mcOwnerCompatHooks {
    private SharedWorldE4mcOwnerCompatHooks() {
    }

    public static void applySharedWorldOwnerCheck(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (!SharedWorldDevSessionBridge.isHostingSharedWorld()) {
            return;
        }

        String playerUuid = player == null || player.getUUID() == null ? null : player.getUUID().toString();
        cir.setReturnValue(SharedWorldE4mcCompatibility.shouldTreatPlayerAsSharedWorldOwnerForE4mc(playerUuid));
    }
}
