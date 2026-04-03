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
    private final CreateBackend backend;
    private final IconEncoder iconEncoder;
    private final WorkingCopyStore worldStore;
    private final SnapshotUploader snapshotUploader;

    SharedWorldCreateFlow(CreateBackend backend, IconEncoder iconEncoder, WorkingCopyStore worldStore, SnapshotUploader snapshotUploader) {
        this.backend = backend;
        this.iconEncoder = iconEncoder;
        this.worldStore = worldStore;
        this.snapshotUploader = snapshotUploader;
    }

    /**
     * Responsibility:
     * Create a SharedWorld, stage the imported save, seed the first snapshot, and release the temporary seed lease.
     *
     * Preconditions:
     * The request is fully populated and the caller supplies a progress sink owned by the UI.
     *
     * Postconditions:
     * The new world exists remotely with an initial snapshot, or the flow fails without stranding the seed lease.
     *
     * Stale-work rule:
     * Initial upload always uses the exact epoch/token returned by enterSession for this create flow.
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
        HostAssignmentDto initialUploadAssignment = result.initialUploadAssignment();

        progressSink.updateDeterminate(Component.translatable("screen.sharedworld.create_progress_copying"), "create_copy", 0.0D, 0L, 0L);
        this.worldStore.resetWorkingCopy(createdWorld.id());
        Path workingCopy = this.worldStore.workingCopy(createdWorld.id());
        copyIntoManagedWorldWithProgress(request.save().directory(), workingCopy, progressSink);

        InitialUploadLease uploadLease = requireInitialUploadLease(createdWorld.id(), createdWorld.name(), initialUploadAssignment);
        Throwable uploadFailure = null;
        progressSink.updateIndeterminate(Component.translatable("screen.sharedworld.create_progress_uploading"), "create_upload_prepare");
        try {
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
            releaseInitialUploadLease(uploadLease, uploadFailure);
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

    private void releaseInitialUploadLease(InitialUploadLease uploadLease, Throwable uploadFailure) throws IOException, InterruptedException {
        try {
            this.backend.releaseHost(
                    uploadLease.worldId(),
                    false,
                    uploadLease.runtimeEpoch(),
                    uploadLease.hostToken()
            );
        } catch (IOException | InterruptedException exception) {
            if (uploadFailure != null) {
                uploadFailure.addSuppressed(exception);
                return;
            }
            throw exception;
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

        String canonicalAssignedPlayerUuidWithHyphens(String backendAssignedPlayerUuid);
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
