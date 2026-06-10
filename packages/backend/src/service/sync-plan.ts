import {
  MAX_PACK_DELTA_CHAIN_DEPTH,
  MAX_REGION_DELTA_CHAIN_DEPTH,
  NON_REGION_PACK_ID,
  PACK_DELTA_TRANSFER_MODE,
  PACK_FULL_TRANSFER_MODE,
  REGION_DELTA_TRANSFER_MODE,
  REGION_FULL_TRANSFER_MODE,
  isRegionBundleId,
  storageKeyForPackDelta,
  storageKeyForPackFull,
  storageKeyForRegionBundleDelta,
  storageKeyForRegionBundleFull,
  type DownloadPackPlan,
  type DownloadPlan,
  type DownloadPlanStep,
  type LocalPackDescriptor,
  type SnapshotManifest,
  type SnapshotPack,
  type SyncPolicy,
  type UploadPlan,
  type UploadPlanRequest
} from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import type { RequestContext, WorldStorageBinding } from "../repository.ts";
import type { WorldRuntimeRecord } from "../runtime-protocol.ts";
import {
  HOST_TOKEN_HEADER,
  RUNTIME_EPOCH_HEADER,
  signDownloadForWorld,
  signUploadForWorld,
  type ServiceContext
} from "./context.ts";
import {
  requireAuthorizedRuntime,
  requireMembership,
  requireSessionAccess,
  requireWorldStorageBinding
} from "./runtime-access.ts";

/**
 * Responsibility:
 * Plan which artifacts the current host must upload for its next snapshot,
 * reusing already-stored artifacts and offering delta slots where the chain
 * depth allows.
 *
 * Stale-work rule:
 * Upload planning is epoch/token gated; a stale host cannot obtain signed
 * upload URLs.
 */
export async function prepareUploads(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: UploadPlanRequest
): Promise<UploadPlan> {
  await requireSessionAccess(svc, ctx, worldId, { allowRevokedHost: true });
  const authorizedRuntime = await requireAuthorizedRuntime(
    svc,
    ctx,
    worldId,
    new Date(),
    request.runtimeEpoch,
    request.hostToken,
    ["host-starting", "host-live", "host-finalizing"]
  );
  const latest = await svc.repository.getLatestSnapshot(worldId);
  const latestPack = latest?.packs.find((pack) => pack.packId === NON_REGION_PACK_ID) ?? null;
  const latestRegionBundleById = new Map(
    (latest?.packs ?? []).filter((pack) => isRegionBundleId(pack.packId)).map((pack) => [pack.packId, pack])
  );
  const binding = await requireWorldStorageBinding(svc, worldId);
  const regionBundleUploads: NonNullable<Awaited<ReturnType<typeof prepareGroupedArtifactUpload>>>[] = [];
  for (const bundle of request.regionBundles ?? []) {
    const plan = await prepareGroupedArtifactUpload(
      svc,
      ctx,
      worldId,
      bundle,
      latest?.snapshotId ?? null,
      latestRegionBundleById.get(bundle.packId) ?? null,
      authorizedRuntime.runtime,
      binding,
      MAX_REGION_DELTA_CHAIN_DEPTH,
      storageKeyForRegionBundleFull,
      storageKeyForRegionBundleDelta,
      REGION_FULL_TRANSFER_MODE,
      REGION_DELTA_TRANSFER_MODE
    );
    if (plan != null) {
      regionBundleUploads.push(plan);
    }
  }
  return {
    worldId,
    snapshotBaseId: latest?.snapshotId ?? null,
    uploads: [],
    nonRegionPackUpload: await prepareGroupedArtifactUpload(
      svc,
      ctx,
      worldId,
      request.nonRegionPack ?? null,
      latest?.snapshotId ?? null,
      latestPack,
      authorizedRuntime.runtime,
      binding,
      MAX_PACK_DELTA_CHAIN_DEPTH,
      storageKeyForPackFull,
      storageKeyForPackDelta,
      PACK_FULL_TRANSFER_MODE,
      PACK_DELTA_TRANSFER_MODE
    ),
    regionBundleUploads,
    syncPolicy: syncPolicyForProvider(svc)
  };
}

/**
 * Responsibility:
 * Plan the downloads needed to bring a local cache up to the latest snapshot,
 * preferring delta chains that end at the client's current artifact hash.
 */
