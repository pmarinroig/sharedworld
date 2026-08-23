package link.sharedworld.mixin;

import java.util.Collection;

import link.sharedworld.command.SharedWorldCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.WhitelistCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla registers /whitelist only on dedicated servers, but e4mc's
 * "restoreDedicatedCommands" brings it back on integrated ones. On a hosted
 * shared world, membership is the only join authority; an enabled whitelist
 * would silently refuse legit members, so every mutation is refused with a
 * pointer at the SharedWorld screen while /whitelist list stays readable.
 * The raw Collection parameters match every bucket (GameProfile before
 * 1.21.9, NameAndId after; erasure keeps the descriptors identical).
 */
@Mixin(WhitelistCommand.class)
abstract class VanillaWhitelistCommandInterceptMixin {
    @Inject(method = "enableWhitelist", at = @At("HEAD"), cancellable = true)
    private static void sharedworld$refuseEnable(CommandSourceStack source, CallbackInfoReturnable<Integer> cir) {
        if (SharedWorldCommands.interceptVanillaWhitelistMutation(source)) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "disableWhitelist", at = @At("HEAD"), cancellable = true)
    private static void sharedworld$refuseDisable(CommandSourceStack source, CallbackInfoReturnable<Integer> cir) {
        if (SharedWorldCommands.interceptVanillaWhitelistMutation(source)) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "addPlayers", at = @At("HEAD"), cancellable = true)
    private static void sharedworld$refuseAdd(CommandSourceStack source, Collection<?> targets, CallbackInfoReturnable<Integer> cir) {
        if (SharedWorldCommands.interceptVanillaWhitelistMutation(source)) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "removePlayers", at = @At("HEAD"), cancellable = true)
    private static void sharedworld$refuseRemove(CommandSourceStack source, Collection<?> targets, CallbackInfoReturnable<Integer> cir) {
        if (SharedWorldCommands.interceptVanillaWhitelistMutation(source)) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private static void sharedworld$refuseReload(CommandSourceStack source, CallbackInfoReturnable<Integer> cir) {
        if (SharedWorldCommands.interceptVanillaWhitelistMutation(source)) {
            cir.setReturnValue(0);
        }
    }
}
