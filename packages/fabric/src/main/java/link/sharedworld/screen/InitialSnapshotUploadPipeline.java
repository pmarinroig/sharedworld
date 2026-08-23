package link.sharedworld.screen;

import link.sharedworld.api.SharedWorldModels.HostAssignmentDto;
import link.sharedworld.sync.WorldCanonicalizer;
import link.sharedworld.sync.WorldSyncCoordinator;
import link.sharedworld.sync.WorldSyncProgress;
import link.sharedworld.sync.WorldSyncProgressListener;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The lease-scoped initial-snapshot upload shared by world creation and world
 * replacement: keep the seed host-starting lease alive across the whole
 * copy+upload, reset the working copy (which also clears delta baselines, so
 * the upload is a full snapshot), copy the source save in minus local-only
 * files, upload with the lease's exact epoch/token, and always release the
 * lease afterwards. A release failure after a committed snapshot is cosmetic;
 * the lease expires on its own, and must never fail the operation.
 */
final class InitialSnapshotUploadPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-initial-upload");

    private final LeaseBackend backend;
    private final WorkingCopyStore worldStore;
    private final SnapshotUploader snapshotUploader;
    private final LeaseKeepAlive leaseKeepAlive;

    InitialSnapshotUploadPipeline(
            LeaseBackend backend,
            WorkingCopyStore worldStore,
            SnapshotUploader snapshotUploader,
            LeaseKeepAlive leaseKeepAlive
    ) {
        this.backend = backend;
        this.worldStore = worldStore;
        this.snapshotUploader = snapshotUploader;
        this.leaseKeepAlive = leaseKeepAlive;
    }

    UploadLease lease(String worldId, String worldName, HostAssignmentDto assignment) {
        if (assignment == null) {
            throw new IllegalStateException("SharedWorld couldn't acquire a temporary host assignment for the initial snapshot upload of " + worldName + ".");
        }
        return new UploadLease(
                worldId,
                this.backend.canonicalAssignedPlayerUuidWithHyphens(assignment.playerUuid()),
                assignment.runtimeEpoch(),
                assignment.hostToken()
        );
    }

    void run(UploadLease lease, Path sourceDirectory, ProgressSink progressSink) throws Exception {
        Throwable uploadFailure = null;
        AutoCloseable keepAlive = this.leaseKeepAlive.start(() -> heartbeatLeaseQuietly(lease));
        try {
            progressSink.updateDeterminate(Component.translatable("screen.sharedworld.create_progress_copying"), "create_copy", 0.0D, 0L, 0L);
            this.worldStore.resetWorkingCopy(lease.worldId());
            Path workingCopy = this.worldStore.workingCopy(lease.worldId());
            copyIntoManagedWorldWithProgress(sourceDirectory, workingCopy, progressSink);

            progressSink.updateIndeterminate(Component.translatable("screen.sharedworld.create_progress_uploading"), "create_upload_prepare");
            this.snapshotUploader.uploadSnapshot(
                    lease.worldId(),
                    workingCopy,
                    lease.hostPlayerUuid(),
                    lease.runtimeEpoch(),
                    lease.hostToken(),
                    progress -> applyUploadProgress(progress, progressSink)
            );
        } catch (Throwable throwable) {
            uploadFailure = throwable;
            throw throwable;
        } finally {
            closeQuietly(keepAlive);
            releaseLease(lease, uploadFailure);
        }
    }

    private void releaseLease(UploadLease lease, Throwable uploadFailure) {
        try {
            this.backend.releaseHost(lease.worldId(), false, lease.runtimeEpoch(), lease.hostToken());
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (uploadFailure != null) {
                uploadFailure.addSuppressed(exception);
            } else {
                LOGGER.warn(
                        "SharedWorld uploaded the initial snapshot for '{}' but could not release its seed host lease; it will expire on its own.",
                        lease.worldId(),
                        exception
                );
            }
        }
    }

    private void heartbeatLeaseQuietly(UploadLease lease) {
        try {
            this.backend.heartbeatHost(lease.worldId(), lease.runtimeEpoch(), lease.hostToken());
        } catch (Exception ignored) {
            // A transient heartbeat failure must not abort the operation; if the lease is truly
            // gone the upload's own epoch/token check will surface it.
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private void copyIntoManagedWorldWithProgress(Path source, Path workingCopy, ProgressSink progressSink) throws IOException {
        Files.createDirectories(workingCopy);
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(source)) {
            paths = stream.sorted(Comparator.naturalOrder())
                    .filter(path -> !Files.isRegularFile(path) || !WorldCanonicalizer.isLocalOnlyFileName(path.getFileName().toString()))
                    .toList();
        }

        long totalBytes = paths.stream()
                .filter(Files::isRegularFile)
                .mapToLong(InitialSnapshotUploadPipeline::safeSize)
                .sum();
        long copiedBytes = 0L;

        for (Path path : paths) {
            if (Thread.currentThread().isInterrupted()) {
                // Cancel support: a large world copy must notice the interrupt
                // between files, not only during network I/O.
                throw new IOException("Copy cancelled.");
            }
            Path relative = source.relativize(path);
            if (relative.toString().isBlank()) {
                continue;
            }
            Path target = workingCopy.resolve(relative.toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(target);
                continue;
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            try (InputStream input = Files.newInputStream(path);
                 OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    copiedBytes += read;
                    double fraction = totalBytes <= 0L ? 1.0D : Math.min(1.0D, (double) copiedBytes / (double) totalBytes);
                    progressSink.updateDeterminate(
                            Component.translatable("screen.sharedworld.create_progress_copying"),
                            "create_copy",
                            fraction,
                            copiedBytes,
                            totalBytes
                    );
                }
            }

            try {
                FileTime lastModifiedTime = Files.getLastModifiedTime(path);
                Files.setLastModifiedTime(target, lastModifiedTime);
            } catch (IOException ignored) {
            }
        }

        progressSink.updateDeterminate(Component.translatable("screen.sharedworld.create_progress_copying"), "create_copy", 1.0D, totalBytes, totalBytes);
    }

    private void applyUploadProgress(WorldSyncProgress progress, ProgressSink progressSink) {
        switch (progress.stage()) {
            case WorldSyncCoordinator.STAGE_UPLOADING_CHANGED_FILES -> progressSink.updateDeterminate(
                    Component.translatable("screen.sharedworld.create_progress_uploading"),
                    "create_upload",
                    progress.fraction(),
                    progress.bytesDone(),
                    progress.bytesTotal()
            );
            case WorldSyncCoordinator.STAGE_FINALIZING_SNAPSHOT -> progressSink.updateIndeterminate(
                    Component.translatable("screen.sharedworld.create_progress_finishing"),
                    "create_finish"
            );
            default -> progressSink.updateIndeterminate(
                    Component.translatable("screen.sharedworld.create_progress_preparing"),
                    "create_upload_prepare"
            );
        }
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return 0L;
        }
    }

    record UploadLease(String worldId, String hostPlayerUuid, long runtimeEpoch, String hostToken) {
    }

    interface LeaseBackend {
        void releaseHost(String worldId, boolean graceful, long runtimeEpoch, String hostToken) throws IOException, InterruptedException;

        void heartbeatHost(String worldId, long runtimeEpoch, String hostToken) throws IOException, InterruptedException;

        String canonicalAssignedPlayerUuidWithHyphens(String backendAssignedPlayerUuid);
    }

    interface WorkingCopyStore {
        void resetWorkingCopy(String worldId) throws IOException;

        Path workingCopy(String worldId);
    }

    interface SnapshotUploader {
        void uploadSnapshot(
                String worldId,
                Path worldDirectory,
                String hostPlayerUuid,
                long runtimeEpoch,
                String hostToken,
                WorldSyncProgressListener progressListener
        ) throws IOException, InterruptedException;
    }

    /**
     * Keeps the seed host-starting lease alive for the duration of the copy+upload. The
     * production implementation schedules a periodic heartbeat on a background thread; tests can
     * drive the heartbeat synchronously. start() begins keeping the lease alive and returns a
     * handle whose close() stops it.
     */
    interface LeaseKeepAlive {
        AutoCloseable start(Runnable heartbeat);
    }

    interface ProgressSink {
        void updateDeterminate(Component label, String phase, double targetFraction, Long bytesDone, Long bytesTotal);

        void updateIndeterminate(Component label, String phase);
    }
}
