package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.DownloadPackPlanDto;
import link.sharedworld.api.SharedWorldModels.DownloadPlanDto;
import link.sharedworld.api.SharedWorldModels.DownloadPlanEntryDto;
import link.sharedworld.api.SharedWorldModels.DownloadPlanStepDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.stream.Stream;

/**
 * Applies one download plan to a working copy: downloads and hash-verifies
 * packs, region bundles, and loose entries, moves everything into place
 * atomically, prunes files the plan no longer wants, seeds the scan cache,
 * and refreshes the local baselines. One instance applies one plan; the
 * plan-wide progress accounting lives in instance fields so the per-transfer
 * helpers stay small.
 */
final class DownloadPlanApplier {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-sync");

    private final SharedWorldApiClient apiClient;
    private final ManagedWorldStore worldStore;
    private final String worldId;
    private final Path worldDirectory;
    private final DownloadPlanDto plan;
    private final WorldSyncSupport.LazyArtifact reportedLocalPack;
    private final Map<String, WorldSyncSupport.LazyArtifact> reportedLocalBundles;
    private final WorldScanCache scanCache;
    private final WorldSyncProgressListener progressListener;
    private final WorldSyncSupport.SyncPolicy policy;

    private final long totalDownloadBytes;
    private final AtomicLong downloadedBytes = new AtomicLong(0L);
    /** One slot per transfer item: [non-region pack?][region bundles...][loose entries...]. */
    private final AtomicLongArray perFileDownloadedBytes;
    private final long[] downloadFileSizes;
    private final int bundleIndexOffset;
    private final int entryIndexOffset;

    DownloadPlanApplier(
            SharedWorldApiClient apiClient,
            ManagedWorldStore worldStore,
            String worldId,
            Path worldDirectory,
            DownloadPlanDto plan,
            WorldSyncSupport.LazyArtifact reportedLocalPack,
            Map<String, WorldSyncSupport.LazyArtifact> reportedLocalBundles,
            WorldScanCache scanCache,
            WorldSyncProgressListener progressListener
    ) {
        this.apiClient = apiClient;
        this.worldStore = worldStore;
        this.worldId = worldId;
        this.worldDirectory = worldDirectory;
        this.plan = plan;
        this.reportedLocalPack = reportedLocalPack;
        this.reportedLocalBundles = reportedLocalBundles;
        this.scanCache = scanCache;
        this.progressListener = progressListener;
        this.policy = WorldSyncSupport.SyncPolicy.from(plan.syncPolicy());

        this.totalDownloadBytes = Arrays.stream(plan.downloads())
                .flatMap(download -> Arrays.stream(download.steps()))
                .mapToLong(DownloadPlanStepDto::artifactSize)
                .sum()
                + (plan.nonRegionPackDownload() == null ? 0L : Arrays.stream(plan.nonRegionPackDownload().steps()).mapToLong(DownloadPlanStepDto::artifactSize).sum())
                + (plan.regionBundleDownloads() == null ? 0L : Arrays.stream(plan.regionBundleDownloads()).flatMap(download -> Arrays.stream(download.steps())).mapToLong(DownloadPlanStepDto::artifactSize).sum());
        int bundleCount = plan.regionBundleDownloads() == null ? 0 : plan.regionBundleDownloads().length;
        this.bundleIndexOffset = plan.nonRegionPackDownload() == null ? 0 : 1;
        this.entryIndexOffset = this.bundleIndexOffset + bundleCount;
        int totalTransferItems = this.entryIndexOffset + plan.downloads().length;
        this.perFileDownloadedBytes = new AtomicLongArray(totalTransferItems);
        this.downloadFileSizes = new long[totalTransferItems];
        if (plan.nonRegionPackDownload() != null) {
            this.downloadFileSizes[0] = Math.max(1L, Arrays.stream(plan.nonRegionPackDownload().steps()).mapToLong(DownloadPlanStepDto::artifactSize).sum());
        }
        for (int i = 0; i < bundleCount; i++) {
            this.downloadFileSizes[this.bundleIndexOffset + i] = Math.max(1L, Arrays.stream(plan.regionBundleDownloads()[i].steps()).mapToLong(DownloadPlanStepDto::artifactSize).sum());
        }
        for (int i = 0; i < plan.downloads().length; i++) {
            long artifactBytes = Arrays.stream(plan.downloads()[i].steps()).mapToLong(DownloadPlanStepDto::artifactSize).sum();
            this.downloadFileSizes[this.entryIndexOffset + i] = Math.max(1L, artifactBytes);
        }
    }

