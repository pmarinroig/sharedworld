package link.sharedworld.mixin;

import link.sharedworld.integration.XaeroMapCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hosted shared world gets the same Minimap root container its guests get; see {@link XaeroMapCompat}. */
@Pseudo
@Mixin(targets = XaeroHookTargets.MINIMAP_CONTAINER_TARGET)
abstract class XaeroMinimapContainerMixin {
    @Inject(method = "convertWorldFolderToContainerNode(Ljava/lang/String;I)Ljava/lang/String;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sharedworld$hostContainerNode(String worldFolder, int version, CallbackInfoReturnable<String> cir) {
        String root = XaeroMapCompat.hostRootFor(worldFolder);
        if (root != null) {
            cir.setReturnValue(root);
        }
    }
}
