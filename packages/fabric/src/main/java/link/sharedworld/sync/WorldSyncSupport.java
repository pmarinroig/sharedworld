package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldModels.LocalPackDescriptorDto;
import link.sharedworld.api.SharedWorldModels.SnapshotPackDto;
import link.sharedworld.api.SharedWorldModels.UploadPackPlanDto;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

final class WorldSyncSupport {
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
