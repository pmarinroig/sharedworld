package link.sharedworld.devhelper.mixin;

import com.mojang.blaze3d.platform.Window;
import link.sharedworld.devhelper.DevHelperWindowPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Creates the game window invisible when SHAREDWORLD_HIDE_WINDOW is set, so
 * automated runs never flash a window during boot (the tick-time hide in
 * DevHelperClient only lands once the client is already up).
 *
 * On 26.1 window creation moved to the static Window.createGlfwWindow (the
 * GPU backend supplies the context hints), the window is still created
 * visible, and nothing calls glfwShowWindow later — so hinting
 * GLFW_VISIBLE=false right before glfwCreateWindow keeps it hidden for the
 * process lifetime.
 */
@Mixin(Window.class)
public abstract class WindowDevHelperMixin {
    @Inject(
            method = "createGlfwWindow",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J",
                    remap = false
            )
    )
    private static void sharedworld$maybeHideBeforeCreate(CallbackInfoReturnable<Long> callbackInfo) {
        DevHelperWindowPolicy.applyHiddenWindowHint();
    }
}
