package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.LocalPackDescriptorDto;
import link.sharedworld.api.SharedWorldModels.SnapshotPackDto;
import link.sharedworld.api.SharedWorldModels.SyncPolicyDto;
import link.sharedworld.api.SharedWorldModels.UploadPackPlanDto;
import link.sharedworld.util.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.stream.Stream;

final class WorldSyncSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-sync");

    private WorldSyncSupport() {
    }

    static SnapshotPackDto snapshotPackForExisting(UploadPackPlanDto upload) {
        return new SnapshotPackDto(
                upload.pack().packId(),
                upload.pack().hash(),
                upload.pack().size(),
                upload.storageKey(),
                upload.transferMode() == null ? "pack-full" : upload.transferMode(),
                upload.baseSnapshotId(),
                upload.baseHash(),
                upload.baseChainDepth(),
                upload.pack().files()
        );
    }

    static List<LazyArtifact> lazyRegionBundleArtifacts(List<PreparedWorldFile> regionFiles, WorldScanCache cache) {
        return lazyGroupedArtifacts(SyncPathRules.groupTerrainFiles(regionFiles), cache);
    }

    static List<LazyArtifact> lazyGroupedArtifacts(List<SyncPathRules.RegionBundleGroup> groups, WorldScanCache cache) {
        List<LazyArtifact> artifacts = new ArrayList<>(groups.size());
        for (SyncPathRules.RegionBundleGroup group : groups) {
            artifacts.add(new LazyArtifact(group.bundleId(), group.files(), cache));
        }
        return artifacts;
    }

    static void report(WorldSyncProgressListener listener, String stage, double fraction, Long bytesDone, Long bytesTotal, String detailLine) {
        if (listener == null) {
            return;
        }
        listener.onProgress(new WorldSyncProgress(stage, fraction, bytesDone, bytesTotal, detailLine));
    }

    static double fraction(long current, long total) {
        if (total <= 0L) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (double) current / (double) total));
    }

    static double weightedTransferFraction(long currentBytes, long totalBytes, AtomicLongArray perFileBytes, long[] fileSizes) {
        double byteFraction = fraction(currentBytes, Math.max(totalBytes, 1L));
        if (fileSizes.length == 0) {
            return byteFraction;
        }

        double fileFractionSum = 0.0D;
        for (int i = 0; i < fileSizes.length; i++) {
            fileFractionSum += fraction(perFileBytes.get(i), Math.max(fileSizes[i], 1L));
        }
        double fileFraction = fileFractionSum / (double) fileSizes.length;
        return Math.max(0.0D, Math.min(1.0D, (byteFraction * 0.5D) + (fileFraction * 0.5D)));
    }

    static void logTiming(Logger logger, String step, String worldId, long startedAt) {
        logger.info("SharedWorld sync step '{}' for {} took {} ms", step, worldId, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    static void moveAtomically(Path source, Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    static void deleteRecursivelyQuietly(Path root) {
        try {
            if (Files.exists(root)) {
                deleteRecursively(root);
            }
        } catch (IOException exception) {
            LOGGER.warn("SharedWorld failed to clean up a sync temp directory {}", root, exception);
        }
    }

    /**
     * shutdownNow interrupts workers but does not wait for them; deleting their
     * temp files while they are still writing recreates the leak. Wait briefly,
     * preserving this thread's own interrupt status (sync cancellation).
     */
    static void shutDownAndAwait(ExecutorService executor) {
        executor.shutdownNow();
        boolean interrupted = Thread.interrupted();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("SharedWorld sync worker pool did not terminate promptly after shutdown");
            }
        } catch (InterruptedException exception) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static <T> T await(Future<T> future) throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            throw new IOException("SharedWorld sync task failed.", cause);
        }
    }

    @FunctionalInterface
    interface BlobTransfer {
        void run() throws IOException, InterruptedException;
    }

    /**
     * Bounded retry for blob transport failures only. Integrity failures
     * (hash mismatches, missing delta bases) throw before or after the
     * transfer itself and are never retried — sync fails closed on those.
     * A retried transfer restarts its progress reporting, which can briefly
     * overstate the progress bar; correctness is unaffected.
     */
    static void withTransportRetries(SyncPolicy policy, BlobTransfer transfer) throws IOException, InterruptedException {
        RetryPolicy retry = new RetryPolicy(3, policy == null ? 750L : policy.retryBaseDelayMs(), policy == null ? 8_000L : policy.retryMaxDelayMs());
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            long delayMs = retry.delayBeforeAttemptMs(attempt);
            if (delayMs > 0L) {
                Thread.sleep(delayMs);
            }
            try {
                transfer.run();
                return;
            } catch (IOException exception) {
                if (!SharedWorldApiClient.isRetryableTransportError(exception) || !retry.shouldRetry(attempt)) {
                    throw exception;
                }
                LOGGER.warn("SharedWorld blob transfer failed (attempt {}); retrying", attempt, exception);
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    record SyncPolicy(
            int maxParallelDownloads,
            int maxConcurrentUploadPreparations,
            int maxConcurrentUploads,
            int maxUploadStartsPerSecond,
            long retryBaseDelayMs,
            long retryMaxDelayMs,
            long maxUploadBodyBytes
    ) {
        /**
         * The default matches the backend's UPLOAD_MAX_BODY_BYTES fallback:
         * just under the storage relay's hard request-body limit. A backend
         * predating the field serializes nothing and lands on the same value.
         */
        private static final long DEFAULT_MAX_UPLOAD_BODY_BYTES = 95_000_000L;

        static SyncPolicy from(SyncPolicyDto dto) {
            if (dto == null) {
                return new SyncPolicy(4, 1, 1, 1, 750L, 8_000L, DEFAULT_MAX_UPLOAD_BODY_BYTES);
            }
            return new SyncPolicy(
                    Math.max(1, dto.maxParallelDownloads()),
                    Math.max(1, dto.maxConcurrentUploadPreparations()),
                    Math.max(1, dto.maxConcurrentUploads()),
                    Math.max(1, dto.maxUploadStartsPerSecond()),
                    Math.max(1L, dto.retryBaseDelayMs()),
                    Math.max(1L, dto.retryMaxDelayMs()),
                    dto.maxUploadBodyBytes() > 0L ? dto.maxUploadBodyBytes() : DEFAULT_MAX_UPLOAD_BODY_BYTES
            );
        }
    }

    /**
     * A pack whose descriptor can be answered from the scan cache without
     * writing the pack body. The body is built at most once, on first demand —
     * a no-change sync never asks for it, which is what turns the pre-plan
     * "pack the whole world into temp files" pass into pure metadata work.
     *
     * <p>Thread-safe: upload preparation calls {@link #body()} from a worker
     * pool.
     */
    static final class LazyArtifact {
        private final String packId;
        private final List<PreparedWorldFile> files;
        private final WorldScanCache cache;
        private LocalPackDescriptorDto descriptor;
        private Path bodyPath;

        LazyArtifact(String packId, List<PreparedWorldFile> files, WorldScanCache cache) {
            this.packId = packId;
            this.files = files;
            this.cache = cache;
        }

        String packId() {
            return this.packId;
        }

        synchronized LocalPackDescriptorDto descriptor() throws IOException {
            if (this.descriptor != null) {
                return this.descriptor;
            }
            String fingerprint = WorldScanCache.packFingerprint(this.packId, this.files);
            String cachedHash = this.cache == null ? null : this.cache.cachedPackHash(this.packId, fingerprint);
            if (cachedHash != null) {
                this.descriptor = SharedWorldPack.describePack(this.packId, this.files, cachedHash);
                return this.descriptor;
            }
            build(fingerprint);
            return this.descriptor;
        }

        synchronized Path body() throws IOException {
            if (this.bodyPath == null) {
                build(WorldScanCache.packFingerprint(this.packId, this.files));
            }
            return this.bodyPath;
        }

        /**
         * Blob storage keys are derived from the hash the plan saw, so a body
         * whose bytes hash differently from an already-announced descriptor
         * must never be uploaded — that would store content under the wrong
         * key and break every future download of it. The fresh hash is
         * recorded in the cache first so the retry plans against the truth.
         */
        private void build(String fingerprint) throws IOException {
            Path target = Files.createTempFile("sharedworld-artifact-", ".pack");
            LocalPackDescriptorDto built;
            try {
                built = SharedWorldPack.buildPack(this.packId, this.files, target);
            } catch (IOException | RuntimeException buildFailure) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupFailure) {
                    buildFailure.addSuppressed(cleanupFailure);
                }
                throw buildFailure;
            }
            if (this.cache != null) {
                this.cache.recordPackHash(this.packId, fingerprint, built.hash());
            }
            if (this.descriptor != null && !this.descriptor.hash().equals(built.hash())) {
                Files.deleteIfExists(target);
                throw new IOException("SharedWorld pack " + this.packId
                        + " no longer matches the state this sync was planned against; the next sync will retry.");
            }
            this.descriptor = built;
            this.bodyPath = target;
        }

        synchronized void deleteBodyIfBuilt() throws IOException {
            if (this.bodyPath != null) {
                Files.deleteIfExists(this.bodyPath);
                this.bodyPath = null;
            }
        }
    }
}
