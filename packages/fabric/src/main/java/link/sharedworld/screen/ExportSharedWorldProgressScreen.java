package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldPlaySessionTracker;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.WorldDetailsDto;
import link.sharedworld.host.SharedWorldHostingManager;
import link.sharedworld.progress.SharedWorldProgressRenderer;
import link.sharedworld.progress.SharedWorldProgressState;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Runs {@link SharedWorldExportFlow} behind a progress screen. Export while
 * this client hosts or plays the world is refused up front: the sync would
 * race the live session's own writes.
 */
public final class ExportSharedWorldProgressScreen extends link.sharedworld.versioned.VersionedScreen {
    private final EditSharedWorldScreen parent;
    private final WorldDetailsDto world;
    private volatile SharedWorldProgressState progressState;
    private boolean started;

    public ExportSharedWorldProgressScreen(EditSharedWorldScreen parent, WorldDetailsDto world) {
        super(Component.empty());
        this.parent = parent;
        this.world = world;
        this.progressState = SharedWorldProgressState.indeterminate(
                this.title,
                Component.translatable("screen.sharedworld.export_progress_syncing"),
                "export_sync",
                null
        );
    }

    @Override
    protected void init() {
        if (!this.started) {
            this.started = true;
            this.startExport();
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
        SharedWorldProgressRenderer.renderCentered(guiGraphics, this.font, this.width, this.height, this.progressState, partialTick);
    }

    private void startExport() {
        String busyReason = localBusyReason(this.world.id());
        if (busyReason != null) {
            this.finishWithError(busyReason);
            return;
        }
        String playerUuid = SharedWorldClient.apiClient().authenticatedWorldPlayerUuidWithHyphens();
        Path savesDirectory = Minecraft.getInstance().gameDirectory.toPath().resolve("saves");
        ManagedWorldStore worldStore = new ManagedWorldStore();
        WorldSyncCoordinator syncCoordinator = new WorldSyncCoordinator(SharedWorldClient.apiClient(), worldStore);
        SharedWorldExportFlow flow = new SharedWorldExportFlow(syncCoordinator::ensureSynchronizedWorkingCopy, savesDirectory);

        // The warm cache polls this world's snapshots on its own schedule; keep
        // it away from the working copy while the export flow syncs it.
        SharedWorldClient.guestCacheWarmer().pauseWorld(this.world.id());
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return flow.export(
                                this.world.id(),
                                this.world.name(),
                                playerUuid,
                                (label, phase) -> this.progressState = SharedWorldProgressState.indeterminate(this.title, label, phase, null)
                        );
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    SharedWorldClient.guestCacheWarmer().resumeWorld(this.world.id());
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        this.finishWithError(SharedWorldText.errorMessageOrDefault(cause.getMessage()));
                    } else {
                        this.parent.onExportFinished(
                                SharedWorldText.string("screen.sharedworld.export_done", result.folderName()));
                    }
                }));
    }

    private void finishWithError(String message) {
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new SharedWorldErrorScreen(
                this.parent,
                Component.translatable("screen.sharedworld.error_title"),
                Component.literal(message)
        ));
    }

    private static String localBusyReason(String worldId) {
        SharedWorldHostingManager hostingManager = SharedWorldClient.hostingManager();
        SharedWorldHostingManager.ActiveHostSession hostSession = hostingManager == null ? null : hostingManager.activeHostSession();
        if (hostSession != null && worldId.equals(hostSession.worldId())) {
            return SharedWorldText.string("screen.sharedworld.export_blocked_hosting");
        }
        SharedWorldPlaySessionTracker.ActiveWorldSession playSession = SharedWorldClient.playSessionTracker().currentSession();
        if (playSession != null && worldId.equals(playSession.worldId())) {
            return SharedWorldText.string("screen.sharedworld.export_blocked_playing");
        }
        return null;
    }
}
