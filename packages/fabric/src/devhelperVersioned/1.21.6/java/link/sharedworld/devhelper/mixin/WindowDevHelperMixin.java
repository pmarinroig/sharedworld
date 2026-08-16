package link.sharedworld.devhelper.mixin;

import com.mojang.blaze3d.platform.Window;
import link.sharedworld.devhelper.DevHelperWindowPolicy;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Creates the game window invisible when SHAREDWORLD_HIDE_WINDOW is set, so
 * automated runs never flash a window during boot (the tick-time hide in
 * DevHelperClient only lands once the client is already up).
 *
 * On this version the Window constructor sets its hints right after
 * glfwDefaultWindowHints and never calls glfwShowWindow, so adding a
 * GLFW_VISIBLE=false hint here keeps the window hidden for the process
 * lifetime.
 */
@Mixin(Window.class)
public abstract class WindowDevHelperMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwDefaultWindowHints()V",
                    remap = false
            )
    )
    private void sharedworld$defaultHintsThenMaybeHide() {
        GLFW.glfwDefaultWindowHints();
        DevHelperWindowPolicy.applyHiddenWindowHint();
    }
}
