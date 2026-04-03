package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.DownloadPlanDto;
import link.sharedworld.api.SharedWorldModels.DownloadPlanEntryDto;
import link.sharedworld.api.SharedWorldModels.DownloadPackPlanDto;
import link.sharedworld.api.SharedWorldModels.DownloadPlanStepDto;
import link.sharedworld.api.SharedWorldModels.LocalFileDescriptorDto;
import link.sharedworld.api.SharedWorldModels.LocalPackDescriptorDto;
import link.sharedworld.api.SharedWorldModels.ManifestFileDto;
import link.sharedworld.api.SharedWorldModels.SnapshotPackDto;
import link.sharedworld.api.SharedWorldModels.SignedBlobUrlDto;
import link.sharedworld.api.SharedWorldModels.SnapshotManifestDto;
import link.sharedworld.api.SharedWorldModels.SyncPolicyDto;
import link.sharedworld.api.SharedWorldModels.UploadPackPlanDto;
import link.sharedworld.api.SharedWorldModels.UploadPlanDto;
import link.sharedworld.api.SharedWorldModels.UploadPlanEntryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class WorldSyncCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-sync");
    public static final String STAGE_CHECKING_LOCAL_CACHE = "checking_local_cache";
    public static final String STAGE_REQUESTING_DOWNLOAD_PLAN = "requesting_download_plan";
    public static final String STAGE_DOWNLOADING_CHANGED_FILES = "downloading_changed_files";
    public static final String STAGE_APPLYING_WORLD_UPDATE = "applying_world_update";
    public static final String STAGE_PREPARING_SNAPSHOT = "preparing_snapshot";
    public static final String STAGE_REQUESTING_UPLOAD_PLAN = "requesting_upload_plan";
    public static final String STAGE_PREPARING_UPLOADS = "preparing_uploads";
    public static final String STAGE_UPLOADING_CHANGED_FILES = "uploading_changed_files";
    public static final String STAGE_FINALIZING_SNAPSHOT = "finalizing_snapshot";
    private static final double REGION_DELTA_MIN_SAVINGS_RATIO = 0.10D;
    private static final double PACK_DELTA_MIN_SAVINGS_RATIO = 0.10D;
    private static final int PACK_DELTA_BLOCK_SIZE = 64 * 1024;
    private static final int REGION_DELTA_BLOCK_SIZE = 4 * 1024;

    private final SharedWorldApiClient apiClient;
    private final ManagedWorldStore worldStore;

    public WorldSyncCoordinator(SharedWorldApiClient apiClient, ManagedWorldStore worldStore) {
        this.apiClient = apiClient;
        this.worldStore = worldStore;
    }

    public Path ensureSynchronizedWorkingCopy(String worldId, String hostPlayerUuid) throws IOException, InterruptedException {
        return this.ensureSynchronizedWorkingCopy(worldId, hostPlayerUuid, null);
    }

    public Path ensureSynchronizedWorkingCopy(String worldId, String hostPlayerUuid, WorldSyncProgressListener progressListener) throws IOException, InterruptedException {
        return this.ensureWorkingCopy(worldId, hostPlayerUuid, true, progressListener);
    }

    public Path ensureCanonicalSynchronizedWorkingCopy(String worldId, String hostPlayerUuid) throws IOException, InterruptedException {
        return this.ensureCanonicalSynchronizedWorkingCopy(worldId, hostPlayerUuid, null);
    }

    public Path ensureCanonicalSynchronizedWorkingCopy(String worldId, String hostPlayerUuid, WorldSyncProgressListener progressListener) throws IOException, InterruptedException {
        return this.ensureWorkingCopy(worldId, hostPlayerUuid, false, progressListener);
    }

    public SnapshotManifestDto uploadSnapshot(String worldId, Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken) throws IOException, InterruptedException {
        return this.uploadSnapshot(worldId, worldDirectory, hostPlayerUuid, runtimeEpoch, hostToken, (WorldSyncProgressListener) null);
    }

    public SnapshotManifestDto uploadSnapshot(String worldId, Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken, SnapshotUploadProgressListener progressListener) throws IOException, InterruptedException {
        WorldSyncProgressListener listener = progressListener == null
                ? null
                : progress -> {
                    if (STAGE_UPLOADING_CHANGED_FILES.equals(progress.stage()) && progress.bytesDone() != null && progress.bytesTotal() != null) {
                        progressListener.onProgress(progress.bytesDone(), progress.bytesTotal());
                    }
                };
        return this.uploadSnapshot(worldId, worldDirectory, hostPlayerUuid, runtimeEpoch, hostToken, listener);
    }

    public SnapshotManifestDto uploadSnapshot(String worldId, Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken, WorldSyncProgressListener progressListener) throws IOException, InterruptedException {
        WorldSyncSupport.report(progressListener, STAGE_PREPARING_SNAPSHOT, 0.02D, null, null, "Scanning world files");
        long scanStartedAt = System.nanoTime();
        List<PreparedWorldFile> canonicalFiles = WorldCanonicalizer.scanCanonical(worldDirectory, hostPlayerUuid);
        List<PreparedWorldFile> regionFiles = canonicalFiles.stream().filter(file -> SyncPathRules.isTerrainRegionFile(file.relativePath())).toList();
        List<PreparedWorldFile> nonRegionFiles = canonicalFiles.stream().filter(file -> SyncPathRules.belongsInSuperpack(file.relativePath())).toList();
        Path packFile = Files.createTempFile("sharedworld-non-region-", ".pack");
        LocalPackDescriptorDto localPack = SharedWorldPack.buildPack(nonRegionFiles, packFile);
        List<WorldSyncSupport.LocalArtifact> regionBundles = WorldSyncSupport.buildRegionBundleArtifacts(regionFiles);
        WorldSyncSupport.logTiming(LOGGER, "scan canonical files", worldId, scanStartedAt);

        WorldSyncSupport.report(progressListener, STAGE_REQUESTING_UPLOAD_PLAN, 0.14D, null, null, "Requesting upload plan");
        long planStartedAt = System.nanoTime();
        UploadPlanDto plan = this.apiClient.prepareUploads(
                worldId,
                runtimeEpoch,
                hostToken,
                canonicalFiles.stream().map(PreparedWorldFile::toDescriptor).toArray(LocalFileDescriptorDto[]::new),
                localPack,
                regionBundles.stream().map(WorldSyncSupport.LocalArtifact::descriptor).toArray(LocalPackDescriptorDto[]::new)
        );
        WorldSyncSupport.logTiming(LOGGER, "request upload plan", worldId, planStartedAt);

        SyncPolicy resolvedPolicy = SyncPolicy.from(plan.syncPolicy());
        Map<String, PreparedWorldFile> filesByPath = canonicalFiles.stream()
                .collect(Collectors.toMap(PreparedWorldFile::relativePath, file -> file));
        Map<String, WorldSyncSupport.LocalArtifact> regionBundlesById = regionBundles.stream().collect(Collectors.toMap(artifact -> artifact.descriptor().packId(), artifact -> artifact));
        List<PreparedUpload> preparedUploads = prepareUploads(worldId, plan, packFile, localPack, regionBundlesById, resolvedPolicy, progressListener);
        Map<String, PreparedUpload> preparedByPath = preparedUploads.stream()
                .collect(Collectors.toMap(PreparedUpload::relativePath, prepared -> prepared));

        long totalUploadBytes = preparedUploads.stream().mapToLong(PreparedUpload::bodySize).sum();
        uploadPreparedFiles(worldId, preparedUploads, resolvedPolicy, progressListener, totalUploadBytes);

        WorldSyncSupport.report(progressListener, STAGE_FINALIZING_SNAPSHOT, 0.96D, null, null, "Finalizing snapshot");
        long finalizeStartedAt = System.nanoTime();
        List<ManifestFileDto> manifestFiles = new ArrayList<>(0);
        List<SnapshotPackDto> packs = new ArrayList<>(1 + (plan.regionBundleUploads() == null ? 0 : plan.regionBundleUploads().length));
        PreparedUpload preparedPack = preparedByPath.get(SharedWorldPack.PACK_ID);
        if (preparedPack != null && preparedPack.snapshotPack() != null) {
            packs.add(preparedPack.snapshotPack());
        } else if (plan.nonRegionPackUpload() != null) {
                packs.add(WorldSyncSupport.snapshotPackForExisting(plan.nonRegionPackUpload()));
        }
        if (plan.regionBundleUploads() != null) {
            for (UploadPackPlanDto upload : plan.regionBundleUploads()) {
                PreparedUpload preparedBundle = preparedByPath.get(upload.pack().packId());
                if (preparedBundle != null && preparedBundle.snapshotPack() != null) {
                    packs.add(preparedBundle.snapshotPack());
                } else {
                    packs.add(WorldSyncSupport.snapshotPackForExisting(upload));
                }
            }
        }

        try {
            SnapshotManifestDto manifest = this.apiClient.finalizeSnapshot(
                    worldId,
                    runtimeEpoch,
                    hostToken,
                    plan.snapshotBaseId(),
                    manifestFiles.toArray(ManifestFileDto[]::new),
                    packs.toArray(SnapshotPackDto[]::new)
            );
            Map<String, Path> bundleBaselineFiles = new HashMap<>();
            for (WorldSyncSupport.LocalArtifact bundle : regionBundles) {
                bundleBaselineFiles.put(bundle.descriptor().packId(), bundle.artifactPath());
            }
            this.worldStore.replaceRegionBaselines(worldId, bundleBaselineFiles, manifest.snapshotId());
            this.worldStore.refreshPackBaseline(worldId, packFile, manifest.snapshotId());
            WorldSyncSupport.report(progressListener, STAGE_FINALIZING_SNAPSHOT, 1.0D, null, null, "Snapshot finalized");
            WorldSyncSupport.logTiming(LOGGER, "finalize snapshot", worldId, finalizeStartedAt);
            return manifest;
        } finally {
            for (PreparedUpload preparedUpload : preparedByPath.values()) {
                if (preparedUpload.bodyPath() != null) {
                    Files.deleteIfExists(preparedUpload.bodyPath());
                }
            }
            Files.deleteIfExists(packFile);
            for (WorldSyncSupport.LocalArtifact bundle : regionBundles) {
                Files.deleteIfExists(bundle.artifactPath());
            }
        }
    }

    private Path ensureWorkingCopy(String worldId, String hostPlayerUuid, boolean materializeHostPlayer, WorldSyncProgressListener progressListener) throws IOException, InterruptedException {
        this.worldStore.ensureWorldContainer(worldId);
        Path worldDirectory = this.worldStore.workingCopy(worldId);
        Files.createDirectories(worldDirectory);

        WorldSyncSupport.report(progressListener, STAGE_CHECKING_LOCAL_CACHE, 0.08D, null, null, "Scanning local cache");
        long scanStartedAt = System.nanoTime();
        List<PreparedWorldFile> localCanonicalFiles = Files.exists(worldDirectory)
                ? WorldCanonicalizer.scanCanonical(worldDirectory, hostPlayerUuid)
                : List.of();
        List<LocalFileDescriptorDto> localFiles = localCanonicalFiles.stream()
                .map(PreparedWorldFile::toDescriptor)
                .toList();
        List<PreparedWorldFile> localNonRegionFiles = localCanonicalFiles.stream().filter(file -> SyncPathRules.belongsInSuperpack(file.relativePath())).toList();
        List<PreparedWorldFile> localRegionFiles = localCanonicalFiles.stream().filter(file -> SyncPathRules.isTerrainRegionFile(file.relativePath())).toList();
        Path localPackFile = Files.createTempFile("sharedworld-local-pack-", ".pack");
        LocalPackDescriptorDto localPack = SharedWorldPack.buildPack(localNonRegionFiles, localPackFile);
        List<WorldSyncSupport.LocalArtifact> localRegionBundles = WorldSyncSupport.buildRegionBundleArtifacts(localRegionFiles);
        WorldSyncSupport.logTiming(LOGGER, "scan local cache", worldId, scanStartedAt);

        WorldSyncSupport.report(progressListener, STAGE_REQUESTING_DOWNLOAD_PLAN, 0.18D, null, null, "Requesting download plan");
        long planStartedAt = System.nanoTime();
        DownloadPlanDto plan = this.apiClient.downloadPlan(
                worldId,
                localFiles.toArray(LocalFileDescriptorDto[]::new),
                localPack,
                localRegionBundles.stream().map(WorldSyncSupport.LocalArtifact::descriptor).toArray(LocalPackDescriptorDto[]::new)
        );
        WorldSyncSupport.logTiming(LOGGER, "request download plan", worldId, planStartedAt);
        Files.deleteIfExists(localPackFile);
        for (WorldSyncSupport.LocalArtifact bundle : localRegionBundles) {
            Files.deleteIfExists(bundle.artifactPath());
        }

        applyDownloadPlan(worldId, worldDirectory, plan, progressListener);

        if (materializeHostPlayer) {
            WorldSyncSupport.report(progressListener, STAGE_APPLYING_WORLD_UPDATE, 0.98D, null, null, "Preparing host player data");
            WorldCanonicalizer.materializeHostPlayer(worldDirectory, hostPlayerUuid);
        }
        WorldSyncSupport.report(progressListener, STAGE_APPLYING_WORLD_UPDATE, 1.0D, null, null, "World is ready");
        return worldDirectory;
    }

    private List<PreparedUpload> prepareUploads(
            String worldId,
            UploadPlanDto plan,
            Path packFile,
            LocalPackDescriptorDto localPack,
            Map<String, WorldSyncSupport.LocalArtifact> regionBundlesById,
            SyncPolicy policy,
            WorldSyncProgressListener progressListener
    ) throws IOException, InterruptedException {
        boolean preparePack = plan.nonRegionPackUpload() != null && !plan.nonRegionPackUpload().alreadyPresent();
        List<UploadPackPlanDto> bundlesToPrepare = plan.regionBundleUploads() == null
                ? List.of()
                : Arrays.stream(plan.regionBundleUploads()).filter(upload -> !upload.alreadyPresent()).toList();
        if (bundlesToPrepare.isEmpty() && !preparePack) {
            WorldSyncSupport.report(progressListener, STAGE_PREPARING_UPLOADS, 1.0D, 0L, 0L, "No changed files to upload");
            return List.of();
        }

        long startedAt = System.nanoTime();
        long totalExpectedBytes = bundlesToPrepare.stream().mapToLong(upload -> upload.pack().size()).sum()
                + (preparePack ? localPack.size() : 0L);
        AtomicLong preparedBytes = new AtomicLong(0L);
        ExecutorService executor = Executors.newFixedThreadPool(policy.maxConcurrentUploadPreparations());
        List<Future<PreparedUpload>> futures = new ArrayList<>(bundlesToPrepare.size() + (preparePack ? 1 : 0));

        try {
            for (UploadPackPlanDto upload : bundlesToPrepare) {
                futures.add(executor.submit(() -> {
                    WorldSyncSupport.LocalArtifact bundle = regionBundlesById.get(upload.pack().packId());
                    if (bundle == null) {
                        throw new IOException("SharedWorld upload plan referenced unknown region bundle " + upload.pack().packId() + ".");
                    }
                    PreparedUpload preparedUpload = prepareGroupedArtifactUpload(
                            worldId,
                            bundle.artifactPath(),
                            upload,
                            bundle.descriptor(),
                            this.worldStore.regionBundleBaselineFile(worldId, upload.pack().packId()),
                            this.worldStore.regionBaselineSnapshotId(worldId),
                            "region-full",
                            "region-delta",
                            REGION_DELTA_MIN_SAVINGS_RATIO,
                            REGION_DELTA_BLOCK_SIZE
                    );
                    long current = preparedBytes.addAndGet(preparedUpload.bodySize());
                    WorldSyncSupport.report(
                            progressListener,
                            STAGE_PREPARING_UPLOADS,
                            WorldSyncSupport.fraction(current, Math.max(totalExpectedBytes, 1L)),
                            current,
                            totalExpectedBytes,
                            "Preparing changed regions"
                    );
                    return preparedUpload;
                }));
            }
            if (preparePack) {
                futures.add(executor.submit(() -> {
                    PreparedUpload preparedUpload = prepareGroupedArtifactUpload(
                            worldId,
                            packFile,
                            plan.nonRegionPackUpload(),
                            localPack,
                            this.worldStore.packBaselineFile(worldId),
                            this.worldStore.packBaselineSnapshotId(worldId),
                            "pack-full",
                            "pack-delta",
                            PACK_DELTA_MIN_SAVINGS_RATIO,
                            PACK_DELTA_BLOCK_SIZE
                    );
                    long current = preparedBytes.addAndGet(preparedUpload.bodySize());
                    WorldSyncSupport.report(
                            progressListener,
                            STAGE_PREPARING_UPLOADS,
                            WorldSyncSupport.fraction(current, Math.max(totalExpectedBytes, 1L)),
                            current,
                            totalExpectedBytes,
                            "Preparing changed files"
                    );
                    return preparedUpload;
                }));
            }

            List<PreparedUpload> preparedUploads = new ArrayList<>(bundlesToPrepare.size() + (preparePack ? 1 : 0));
            for (Future<PreparedUpload> future : futures) {
                preparedUploads.add(await(future));
            }
            WorldSyncSupport.logTiming(LOGGER, "prepare upload bodies", worldId, startedAt);
            return preparedUploads;
        } finally {
            executor.shutdownNow();
        }
    }

    private void uploadPreparedFiles(
            String worldId,
            List<PreparedUpload> preparedUploads,
            SyncPolicy policy,
            WorldSyncProgressListener progressListener,
            long totalUploadBytes
    ) throws IOException, InterruptedException {
        if (preparedUploads.isEmpty()) {
            WorldSyncSupport.report(progressListener, STAGE_UPLOADING_CHANGED_FILES, 1.0D, 0L, 0L, "No changed files to upload");
            return;
        }

        long startedAt = System.nanoTime();
        AtomicLong uploadedBytes = new AtomicLong(0L);
        ExecutorService executor = Executors.newFixedThreadPool(policy.maxConcurrentUploads());
        UploadStartLimiter limiter = new UploadStartLimiter(policy.maxUploadStartsPerSecond());
        List<Future<Void>> futures = new ArrayList<>(preparedUploads.size());
        AtomicLongArray perFileUploadedBytes = new AtomicLongArray(preparedUploads.size());
        long[] fileSizes = new long[preparedUploads.size()];
        for (int i = 0; i < preparedUploads.size(); i++) {
            fileSizes[i] = Math.max(1L, preparedUploads.get(i).bodySize());
        }
        List<String> largestUploads = preparedUploads.stream()
                .sorted(Comparator.comparingLong(PreparedUpload::bodySize).reversed())
                .limit(5)
                .map(upload -> upload.relativePath() + " (" + upload.bodySize() + " bytes)")
                .toList();

        LOGGER.info(
                "SharedWorld uploading {} changed files for {} totaling {} bytes with concurrency {} and {} starts/sec. Largest uploads: {}",
                preparedUploads.size(),
                worldId,
                totalUploadBytes,
                policy.maxConcurrentUploads(),
                policy.maxUploadStartsPerSecond(),
                largestUploads
        );

        try {
            for (int uploadIndex = 0; uploadIndex < preparedUploads.size(); uploadIndex++) {
                PreparedUpload preparedUpload = preparedUploads.get(uploadIndex);
                int fileIndex = uploadIndex;
                futures.add(executor.submit(() -> {
                    if (preparedUpload.uploadUrl() == null || preparedUpload.bodyPath() == null) {
                        return null;
                    }
                    limiter.awaitTurn();
                    this.apiClient.uploadBlob(
                            preparedUpload.uploadUrl(),
                            preparedUpload.bodyPath(),
                            preparedUpload.manifestFile() != null ? preparedUpload.manifestFile().contentType() : "application/octet-stream",
                            (bytesTransferred, totalBytes) -> {
                                long clampedTransferred = Math.max(0L, Math.min(bytesTransferred, preparedUpload.bodySize()));
                                long previous = perFileUploadedBytes.getAndSet(fileIndex, clampedTransferred);
                                long delta = Math.max(0L, clampedTransferred - previous);
                                long current = uploadedBytes.addAndGet(delta);
                                WorldSyncSupport.report(
                                        progressListener,
                                        STAGE_UPLOADING_CHANGED_FILES,
                                        WorldSyncSupport.weightedTransferFraction(current, totalUploadBytes, perFileUploadedBytes, fileSizes),
                                        current,
                                        totalUploadBytes,
                                        "Uploading changed files"
                                );
                            }
                    );
                    long finalProgress = perFileUploadedBytes.getAndSet(fileIndex, preparedUpload.bodySize());
                    long remaining = Math.max(0L, preparedUpload.bodySize() - finalProgress);
                    if (remaining > 0L) {
                        long current = uploadedBytes.addAndGet(remaining);
                        WorldSyncSupport.report(
                                progressListener,
                                STAGE_UPLOADING_CHANGED_FILES,
                                WorldSyncSupport.weightedTransferFraction(current, totalUploadBytes, perFileUploadedBytes, fileSizes),
                                current,
                                totalUploadBytes,
                                "Uploading changed files"
                        );
                    }
                    return null;
                }));
            }

            for (Future<Void> future : futures) {
                await(future);
            }
            WorldSyncSupport.logTiming(LOGGER, "upload changed files", worldId, startedAt);
        } finally {
            executor.shutdownNow();
        }
    }

    private void applyDownloadPlan(String worldId, Path worldDirectory, DownloadPlanDto plan, WorldSyncProgressListener progressListener) throws IOException, InterruptedException {
        SyncPolicy policy = SyncPolicy.from(plan.syncPolicy());
        long totalDownloadBytes = Arrays.stream(plan.downloads())
                .flatMap(download -> Arrays.stream(download.steps()))
                .mapToLong(DownloadPlanStepDto::artifactSize)
                .sum()
                + (plan.nonRegionPackDownload() == null ? 0L : Arrays.stream(plan.nonRegionPackDownload().steps()).mapToLong(DownloadPlanStepDto::artifactSize).sum())
                + (plan.regionBundleDownloads() == null ? 0L : Arrays.stream(plan.regionBundleDownloads()).flatMap(download -> Arrays.stream(download.steps())).mapToLong(DownloadPlanStepDto::artifactSize).sum());
        AtomicLong downloadedBytes = new AtomicLong(0L);
        int regionBundleFileCount = plan.regionBundleDownloads() == null ? 0 : Arrays.stream(plan.regionBundleDownloads()).mapToInt(download -> download.files().length).sum();
        List<DownloadedFile> downloadedFiles = new ArrayList<>(plan.downloads().length + (plan.nonRegionPackDownload() == null ? 0 : plan.nonRegionPackDownload().files().length) + regionBundleFileCount);
        long downloadStartedAt = System.nanoTime();
        int totalTransferItems = plan.downloads().length + (plan.nonRegionPackDownload() == null ? 0 : 1) + (plan.regionBundleDownloads() == null ? 0 : plan.regionBundleDownloads().length);
        AtomicLongArray perFileDownloadedBytes = new AtomicLongArray(totalTransferItems);
        long[] downloadFileSizes = new long[totalTransferItems];
        int offset = 0;
        if (plan.nonRegionPackDownload() != null) {
            downloadFileSizes[0] = Math.max(1L, Arrays.stream(plan.nonRegionPackDownload().steps()).mapToLong(DownloadPlanStepDto::artifactSize).sum());
            offset = 1;
        }
        int bundleOffset = offset;
        if (plan.regionBundleDownloads() != null) {
            for (int i = 0; i < plan.regionBundleDownloads().length; i++) {
                downloadFileSizes[bundleOffset + i] = Math.max(1L, Arrays.stream(plan.regionBundleDownloads()[i].steps()).mapToLong(DownloadPlanStepDto::artifactSize).sum());
            }
            offset += plan.regionBundleDownloads().length;
        }
        for (int i = 0; i < plan.downloads().length; i++) {
            long artifactBytes = Arrays.stream(plan.downloads()[i].steps()).mapToLong(DownloadPlanStepDto::artifactSize).sum();
            downloadFileSizes[i + offset] = Math.max(1L, artifactBytes);
        }
        Path downloadedPack = null;
        Path extractedPackRoot = null;
        Map<String, Path> downloadedRegionBundleArtifacts = new HashMap<>();
        List<Path> extractedRegionBundleRoots = new ArrayList<>();

        if (plan.nonRegionPackDownload() != null) {
            downloadedPack = downloadGroupedArtifactToTempFile(
                    worldId,
                    plan.nonRegionPackDownload(),
                    this.worldStore.packBaselineFile(worldId),
                    "pack-full",
                    "pack-delta",
                    downloadedBytes,
                    totalDownloadBytes,
                    perFileDownloadedBytes,
                    downloadFileSizes[0],
                    downloadFileSizes,
                    0,
                    progressListener
            );
            if (!LocalWorldHasher.hashFile(downloadedPack).equals(plan.nonRegionPackDownload().hash())) {
                throw new IOException("SharedWorld reconstructed pack hash mismatch.");
            }
            extractedPackRoot = Files.createTempDirectory(this.worldStore.worldContainer(worldId), "pack-extract-");
            SharedWorldPack.extract(downloadedPack, extractedPackRoot);
            for (var file : plan.nonRegionPackDownload().files()) {
                Path tempFile = extractedPackRoot.resolve(file.path().replace('/', java.io.File.separatorChar));
                if (!Files.exists(tempFile)) {
                    throw new IOException("SharedWorld pack was missing extracted file " + file.path() + ".");
                }
                if (!LocalWorldHasher.hashFile(tempFile).equals(file.hash())) {
                    throw new IOException("SharedWorld extracted pack file hash mismatch for " + file.path() + ".");
                }
                downloadedFiles.add(new DownloadedFile(file.path(), worldDirectory.resolve(file.path().replace('/', java.io.File.separatorChar)), tempFile));
            }
        }

        if (plan.regionBundleDownloads() != null) {
            for (int bundleIndex = 0; bundleIndex < plan.regionBundleDownloads().length; bundleIndex++) {
                DownloadPackPlanDto bundle = plan.regionBundleDownloads()[bundleIndex];
                Path downloadedBundle = downloadGroupedArtifactToTempFile(
                        worldId,
                        bundle,
                        this.worldStore.regionBundleBaselineFile(worldId, bundle.packId()),
                        "region-full",
                        "region-delta",
                        downloadedBytes,
                        totalDownloadBytes,
                        perFileDownloadedBytes,
                        downloadFileSizes[bundleIndex + bundleOffset],
                        downloadFileSizes,
                        bundleIndex + bundleOffset,
                        progressListener
                );
                if (!LocalWorldHasher.hashFile(downloadedBundle).equals(bundle.hash())) {
                    throw new IOException("SharedWorld reconstructed region bundle hash mismatch for " + bundle.packId() + ".");
                }
                Path extractRoot = Files.createTempDirectory(this.worldStore.worldContainer(worldId), "region-bundle-extract-");
                extractedRegionBundleRoots.add(extractRoot);
                SharedWorldPack.extract(downloadedBundle, extractRoot);
                downloadedRegionBundleArtifacts.put(bundle.packId(), downloadedBundle);
                for (var file : bundle.files()) {
                    Path tempFile = extractRoot.resolve(file.path().replace('/', java.io.File.separatorChar));
                    if (!Files.exists(tempFile)) {
                        throw new IOException("SharedWorld region bundle was missing extracted file " + file.path() + ".");
                    }
                    if (!LocalWorldHasher.hashFile(tempFile).equals(file.hash())) {
                        throw new IOException("SharedWorld extracted region bundle file hash mismatch for " + file.path() + ".");
                    }
                    downloadedFiles.add(new DownloadedFile(file.path(), worldDirectory.resolve(file.path().replace('/', java.io.File.separatorChar)), tempFile));
                }
            }
        }

        if (plan.downloads().length > 0) {
            ExecutorService executor = Executors.newFixedThreadPool(policy.maxParallelDownloads());
            List<Future<DownloadedFile>> futures = new ArrayList<>(plan.downloads().length);
            try {
                for (int downloadIndex = 0; downloadIndex < plan.downloads().length; downloadIndex++) {
                    DownloadPlanEntryDto download = plan.downloads()[downloadIndex];
                    int fileIndex = downloadIndex + offset;
                    futures.add(executor.submit(() -> {
                        Path target = worldDirectory.resolve(download.path().replace('/', java.io.File.separatorChar));
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        try {
                            Path tempFile = downloadEntryToTempFile(
                                    worldId,
                                    worldDirectory,
                                    target,
                                    download,
                                    downloadedBytes,
                                    totalDownloadBytes,
                                    perFileDownloadedBytes,
                                    downloadFileSizes[fileIndex],
                                    downloadFileSizes,
                                    fileIndex,
                                    progressListener
                            );
                            if (LocalWorldHasher.hashFile(tempFile).equals(download.hash())) {
                                return new DownloadedFile(download.path(), target, tempFile);
                            }
                            throw new IOException("SharedWorld reconstructed region file hash mismatch for " + download.path() + ".");
                        } catch (Exception exception) {
                            throw exception;
                        }
                    }));
                }

                for (Future<DownloadedFile> future : futures) {
                    downloadedFiles.add(await(future));
                }
            } finally {
                executor.shutdownNow();
            }
        } else if (plan.nonRegionPackDownload() == null) {
            WorldSyncSupport.report(progressListener, STAGE_DOWNLOADING_CHANGED_FILES, 1.0D, 0L, 0L, "No downloads required");
        }

        WorldSyncSupport.logTiming(LOGGER, "download changed files", worldDirectory.getFileName().toString(), downloadStartedAt);

        WorldSyncSupport.report(progressListener, STAGE_APPLYING_WORLD_UPDATE, 0.72D, null, null, "Applying world update");
        try {
            for (DownloadedFile downloadedFile : downloadedFiles) {
                moveAtomically(downloadedFile.tempPath(), downloadedFile.targetPath());
            }
        } catch (IOException exception) {
            for (DownloadedFile downloadedFile : downloadedFiles) {
                Files.deleteIfExists(downloadedFile.tempPath());
            }
            if (extractedPackRoot != null && Files.exists(extractedPackRoot)) {
                deleteRecursively(extractedPackRoot);
            }
            if (downloadedPack != null) {
                Files.deleteIfExists(downloadedPack);
            }
            for (Path bundleArtifact : downloadedRegionBundleArtifacts.values()) {
                Files.deleteIfExists(bundleArtifact);
            }
            for (Path extractRoot : extractedRegionBundleRoots) {
                deleteRecursively(extractRoot);
            }
            throw exception;
        }

        Set<String> desiredPaths = new HashSet<>(List.of(plan.retainedPaths()));
        for (DownloadPlanEntryDto download : plan.downloads()) {
            desiredPaths.add(download.path());
        }
        if (plan.nonRegionPackDownload() != null) {
            for (var file : plan.nonRegionPackDownload().files()) {
                desiredPaths.add(file.path());
            }
        }
        if (plan.regionBundleDownloads() != null) {
            for (var bundle : plan.regionBundleDownloads()) {
                for (var file : bundle.files()) {
                    desiredPaths.add(file.path());
                }
            }
        }

        if (Files.exists(worldDirectory)) {
            try (Stream<Path> stream = Files.walk(worldDirectory)) {
                for (Path path : stream.filter(Files::isRegularFile).sorted(Comparator.reverseOrder()).toList()) {
                    String relativePath = worldDirectory.relativize(path).toString().replace('\\', '/');
                    if (!desiredPaths.contains(relativePath) && !"session.lock".equals(path.getFileName().toString())) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }

        pruneEmptyDirectories(worldDirectory);
        if (plan.snapshotId() != null) {
            this.worldStore.updateRegionBaselines(worldId, downloadedRegionBundleArtifacts, plan.snapshotId());
            if (downloadedPack != null) {
                this.worldStore.refreshPackBaseline(worldId, downloadedPack, plan.snapshotId());
            }
        }
        if (extractedPackRoot != null && Files.exists(extractedPackRoot)) {
            deleteRecursively(extractedPackRoot);
        }
        if (downloadedPack != null) {
            Files.deleteIfExists(downloadedPack);
        }
        for (Path bundleArtifact : downloadedRegionBundleArtifacts.values()) {
            Files.deleteIfExists(bundleArtifact);
        }
        for (Path extractRoot : extractedRegionBundleRoots) {
            deleteRecursively(extractRoot);
        }
        WorldSyncSupport.report(progressListener, STAGE_APPLYING_WORLD_UPDATE, 1.0D, null, null, "World update applied");
    }

    private PreparedUpload prepareGroupedArtifactUpload(
            String worldId,
            Path artifactFile,
            UploadPackPlanDto upload,
            LocalPackDescriptorDto localPack,
            Path baselineFile,
            String baselineSnapshotId,
            String fullTransferMode,
            String deltaTransferMode,
            double minSavingsRatio,
            int deltaBlockSize
    ) throws IOException {
        boolean canUseDelta = upload.deltaStorageKey() != null
                && upload.baseHash() != null
                && upload.baseSnapshotId() != null
                && upload.baseSnapshotId().equals(baselineSnapshotId)
                && baselineFile != null
                && Files.exists(baselineFile)
                && upload.baseHash().equals(LocalWorldHasher.hashFile(baselineFile));

        long fullSize = Files.size(artifactFile);
        if (!canUseDelta) {
            return new PreparedUpload(
                    upload.pack().packId(),
                    upload.fullUpload(),
                    upload.fullUpload() == null ? null : artifactFile,
                    upload.fullUpload() == null ? 0L : fullSize,
                    null,
                    new SnapshotPackDto(localPack.packId(), localPack.hash(), localPack.size(), upload.fullStorageKey(), fullTransferMode, null, null, 0, localPack.files())
            );
        }

        Path deltaBody = Files.createTempFile("sharedworld-pack-delta-", ".bin");
        ArtifactDeltaEngine.DeltaStats deltaStats = ArtifactDeltaEngine.writeDelta(baselineFile, artifactFile, deltaBody, deltaBlockSize);
        long deltaSize = deltaStats.artifactSize();
        boolean useDelta = deltaSize <= Math.floor(fullSize * (1.0D - minSavingsRatio));
        if (!useDelta) {
            Files.deleteIfExists(deltaBody);
            return new PreparedUpload(
                    upload.pack().packId(),
                    upload.fullUpload(),
                    upload.fullUpload() == null ? null : artifactFile,
                    upload.fullUpload() == null ? 0L : fullSize,
                    null,
                    new SnapshotPackDto(localPack.packId(), localPack.hash(), localPack.size(), upload.fullStorageKey(), fullTransferMode, null, null, 0, localPack.files())
            );
        }

        int nextChainDepth = upload.baseChainDepth() == null ? 1 : upload.baseChainDepth() + 1;
        if (upload.deltaUpload() == null) {
            Files.deleteIfExists(deltaBody);
            return new PreparedUpload(
                    upload.pack().packId(),
                    null,
                    null,
                    0L,
                    null,
                    new SnapshotPackDto(localPack.packId(), localPack.hash(), localPack.size(), upload.deltaStorageKey(), deltaTransferMode, upload.baseSnapshotId(), upload.baseHash(), nextChainDepth, localPack.files())
            );
        }
        return new PreparedUpload(
                upload.pack().packId(),
                upload.deltaUpload(),
                deltaBody,
                deltaSize,
                null,
                new SnapshotPackDto(localPack.packId(), localPack.hash(), localPack.size(), upload.deltaStorageKey(), deltaTransferMode, upload.baseSnapshotId(), upload.baseHash(), nextChainDepth, localPack.files())
        );
    }

    private Path downloadEntryToTempFile(
            String worldId,
            Path worldDirectory,
            Path target,
            DownloadPlanEntryDto download,
            AtomicLong downloadedBytes,
            long totalDownloadBytes,
            AtomicLongArray perFileDownloadedBytes,
            long totalFileBytes,
            long[] allFileSizes,
            int fileIndex,
            WorldSyncProgressListener progressListener
    ) throws IOException, InterruptedException {
        Path currentBase = null;
        long fileTransferred = 0L;

        for (int stepIndex = 0; stepIndex < download.steps().length; stepIndex++) {
            DownloadPlanStepDto step = download.steps()[stepIndex];
            Path artifactFile = Files.createTempFile(
                    target.getParent() == null ? worldDirectory : target.getParent(),
                    target.getFileName().toString() + ".artifact.",
                    ".part"
            );
            long stepStart = fileTransferred;
            try {
                if ("whole-gzip".equals(step.transferMode())) {
                    this.apiClient.downloadBlobToFile(step.download(), artifactFile, (bytesTransferred, ignoredTotalBytes) ->
                            reportFileTransfer(fileIndex, stepStart, step.artifactSize(), bytesTransferred, downloadedBytes, totalDownloadBytes, perFileDownloadedBytes, totalFileBytes, allFileSizes, progressListener)
                    );
                    fileTransferred = finalizeFileTransfer(fileIndex, stepStart, step.artifactSize(), downloadedBytes, totalDownloadBytes, perFileDownloadedBytes, totalFileBytes, allFileSizes, progressListener);
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
    }

    private Path downloadGroupedArtifactToTempFile(
            String worldId,
            DownloadPackPlanDto download,
            Path baselineFile,
            String fullTransferMode,
            String deltaTransferMode,
            AtomicLong downloadedBytes,
            long totalDownloadBytes,
            AtomicLongArray perFileDownloadedBytes,
            long totalFileBytes,
            long[] allFileSizes,
            int fileIndex,
            WorldSyncProgressListener progressListener
    ) throws IOException, InterruptedException {
        Path currentBase = null;
        long fileTransferred = 0L;

        for (DownloadPlanStepDto step : download.steps()) {
            Path artifactFile = Files.createTempFile(this.worldStore.worldContainer(worldId), "pack-artifact-", ".part");
            long stepStart = fileTransferred;
            try {
                if (fullTransferMode.equals(step.transferMode())) {
                    this.apiClient.downloadRawBlobToFile(step.download(), artifactFile, (bytesTransferred, ignoredTotalBytes) ->
                            reportFileTransfer(fileIndex, stepStart, step.artifactSize(), bytesTransferred, downloadedBytes, totalDownloadBytes, perFileDownloadedBytes, totalFileBytes, allFileSizes, progressListener)
                    );
                    fileTransferred = finalizeFileTransfer(fileIndex, stepStart, step.artifactSize(), downloadedBytes, totalDownloadBytes, perFileDownloadedBytes, totalFileBytes, allFileSizes, progressListener);
                    currentBase = artifactFile;
                } else if (deltaTransferMode.equals(step.transferMode())) {
                    Path baseFile = resolveGroupedDeltaBase(currentBase, baselineFile, step);
                    if (baseFile == null || !Files.exists(baseFile)) {
                        throw new IOException("SharedWorld grouped artifact delta base was missing.");
                    }
                    this.apiClient.downloadRawBlobToFile(step.download(), artifactFile, (bytesTransferred, ignoredTotalBytes) ->
                            reportFileTransfer(fileIndex, stepStart, step.artifactSize(), bytesTransferred, downloadedBytes, totalDownloadBytes, perFileDownloadedBytes, totalFileBytes, allFileSizes, progressListener)
                    );
                    fileTransferred = finalizeFileTransfer(fileIndex, stepStart, step.artifactSize(), downloadedBytes, totalDownloadBytes, perFileDownloadedBytes, totalFileBytes, allFileSizes, progressListener);
                    Path patchedFile = Files.createTempFile(this.worldStore.worldContainer(worldId), "pack-patched-", ".pack");
                    ArtifactDeltaEngine.applyDelta(baseFile, artifactFile, patchedFile);
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
    }

    private Path resolveGroupedDeltaBase(Path currentBase, Path baselineFile, DownloadPlanStepDto step) throws IOException {
        if (currentBase != null) {
            return currentBase;
        }
        if (step.baseHash() == null) {
            return null;
        }
        if (baselineFile != null && Files.exists(baselineFile) && step.baseHash().equals(LocalWorldHasher.hashFile(baselineFile))) {
            return baselineFile;
        }
        return null;
    }

    private void reportFileTransfer(
            int fileIndex,
            long stepStart,
            long stepSize,
            long stepTransferred,
            AtomicLong downloadedBytes,
            long totalDownloadBytes,
            AtomicLongArray perFileDownloadedBytes,
            long totalFileBytes,
            long[] allFileSizes,
            WorldSyncProgressListener progressListener
    ) {
        long clampedTransferred = Math.max(0L, Math.min(stepTransferred, stepSize));
        long overallForFile = Math.max(0L, Math.min(stepStart + clampedTransferred, totalFileBytes));
        long previous = perFileDownloadedBytes.getAndSet(fileIndex, overallForFile);
        long delta = Math.max(0L, overallForFile - previous);
        long current = downloadedBytes.addAndGet(delta);
        WorldSyncSupport.report(
                progressListener,
                STAGE_DOWNLOADING_CHANGED_FILES,
                WorldSyncSupport.weightedTransferFraction(current, totalDownloadBytes, perFileDownloadedBytes, allFileSizes),
                current,
                totalDownloadBytes,
                "Downloading changed files"
        );
    }

    private long finalizeFileTransfer(
            int fileIndex,
            long stepStart,
            long stepSize,
            AtomicLong downloadedBytes,
            long totalDownloadBytes,
            AtomicLongArray perFileDownloadedBytes,
            long totalFileBytes,
            long[] allFileSizes,
            WorldSyncProgressListener progressListener
    ) {
        long overallForFile = Math.max(0L, Math.min(stepStart + stepSize, totalFileBytes));
        long previous = perFileDownloadedBytes.getAndSet(fileIndex, overallForFile);
        long delta = Math.max(0L, overallForFile - previous);
        long current = downloadedBytes.addAndGet(delta);
        WorldSyncSupport.report(
                progressListener,
                STAGE_DOWNLOADING_CHANGED_FILES,
                WorldSyncSupport.weightedTransferFraction(current, totalDownloadBytes, perFileDownloadedBytes, allFileSizes),
                current,
                totalDownloadBytes,
                "Downloading changed files"
        );
        return overallForFile;
    }

    private void pruneEmptyDirectories(Path worldDirectory) throws IOException {
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

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static <T> T await(Future<T> future) throws IOException, InterruptedException {
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
    public interface SnapshotUploadProgressListener {
        void onProgress(long uploadedBytes, long totalBytes);
    }

    private record PreparedUpload(
            String relativePath,
            SignedBlobUrlDto uploadUrl,
            Path bodyPath,
            long bodySize,
            ManifestFileDto manifestFile,
            SnapshotPackDto snapshotPack
    ) {
    }

    private record DownloadedFile(String relativePath, Path targetPath, Path tempPath) {
    }

    private record SyncPolicy(
            int maxParallelDownloads,
            int maxConcurrentUploadPreparations,
            int maxConcurrentUploads,
            int maxUploadStartsPerSecond
    ) {
        private static SyncPolicy from(SyncPolicyDto dto) {
            if (dto == null) {
                return new SyncPolicy(4, 1, 1, 1);
            }
            return new SyncPolicy(
                    Math.max(1, dto.maxParallelDownloads()),
                    Math.max(1, dto.maxConcurrentUploadPreparations()),
                    Math.max(1, dto.maxConcurrentUploads()),
                    Math.max(1, dto.maxUploadStartsPerSecond())
            );
        }
    }

    private static final class UploadStartLimiter {
        private final long minIntervalMs;
        private long nextAllowedAt;

        private UploadStartLimiter(int startsPerSecond) {
            this.minIntervalMs = Math.max(1L, Math.round(1000.0D / (double) Math.max(1, startsPerSecond)));
        }

        private synchronized void awaitTurn() throws InterruptedException {
            long now = System.currentTimeMillis();
            long scheduledAt = Math.max(now, this.nextAllowedAt);
            this.nextAllowedAt = scheduledAt + this.minIntervalMs;
            long waitMs = scheduledAt - now;
            if (waitMs > 0L) {
                Thread.sleep(waitMs);
            }
        }
    }
}
