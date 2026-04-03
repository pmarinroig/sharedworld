package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.HostAssignmentDto;
import link.sharedworld.api.SharedWorldModels.SnapshotManifestDto;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.host.SharedWorldHostingManager;
import link.sharedworld.progress.SharedWorldProgressRenderer;
import link.sharedworld.progress.SharedWorldProgressState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class HostAcquiredScreen extends Screen {
    private final Screen parent;
    private final WorldSummaryDto world;
    private final SnapshotManifestDto latestManifest;
    private final HostAssignmentDto assignment;
    private Button actionButton;
    private boolean cancelRequested;

    public HostAcquiredScreen(Screen parent, WorldSummaryDto world, SnapshotManifestDto latestManifest, HostAssignmentDto assignment) {
        super(Component.translatable("screen.sharedworld.host_acquired"));
        this.parent = parent;
        this.world = world;
        this.latestManifest = latestManifest;
        this.assignment = assignment;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.actionButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.cancel"), button -> {
                    SharedWorldHostingManager manager = SharedWorldClient.hostingManager();
                    SharedWorldHostingManager.StartupView view = manager.startupView();
                    if (view.hasError()) {
                        this.parent.clearFocus();
                        this.minecraft.setScreen(this.parent);
                        return;
                    }
                    this.cancelRequested = true;
                    manager.cancelStartup();
                })
                .bounds(centerX - 100, this.height - 28, 200, 20)
                .build());
    }

    @Override
    public void tick() {
        SharedWorldHostingManager manager = SharedWorldClient.hostingManager();
        SharedWorldHostingManager.StartupView view = manager.startupView();
        if (view.hasError()) {
            this.minecraft.setScreen(new SharedWorldErrorScreen(
                    this.parent,
                    Component.translatable("screen.sharedworld.error_host_title"),
                    Component.literal(SharedWorldText.errorMessageOrDefault(view.errorMessage()))
            ));
            return;
        }
        if (this.cancelRequested && view.complete()) {
            if (this.parent instanceof HandoffWaitingScreen waitingScreen) {
                waitingScreen.resumeAfterHostStartupCancel();
            }
            this.parent.clearFocus();
            this.minecraft.setScreen(this.parent);
            return;
        }
        if (this.actionButton != null) {
            if (view.canCancel()) {
                this.actionButton.visible = true;
                this.actionButton.active = !this.cancelRequested;
                this.actionButton.setMessage(Component.translatable("screen.sharedworld.cancel"));
            } else {
                this.actionButton.visible = false;
                this.actionButton.active = false;
            }
        }
    }

    @Override
    public void onClose() {
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        SharedWorldHostingManager.StartupView view = SharedWorldClient.hostingManager().startupView();
        SharedWorldProgressState progressState = view.progressState() != null
                ? view.progressState()
                : SharedWorldProgressState.indeterminate(
                        Component.translatable("screen.sharedworld.starting_title"),
                        Component.translatable("screen.sharedworld.progress.preparing_world"),
                        "starting",
                        null
                );
        SharedWorldProgressRenderer.renderCentered(guiGraphics, this.font, this.width, this.height, progressState, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
