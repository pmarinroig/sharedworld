package link.sharedworld.mixin;

import link.sharedworld.integration.XaeroMapCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Minimap keeps singleplayer waypoints in a fixed "waypoints" node and
 * multiplayer ones in {@code mw$<server level id>}; a hosted shared world uses
 * the latter so host and guest sessions share one waypoint file. Targeted by
 * name only: the descriptor carries Minecraft types whose mapping differs
 * between the dev and the shipped runtime.
 */
@Pseudo
@Mixin(targets = XaeroMapCompat.MINIMAP_NODE_TARGET)
abstract class XaeroMinimapWorldNodeMixin {
    @Inject(method = "getPotentialWorldNode", at = @At("HEAD"), cancellable = true, remap = false)
    private void sharedworld$hostWorldNode(CallbackInfoReturnable<String> cir) {
        String node = XaeroMapCompat.hostWorldNode();
        if (node != null) {
            cir.setReturnValue(node);
        }
    }
}
