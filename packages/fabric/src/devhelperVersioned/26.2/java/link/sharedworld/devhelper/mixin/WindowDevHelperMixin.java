package link.sharedworld.devhelper.mixin;

import link.sharedworld.devhelper.DevHelperWindowPolicy;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the game window invisible when SHAREDWORLD_HIDE_WINDOW is set, so
 * automated runs never flash a window during boot (the tick-time hide in
 * DevHelperClient only lands once the client is already up).
 *
 * On 26.2 the window is already created hidden (Window.createGlfwWindow
 * hints GLFW_VISIBLE=false) and the Minecraft constructor shows it
 * explicitly once client init finishes — so here the fix is suppressing
 * that one glfwShowWindow call instead of adding a creation hint.
 */
@Mixin(Minecraft.class)
public abstract class WindowDevHelperMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V",
                    remap = false
            )
    )
    private void sharedworld$maybeShowWindow(long windowHandle) {
        if (DevHelperWindowPolicy.hideWindowRequested()) {
            return;
        }
        GLFW.glfwShowWindow(windowHandle);
    }
}
