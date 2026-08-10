package link.sharedworld.sync;

import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.DownloadPlanDto;
import link.sharedworld.api.SharedWorldModels.LocalFileDescriptorDto;
import link.sharedworld.api.SharedWorldModels.LocalPackDescriptorDto;
import link.sharedworld.api.SharedWorldModels.ManifestFileDto;
import link.sharedworld.api.SharedWorldModels.SnapshotPackDto;
import link.sharedworld.api.SharedWorldModels.SignedBlobUrlDto;
import link.sharedworld.api.SharedWorldModels.SnapshotManifestDto;
import link.sharedworld.api.SharedWorldModels.UploadPackPlanDto;
import link.sharedworld.api.SharedWorldModels.UploadPlanDto;
import link.sharedworld.api.SharedWorldModels.UploadPlanEntryDto;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.stream.Collectors;

public final class WorldSyncCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-sync");
    private static final String DEV_SLOW_UPLOAD_MS_PROPERTY = "sharedworld.devSlowUploadMs";
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

    /**
     * Uploads changed artifacts and finalizes a new snapshot.
     *
     * @return the finalized manifest, or {@code null} when the backend proved
     *         nothing changed since the latest snapshot and the finalize was
     *         skipped (the latest snapshot remains valid).
     */
    public SnapshotManifestDto uploadSnapshot(String worldId, Path worldDirectory, String hostPlayerUuid, long runtimeEpoch, String hostToken, WorldSyncProgressListener progressListener) throws IOException, InterruptedException {
        WorldSyncSupport.report(progressListener, STAGE_PREPARING_SNAPSHOT, 0.02D, null, null, "Scanning world files");
        long scanStartedAt = System.nanoTime();
        WorldScanCache scanCache = WorldScanCache.load(this.worldStore.scanCacheFile(worldId));
        List<PreparedWorldFile> canonicalFiles = WorldCanonicalizer.scanCanonical(worldDirectory, hostPlayerUuid, scanCache);
        List<PreparedWorldFile> regionFiles = canonicalFiles.stream().filter(file -> SyncPathRules.isTerrainRegionFile(file.relativePath())).toList();
        List<PreparedWorldFile> nonRegionFiles = canonicalFiles.stream().filter(file -> SyncPathRules.belongsInSuperpack(file.relativePath())).toList();
        // Above the shard cap the non-region files travel as capped shard packs
        // inside the region-bundle wire namespace (one blob must stay under the
        // worker's request-body limit); below it they keep the single
        // "non-region" superpack, wire-identical to pre-0.3.1 clients.
        List<SyncPathRules.RegionBundleGroup> superpackShards = SyncPathRules.groupSuperpackFiles(nonRegionFiles);
        boolean sharded = !superpackShards.isEmpty();
        // Artifacts are lazy: the plan request needs only descriptors (answered
        // from the scan cache when nothing changed), and bodies are built after
        // the plan for exactly the packs it wants uploaded. Built bodies are
        // deleted in the single finally, so a failure at any stage cannot leak
        // pack/bundle/delta temps. Autosave retries every five minutes, so
        // leaks here compound quickly.
        List<WorldSyncSupport.LazyArtifact> regionBundles = new ArrayList<>(WorldSyncSupport.lazyRegionBundleArtifacts(regionFiles, scanCache));
        if (sharded) {
            regionBundles.addAll(WorldSyncSupport.lazyGroupedArtifacts(superpackShards, scanCache));
        }
        WorldSyncSupport.LazyArtifact nonRegionArtifact = sharded
                ? null
                : new WorldSyncSupport.LazyArtifact(SharedWorldPack.PACK_ID, nonRegionFiles, scanCache);
        List<PreparedUpload> preparedUploads = List.of();
        try {
            LocalPackDescriptorDto localPack = nonRegionArtifact == null ? null : nonRegionArtifact.descriptor();
            LocalPackDescriptorDto[] bundleDescriptors = new LocalPackDescriptorDto[regionBundles.size()];
            for (int i = 0; i < regionBundles.size(); i++) {
                bundleDescriptors[i] = regionBundles.get(i).descriptor();
            }
            pruneScanCache(scanCache, canonicalFiles, regionBundles, nonRegionArtifact);
            WorldSyncSupport.logTiming(LOGGER, "scan canonical files", worldId, scanStartedAt);

            WorldSyncSupport.report(progressListener, STAGE_REQUESTING_UPLOAD_PLAN, 0.14D, null, null, "Requesting upload plan");
            long planStartedAt = System.nanoTime();
            UploadPlanDto plan = this.apiClient.prepareUploads(
                    worldId,
                    runtimeEpoch,
                    hostToken,
                    canonicalFiles.stream().map(PreparedWorldFile::toDescriptor).toArray(LocalFileDescriptorDto[]::new),
                    localPack,
                    bundleDescriptors
            );
            WorldSyncSupport.logTiming(LOGGER, "request upload plan", worldId, planStartedAt);

            WorldSyncSupport.SyncPolicy resolvedPolicy = WorldSyncSupport.SyncPolicy.from(plan.syncPolicy());
            if (plan.directUpload() == null) {
                // Only the relay path caps per-body bytes; with direct uploads
                // available there is no size gate to fail.
                failOnOversizedWorldFile(canonicalFiles, resolvedPolicy.maxUploadBodyBytes());
            }
            Map<String, WorldSyncSupport.LazyArtifact> regionBundlesById = new HashMap<>();
            Map<String, String> bundleHashesById = new HashMap<>();
            for (WorldSyncSupport.LazyArtifact bundle : regionBundles) {
                regionBundlesById.put(bundle.packId(), bundle);
                bundleHashesById.put(bundle.packId(), bundle.descriptor().hash());
            }
            if (canSkipUnchangedSnapshot(plan, regionBundlesById.keySet())) {
                // Nothing changed since the latest snapshot: skip the finalize
                // entirely instead of publishing an identical backup (each one
                // costs backend rows and clutters the backup list). Baselines
                // still converge on the latest snapshot so the next delta plan
                // starts from the right ancestor even if a marker was stale.
                this.worldStore.ensureRegionBaselines(worldId, bundleHashesById, packId -> regionBundlesById.get(packId).body(), plan.snapshotBaseId());
                if (sharded) {
                    this.worldStore.clearPackBaseline(worldId);
                } else {
                    this.worldStore.ensurePackBaseline(worldId, localPack.hash(), packId -> nonRegionArtifact.body(), plan.snapshotBaseId());
                }
                WorldSyncSupport.report(progressListener, STAGE_FINALIZING_SNAPSHOT, 1.0D, null, null, "No changes; snapshot up to date");
                LOGGER.info("SharedWorld snapshot skipped for {}: no changes since {}", worldId, plan.snapshotBaseId());
                return null;
            }
            preparedUploads = prepareUploads(worldId, plan, nonRegionArtifact, regionBundlesById, resolvedPolicy, progressListener);
            Map<String, PreparedUpload> preparedByPath = preparedUploads.stream()
                    .collect(Collectors.toMap(PreparedUpload::relativePath, prepared -> prepared));

            long totalUploadBytes = preparedUploads.stream().mapToLong(PreparedUpload::bodySize).sum();
            uploadPreparedFiles(worldId, preparedUploads, resolvedPolicy, progressListener, totalUploadBytes,
                    plan.directUpload(), runtimeEpoch, hostToken);

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

            SnapshotManifestDto manifest = this.apiClient.finalizeSnapshot(
                    worldId,
                    runtimeEpoch,
                    hostToken,
                    plan.snapshotBaseId(),
                    manifestFiles.toArray(ManifestFileDto[]::new),
                    packs.toArray(SnapshotPackDto[]::new)
            );
            this.worldStore.ensureRegionBaselines(worldId, bundleHashesById, packId -> regionBundlesById.get(packId).body(), manifest.snapshotId());
            if (sharded) {
                this.worldStore.clearPackBaseline(worldId);
            } else {
                this.worldStore.ensurePackBaseline(worldId, localPack.hash(), packId -> nonRegionArtifact.body(), manifest.snapshotId());
            }
            WorldSyncSupport.report(progressListener, STAGE_FINALIZING_SNAPSHOT, 1.0D, null, null, "Snapshot finalized");
            WorldSyncSupport.logTiming(LOGGER, "finalize snapshot", worldId, finalizeStartedAt);
            return manifest;
        } finally {
            // Saved even when the sync failed: the file and pack hashes gathered
            // by the scan stay valid regardless of what the backend said.
            scanCache.save();
            for (PreparedUpload preparedUpload : preparedUploads) {
                if (preparedUpload.bodyPath() != null) {
                    Files.deleteIfExists(preparedUpload.bodyPath());
                }
            }
            if (nonRegionArtifact != null) {
                nonRegionArtifact.deleteBodyIfBuilt();
            }
            for (WorldSyncSupport.LazyArtifact bundle : regionBundles) {
                bundle.deleteBodyIfBuilt();
            }
        }
    }

    /**
     * Entries for files and packs that vanished from the world would otherwise
     * accumulate in the cache forever (worlds rename region tiles as they
     * grow).
     */
    private static void pruneScanCache(
            WorldScanCache scanCache,
            List<PreparedWorldFile> canonicalFiles,
            List<WorldSyncSupport.LazyArtifact> regionBundles,
            WorldSyncSupport.LazyArtifact nonRegionArtifact
    ) {
        Set<String> paths = new HashSet<>(canonicalFiles.size());
        for (PreparedWorldFile file : canonicalFiles) {
            paths.add(file.relativePath());
        }
        Set<String> packIds = new HashSet<>(regionBundles.size() + 1);
        for (WorldSyncSupport.LazyArtifact bundle : regionBundles) {
            packIds.add(bundle.packId());
        }
        if (nonRegionArtifact != null) {
            packIds.add(nonRegionArtifact.packId());
        }
        scanCache.retainOnly(paths, packIds);
    }

    /**
     * True only when the backend proved nothing changed: it is new enough to
     * report the latest snapshot's pack ids, every local pack is already
     * present, and the pack id sets match exactly (a removed local pack must
     * still finalize so the manifest records the removal).
     */
    private static boolean canSkipUnchangedSnapshot(UploadPlanDto plan, java.util.Set<String> regionBundleIds) {
        if (plan.latestPackIds() == null || plan.snapshotBaseId() == null) {
            return false;
        }
        if (plan.nonRegionPackUpload() != null && !plan.nonRegionPackUpload().alreadyPresent()) {
            return false;
        }
        if (plan.regionBundleUploads() != null) {
            for (UploadPackPlanDto upload : plan.regionBundleUploads()) {
                if (!upload.alreadyPresent()) {
                    return false;
                }
            }
        }
        java.util.Set<String> localPackIds = new java.util.HashSet<>(regionBundleIds);
        if (plan.nonRegionPackUpload() != null) {
            localPackIds.add(plan.nonRegionPackUpload().pack().packId());
        }
        // HashSet (not Set.of): a backend answering with a duplicated pack id
        // must degrade to a needless finalize, not crash the sync path.
        return localPackIds.equals(new java.util.HashSet<>(java.util.Arrays.asList(plan.latestPackIds())));
    }

    private Path ensureWorkingCopy(String worldId, String hostPlayerUuid, boolean materializeHostPlayer, WorldSyncProgressListener progressListener) throws IOException, InterruptedException {
        this.worldStore.ensureWorldContainer(worldId);
        Path worldDirectory = this.worldStore.workingCopy(worldId);
        Files.createDirectories(worldDirectory);

        WorldSyncSupport.report(progressListener, STAGE_CHECKING_LOCAL_CACHE, 0.08D, null, null, "Scanning local cache");
        long scanStartedAt = System.nanoTime();
        WorldScanCache scanCache = WorldScanCache.load(this.worldStore.scanCacheFile(worldId));
        List<PreparedWorldFile> localCanonicalFiles = Files.exists(worldDirectory)
                ? WorldCanonicalizer.scanCanonical(worldDirectory, hostPlayerUuid, scanCache)
                : List.of();
        List<LocalFileDescriptorDto> localFiles = localCanonicalFiles.stream()
                .map(PreparedWorldFile::toDescriptor)
                .toList();
        List<PreparedWorldFile> localNonRegionFiles = localCanonicalFiles.stream().filter(file -> SyncPathRules.belongsInSuperpack(file.relativePath())).toList();
        List<PreparedWorldFile> localRegionFiles = localCanonicalFiles.stream().filter(file -> SyncPathRules.isTerrainRegionFile(file.relativePath())).toList();
        // Mirrors the upload-side split: a cache over the shard cap reports its
        // non-region files as shard packs so the backend can plan shard deltas
        // against exactly what we hold.
        List<SyncPathRules.RegionBundleGroup> localSuperpackShards = SyncPathRules.groupSuperpackFiles(localNonRegionFiles);
        boolean shardedLocal = !localSuperpackShards.isEmpty();
        // Artifacts are lazy: descriptors answer the plan request, and a body
        // is only built if the plan actually bases a delta on the state this
        // client just reported (rare — a stale or missing cached baseline).
        List<WorldSyncSupport.LazyArtifact> localRegionBundles = new ArrayList<>(WorldSyncSupport.lazyRegionBundleArtifacts(localRegionFiles, scanCache));
        if (shardedLocal) {
            localRegionBundles.addAll(WorldSyncSupport.lazyGroupedArtifacts(localSuperpackShards, scanCache));
        }
        WorldSyncSupport.LazyArtifact localPackArtifact = shardedLocal
                ? null
                : new WorldSyncSupport.LazyArtifact(SharedWorldPack.PACK_ID, localNonRegionFiles, scanCache);
        // The guest cache warmer retries this flow every 30 seconds while the
        // backend is unreachable, so the plan request failure path must clean
        // its temps just like the apply path does.
        try {
            LocalPackDescriptorDto localPack = localPackArtifact == null ? null : localPackArtifact.descriptor();
            LocalPackDescriptorDto[] bundleDescriptors = new LocalPackDescriptorDto[localRegionBundles.size()];
            for (int i = 0; i < localRegionBundles.size(); i++) {
                bundleDescriptors[i] = localRegionBundles.get(i).descriptor();
            }
            pruneScanCache(scanCache, localCanonicalFiles, localRegionBundles, localPackArtifact);
            WorldSyncSupport.logTiming(LOGGER, "scan local cache", worldId, scanStartedAt);

            WorldSyncSupport.report(progressListener, STAGE_REQUESTING_DOWNLOAD_PLAN, 0.18D, null, null, "Requesting download plan");
            long planStartedAt = System.nanoTime();
            DownloadPlanDto plan = this.apiClient.downloadPlan(
                    worldId,
                    localFiles.toArray(LocalFileDescriptorDto[]::new),
                    localPack,
                    bundleDescriptors
            );
            WorldSyncSupport.logTiming(LOGGER, "request download plan", worldId, planStartedAt);

            // The plan's delta steps may be based on exactly what we just reported; keep
            // the scanned artifacts alive so those deltas stay satisfiable even when the
            // cached baselines are stale or missing (e.g. after a cancelled sync).
            Map<String, WorldSyncSupport.LazyArtifact> reportedLocalBundles = new HashMap<>();
            for (WorldSyncSupport.LazyArtifact bundle : localRegionBundles) {
                reportedLocalBundles.put(bundle.packId(), bundle);
            }
            new DownloadPlanApplier(this.apiClient, this.worldStore, worldId, worldDirectory, plan, localPackArtifact, reportedLocalBundles, scanCache, progressListener).apply();
        } finally {
            scanCache.save();
            if (localPackArtifact != null) {
                localPackArtifact.deleteBodyIfBuilt();
            }
            for (WorldSyncSupport.LazyArtifact bundle : localRegionBundles) {
                bundle.deleteBodyIfBuilt();
            }
        }

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
            WorldSyncSupport.LazyArtifact nonRegionArtifact,
            Map<String, WorldSyncSupport.LazyArtifact> regionBundlesById,
            WorldSyncSupport.SyncPolicy policy,
            WorldSyncProgressListener progressListener
    ) throws IOException, InterruptedException {
        // nonRegionArtifact is null when the non-region files went up as shard
        // packs; a backend echoing a pack plan anyway must not NPE the sync path.
        boolean preparePack = nonRegionArtifact != null && plan.nonRegionPackUpload() != null && !plan.nonRegionPackUpload().alreadyPresent();
        List<UploadPackPlanDto> bundlesToPrepare = plan.regionBundleUploads() == null
                ? List.of()
                : Arrays.stream(plan.regionBundleUploads()).filter(upload -> !upload.alreadyPresent()).toList();
        if (bundlesToPrepare.isEmpty() && !preparePack) {
            WorldSyncSupport.report(progressListener, STAGE_PREPARING_UPLOADS, 1.0D, 0L, 0L, "No changed files to upload");
            return List.of();
        }

        long startedAt = System.nanoTime();
        long totalExpectedBytes = bundlesToPrepare.stream().mapToLong(upload -> upload.pack().size()).sum()
                + (preparePack ? nonRegionArtifact.descriptor().size() : 0L);
        AtomicLong preparedBytes = new AtomicLong(0L);
        ExecutorService executor = Executors.newFixedThreadPool(policy.maxConcurrentUploadPreparations());
        List<Future<PreparedUpload>> futures = new ArrayList<>(bundlesToPrepare.size() + (preparePack ? 1 : 0));

        try {
            for (UploadPackPlanDto upload : bundlesToPrepare) {
                futures.add(executor.submit(() -> {
                    WorldSyncSupport.LazyArtifact bundle = regionBundlesById.get(upload.pack().packId());
                    if (bundle == null) {
                        throw new IOException("SharedWorld upload plan referenced unknown region bundle " + upload.pack().packId() + ".");
                    }
                    PreparedUpload preparedUpload = prepareGroupedArtifactUpload(
                            worldId,
                            bundle.body(),
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
                            nonRegionArtifact.body(),
                            plan.nonRegionPackUpload(),
                            nonRegionArtifact.descriptor(),
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
                preparedUploads.add(WorldSyncSupport.await(future));
            }
            WorldSyncSupport.logTiming(LOGGER, "prepare upload bodies", worldId, startedAt);
            return preparedUploads;
        } finally {
            WorldSyncSupport.shutDownAndAwait(executor);
        }
    }

    /**
     * The storage relay hard-rejects request bodies over the advertised limit
     * before any SharedWorld code runs, so an oversized body must fail here
     * with a message that names the culprit instead of a bare 413.
     */
    private static void failOnOversizedWorldFile(List<PreparedWorldFile> canonicalFiles, long maxUploadBodyBytes) throws IOException {
        for (PreparedWorldFile file : canonicalFiles) {
            if (file.size() > maxUploadBodyBytes) {
                throw new IOException("SharedWorld cannot upload this world: " + file.relativePath() + " is "
                        + megabytes(file.size()) + " MB, and this backend's relay transfer path is limited to "
                        + megabytes(maxUploadBodyBytes) + " MB per file. Remove or shrink that file, or wait for the "
                        + "SharedWorld backend to enable large-file uploads.");
            }
        }
    }

    private static void failOnOversizedUploadBody(List<PreparedUpload> preparedUploads, long maxUploadBodyBytes) throws IOException {
        for (PreparedUpload preparedUpload : preparedUploads) {
            if (preparedUpload.bodyPath() != null && preparedUpload.bodySize() > maxUploadBodyBytes) {
                throw new IOException("SharedWorld cannot upload \"" + preparedUpload.relativePath() + "\": its "
                        + megabytes(preparedUpload.bodySize()) + " MB body exceeds the " + megabytes(maxUploadBodyBytes)
                        + " MB upload limit.");
            }
        }
    }

    private static long megabytes(long bytes) {
        return Math.max(1L, Math.round(bytes / 1_000_000.0D));
    }

    private void uploadPreparedFiles(
            String worldId,
            List<PreparedUpload> preparedUploads,
            WorldSyncSupport.SyncPolicy policy,
            WorldSyncProgressListener progressListener,
            long totalUploadBytes,
            link.sharedworld.api.SharedWorldModels.DirectUploadPolicyDto directUpload,
            long runtimeEpoch,
            String hostToken
    ) throws IOException, InterruptedException {
        if (preparedUploads.isEmpty()) {
            WorldSyncSupport.report(progressListener, STAGE_UPLOADING_CHANGED_FILES, 1.0D, 0L, 0L, "No changed files to upload");
            return;
        }
        if (directUpload == null) {
            // Only the relay path has a per-body byte ceiling; direct uploads
            // are bounded by provider quota alone.
            failOnOversizedUploadBody(preparedUploads, policy.maxUploadBodyBytes());
        }

        applyConfiguredDevUploadDelay(worldId);

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
                    link.sharedworld.api.SharedWorldApiClient.UploadProgressListener transferProgress = (bytesTransferred, totalBytes) -> {
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
                    };
                    String contentType = preparedUpload.manifestFile() != null
                            ? preparedUpload.manifestFile().contentType()
                            : "application/octet-stream";
                    String directStorageKey = preparedUpload.snapshotPack() != null
                            ? preparedUpload.snapshotPack().storageKey()
                            : null;
                    limiter.awaitTurn();
                    if (directUpload != null && directStorageKey != null) {
                        // The uploader owns transport retries with resume; an
                        // outer whole-transfer retry would restart from byte 0.
                        // The blob stamp rides the plan's signed headers and
                        // spares the backend a coordinator call per artifact.
                        java.util.Map<String, String> signedHeaders = preparedUpload.uploadUrl().headers();
                        this.apiClient.uploadBlobDirect(
                                worldId,
                                directStorageKey,
                                runtimeEpoch,
                                hostToken,
                                signedHeaders == null ? null : signedHeaders.get("x-sharedworld-blob-stamp"),
                                preparedUpload.bodyPath(),
                                contentType,
                                directUpload.chunkSizeBytes(),
                                transferProgress
                        );
                    } else {
                        WorldSyncSupport.withTransportRetries(policy, () -> this.apiClient.uploadBlob(
                                preparedUpload.uploadUrl(),
                                preparedUpload.bodyPath(),
                                contentType,
                                transferProgress
                        ));
                    }
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
                WorldSyncSupport.await(future);
            }
            WorldSyncSupport.logTiming(LOGGER, "upload changed files", worldId, startedAt);
        } finally {
            WorldSyncSupport.shutDownAndAwait(executor);
        }
    }

    private void applyConfiguredDevUploadDelay(String worldId) throws InterruptedException {
        if (!SharedWorldDevSessionBridge.isCurrentSessionDev()) {
            return;
        }
        String rawDelay = System.getProperty(DEV_SLOW_UPLOAD_MS_PROPERTY, "").trim();
        if (rawDelay.isEmpty()) {
            return;
        }
        long delayMs;
        try {
            delayMs = Long.parseLong(rawDelay);
        } catch (NumberFormatException exception) {
            LOGGER.warn("SharedWorld ignoring invalid {} value {}", DEV_SLOW_UPLOAD_MS_PROPERTY, rawDelay);
            return;
        }
        if (delayMs <= 0L) {
            return;
        }
        LOGGER.info(
                "SharedWorld applying dev upload delay of {} ms for {} via {}",
                delayMs,
                worldId,
                DEV_SLOW_UPLOAD_MS_PROPERTY
        );
        Thread.sleep(delayMs);
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
                // 0.4.0 clients write v2 deltas only, into delta2 slots the
                // backend offers to capable clients; an old backend (field
                // absent) gets full artifacts — no v1 writer remains.
                && Integer.valueOf(2).equals(upload.deltaFormatVersion())
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
        ArtifactDeltaEngine.DeltaStats deltaStats = ArtifactDeltaEngine.writeDeltaV2(baselineFile, artifactFile, deltaBody);
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
            // The delta2 key already exists server-side (another host wrote
            // this exact base→target transition); the recorded blob size must
            // still reflect the delta blob so the accumulator stays honest.
            Files.deleteIfExists(deltaBody);
            return new PreparedUpload(
                    upload.pack().packId(),
                    null,
                    null,
                    0L,
                    null,
                    new SnapshotPackDto(localPack.packId(), localPack.hash(), localPack.size(), upload.deltaStorageKey(), deltaTransferMode, upload.baseSnapshotId(), upload.baseHash(), nextChainDepth, 2, deltaSize, localPack.files())
            );
        }
        return new PreparedUpload(
                upload.pack().packId(),
                upload.deltaUpload(),
                deltaBody,
                deltaSize,
                null,
                new SnapshotPackDto(localPack.packId(), localPack.hash(), localPack.size(), upload.deltaStorageKey(), deltaTransferMode, upload.baseSnapshotId(), upload.baseHash(), nextChainDepth, 2, deltaSize, localPack.files())
        );
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
