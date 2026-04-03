package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public final class RedeemInviteScreen extends Screen {
    private final SharedWorldScreen parent;
    private EditBox codeBox;
    private String statusMessage = "";

    public RedeemInviteScreen(SharedWorldScreen parent) {
        super(Component.translatable("screen.sharedworld.redeem_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.codeBox = new EditBox(this.font, centerX - 100, 106, 200, 20, Component.translatable("screen.sharedworld.code_hint"));
        this.codeBox.setMaxLength(32);
        this.codeBox.setHint(Component.translatable("screen.sharedworld.code_hint"));
        this.addRenderableWidget(this.codeBox);
        this.setInitialFocus(this.codeBox);

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.done"), button -> this.submit())
                .bounds(centerX - 100, this.height / 4 + 96, 200, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.cancel"), button -> {
                    this.parent.clearTransientFocus();
                    this.minecraft.setScreen(this.parent);
                })
                .bounds(centerX - 100, this.height / 4 + 120, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 32, 0xFFFFFFFF);
        guiGraphics.drawString(
                this.font,
                Component.translatable("screen.sharedworld.invite_code"),
                this.width / 2 - 100,
                92,
                0xFFA0A0A0
        );
        guiGraphics.drawString(this.font, this.statusMessage, this.width / 2 - 100, 158, 0xFFA0A0A0);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void submit() {
        String code = this.codeBox.getValue().trim().toUpperCase();
        if (code.isEmpty()) {
            this.statusMessage = SharedWorldText.string("screen.sharedworld.invite_code_required");
            return;
        }

        this.statusMessage = SharedWorldText.string("screen.sharedworld.redeem_adding_world");
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return SharedWorldClient.apiClient().redeemInvite(code);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        this.parent.clearTransientFocus();
                        this.minecraft.setScreen(new SharedWorldErrorScreen(
                                this.parent,
                                Component.translatable("screen.sharedworld.error_title"),
                                inviteRedeemErrorMessage(error)
                        ));
                    } else {
                        this.parent.onChildOperationFinished(SharedWorldText.string("screen.sharedworld.operation_added_world", SharedWorldText.displayWorldName(result.name())));
                        this.parent.clearTransientFocus();
                        this.minecraft.setScreen(this.parent);
                    }
                }));
    }

    private static Component inviteRedeemErrorMessage(Throwable error) {
        String code = SharedWorldApiClient.errorCode(error);
        if ("invite_not_found".equals(code)) {
            return Component.translatable("screen.sharedworld.invite_error_not_found");
        }
        if ("invite_inactive".equals(code)) {
            return Component.translatable("screen.sharedworld.invite_error_inactive");
        }
        if ("invite_expired".equals(code)) {
            return Component.translatable("screen.sharedworld.invite_error_expired");
        }
        String message = SharedWorldApiClient.friendlyErrorMessage(error);
        return Component.literal(SharedWorldText.errorMessageOrDefault(message));
    }
}
