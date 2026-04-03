package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.progress.SharedWorldProgressRenderer;
import link.sharedworld.progress.SharedWorldProgressState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public final class DeleteSharedWorldProgressScreen extends Screen {
    private final SharedWorldScreen parent;
    private final WorldSummaryDto world;
    private final boolean ownerDelete;
    private volatile SharedWorldProgressState progressState;
    private boolean started;

    public DeleteSharedWorldProgressScreen(SharedWorldScreen parent, WorldSummaryDto world) {
        super(Component.empty());
        this.parent = parent;
        this.world = world;
        this.ownerDelete = isOwner(world);
        this.progressState = SharedWorldProgressState.indeterminate(
                this.title,
                Component.translatable(this.ownerDelete
                        ? "screen.sharedworld.delete_progress_owner"
                        : "screen.sharedworld.delete_progress_member"),
                "delete_request",
                null
        );
    }

    @Override
    protected void init() {
        if (!this.started) {
            this.started = true;
            this.startDeleteFlow();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderMenuBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        SharedWorldProgressRenderer.renderCentered(guiGraphics, this.font, this.width, this.height, this.progressState, partialTick);
    }

    private void startDeleteFlow() {
        CompletableFuture
                .runAsync(() -> {
                    try {
                        SharedWorldClient.apiClient().deleteWorld(this.world.id());
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        this.minecraft.setScreen(new SharedWorldErrorScreen(
                                this.parent,
                                Component.translatable("screen.sharedworld.error_title"),
                                Component.literal(SharedWorldText.errorMessageOrDefault(cause.getMessage()))
                        ));
                    } else {
                        SharedWorldClient.releaseCoordinator().discardPendingReleaseIfMatches(this.world.id());
                        this.parent.onChildOperationFinished(this.ownerDelete
                                ? SharedWorldText.string("screen.sharedworld.operation_deleted_world", displayName(this.world))
                                : SharedWorldText.string("screen.sharedworld.operation_left_world", displayName(this.world)));
                        this.parent.clearFocus();
                        this.minecraft.setScreen(this.parent);
                    }
                }));
    }

    private static boolean isOwner(WorldSummaryDto world) {
        String ownerUuid = world.ownerUuid();
        if (ownerUuid == null || ownerUuid.isBlank()) {
            return false;
        }
        return ownerUuid.replace("-", "").equalsIgnoreCase(SharedWorldApiClient.currentPlayerUuid());
    }

    private static String displayName(WorldSummaryDto world) {
        return SharedWorldText.displayWorldName(world.name());
    }
}
