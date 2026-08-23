package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.EnterSessionResponseDto;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Replaces a shared world's content from a local save. The lease comes from the
 * ordinary enterSession host path; when a runtime is live the backend answers
 * connect/wait instead of host, which is exactly the busy protection replace
 * needs, and the upload rides {@link InitialSnapshotUploadPipeline}, so the
 * working copy reset forces a full (non-delta) snapshot and every other client
 * re-downloads cleanly. On failure the lease is released without a snapshot, so
 * the previous latest snapshot stays authoritative; the world is never deleted.
 */
final class SharedWorldReplaceFlow {
    private final ReplaceBackend backend;
    private final InitialSnapshotUploadPipeline pipeline;

    SharedWorldReplaceFlow(
            ReplaceBackend backend,
            InitialSnapshotUploadPipeline.WorkingCopyStore worldStore,
            InitialSnapshotUploadPipeline.SnapshotUploader snapshotUploader,
            InitialSnapshotUploadPipeline.LeaseKeepAlive leaseKeepAlive
    ) {
        this.backend = backend;
        this.pipeline = new InitialSnapshotUploadPipeline(backend, worldStore, snapshotUploader, leaseKeepAlive);
    }

    String replace(
            String worldId,
            String worldName,
            Path sourceDirectory,
            boolean acknowledgeUncleanShutdown,
            InitialSnapshotUploadPipeline.ProgressSink progressSink
    ) throws Exception {
        progressSink.updateIndeterminate(Component.translatable("screen.sharedworld.create_progress_preparing"), "replace_prepare");
        EnterSessionResponseDto entered = this.backend.enterSession(worldId, acknowledgeUncleanShutdown);
        String action = entered.action();
        if ("warn-host".equals(action)) {
            throw new UncleanShutdownPendingException();
        }
        if (!"host".equals(action) || entered.assignment() == null) {
            // connect / wait: someone is hosting or about to host.
            throw new WorldBusyException(SharedWorldText.string("screen.sharedworld.replace_blocked_busy"));
        }

        InitialSnapshotUploadPipeline.UploadLease lease = this.pipeline.lease(worldId, worldName, entered.assignment());
        this.pipeline.run(lease, sourceDirectory, progressSink);
        return SharedWorldText.string("screen.sharedworld.replace_done", SharedWorldText.displayWorldName(worldName));
    }

    interface ReplaceBackend extends InitialSnapshotUploadPipeline.LeaseBackend {
        EnterSessionResponseDto enterSession(String worldId, boolean acknowledgeUncleanShutdown) throws IOException, InterruptedException;
    }

    /** The world has (or is about to have) a live host; replacing now would fight it. */
    static final class WorldBusyException extends Exception {
        WorldBusyException(String message) {
            super(message);
        }
    }

    /** The backend wants the unclean-shutdown warning acknowledged before handing out the lease. */
    static final class UncleanShutdownPendingException extends Exception {
        UncleanShutdownPendingException() {
            super("unclean shutdown warning pending");
        }
    }
}