    void apply() throws IOException, InterruptedException {
        int regionBundleFileCount = this.plan.regionBundleDownloads() == null ? 0 : Arrays.stream(this.plan.regionBundleDownloads()).mapToInt(download -> download.files().length).sum();
        List<DownloadedFile> downloadedFiles = new ArrayList<>(this.plan.downloads().length + (this.plan.nonRegionPackDownload() == null ? 0 : this.plan.nonRegionPackDownload().files().length) + regionBundleFileCount);
        long downloadStartedAt = System.nanoTime();
        Path downloadedPack = null;
        Path extractedPackRoot = null;
        Map<String, Path> downloadedRegionBundleArtifacts = new HashMap<>();
        List<Path> extractedRegionBundleRoots = new ArrayList<>();
        List<Future<DownloadedFile>> pendingEntryDownloads = List.of();

        // All temps below (downloaded artifacts, extract roots, per-file .part
        // temps) live inside the world container and are cleaned by the single
        // finally at the end, so any of the validation/apply throw sites leaves
        // no residue behind.
        try {
        if (this.plan.nonRegionPackDownload() != null) {
            downloadedPack = downloadGroupedArtifactToTempFile(
                    this.plan.nonRegionPackDownload(),
                    this.worldStore.packBaselineFile(this.worldId),
                    this.reportedLocalPack,
                    "pack-full",
                    "pack-delta",
                    0
            );
            if (!LocalWorldHasher.hashFile(downloadedPack).equals(this.plan.nonRegionPackDownload().hash())) {
                throw new IOException("SharedWorld reconstructed pack hash mismatch.");
            }
            extractedPackRoot = Files.createTempDirectory(this.worldStore.worldContainer(this.worldId), "pack-extract-");
            Map<String, String> extractedPackHashes = SharedWorldPack.extract(downloadedPack, extractedPackRoot);
            for (var file : this.plan.nonRegionPackDownload().files()) {
                Path tempFile = extractedPackRoot.resolve(file.path().replace('/', java.io.File.separatorChar));
                if (!Files.exists(tempFile)) {
                    throw new IOException("SharedWorld pack was missing extracted file " + file.path() + ".");
                }
                if (!file.hash().equals(extractedPackHashes.get(file.path()))) {
                    throw new IOException("SharedWorld extracted pack file hash mismatch for " + file.path() + ".");
                }
                downloadedFiles.add(new DownloadedFile(file.path(), this.worldDirectory.resolve(file.path().replace('/', java.io.File.separatorChar)), tempFile));
            }
        }

        if (this.plan.regionBundleDownloads() != null) {
            for (int bundleIndex = 0; bundleIndex < this.plan.regionBundleDownloads().length; bundleIndex++) {
                DownloadPackPlanDto bundle = this.plan.regionBundleDownloads()[bundleIndex];
                Path downloadedBundle = downloadGroupedArtifactToTempFile(
                        bundle,
                        this.worldStore.regionBundleBaselineFile(this.worldId, bundle.packId()),
                        this.reportedLocalBundles.get(bundle.packId()),
                        "region-full",
                        "region-delta",
                        bundleIndex + this.bundleIndexOffset
                );
                if (!LocalWorldHasher.hashFile(downloadedBundle).equals(bundle.hash())) {
                    throw new IOException("SharedWorld reconstructed region bundle hash mismatch for " + bundle.packId() + ".");
                }
                Path extractRoot = Files.createTempDirectory(this.worldStore.worldContainer(this.worldId), "region-bundle-extract-");
                extractedRegionBundleRoots.add(extractRoot);
                Map<String, String> extractedBundleHashes = SharedWorldPack.extract(downloadedBundle, extractRoot);
                downloadedRegionBundleArtifacts.put(bundle.packId(), downloadedBundle);
                for (var file : bundle.files()) {
                    Path tempFile = extractRoot.resolve(file.path().replace('/', java.io.File.separatorChar));
                    if (!Files.exists(tempFile)) {
                        throw new IOException("SharedWorld region bundle was missing extracted file " + file.path() + ".");
                    }
                    if (!file.hash().equals(extractedBundleHashes.get(file.path()))) {
                        throw new IOException("SharedWorld extracted region bundle file hash mismatch for " + file.path() + ".");
                    }
                    downloadedFiles.add(new DownloadedFile(file.path(), this.worldDirectory.resolve(file.path().replace('/', java.io.File.separatorChar)), tempFile));
                }
            }
        }

        if (this.plan.downloads().length > 0) {
            ExecutorService executor = Executors.newFixedThreadPool(this.policy.maxParallelDownloads());
            List<Future<DownloadedFile>> futures = new ArrayList<>(this.plan.downloads().length);
            pendingEntryDownloads = futures;
            try {
                for (int downloadIndex = 0; downloadIndex < this.plan.downloads().length; downloadIndex++) {
                    DownloadPlanEntryDto download = this.plan.downloads()[downloadIndex];
                    int fileIndex = downloadIndex + this.entryIndexOffset;
                    futures.add(executor.submit(() -> {
                        Path target = this.worldDirectory.resolve(download.path().replace('/', java.io.File.separatorChar));
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        Path tempFile = downloadEntryToTempFile(target, download, fileIndex);
                        if (LocalWorldHasher.hashFile(tempFile).equals(download.hash())) {
                            return new DownloadedFile(download.path(), target, tempFile);
                        }
                        throw new IOException("SharedWorld reconstructed region file hash mismatch for " + download.path() + ".");
                    }));
                }

                for (Future<DownloadedFile> future : futures) {
                    downloadedFiles.add(WorldSyncSupport.await(future));
                }
            } finally {
                WorldSyncSupport.shutDownAndAwait(executor);
            }
        } else if (this.plan.nonRegionPackDownload() == null) {
            WorldSyncSupport.report(this.progressListener, WorldSyncCoordinator.STAGE_DOWNLOADING_CHANGED_FILES, 1.0D, 0L, 0L, "No downloads required");
        }

        WorldSyncSupport.logTiming(LOGGER, "download changed files", this.worldDirectory.getFileName().toString(), downloadStartedAt);

        WorldSyncSupport.report(this.progressListener, WorldSyncCoordinator.STAGE_APPLYING_WORLD_UPDATE, 0.72D, null, null, "Applying world update");
        for (DownloadedFile downloadedFile : downloadedFiles) {
            WorldSyncSupport.moveAtomically(downloadedFile.tempPath(), downloadedFile.targetPath());
        }

        Set<String> desiredPaths = new HashSet<>(List.of(this.plan.retainedPaths()));
        for (DownloadPlanEntryDto download : this.plan.downloads()) {
            desiredPaths.add(download.path());
        }
        if (this.plan.nonRegionPackDownload() != null) {
            for (var file : this.plan.nonRegionPackDownload().files()) {
                desiredPaths.add(file.path());
            }
        }
        if (this.plan.regionBundleDownloads() != null) {
            for (var bundle : this.plan.regionBundleDownloads()) {
                for (var file : bundle.files()) {
                    desiredPaths.add(file.path());
                }
            }
        }

        if (Files.exists(this.worldDirectory)) {
            try (Stream<Path> stream = Files.walk(this.worldDirectory)) {
                for (Path path : stream.filter(Files::isRegularFile).sorted(Comparator.reverseOrder()).toList()) {
                    String relativePath = this.worldDirectory.relativize(path).toString().replace('\\', '/');
                    if (!desiredPaths.contains(relativePath) && !"session.lock".equals(path.getFileName().toString())) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }

        pruneEmptyDirectories(this.worldDirectory);
        seedScanCacheAfterApply(this.scanCache, this.plan, downloadedFiles);
        if (this.plan.snapshotId() != null) {
            Map<String, String> downloadedBundleHashes = new HashMap<>();
            if (this.plan.regionBundleDownloads() != null) {
                for (DownloadPackPlanDto bundle : this.plan.regionBundleDownloads()) {
                    downloadedBundleHashes.put(bundle.packId(), bundle.hash());
                }
            }
            this.worldStore.updateRegionBaselines(this.worldId, downloadedRegionBundleArtifacts, downloadedBundleHashes, this.plan.snapshotId());
            if (downloadedPack != null) {
                this.worldStore.refreshPackBaseline(this.worldId, downloadedPack, this.plan.nonRegionPackDownload().hash(), this.plan.snapshotId());
            }
        }
        WorldSyncSupport.report(this.progressListener, WorldSyncCoordinator.STAGE_APPLYING_WORLD_UPDATE, 1.0D, null, null, "World update applied");
        } finally {
            for (Future<DownloadedFile> future : pendingEntryDownloads) {
                if (future.isDone() && !future.isCancelled()) {
                    try {
                        DownloadedFile completed = future.get();
                        if (completed != null) {
                            Files.deleteIfExists(completed.tempPath());
                        }
                    } catch (ExecutionException | InterruptedException | IOException ignored) {
                        // Failed futures cleaned their own temp; nothing to reclaim.
                    }
                }
            }
            for (DownloadedFile downloadedFile : downloadedFiles) {
                Files.deleteIfExists(downloadedFile.tempPath());
            }
            if (extractedPackRoot != null) {
                WorldSyncSupport.deleteRecursivelyQuietly(extractedPackRoot);
            }
            if (downloadedPack != null) {
                Files.deleteIfExists(downloadedPack);
            }
            for (Path bundleArtifact : downloadedRegionBundleArtifacts.values()) {
                Files.deleteIfExists(bundleArtifact);
            }
            for (Path extractRoot : extractedRegionBundleRoots) {
                WorldSyncSupport.deleteRecursivelyQuietly(extractRoot);
            }
        }
    }

    /**
     * Everything just moved into place was hash-verified against the plan, so
     * the scan cache can adopt those hashes (and the downloaded packs' hashes,
     * via their manifests) immediately: the next sync on this machine is all
     * cache hits instead of re-hashing a world it just downloaded.
     */
    private static void seedScanCacheAfterApply(WorldScanCache scanCache, DownloadPlanDto plan, List<DownloadedFile> downloadedFiles) {
        if (scanCache == null) {
            return;
        }
        Map<String, String> planHashesByPath = new HashMap<>();
        if (plan.nonRegionPackDownload() != null) {
            for (var file : plan.nonRegionPackDownload().files()) {
                planHashesByPath.put(file.path(), file.hash());
            }
        }
        if (plan.regionBundleDownloads() != null) {
            for (DownloadPackPlanDto bundle : plan.regionBundleDownloads()) {
                for (var file : bundle.files()) {
                    planHashesByPath.put(file.path(), file.hash());
                }
            }
        }
        for (DownloadPlanEntryDto download : plan.downloads()) {
            planHashesByPath.put(download.path(), download.hash());
        }
        for (DownloadedFile downloadedFile : downloadedFiles) {
            String hash = planHashesByPath.get(downloadedFile.relativePath());
            if (hash == null) {
                continue;
            }
            try {
                var attributes = Files.readAttributes(downloadedFile.targetPath(), java.nio.file.attribute.BasicFileAttributes.class);
                scanCache.recordVerifiedFileHash(downloadedFile.relativePath(), attributes.size(), attributes.lastModifiedTime().toMillis(), hash);
            } catch (IOException exception) {
                // Seeding is an optimization; a file we cannot stat just stays
                // uncached and gets hashed on the next scan.
            }
        }
        if (plan.nonRegionPackDownload() != null) {
            scanCache.recordPackHash(
                    SharedWorldPack.PACK_ID,
                    WorldScanCache.packFingerprintFromManifest(SharedWorldPack.PACK_ID, plan.nonRegionPackDownload().files()),
                    plan.nonRegionPackDownload().hash()
            );
        }
        if (plan.regionBundleDownloads() != null) {
            for (DownloadPackPlanDto bundle : plan.regionBundleDownloads()) {
                scanCache.recordPackHash(
                        bundle.packId(),
                        WorldScanCache.packFingerprintFromManifest(bundle.packId(), bundle.files()),
                        bundle.hash()
                );
            }
        }
    }

    private Path downloadEntryToTempFile(Path target, DownloadPlanEntryDto download, int fileIndex) throws IOException, InterruptedException {
        Path currentBase = null;
        long fileTransferred = 0L;

        try {
        for (int stepIndex = 0; stepIndex < download.steps().length; stepIndex++) {
            DownloadPlanStepDto step = download.steps()[stepIndex];
            Path artifactFile = Files.createTempFile(
                    target.getParent() == null ? this.worldDirectory : target.getParent(),
                    target.getFileName().toString() + ".artifact.",
                    ".part"
            );
            long stepStart = fileTransferred;
            try {
                if ("whole-gzip".equals(step.transferMode())) {
                    WorldSyncSupport.withTransportRetries(this.policy, () -> this.apiClient.downloadBlobToFile(step.download(), artifactFile, (bytesTransferred, ignoredTotalBytes) ->
                            reportFileTransfer(fileIndex, stepStart, step.artifactSize(), bytesTransferred)
                    ));
                    fileTransferred = finalizeFileTransfer(fileIndex, stepStart, step.artifactSize());
                    currentBase = artifactFile;
                } else {
                        throw new IOException("SharedWorld download step had unknown transfer mode " + step.transferMode() + ".");
                }
            } finally {
                if (currentBase == null || !currentBase.equals(artifactFile)) {
                    Files.deleteIfExists(artifactFile);
                }
            }
        }

        if (currentBase == null) {
            throw new IOException("SharedWorld download plan did not produce a file for " + download.path() + ".");
        }
        return currentBase;
        } catch (IOException | RuntimeException | InterruptedException failure) {
            // The chain cursor is always a temp this walk created, never a
            // baseline or reported-local artifact.
            if (currentBase != null) {
                Files.deleteIfExists(currentBase);
            }
            throw failure;
        }
    }

    private Path downloadGroupedArtifactToTempFile(
            DownloadPackPlanDto download,
            Path baselineFile,
            WorldSyncSupport.LazyArtifact reportedLocalArtifact,
            String fullTransferMode,
            String deltaTransferMode,
            int fileIndex
    ) throws IOException, InterruptedException {
        Path currentBase = null;
        long fileTransferred = 0L;

        try {
        for (DownloadPlanStepDto step : download.steps()) {
            Path artifactFile = Files.createTempFile(this.worldStore.worldContainer(this.worldId), "pack-artifact-", ".part");
            long stepStart = fileTransferred;
            try {
                if (fullTransferMode.equals(step.transferMode())) {
                    WorldSyncSupport.withTransportRetries(this.policy, () -> this.apiClient.downloadRawBlobToFile(step.download(), artifactFile, (bytesTransferred, ignoredTotalBytes) ->
                            reportFileTransfer(fileIndex, stepStart, step.artifactSize(), bytesTransferred)
                    ));
                    fileTransferred = finalizeFileTransfer(fileIndex, stepStart, step.artifactSize());
                    currentBase = artifactFile;
                } else if (deltaTransferMode.equals(step.transferMode())) {
                    Path baseFile = resolveGroupedDeltaBase(currentBase, baselineFile, reportedLocalArtifact, step);
                    if (baseFile == null || !Files.exists(baseFile)) {
                        throw new IOException("SharedWorld grouped artifact delta base was missing.");
                    }
                    WorldSyncSupport.withTransportRetries(this.policy, () -> this.apiClient.downloadRawBlobToFile(step.download(), artifactFile, (bytesTransferred, ignoredTotalBytes) ->
                            reportFileTransfer(fileIndex, stepStart, step.artifactSize(), bytesTransferred)
                    ));
                    fileTransferred = finalizeFileTransfer(fileIndex, stepStart, step.artifactSize());
                    Path patchedFile = Files.createTempFile(this.worldStore.worldContainer(this.worldId), "pack-patched-", ".pack");
                    try {
                        ArtifactDeltaEngine.applyDelta(baseFile, artifactFile, patchedFile);
                    } catch (IOException | RuntimeException applyFailure) {
                        Files.deleteIfExists(patchedFile);
                        throw applyFailure;
                    }
                    if (currentBase != null && !currentBase.equals(baseFile)) {
                        Files.deleteIfExists(currentBase);
                    }
                    currentBase = patchedFile;
                } else {
                    throw new IOException("SharedWorld grouped artifact download step had unknown transfer mode " + step.transferMode() + ".");
                }
            } finally {
                if (currentBase == null || !currentBase.equals(artifactFile)) {
                    Files.deleteIfExists(artifactFile);
                }
            }
        }

        if (currentBase == null) {
            throw new IOException("SharedWorld grouped artifact download plan did not produce an artifact.");
        }
        return currentBase;
        } catch (IOException | RuntimeException | InterruptedException failure) {
            // The chain cursor is always a downloaded or patched temp this walk
            // created, never a baseline or reported-local artifact.
            if (currentBase != null) {
                Files.deleteIfExists(currentBase);
            }
            throw failure;
        }
    }

    private Path resolveGroupedDeltaBase(Path currentBase, Path baselineFile, WorldSyncSupport.LazyArtifact reportedLocalArtifact, DownloadPlanStepDto step) throws IOException {
        if (currentBase != null) {
            return currentBase;
        }
        if (step.baseHash() == null) {
            return null;
        }
        if (baselineFile != null && Files.exists(baselineFile) && step.baseHash().equals(LocalWorldHasher.hashFile(baselineFile))) {
            return baselineFile;
        }
        // The backend may base deltas on the state this client just reported;
        // building that artifact now is byte-exact for the claim even when the
        // cached baseline has diverged (cancelled sync, partial apply). Only
        // this rare path pays for a body build, and the built bytes are still
        // hash-checked before being trusted as a delta base.
        if (reportedLocalArtifact != null && step.baseHash().equals(reportedLocalArtifact.descriptor().hash())) {
            Path body = reportedLocalArtifact.body();
            if (Files.exists(body) && step.baseHash().equals(LocalWorldHasher.hashFile(body))) {
                return body;
            }
        }
        return null;
    }

    private void reportFileTransfer(int fileIndex, long stepStart, long stepSize, long stepTransferred) {
        long totalFileBytes = this.downloadFileSizes[fileIndex];
        long clampedTransferred = Math.max(0L, Math.min(stepTransferred, stepSize));
        long overallForFile = Math.max(0L, Math.min(stepStart + clampedTransferred, totalFileBytes));
        long previous = this.perFileDownloadedBytes.getAndSet(fileIndex, overallForFile);
        long delta = Math.max(0L, overallForFile - previous);
        long current = this.downloadedBytes.addAndGet(delta);
        WorldSyncSupport.report(
                this.progressListener,
                WorldSyncCoordinator.STAGE_DOWNLOADING_CHANGED_FILES,
                WorldSyncSupport.weightedTransferFraction(current, this.totalDownloadBytes, this.perFileDownloadedBytes, this.downloadFileSizes),
                current,
                this.totalDownloadBytes,
                "Downloading changed files"
        );
    }

    private long finalizeFileTransfer(int fileIndex, long stepStart, long stepSize) {
        long totalFileBytes = this.downloadFileSizes[fileIndex];
        long overallForFile = Math.max(0L, Math.min(stepStart + stepSize, totalFileBytes));
        long previous = this.perFileDownloadedBytes.getAndSet(fileIndex, overallForFile);
        long delta = Math.max(0L, overallForFile - previous);
        long current = this.downloadedBytes.addAndGet(delta);
        WorldSyncSupport.report(
                this.progressListener,
                WorldSyncCoordinator.STAGE_DOWNLOADING_CHANGED_FILES,
                WorldSyncSupport.weightedTransferFraction(current, this.totalDownloadBytes, this.perFileDownloadedBytes, this.downloadFileSizes),
                current,
                this.totalDownloadBytes,
                "Downloading changed files"
        );
        return overallForFile;
    }

    private static void pruneEmptyDirectories(Path worldDirectory) throws IOException {
        if (!Files.exists(worldDirectory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(worldDirectory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isDirectory(path) && !path.equals(worldDirectory)) {
                    try (Stream<Path> children = Files.list(path)) {
                        if (children.findAny().isEmpty()) {
                            Files.deleteIfExists(path);
                        }
                    }
                }
            }
        }
    }

    private record DownloadedFile(String relativePath, Path targetPath, Path tempPath) {
    }
}
