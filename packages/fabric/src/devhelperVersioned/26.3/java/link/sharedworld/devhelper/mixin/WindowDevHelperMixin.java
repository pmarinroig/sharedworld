package link.sharedworld.devhelper.mixin;

import com.mojang.blaze3d.platform.Window;
import link.sharedworld.devhelper.DevHelperWindowPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Keeps the game window invisible when SHAREDWORLD_HIDE_WINDOW is set, so
 * automated runs never flash a window during boot (the tick-time hide in
 * DevHelperClient only lands once the client is already up).
 *
 * On 26.3 the window comes from SDL3 through the render backend
 * (GpuBackend.createWindow, OpenGL or Vulkan), created visible and never shown
 * explicitly, so SDL_WINDOW_HIDDEN joins the creation flags Window passes in.
 */
@Mixin(Window.class)
public abstract class WindowDevHelperMixin {
    @ModifyArg(
            method = "createWindow",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"
            ),
            index = 3
    )
    private long sharedworld$hideOnCreate(long flags) {
        return flags | DevHelperWindowPolicy.hiddenWindowFlag();
    }
}
