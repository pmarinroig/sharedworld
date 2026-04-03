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

    static List<LocalArtifact> buildRegionBundleArtifacts(List<PreparedWorldFile> regionFiles) throws IOException {
        List<LocalArtifact> bundles = new ArrayList<>();
        for (SyncPathRules.RegionBundleGroup group : SyncPathRules.groupTerrainFiles(regionFiles)) {
            Path artifactPath = Files.createTempFile("sharedworld-region-bundle-", ".bundle");
            LocalPackDescriptorDto descriptor = SharedWorldPack.buildPack(group.bundleId(), group.files(), artifactPath);
            bundles.add(new LocalArtifact(descriptor, artifactPath));
        }
        return bundles;
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

    record LocalArtifact(LocalPackDescriptorDto descriptor, Path artifactPath) {
    }
}
