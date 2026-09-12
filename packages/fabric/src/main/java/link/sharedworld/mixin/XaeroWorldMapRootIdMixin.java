package link.sharedworld.mixin;

import link.sharedworld.integration.XaeroMapCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hosted shared world gets the same World Map root folder its guests get; see {@link XaeroMapCompat}. */
@Pseudo
@Mixin(targets = XaeroMapCompat.WORLD_MAP_ROOT_TARGET)
abstract class XaeroWorldMapRootIdMixin {
    @Inject(method = "convertWorldFolderToRootId(ILjava/lang/String;)Ljava/lang/String;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sharedworld$hostRootId(int version, String worldFolder, CallbackInfoReturnable<String> cir) {
        String root = XaeroMapCompat.hostRootFor(worldFolder);
        if (root != null) {
            cir.setReturnValue(root);
        }
    }
}
