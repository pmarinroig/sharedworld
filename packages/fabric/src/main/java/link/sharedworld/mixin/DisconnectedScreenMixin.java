package link.sharedworld.mixin;

import link.sharedworld.SharedWorldClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin {
    @Shadow
    @Final
    private Screen parent;

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void sharedworld$redirectSharedWorldRecovery(CallbackInfo callbackInfo) {
        if (!SharedWorldClient.playSessionTracker().hasPendingRecovery()) {
            // A disconnect unrelated to a SharedWorld guest session (vanilla servers included) must
            // keep its vanilla screen even when a persisted recovery record exists on disk; that
            // record resumes through the menu auto-resume path instead.
            return;
        }
        if (link.sharedworld.SharedWorldDisconnectReasonPolicy.isDeliberateRemoval(
                link.sharedworld.versioned.DisconnectReasonCompat.disconnectReason((Screen) (Object) this))) {
            // The server deliberately removed this guest (kick/ban); auto-rejoining
            // would undo the removal on the spot. Abandon recovery everywhere it
            // could restart and let the vanilla screen explain what happened.
            SharedWorldClient.abandonGuestRecoveryAfterDeliberateRemoval();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Screen fallbackParent = this.parent == null ? new JoinMultiplayerScreen(new TitleScreen()) : this.parent;
        if (!SharedWorldClient.sessionCoordinator().openRecoveryScreenIfPresent(fallbackParent)) {
            return;
        }
        callbackInfo.cancel();
    }
}
