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
 * Runs {@link SharedWorldReplaceFlow} behind a progress screen. The unclean
 * shutdown warning, if present, is acknowledged up front: replacing overwrites
 * the world's content wholesale (behind an explicit confirm), so a crashed
 * host's pending state is irrelevant by the user's own decision.
 */
public final class ReplaceSharedWorldProgressScreen extends link.sharedworld.versioned.VersionedScreen {
    private static final long LEASE_KEEPALIVE_INTERVAL_MS = 30_000L;

    private final EditSharedWorldScreen parent;
    private final WorldDetailsDto world;
    private final Path sourceDirectory;
    private volatile SharedWorldProgressState progressState;
    private boolean started;

    public ReplaceSharedWorldProgressScreen(EditSharedWorldScreen parent, WorldDetailsDto world, Path sourceDirectory) {
        super(Component.translatable("screen.sharedworld.replace_progress_title"));
        this.parent = parent;
        this.world = world;
        this.sourceDirectory = sourceDirectory;
        this.progressState = SharedWorldProgressState.indeterminate(
                this.title,
                Component.translatable("screen.sharedworld.create_progress_preparing"),
                "replace_prepare",
                null
        );
    }

    @Override
    protected void init() {
        if (!this.started) {
            this.started = true;
            this.startReplace();
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

    private void startReplace() {
        String busyReason = localBusyReason(this.world.id());
        if (busyReason != null) {
            this.finishWithError(busyReason);
            return;
        }
        ManagedWorldStore worldStore = new ManagedWorldStore();
        WorldSyncCoordinator syncCoordinator = new WorldSyncCoordinator(SharedWorldClient.apiClient(), worldStore);
        SharedWorldReplaceFlow flow = new SharedWorldReplaceFlow(
                new SharedWorldReplaceFlow.ReplaceBackend() {
                    @Override
                    public link.sharedworld.api.SharedWorldModels.EnterSessionResponseDto enterSession(String worldId, boolean acknowledgeUncleanShutdown) throws java.io.IOException, InterruptedException {
                        return SharedWorldClient.apiClient().enterSession(worldId, null, acknowledgeUncleanShutdown);
                    }

                    @Override
                    public void releaseHost(String worldId, boolean graceful, long runtimeEpoch, String hostToken) throws java.io.IOException, InterruptedException {
                        SharedWorldClient.apiClient().releaseHost(worldId, graceful, runtimeEpoch, hostToken);
                    }

                    @Override
                    public void heartbeatHost(String worldId, long runtimeEpoch, String hostToken) throws java.io.IOException, InterruptedException {
                        SharedWorldClient.apiClient().heartbeatHost(worldId, runtimeEpoch, hostToken, null);
                    }

                    @Override
                    public String canonicalAssignedPlayerUuidWithHyphens(String backendAssignedPlayerUuid) {
                        return SharedWorldClient.apiClient().canonicalAssignedPlayerUuidWithHyphens(backendAssignedPlayerUuid);
                    }
                },
                new InitialSnapshotUploadPipeline.WorkingCopyStore() {
                    @Override
                    public void resetWorkingCopy(String worldId) throws java.io.IOException {
                        worldStore.resetWorkingCopy(worldId);
                    }

                    @Override
                    public Path workingCopy(String worldId) {
                        return worldStore.workingCopy(worldId);
                    }
                },
                syncCoordinator::uploadSnapshot,
                heartbeat -> {
                    java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "sharedworld-replace-keepalive");
                        thread.setDaemon(true);
                        return thread;
                    });
                    scheduler.scheduleWithFixedDelay(heartbeat, LEASE_KEEPALIVE_INTERVAL_MS, LEASE_KEEPALIVE_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                    return scheduler::shutdownNow;
                }
        );

        SharedWorldClient.guestCacheWarmer().pauseWorld(this.world.id());
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return flow.replace(
                                this.world.id(),
                                this.world.name(),
                                this.sourceDirectory,
                                true,
                                new InitialSnapshotUploadPipeline.ProgressSink() {
                                    @Override
                                    public void updateDeterminate(Component label, String phase, double targetFraction, Long bytesDone, Long bytesTotal) {
                                        ReplaceSharedWorldProgressScreen.this.progressState = SharedWorldProgressState.determinate(
                                                ReplaceSharedWorldProgressScreen.this.title,
                                                label,
                                                phase,
                                                targetFraction,
                                                ReplaceSharedWorldProgressScreen.this.progressState,
                                                bytesDone,
                                                bytesTotal
                                        );
                                    }

                                    @Override
                                    public void updateIndeterminate(Component label, String phase) {
                                        ReplaceSharedWorldProgressScreen.this.progressState = SharedWorldProgressState.indeterminate(
                                                ReplaceSharedWorldProgressScreen.this.title,
                                                label,
                                                phase,
                                                ReplaceSharedWorldProgressScreen.this.progressState
                                        );
                                    }
                                }
                        );
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((message, error) -> Minecraft.getInstance().execute(() -> {
                    SharedWorldClient.guestCacheWarmer().resumeWorld(this.world.id());
                    if (error != null) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        this.finishWithError(SharedWorldText.errorMessageOrDefault(cause.getMessage()));
                        return;
                    }
                    this.parent.onReplaceFinished(message);
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
            return SharedWorldText.string("screen.sharedworld.replace_blocked_hosting");
        }
        SharedWorldPlaySessionTracker.ActiveWorldSession playSession = SharedWorldClient.playSessionTracker().currentSession();
        if (playSession != null && worldId.equals(playSession.worldId())) {
            return SharedWorldText.string("screen.sharedworld.replace_blocked_playing");
        }
        return null;
    }
}
