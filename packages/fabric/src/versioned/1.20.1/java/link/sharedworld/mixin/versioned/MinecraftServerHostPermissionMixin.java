package link.sharedworld.mixin.versioned;

import com.mojang.authlib.GameProfile;
import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.versioned.HostPermissionsCompat;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerHostPermissionMixin {
    @Inject(method = "getProfilePermissions", at = @At("RETURN"), cancellable = true)
    private void sharedworld$applySharedWorldOwnerPermissions(GameProfile profile, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(
                HostPermissionsCompat.effectivePermissions(
                        cir.getReturnValueI(),
                        SharedWorldDevSessionBridge.isHostingSharedWorld(),
                        profile.getId() == null ? null : profile.getId().toString(),
                        SharedWorldDevSessionBridge.hostingSharedWorldOwnerUuid()
                )
        );
    }
}