export async function downloadPlan(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: UploadPlanRequest
): Promise<DownloadPlan> {
  await requireMembership(svc, ctx, worldId);
  const latest = await svc.repository.getLatestSnapshot(worldId);
  if (!latest) {
    return {
      worldId,
      snapshotId: null,
      downloads: [],
      nonRegionPackDownload: null,
      regionBundleDownloads: [],
      retainedPaths: request.files.map((file) => file.path),
      syncPolicy: syncPolicyForProvider(svc)
    };
  }

  const localByPath = new Map(request.files.map((file) => [file.path, file]));
  const retainedPaths: string[] = [];
  const snapshotCache = new Map<string, SnapshotManifest>();

  let nonRegionPackDownload: DownloadPackPlan | null = null;
  const regionBundleDownloads: DownloadPackPlan[] = [];
  const latestPack = latest.packs.find((pack) => pack.packId === NON_REGION_PACK_ID) ?? null;
  if (latestPack) {
    const packChanged = latestPack.files.some((file) => localByPath.get(file.path)?.hash !== file.hash);
    if (packChanged) {
      nonRegionPackDownload = {
        packId: latestPack.packId,
        hash: latestPack.hash,
        size: latestPack.size,
        files: latestPack.files,
        steps: await buildPackDownloadSteps(
          svc,
          worldId,
          latestPack,
          request.nonRegionPack?.hash ?? null,
          ctx.requestOrigin,
          snapshotCache,
          PACK_DELTA_TRANSFER_MODE
        )
      };
    } else {
      retainedPaths.push(...latestPack.files.map((file) => file.path));
    }
  }
  for (const bundle of latest.packs.filter((pack) => isRegionBundleId(pack.packId))) {
    const bundleChanged = bundle.files.some((file) => localByPath.get(file.path)?.hash !== file.hash);
    if (bundleChanged) {
      regionBundleDownloads.push({
        packId: bundle.packId,
        hash: bundle.hash,
        size: bundle.size,
        files: bundle.files,
        steps: await buildPackDownloadSteps(
          svc,
          worldId,
          bundle,
          request.regionBundles?.find((entry) => entry.packId === bundle.packId)?.hash ?? null,
          ctx.requestOrigin,
          snapshotCache,
          REGION_DELTA_TRANSFER_MODE
        )
      });
    } else {
      retainedPaths.push(...bundle.files.map((file) => file.path));
    }
  }

  return {
    worldId,
    snapshotId: latest.snapshotId,
    downloads: [],
    nonRegionPackDownload,
    regionBundleDownloads,
    retainedPaths,
    syncPolicy: syncPolicyForProvider(svc)
  };
}

/**
 * Blob bytes flow through the worker; host authority for uploads is re-checked
 * from the runtime headers stamped onto the signed upload URL.
 */
export async function uploadStorageBlob(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  storageKey: string,
  request: Request
): Promise<void> {
  await requireSessionAccess(svc, ctx, worldId, { allowRevokedHost: true });
  const runtimeEpochHeader = request.headers.get(RUNTIME_EPOCH_HEADER);
  await requireAuthorizedRuntime(
    svc,
    ctx,
    worldId,
    new Date(),
    runtimeEpochHeader == null ? null : Number(runtimeEpochHeader),
    request.headers.get(HOST_TOKEN_HEADER),
    ["host-starting", "host-live", "host-finalizing"]
  );
  const contentType = request.headers.get("content-type") ?? "application/octet-stream";
  await svc.storageProvider.put(await requireWorldStorageBinding(svc, worldId), storageKey, request.body ?? "", contentType);
}

export async function downloadStorageBlob(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  storageKey: string
): Promise<Response> {
  await requireSessionAccess(svc, ctx, worldId, { allowRevokedHost: true });
  const blob = await svc.storageProvider.get(await requireWorldStorageBinding(svc, worldId), storageKey);
  if (!blob) {
    throw new HttpError(404, "blob_not_found", "Blob not found.");
  }
  return new Response(blob.body, {
    status: 200,
    headers: {
      "content-type": blob.contentType
    }
  });
}

/**
 * Google Drive gets the conservative pacing because upload request starts are
 * its constrained resource; other providers can be driven harder.
 */
export function syncPolicyForProvider(svc: ServiceContext): SyncPolicy {
  if (svc.storageProvider.provider === "google-drive") {
    return {
      maxParallelDownloads: parsePositiveInt(svc.env.DRIVE_MAX_PARALLEL_DOWNLOADS, 8),
      maxConcurrentUploadPreparations: parsePositiveInt(svc.env.DRIVE_MAX_UPLOAD_PREPARATIONS, 2),
      maxConcurrentUploads: parsePositiveInt(svc.env.DRIVE_MAX_CONCURRENT_UPLOADS, 3),
      maxUploadStartsPerSecond: parsePositiveInt(svc.env.DRIVE_MAX_UPLOAD_STARTS_PER_SECOND, 3),
      retryBaseDelayMs: parsePositiveInt(svc.env.DRIVE_RETRY_BASE_DELAY_MS, 750),
      retryMaxDelayMs: parsePositiveInt(svc.env.DRIVE_RETRY_MAX_DELAY_MS, 8_000)
    };
  }

  return {
    maxParallelDownloads: 16,
    maxConcurrentUploadPreparations: 4,
    maxConcurrentUploads: 4,
    maxUploadStartsPerSecond: 8,
    retryBaseDelayMs: 250,
    retryMaxDelayMs: 4_000
  };
}

