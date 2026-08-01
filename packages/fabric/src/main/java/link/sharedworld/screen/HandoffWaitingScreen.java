package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldSessionCoordinator;
import link.sharedworld.progress.SharedWorldProgressRenderer;
import link.sharedworld.progress.SharedWorldProgressState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class HandoffWaitingScreen extends link.sharedworld.versioned.VersionedScreen {
    private final Screen parent;
    private final String worldId;
    private final String worldName;
    private boolean unregisterSuspended;

    public HandoffWaitingScreen(Screen parent, String worldId, String worldName) {
        this(parent, worldId, worldName, null);
    }

    public HandoffWaitingScreen(Screen parent, String worldId, String worldName, String ownerUuid) {
        super(Component.translatable("screen.sharedworld.joining_title"));
        this.parent = parent;
        this.worldId = worldId;
        this.worldName = worldName;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.cancel"), button -> SharedWorldClient.sessionCoordinator().cancelWaiting())
                .bounds(centerX - 100, this.height - 28, 200, 20)
                .build());
    }

    @Override
    public void removed() {
        if (this.unregisterSuspended) {
            return;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        SharedWorldClient.sessionCoordinator().cancelWaiting();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        SharedWorldSessionCoordinator.WaitingView view = SharedWorldClient.sessionCoordinator().waitingView();
        SharedWorldProgressState progressState = view != null && view.progressState() != null
                ? view.progressState()
                : SharedWorldProgressState.indeterminate(this.title, Component.translatable("screen.sharedworld.progress.waiting_for_host"), "handoff_wait", null);
        SharedWorldProgressRenderer.renderCentered(guiGraphics, this.font, this.width, this.height, progressState, partialTick);
        // A failed cancel must not silently revert to "Waiting": show why the
        // player is still here.
        if (view != null && view.discardErrorMessage() != null && !view.discardErrorMessage().isBlank()) {
            guiGraphics.drawCenteredString(this.font, Component.literal(view.discardErrorMessage()), this.width / 2, this.height - 44, 0xFF6666);
        }
    }

    public void resumeAfterHostStartupCancel() {
        SharedWorldClient.sessionCoordinator().resumeAfterHostStartupCancel();
    }
}
