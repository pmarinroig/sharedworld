package link.sharedworld.mixin.versioned;

import link.sharedworld.SharedWorldDisconnectHook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftDisconnectMixin {
    // 1.20.x funnels every teardown (intentional quits, error disconnects, and the no-arg
    // wrapper) through clearLevel(Screen), so the method itself cannot distinguish why it
    // ran. The pause-screen gate approximates "user pressed Disconnect / Save and Quit":
    // both intentional leaves run while the PauseScreen is showing, while error teardowns
    // almost never do; mis-marking a connection drop as user-initiated would break the
    // guest rejoin watcher. Programmatic disconnects are covered by the 1.20.1
    // ClientCompat.disconnectFromWorld, which fires the hook itself (consuming the release
    // pass-through) exactly like the newer versions' wrapper methods do.
    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void sharedworld$markUserInitiatedDisconnect(CallbackInfo callbackInfo) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.screen instanceof PauseScreen) {
            SharedWorldDisconnectHook.onDisconnect(minecraft);
        }
    }
}
