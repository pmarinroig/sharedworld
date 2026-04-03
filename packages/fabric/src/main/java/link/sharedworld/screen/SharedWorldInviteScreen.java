package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public final class SharedWorldInviteScreen extends Screen {
    private final SharedWorldScreen parent;
    private final WorldSummaryDto world;
    private boolean started;
    private boolean actionInFlight;
    private boolean confirmCreateNewCode;
    private String inviteCode;
    private Button doneButton;
    private Button createNewCodeButton;

    public SharedWorldInviteScreen(SharedWorldScreen parent, WorldSummaryDto world) {
        super(Component.translatable("screen.sharedworld.invite_title"));
        this.parent = parent;
        this.world = world;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.createNewCodeButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.invite_create_new_code"), button -> this.onCreateNewCode())
                .bounds(centerX - 100, this.height - 52, 200, 20)
                .build());
        this.doneButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.done"), button -> {
                    this.parent.clearFocus();
                    this.minecraft.setScreen(this.parent);
                })
                .bounds(centerX - 100, this.height - 28, 200, 20)
                .build());
        this.updateButtons();

        if (!this.started) {
            this.started = true;
            this.createInvite();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 32, 0xFFFFFFFF);
        if (this.inviteCode == null) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.translatable("screen.sharedworld.invite_creating"),
                    this.width / 2,
                    72,
                    0xFFB0B0B0
            );
        } else {
            guiGraphics.drawCenteredString(this.font, Component.translatable("screen.sharedworld.invite_share_line_1"), this.width / 2, 76, 0xFFFFFFFF);
            guiGraphics.drawCenteredString(this.font, Component.translatable("screen.sharedworld.invite_share_line_2"), this.width / 2, 88, 0xFFFFFFFF);
            guiGraphics.drawCenteredString(this.font, Component.literal(this.inviteCode), this.width / 2, 120, 0xFFFFFFFF);
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.translatable("screen.sharedworld.invite_copied"),
                    this.width / 2,
                    146,
                    0xFFB0B0B0
            );
            if (this.confirmCreateNewCode) {
                guiGraphics.drawCenteredString(
                        this.font,
                        Component.translatable("screen.sharedworld.invite_replace_confirm"),
                        this.width / 2,
                        160,
                        0xFFFFD37A
                );
            }
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void createInvite() {
        this.actionInFlight = true;
        this.confirmCreateNewCode = false;
        this.updateButtons();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return SharedWorldClient.apiClient().createInvite(this.world.id());
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    this.actionInFlight = false;
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        this.minecraft.setScreen(new SharedWorldErrorScreen(
                                this.parent,
                                Component.translatable("screen.sharedworld.error_title"),
                                Component.literal(SharedWorldText.errorMessageOrDefault(cause.getMessage()))
                        ));
                    } else {
                        this.inviteCode = result.code();
                        this.minecraft.keyboardHandler.setClipboard(result.code());
                        this.parent.onChildOperationFinished(SharedWorldText.string("screen.sharedworld.operation_copied_share_code", displayName(this.world)));
                        this.updateButtons();
                    }
                }));
    }

    private void createNewCode() {
        this.actionInFlight = true;
        this.confirmCreateNewCode = false;
        this.updateButtons();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return SharedWorldClient.apiClient().resetInvite(this.world.id());
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    this.actionInFlight = false;
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        this.minecraft.setScreen(new SharedWorldErrorScreen(
                                this.parent,
                                Component.translatable("screen.sharedworld.error_title"),
                                Component.literal(SharedWorldText.errorMessageOrDefault(cause.getMessage()))
                        ));
                    } else {
                        this.inviteCode = result.invite().code();
                        this.minecraft.keyboardHandler.setClipboard(result.invite().code());
                        this.parent.onChildOperationFinished(SharedWorldText.string("screen.sharedworld.operation_created_share_code", displayName(this.world)));
                        this.updateButtons();
                    }
                }));
    }

    private void onCreateNewCode() {
        if (this.inviteCode == null || this.actionInFlight) {
            return;
        }
        if (this.confirmCreateNewCode) {
            this.createNewCode();
            return;
        }
        this.confirmCreateNewCode = true;
        this.updateButtons();
    }

    private void updateButtons() {
        if (this.doneButton != null) {
            this.doneButton.active = !this.actionInFlight;
        }
        if (this.createNewCodeButton != null) {
            this.createNewCodeButton.active = this.inviteCode != null && !this.actionInFlight;
            this.createNewCodeButton.setMessage(Component.translatable(this.confirmCreateNewCode
                    ? "screen.sharedworld.invite_confirm_new_code"
                    : "screen.sharedworld.invite_create_new_code"));
        }
    }

    private static String displayName(WorldSummaryDto world) {
        return SharedWorldText.displayWorldName(world.name());
    }
}
