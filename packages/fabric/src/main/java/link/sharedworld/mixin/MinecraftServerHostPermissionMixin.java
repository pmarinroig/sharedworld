package link.sharedworld.mixin;

import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.host.SharedWorldHostPermissionPolicy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerHostPermissionMixin {
    @Inject(method = "getProfilePermissions", at = @At("RETURN"), cancellable = true)
    private void sharedworld$applySharedWorldOwnerPermissions(NameAndId profile, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        cir.setReturnValue(
                SharedWorldHostPermissionPolicy.effectivePermissions(
                        cir.getReturnValue(),
                        SharedWorldDevSessionBridge.isHostingSharedWorld(),
                        profile.id() == null ? null : profile.id().toString(),
                        SharedWorldDevSessionBridge.hostingSharedWorldOwnerUuid()
                )
        );
    }
}
