package link.sharedworld.devhelper.e2e.mixin;

import link.sharedworld.devhelper.e2e.FrameRecorder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands every finished frame (world, HUD, screen and toasts all drawn into
 * the main render target) to the promo frame recorder. Dev e2e jar only, so
 * this is pinned to the default bucket's GameRenderer.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFrameRecorderMixin {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
    private void sharedworld$recordFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callbackInfo) {
        FrameRecorder.INSTANCE.onFrame(Minecraft.getInstance());
    }
}
