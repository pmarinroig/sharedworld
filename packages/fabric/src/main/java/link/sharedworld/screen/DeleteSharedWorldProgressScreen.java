package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.progress.SharedWorldProgressRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public final class DeleteSharedWorldProgressScreen extends link.sharedworld.versioned.VersionedScreen {
    private final SharedWorldScreen parent;
    private final WorldSummaryDto world;
    private final boolean ownerDelete;
    private final Component label;
    private boolean started;
    /** 0 = indeterminate; the worker advances it per deleted backup. */
    private volatile float progress;
    private volatile Component progressDetail;

    public DeleteSharedWorldProgressScreen(SharedWorldScreen parent, WorldSummaryDto world) {
        super(Component.empty());
        this.parent = parent;
        this.world = world;
        this.ownerDelete = isOwner(world);
        this.label = Component.translatable(this.ownerDelete
                ? "screen.sharedworld.delete_progress_owner"
                : "screen.sharedworld.delete_progress_member");
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
        this.sharedworldRenderMenuBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // Owner deletes advance per removed backup (a real fraction); the
        // fast member-leave path stays an indeterminate activity band.
        Component detail = this.progressDetail;
        float fraction = this.progress;
        if (fraction > 0.0F) {
            SharedWorldProgressRenderer.renderCenteredBar(
                    guiGraphics, this.font, this.width, this.height,
                    this.title, detail != null ? detail : this.label, fraction, null, null, partialTick);
        } else {
            SharedWorldProgressRenderer.renderCenteredBar(
                    guiGraphics, this.font, this.width, this.height,
                    this.title, this.label, 0.0F, 0.0F, 1.0F, partialTick);
        }
    }

    private void startDeleteFlow() {
        CompletableFuture
                .runAsync(() -> {
                    try {
                        if (this.ownerDelete) {
                            deleteBackupsThenWorld();
                        } else {
                            SharedWorldClient.apiClient().deleteWorld(this.world.id());
                        }
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((ignored, error) -> ScreenGuards.runIfCurrent(this, () -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new SharedWorldErrorScreen(
                                this.parent,
                                Component.translatable("screen.sharedworld.error_title"),
                                Component.literal(SharedWorldText.errorMessageOrDefault(cause.getMessage()))
                        ));
                    } else {
                        SharedWorldClient.releaseCoordinator().discardPendingReleaseIfMatches(this.world.id());
                        this.parent.onChildOperationFinished(null, null);
                        link.sharedworld.versioned.GuiCompat.clearFocus(this.parent);
                        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, this.parent);
                    }
                }));
    }

    /**
     * Owner deletes remove backups one by one so the bar shows real progress
     * (the old single request purged everything opaquely and could sit for a
     * long time on Drive-heavy worlds). Order falls out of the chain rules:
     * the backend refuses the latest backup and delta bases still in use, so
     * each round deletes the current leaves and the final world delete
     * cleans up whatever stayed protected.
     */
    private void deleteBackupsThenWorld() throws Exception {
        SharedWorldApiClient api = SharedWorldClient.apiClient();
        java.util.List<link.sharedworld.api.SharedWorldModels.WorldSnapshotSummaryDto> remaining =
                new java.util.ArrayList<>(java.util.Arrays.asList(api.listSnapshots(this.world.id())));
        int total = remaining.size() + 1;
        int done = 0;
        boolean deletedAnyThisRound = true;
        while (!remaining.isEmpty() && deletedAnyThisRound) {
            deletedAnyThisRound = false;
            for (java.util.Iterator<link.sharedworld.api.SharedWorldModels.WorldSnapshotSummaryDto> iterator = remaining.iterator(); iterator.hasNext(); ) {
                link.sharedworld.api.SharedWorldModels.WorldSnapshotSummaryDto snapshot = iterator.next();
                try {
                    api.deleteSnapshot(this.world.id(), snapshot.snapshotId());
                } catch (SharedWorldApiClient.SharedWorldApiException exception) {
                    if (isProtectedUntilWorldDelete(exception)) {
                        continue;
                    }
                    throw exception;
                }
                iterator.remove();
                done += 1;
                updateProgress(done, total);
                deletedAnyThisRound = true;
            }
        }
        api.deleteWorld(this.world.id());
        updateProgress(total, total);
    }

    /** The latest backup and in-use delta bases fall with the world itself. */
    private static boolean isProtectedUntilWorldDelete(SharedWorldApiClient.SharedWorldApiException exception) {
        return "cannot_delete_latest_snapshot".equals(exception.error())
                || "snapshot_base_in_use".equals(exception.error());
    }

    private void updateProgress(int done, int total) {
        this.progress = total == 0 ? 1.0F : (float) done / total;
        this.progressDetail = Component.translatable(
                "screen.sharedworld.delete_progress_backups", done, total);
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