async function prepareGroupedArtifactUpload(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  pack: LocalPackDescriptor | null,
  latestSnapshotId: string | null,
  latestPack: SnapshotPack | null,
  runtime: WorldRuntimeRecord,
  binding: WorldStorageBinding,
  maxChainDepth: number,
  fullStorageKeyForHash: (hash: string) => string,
  deltaStorageKeyForHashes: (baseHash: string, hash: string) => string,
  fullTransferMode: typeof PACK_FULL_TRANSFER_MODE | typeof REGION_FULL_TRANSFER_MODE,
  deltaTransferMode: typeof PACK_DELTA_TRANSFER_MODE | typeof REGION_DELTA_TRANSFER_MODE
) {
  if (!pack) {
    return null;
  }
  if (latestPack?.hash === pack.hash) {
    return {
      pack,
      alreadyPresent: true,
      storageKey: latestPack.storageKey,
      transferMode: latestPack.transferMode,
      baseSnapshotId: latestPack.baseSnapshotId ?? null,
      baseHash: latestPack.baseHash ?? null,
      baseChainDepth: latestPack.chainDepth ?? null
    };
  }

  const fullStorageKey = fullStorageKeyForHash(pack.hash);
  const fullExists = await svc.storageProvider.exists(binding, fullStorageKey);
  const baseChainDepth = latestPack?.transferMode === deltaTransferMode
    ? (latestPack.chainDepth ?? 0)
    : 0;
  const deltaAvailable = latestPack != null
    && (latestPack.transferMode === fullTransferMode || latestPack.transferMode === deltaTransferMode)
    && baseChainDepth < maxChainDepth;
  const deltaStorageKey = deltaAvailable ? deltaStorageKeyForHashes(latestPack.hash, pack.hash) : null;
  const deltaExists = deltaStorageKey ? await svc.storageProvider.exists(binding, deltaStorageKey) : false;

  return {
    pack,
    alreadyPresent: false,
    transferMode: fullTransferMode,
    storageKey: null,
    upload: undefined,
    fullStorageKey,
    fullUpload: fullExists ? undefined : await signUploadForWorld(svc, worldId, fullStorageKey, runtime, ctx.requestOrigin),
    deltaStorageKey,
    deltaUpload: deltaStorageKey == null || deltaExists ? undefined : await signUploadForWorld(svc, worldId, deltaStorageKey, runtime, ctx.requestOrigin),
    baseSnapshotId: latestSnapshotId,
    baseHash: latestPack?.hash ?? null,
    baseChainDepth
  };
}

async function buildPackDownloadSteps(
  svc: ServiceContext,
  worldId: string,
  latestPack: SnapshotPack,
  localPackHash: string | null,
  requestOrigin: string | undefined,
  snapshotCache: Map<string, SnapshotManifest>,
  deltaTransferMode: typeof PACK_DELTA_TRANSFER_MODE | typeof REGION_DELTA_TRANSFER_MODE
): Promise<DownloadPlanStep[]> {
  const steps: DownloadPlanStep[] = [];
  let cursor: SnapshotPack | null = latestPack;
  while (cursor) {
    if (localPackHash != null && localPackHash === cursor.hash) {
      break;
    }
    steps.push({
      transferMode: cursor.transferMode,
      storageKey: cursor.storageKey,
      artifactSize: cursor.size,
      baseSnapshotId: cursor.baseSnapshotId ?? null,
      baseHash: cursor.baseHash ?? null,
      download: await signDownloadForWorld(svc, worldId, cursor.storageKey, requestOrigin)
    });
    if (cursor.transferMode !== deltaTransferMode || !cursor.baseSnapshotId) {
      break;
    }
    if (localPackHash != null && cursor.baseHash != null && localPackHash === cursor.baseHash) {
      break;
    }
    cursor = await loadSnapshotPack(svc, worldId, cursor.baseSnapshotId, cursor.packId, snapshotCache);
  }
  return steps.reverse();
}

async function loadSnapshotPack(
  svc: ServiceContext,
  worldId: string,
  snapshotId: string,
  packId: string,
  snapshotCache: Map<string, SnapshotManifest>
): Promise<SnapshotPack | null> {
  let snapshot: SnapshotManifest | undefined | null = snapshotCache.get(snapshotId);
  if (!snapshot) {
    snapshot = await svc.repository.getSnapshot(worldId, snapshotId);
    if (!snapshot) {
      return null;
    }
    snapshotCache.set(snapshotId, snapshot);
  }
  return snapshot.packs.find((pack) => pack.packId === packId) ?? null;
}

function parsePositiveInt(value: string | undefined, fallback: number): number {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
