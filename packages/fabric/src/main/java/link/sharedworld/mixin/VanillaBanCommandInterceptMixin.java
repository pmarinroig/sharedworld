package link.sharedworld.mixin;

import java.util.Collection;

import link.sharedworld.command.SharedWorldCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.BanPlayerCommands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla registers /ban only on dedicated servers, but e4mc's
 * "restoreDedicatedCommands" brings it back on integrated ones, where it can
 * kick any target — the hosting player included — and stores bans in a local
 * banned-players.json that outlives the session. While a shared world is
 * hosted, execution reroutes to SharedWorld's membership ban (same guards and
 * feedback as the /ban literal SharedWorld registers itself); other sessions
 * are untouched. The raw Collection parameter matches every bucket: the
 * element type drifted (GameProfile before 1.21.9, NameAndId after) but
 * erasure keeps the descriptor identical.
 */
@Mixin(BanPlayerCommands.class)
abstract class VanillaBanCommandInterceptMixin {
    @Inject(method = "banPlayers", at = @At("HEAD"), cancellable = true)
    private static void sharedworld$rerouteToMembershipBan(
            CommandSourceStack source,
            Collection<?> targets,
            Component reason,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (SharedWorldCommands.interceptVanillaBan(source, targets)) {
            cir.setReturnValue(0);
        }
    }
}
