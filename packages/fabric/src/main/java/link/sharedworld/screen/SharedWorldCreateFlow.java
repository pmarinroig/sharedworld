package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.HostAssignmentDto;
import link.sharedworld.api.SharedWorldModels.CreateWorldResultDto;
import link.sharedworld.api.SharedWorldModels.WorldDetailsDto;
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

final class SharedWorldCreateFlow {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-create");

    private final CreateBackend backend;
    private final IconEncoder iconEncoder;
    private final WorkingCopyStore worldStore;
    private final SnapshotUploader snapshotUploader;
    private final LeaseKeepAlive leaseKeepAlive;

    SharedWorldCreateFlow(
            CreateBackend backend,
            IconEncoder iconEncoder,
            WorkingCopyStore worldStore,
            SnapshotUploader snapshotUploader,
            LeaseKeepAlive leaseKeepAlive
    ) {
        this.backend = backend;
        this.iconEncoder = iconEncoder;
        this.worldStore = worldStore;
        this.snapshotUploader = snapshotUploader;
        this.leaseKeepAlive = leaseKeepAlive;
    }

    /**
     * Responsibility:
     * Create a SharedWorld, stage the imported save, seed the first snapshot, and release the temporary seed lease.
     *
     * Preconditions:
     * The request is fully populated and the caller supplies a progress sink owned by the UI.
     *
     * Postconditions:
     * The new world exists remotely with an initial snapshot, or the flow fails without stranding the
     * seed lease and without leaving a snapshot-less world behind.
     *
     * Stale-work rule:
     * Initial upload always uses the exact epoch/token returned by enterSession for this create flow.
     * A keep-alive heartbeat runs for the whole copy+upload so a large world or slow link cannot let
     * the seed host-starting lease expire mid-create.
     *
     * Authority source:
     * Backend world creation + temporary host assignment for the initial snapshot upload.
     */
    String create(CreateSharedWorldScreen.CreateRequest request, ProgressSink progressSink) throws Exception {
        progressSink.updateIndeterminate(Component.translatable("screen.sharedworld.create_progress_preparing"), "create_prepare");
        String customIconBase64 = request.selectedIcon() == null
                ? null
                : this.iconEncoder.encodePngBase64(request.selectedIcon().path());
        CreateWorldResultDto result = this.backend.createWorld(
                request.name(),
                request.motd(),
                customIconBase64,
                request.importSource(),
                request.storageLink().id()
        );
        WorldDetailsDto createdWorld = result.world();
        InitialUploadLease uploadLease = requireInitialUploadLease(createdWorld.id(), createdWorld.name(), result.initialUploadAssignment());

        // The seed lease is created host-starting with a fixed startup deadline. Copying a large
        // save and uploading it can outlast that deadline, so keep the lease alive across both
        // phases; a missed heartbeat must never abort create (the upload's own authority check is
        // the real gate).
        Throwable uploadFailure = null;
        AutoCloseable keepAlive = this.leaseKeepAlive.start(() -> heartbeatSeedLeaseQuietly(uploadLease));
        try {
            progressSink.updateDeterminate(Component.translatable("screen.sharedworld.create_progress_copying"), "create_copy", 0.0D, 0L, 0L);
            this.worldStore.resetWorkingCopy(createdWorld.id());
            Path workingCopy = this.worldStore.workingCopy(createdWorld.id());
            copyIntoManagedWorldWithProgress(request.save().directory(), workingCopy, progressSink);

            progressSink.updateIndeterminate(Component.translatable("screen.sharedworld.create_progress_uploading"), "create_upload_prepare");
            this.snapshotUploader.uploadSnapshot(
                    createdWorld.id(),
                    workingCopy,
                    uploadLease.hostPlayerUuid(),
                    uploadLease.runtimeEpoch(),
                    uploadLease.hostToken(),
                    progress -> applyUploadProgress(progress, progressSink)
            );
        } catch (Throwable throwable) {
            uploadFailure = throwable;
            throw throwable;
        } finally {
            closeQuietly(keepAlive);
            finishInitialUpload(uploadLease, uploadFailure);
        }

        progressSink.updateIndeterminate(Component.translatable("screen.sharedworld.create_progress_finishing"), "create_finish");
        return SharedWorldText.string("screen.sharedworld.operation_created_world", SharedWorldText.displayWorldName(createdWorld.name()));
    }

