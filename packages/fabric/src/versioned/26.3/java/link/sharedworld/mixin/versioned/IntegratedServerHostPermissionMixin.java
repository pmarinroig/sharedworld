package link.sharedworld.mixin.versioned;

import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.host.SharedWorldHostServerGate;
import link.sharedworld.versioned.HostPermissionsCompat;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3 overrides getProfilePermissions on the integrated server and answers most profiles
 * from its own LAN settings (commands allowed, guest command access) without reaching
 * MinecraftServer's method, so MinecraftServerHostPermissionMixin alone no longer covers a
 * SharedWorld host: granted members and a visiting owner kept vanilla's level. Same decision
 * here; when the override does fall through to super, both mixins apply the same answer.
 */
@Mixin(IntegratedServer.class)
abstract class IntegratedServerHostPermissionMixin {
    @Inject(method = "getProfilePermissions", at = @At("RETURN"), cancellable = true)
    private void sharedworld$applySharedWorldPermissions(NameAndId profile, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        cir.setReturnValue(
                HostPermissionsCompat.effectivePermissions(
                        cir.getReturnValue(),
                        SharedWorldHostServerGate.isManagedSharedWorldHost((IntegratedServer) (Object) this),
                        profile.id() == null ? null : profile.id().toString(),
                        SharedWorldDevSessionBridge.hostingSharedWorldOwnerUuid()
                )
        );
    }
}
