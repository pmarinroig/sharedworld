package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.CreateWorldResultDto;
import link.sharedworld.api.SharedWorldModels.WorldDetailsDto;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;

final class SharedWorldCreateFlow {
    private final CreateBackend backend;
    private final IconEncoder iconEncoder;
    private final InitialSnapshotUploadPipeline pipeline;

    SharedWorldCreateFlow(
            CreateBackend backend,
            IconEncoder iconEncoder,
            InitialSnapshotUploadPipeline.WorkingCopyStore worldStore,
            InitialSnapshotUploadPipeline.SnapshotUploader snapshotUploader,
            InitialSnapshotUploadPipeline.LeaseKeepAlive leaseKeepAlive
    ) {
        this.backend = backend;
        this.iconEncoder = iconEncoder;
        this.pipeline = new InitialSnapshotUploadPipeline(backend, worldStore, snapshotUploader, leaseKeepAlive);
    }

    /**
     * Responsibility:
     * Create a SharedWorld, stage the imported save, seed the first snapshot, and release the seed lease.
     *
     * Preconditions:
     * The request is fully populated and the caller supplies a progress sink owned by the UI.
     *
     * Postconditions:
     * The new world exists remotely with an initial snapshot, or the flow fails without stranding the
     * seed lease and without leaving a snapshot-less world behind.
     *
     * Stale-work rule:
     * Initial upload always uses the exact epoch/token returned for this create flow; the pipeline's
     * keep-alive heartbeat covers the whole copy+upload so the seed lease cannot expire mid-create.
     *
     * Authority source:
     * Backend world creation + temporary host assignment for the initial snapshot upload.
     */
    /** What the hub needs after a create: the world (to select and invite for) and the message to show. */
    record Outcome(link.sharedworld.api.SharedWorldModels.WorldSummaryDto world, String message) {
        String worldId() {
            return this.world.id();
        }
    }

    Outcome create(CreateSharedWorldScreen.CreateRequest request, InitialSnapshotUploadPipeline.ProgressSink progressSink) throws Exception {
        progressSink.updateIndeterminate(Component.translatable("screen.sharedworld.create_progress_preparing"), "create_prepare");
        String customIconBase64 = request.selectedIcon() == null
                ? null
                : this.iconEncoder.encodePngBase64(request.selectedIcon().path());
        CreateWorldResultDto result = this.backend.createWorld(
                request.name(),
                request.motd(),
                customIconBase64,
                request.importSource(),
                request.storageLink() == null ? null : request.storageLink().id(),
                request.storageLink() == null
        );
        WorldDetailsDto createdWorld = result.world();
        InitialSnapshotUploadPipeline.UploadLease uploadLease =
                this.pipeline.lease(createdWorld.id(), createdWorld.name(), result.initialUploadAssignment());

        try {
            this.pipeline.run(uploadLease, request.save().directory(), progressSink);
        } catch (Throwable throwable) {
            // The snapshot is finalized as the last step of the upload, so any failure reaching
            // here means no usable snapshot exists — delete the half-created world so a
            // snapshot-less ghost never lingers in the player's world list.
            deleteCreatedWorldQuietly(createdWorld.id(), throwable);
            throw throwable;
        }

        progressSink.updateIndeterminate(Component.translatable("screen.sharedworld.create_progress_finishing"), "create_finish");
        return new Outcome(
                link.sharedworld.api.SharedWorldModels.summaryOf(createdWorld),
                SharedWorldText.string("screen.sharedworld.operation_created_world", SharedWorldText.displayWorldName(createdWorld.name()))
        );
    }

    private void deleteCreatedWorldQuietly(String worldId, Throwable uploadFailure) {
        try {
            this.backend.deleteWorld(worldId);
        } catch (Exception exception) {
            uploadFailure.addSuppressed(exception);
        }
    }

    @FunctionalInterface
    interface IconEncoder {
        String encodePngBase64(Path path) throws IOException;
    }

    interface CreateBackend extends InitialSnapshotUploadPipeline.LeaseBackend {
        CreateWorldResultDto createWorld(
                String name,
                String motdLine1,
                String customIconPngBase64,
                link.sharedworld.api.SharedWorldModels.ImportedWorldSourceDto importSource,
                String storageLinkSessionId,
                boolean useLinkedStorageAccount
        ) throws IOException, InterruptedException;

        void deleteWorld(String worldId) throws IOException, InterruptedException;
    }
}