    private InitialUploadLease requireInitialUploadLease(String worldId, String worldName, HostAssignmentDto assignment) {
        if (assignment == null) {
            throw new IllegalStateException("SharedWorld couldn't acquire a temporary host assignment for the initial snapshot upload of " + worldName + ".");
        }
        return new InitialUploadLease(
                worldId,
                this.backend.canonicalAssignedPlayerUuidWithHyphens(assignment.playerUuid()),
                assignment.runtimeEpoch(),
                assignment.hostToken()
        );
    }

    /**
     * Release the seed lease and, when the create failed, delete the half-created world so a
     * snapshot-less ghost never lingers in the player's world list. The snapshot is finalized as the
     * last step of uploadSnapshot, so any failure reaching here means no usable snapshot exists.
     *
     * Once the snapshot is committed the create has succeeded, so a failure to release the seed lease
     * is cosmetic — the lease expires on its own at its startup deadline. We log it but never turn a
     * good create into an error screen. A failed release is only propagated when the upload itself
     * already failed (where it just annotates the real cause).
     */
    private void finishInitialUpload(InitialUploadLease uploadLease, Throwable uploadFailure) {
        try {
            this.backend.releaseHost(
                    uploadLease.worldId(),
                    false,
                    uploadLease.runtimeEpoch(),
                    uploadLease.hostToken()
            );
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (uploadFailure != null) {
                uploadFailure.addSuppressed(exception);
            } else {
                LOGGER.warn(
                        "SharedWorld created '{}' but could not release its seed host lease; it will expire on its own.",
                        uploadLease.worldId(),
                        exception
                );
            }
        }
        if (uploadFailure != null) {
            deleteCreatedWorldQuietly(uploadLease.worldId(), uploadFailure);
        }
    }

    private void deleteCreatedWorldQuietly(String worldId, Throwable uploadFailure) {
        try {
            this.backend.deleteWorld(worldId);
        } catch (Exception exception) {
            uploadFailure.addSuppressed(exception);
        }
    }

    private void heartbeatSeedLeaseQuietly(InitialUploadLease uploadLease) {
        try {
            this.backend.heartbeatHost(uploadLease.worldId(), uploadLease.runtimeEpoch(), uploadLease.hostToken());
        } catch (Exception ignored) {
            // A transient heartbeat failure must not abort create; if the lease is truly gone the
            // upload's own epoch/token check will surface it.
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
            paths = stream.sorted(Comparator.naturalOrder()).toList();
        }

        long totalBytes = paths.stream()
                .filter(Files::isRegularFile)
                .mapToLong(this::safeSize)
                .sum();
        long copiedBytes = 0L;

        for (Path path : paths) {
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

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return 0L;
        }
    }

    @FunctionalInterface
    interface IconEncoder {
        String encodePngBase64(Path path) throws IOException;
    }

    interface CreateBackend {
        CreateWorldResultDto createWorld(
                String name,
                String motdLine1,
                String customIconPngBase64,
                link.sharedworld.api.SharedWorldModels.ImportedWorldSourceDto importSource,
                String storageLinkSessionId
        ) throws IOException, InterruptedException;

        void releaseHost(String worldId, boolean graceful, long runtimeEpoch, String hostToken) throws IOException, InterruptedException;

        void heartbeatHost(String worldId, long runtimeEpoch, String hostToken) throws IOException, InterruptedException;

        void deleteWorld(String worldId) throws IOException, InterruptedException;

        String canonicalAssignedPlayerUuidWithHyphens(String backendAssignedPlayerUuid);
    }

    /**
     * Keeps the seed host-starting lease alive for the duration of the create copy+upload. The
     * production implementation schedules a periodic heartbeat on a background thread; tests can drive
     * the heartbeat synchronously. start() begins keeping the lease alive and returns a handle whose
     * close() stops it.
     */
    interface LeaseKeepAlive {
        AutoCloseable start(Runnable heartbeat);
    }

    interface ProgressSink {
        void updateDeterminate(Component label, String phase, double targetFraction, Long bytesDone, Long bytesTotal);

        void updateIndeterminate(Component label, String phase);
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

    private record InitialUploadLease(String worldId, String hostPlayerUuid, long runtimeEpoch, String hostToken) {
    }
}
