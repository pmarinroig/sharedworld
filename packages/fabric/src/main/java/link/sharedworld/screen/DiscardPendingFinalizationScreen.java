package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldSessionCoordinator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public final class DiscardPendingFinalizationScreen extends Screen {
    private final HandoffWaitingScreen parent;
    private final String worldId;
    private final String worldName;
    private Button confirmButton;
    private Button cancelButton;
    private boolean requestInFlight;
    private boolean closeOnSuccess;

    public DiscardPendingFinalizationScreen(HandoffWaitingScreen parent, String worldId, String worldName) {
        super(Component.translatable("screen.sharedworld.discard_finalization_title"));
        this.parent = parent;
        this.worldId = worldId;
        this.worldName = worldName;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.confirmButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.discard_pending_changes"), button -> this.confirmDiscard())
                .bounds(centerX - 100, this.height - 52, 200, 20)
                .build());
        this.cancelButton = this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> this.returnToParent())
                .bounds(centerX - 100, this.height - 28, 200, 20)
                .build());
    }

    @Override
    public void onClose() {
        this.returnToParent();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !this.requestInFlight;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int top = this.height / 2 - 48;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, top, 0xFFFFFFFF);
        List<FormattedCharSequence> lines = this.font.split(Component.translatable("screen.sharedworld.discard_finalization_detail", this.worldName), Math.min(this.width - 60, 320));
        int y = top + 24;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawCenteredString(this.font, line, centerX, y, 0xFFFF8080);
            y += 12;
        }
        Component errorMessage = currentErrorMessage();
        if (errorMessage != null) {
            for (FormattedCharSequence line : this.font.split(errorMessage, Math.min(this.width - 60, 320))) {
                guiGraphics.drawCenteredString(this.font, line, centerX, y + 8, 0xFFFF5555);
                y += 12;
            }
        }
    }

    @Override
    public void tick() {
        SharedWorldSessionCoordinator.WaitingView view = SharedWorldClient.sessionCoordinator().waitingView();
        if (view == null || !this.worldId.equals(view.worldId())) {
            this.returnToParent();
            return;
        }
        this.requestInFlight = view.discardInFlight();
        if (this.confirmButton != null) {
            this.confirmButton.active = view.canDiscardPendingFinalization() && !view.discardInFlight();
        }
        if (this.cancelButton != null) {
            this.cancelButton.active = !view.discardInFlight();
        }
        if (this.closeOnSuccess && !view.discardInFlight() && view.discardErrorMessage() == null) {
            this.parent.resumeWaitingUnregister();
            this.parent.refreshImmediately();
            this.minecraft.setScreen(this.parent);
        }
    }

    private void confirmDiscard() {
        if (this.requestInFlight) {
            return;
        }
        if (SharedWorldClient.sessionCoordinator().requestDiscardPendingFinalization()) {
            this.requestInFlight = true;
            this.closeOnSuccess = true;
        }
    }

    private Component currentErrorMessage() {
        SharedWorldSessionCoordinator.WaitingView view = SharedWorldClient.sessionCoordinator().waitingView();
        if (view == null || view.discardErrorMessage() == null || view.discardErrorMessage().isBlank()) {
            return null;
        }
        return Component.literal(view.discardErrorMessage());
    }

    private void returnToParent() {
        this.parent.resumeWaitingUnregister();
        this.minecraft.setScreen(this.parent);
    }
}
