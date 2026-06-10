package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.progress.SharedWorldProgressRenderer;
import link.sharedworld.progress.SharedWorldProgressState;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public final class CreateSharedWorldProgressScreen extends Screen {
    // The seed lease has a fixed startup deadline; heartbeat well inside it so a slow copy/upload
    // cannot let the lease expire mid-create.
    private static final long LEASE_KEEPALIVE_INTERVAL_MS = 30_000L;

    private final SharedWorldScreen parent;
    private final CreateSharedWorldScreen.CreateDraft draft;
    private final CreateSharedWorldScreen.CreateRequest request;
    private final ManagedWorldStore worldStore = new ManagedWorldStore();
    private final SharedWorldCreateFlow createFlow = new SharedWorldCreateFlow(
            new SharedWorldCreateFlow.CreateBackend() {
                @Override
                public link.sharedworld.api.SharedWorldModels.CreateWorldResultDto createWorld(String name, String motdLine1, String customIconPngBase64, link.sharedworld.api.SharedWorldModels.ImportedWorldSourceDto importSource, String storageLinkSessionId) throws java.io.IOException, InterruptedException {
                    return SharedWorldClient.apiClient().createWorld(name, motdLine1, null, customIconPngBase64, importSource, storageLinkSessionId);
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
                public void deleteWorld(String worldId) throws java.io.IOException, InterruptedException {
                    SharedWorldClient.apiClient().deleteWorld(worldId);
                }

                @Override
                public String canonicalAssignedPlayerUuidWithHyphens(String backendAssignedPlayerUuid) {
                    return SharedWorldClient.apiClient().canonicalAssignedPlayerUuidWithHyphens(backendAssignedPlayerUuid);
                }
            },
            path -> SharedWorldClient.customIconStore().encodePngBase64(path),
            new SharedWorldCreateFlow.WorkingCopyStore() {
                @Override
                public void resetWorkingCopy(String worldId) throws java.io.IOException {
                    CreateSharedWorldProgressScreen.this.worldStore.resetWorkingCopy(worldId);
                }

                @Override
                public java.nio.file.Path workingCopy(String worldId) {
                    return CreateSharedWorldProgressScreen.this.worldStore.workingCopy(worldId);
                }
            },
            new SharedWorldCreateFlow.SnapshotUploader() {
                private final WorldSyncCoordinator syncCoordinator = new WorldSyncCoordinator(SharedWorldClient.apiClient(), CreateSharedWorldProgressScreen.this.worldStore);

                @Override
                public void uploadSnapshot(String worldId, java.nio.file.Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken, link.sharedworld.sync.WorldSyncProgressListener progressListener) throws java.io.IOException, InterruptedException {
                    this.syncCoordinator.uploadSnapshot(worldId, worldDirectory, hostPlayerUuid, runtimeEpoch, hostToken, progressListener);
                }
            },
            heartbeat -> {
                java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "sharedworld-create-keepalive");
                    thread.setDaemon(true);
                    return thread;
                });
                scheduler.scheduleWithFixedDelay(heartbeat, LEASE_KEEPALIVE_INTERVAL_MS, LEASE_KEEPALIVE_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                return scheduler::shutdownNow;
            }
    );

    private volatile SharedWorldProgressState progressState = SharedWorldProgressState.indeterminate(
            Component.translatable("screen.sharedworld.create_progress_title"),
            Component.translatable("screen.sharedworld.progress.preparing_world"),
            "create_prepare",
            null
    );
    private boolean started;

    public CreateSharedWorldProgressScreen(
            SharedWorldScreen parent,
            CreateSharedWorldScreen.CreateDraft draft,
            CreateSharedWorldScreen.CreateRequest request
    ) {
        super(Component.translatable("screen.sharedworld.create_progress_title"));
        this.parent = parent;
        this.draft = draft;
        this.request = request;
    }

    @Override
    protected void init() {
        if (!this.started) {
            this.started = true;
            this.startCreateFlow();
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

    private void startCreateFlow() {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return this.createFlow.create(this.request, new SharedWorldCreateFlow.ProgressSink() {
                            @Override
                            public void updateDeterminate(Component label, String phase, double targetFraction, Long bytesDone, Long bytesTotal) {
                                CreateSharedWorldProgressScreen.this.updateDeterminate(label, phase, targetFraction, bytesDone, bytesTotal);
                            }

                            @Override
                            public void updateIndeterminate(Component label, String phase) {
                                CreateSharedWorldProgressScreen.this.updateIndeterminate(label, phase);
                            }
                        });
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((message, error) -> Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        this.minecraft.setScreen(CreateSharedWorldScreen.restored(
                                this.parent,
                                this.draft,
                                AbstractSharedWorldMetadataScreen.friendlyMessage(cause)
                        ));
                        return;
                    }
                    this.parent.onChildOperationFinished(message);
                    this.minecraft.setScreen(this.parent);
                }));
    }

    private void updateDeterminate(Component label, String phase, double targetFraction, Long bytesDone, Long bytesTotal) {
        this.progressState = SharedWorldProgressState.determinate(
                Component.translatable("screen.sharedworld.create_progress_title"),
                label,
                phase,
                targetFraction,
                this.progressState,
                bytesDone,
                bytesTotal
        );
    }

    private void updateIndeterminate(Component label, String phase) {
        this.progressState = SharedWorldProgressState.indeterminate(
                Component.translatable("screen.sharedworld.create_progress_title"),
                label,
                phase,
                this.progressState
        );
    }
}
