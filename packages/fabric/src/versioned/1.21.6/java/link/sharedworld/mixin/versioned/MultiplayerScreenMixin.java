package link.sharedworld.mixin.versioned;

import link.sharedworld.SharedWorldClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
abstract class MultiplayerScreenMixin extends Screen {
    private boolean sharedworld$redirected;
    private Button sharedworld$button;

    protected MultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void sharedworld$addButton(CallbackInfo callbackInfo) {
        this.sharedworld$button = this.addRenderableWidget(Button.builder(Component.translatable("menu.sharedworld"), button ->
                        SharedWorldClient.openMainScreen((Screen) (Object) this))
                .bounds(this.width - 106, 8, 98, 20)
                .build());

        if (!this.sharedworld$redirected && SharedWorldClient.shouldOpenSharedWorldByDefault()) {
            this.sharedworld$redirected = true;
            this.minecraft.execute(() -> SharedWorldClient.openMainScreen((Screen) (Object) this));
        }
    }

    // 1.21.6's JoinMultiplayerScreen does not override repositionElements;
    // resize re-runs init(), which re-adds the button at the right position.
}
