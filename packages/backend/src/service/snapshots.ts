import {
  PACK_DELTA_TRANSFER_MODE,
  PACK_FULL_TRANSFER_MODE,
  REGION_DELTA_TRANSFER_MODE,
  REGION_FULL_TRANSFER_MODE,
  WHOLE_GZIP_TRANSFER_MODE,
  isRegionBundleId,
  type FileTransferMode,
  type FinalizeSnapshotRequest,
  type ManifestFile,
  type SnapshotActionResult,
  type SnapshotManifest,
  type SnapshotPack,
  type WorldSnapshotSummary
} from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import type { RequestContext, WorldStorageBinding } from "../repository.ts";
import type { StorageBinding } from "../storage.ts";
import type { ServiceContext } from "./context.ts";
import {
  requireAuthorizedRuntime,
  requireMembership,
  requireOwner,
  requireSessionAccess,
  requireWorldDetails,
  requireWorldStorageBinding
} from "./runtime-access.ts";

const SNAPSHOT_RETENTION_ALL_RECENT_MS = 24 * 60 * 60_000;
const SNAPSHOT_RETENTION_DAILY_MS = 30 * 24 * 60 * 60_000;

export async function listSnapshots(svc: ServiceContext, ctx: RequestContext, worldId: string): Promise<WorldSnapshotSummary[]> {
  await requireMembership(svc, ctx, worldId);
  return svc.repository.listSnapshotSummaries(worldId);
}

export async function latestManifest(svc: ServiceContext, ctx: RequestContext, worldId: string): Promise<SnapshotManifest | null> {
  await requireSessionAccess(svc, ctx, worldId);
  return svc.repository.getLatestSnapshot(worldId);
}

/**
 * Restoring a backup republishes it as the newest snapshot rather than rewriting
 * history; the restored manifest keeps pointing at the already-stored artifacts.
 */
export async function restoreSnapshot(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  snapshotId: string,
  now: Date
): Promise<SnapshotActionResult> {
  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  requireOwner(world, ctx, "restore backups");
  const snapshot = await svc.repository.getSnapshot(worldId, snapshotId);
  if (!snapshot) {
    throw snapshotNotFoundError();
  }
  await svc.repository.finalizeSnapshot(worldId, ctx, {
    baseSnapshotId: snapshot.snapshotId,
    files: snapshot.files,
    packs: snapshot.packs
  }, now);
  await applySnapshotRetention(svc, worldId, now);
  return {
    worldId,
    snapshotId
  };
}

export async function deleteSnapshot(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  snapshotId: string
): Promise<SnapshotActionResult> {
  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  requireOwner(world, ctx, "delete backups");
  const binding = await requireWorldStorageBinding(svc, worldId);
  const snapshot = await svc.repository.getSnapshot(worldId, snapshotId);
  if (!snapshot) {
    throw snapshotNotFoundError();
  }
  if (world.lastSnapshotId === snapshotId) {
    throw new HttpError(409, "cannot_delete_latest_snapshot", "The latest backup cannot be deleted.");
  }
  const deletion = await svc.repository.deleteSnapshots(worldId, [snapshotId]);
  await deleteUnreferencedBlobs(svc, binding, deletion.unreferencedStorageKeys);
  return {
    worldId,
    snapshotId
  };
}

export async function finalizeSnapshot(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: FinalizeSnapshotRequest,
  now: Date
): Promise<SnapshotManifest> {
  await requireSessionAccess(svc, ctx, worldId, { allowRevokedHost: true });
  await requireAuthorizedRuntime(
    svc,
    ctx,
    worldId,
    now,
    request.runtimeEpoch,
    request.hostToken,
    ["host-starting", "host-live", "host-finalizing"]
  );
  await validateFinalizeSnapshotRequest(svc, worldId, request);
  const manifest = await svc.repository.finalizeSnapshot(worldId, ctx, request, now);
  await applySnapshotRetention(svc, worldId, now);
  return manifest;
}

/**
 * Retention keeps every snapshot from the last day, one per day for a month, and
 * one per month beyond that; the newest snapshot is always kept. Cleanup failures
 * are logged, never propagated: retention must not fail a successful snapshot.
 */
export async function applySnapshotRetention(svc: ServiceContext, worldId: string, now: Date): Promise<void> {
  const snapshots = await svc.repository.listSnapshotsForWorld(worldId);
  const keep = selectSnapshotsToKeep(snapshots, now);
  const deleteIds = snapshots
    .map((snapshot) => snapshot.snapshotId)
    .filter((snapshotId) => !keep.has(snapshotId));
  if (deleteIds.length === 0) {
    return;
  }

  try {
    const binding = await requireWorldStorageBinding(svc, worldId);
    const deletion = await svc.repository.deleteSnapshots(worldId, deleteIds);
    await deleteUnreferencedBlobs(svc, binding, deletion.unreferencedStorageKeys);
  } catch (error) {
    console.warn("SharedWorld snapshot retention cleanup failed", error);
  }
}

export async function purgeWorldSnapshots(svc: ServiceContext, binding: StorageBinding, worldId: string): Promise<void> {
  try {
    const snapshots = await svc.repository.listSnapshotsForWorld(worldId);
    const deletion = await svc.repository.deleteSnapshots(
      worldId,
      snapshots.map((snapshot) => snapshot.snapshotId)
    );
    await deleteUnreferencedBlobs(svc, binding, deletion.unreferencedStorageKeys);
  } catch (error) {
    console.warn("SharedWorld world storage cleanup failed", error);
  }
}

export async function deleteUnreferencedBlobs(svc: ServiceContext, binding: StorageBinding, storageKeys: string[]): Promise<void> {
  for (const storageKey of storageKeys) {
    try {
      await svc.storageProvider.delete(binding, storageKey);
      if (svc.storageProvider.provider === "r2") {
        await svc.blobSigner.deleteBlob?.(storageKey);
      }
    } catch (error) {
      console.warn("SharedWorld blob cleanup failed for", storageKey, error);
    }
  }
}

export async function maybeDeleteUnreferencedBlob(svc: ServiceContext, binding: StorageBinding, storageKey: string | null): Promise<void> {
  if (!storageKey) {
    return;
  }
  const stillReferenced = await svc.repository.isStorageKeyReferenced(storageKey);
  if (!stillReferenced) {
    await deleteUnreferencedBlobs(svc, binding, [storageKey]);
  }
}

export async function storageKeyExists(svc: ServiceContext, binding: WorldStorageBinding, storageKey: string): Promise<boolean> {
  if (binding.provider === "google-drive") {
    if (binding.storageAccountId == null) {
      // Unlinked worlds do not have cheap object metadata to validate against.
      return true;
    }
    // Drive providers record every stored object in the repository; that row is the
    // authoritative existence check (the real provider's exists() is the same lookup).
    return (await svc.repository.getStorageObject(binding.provider, binding.storageAccountId, storageKey)) != null;
  }
  if (binding.provider === "r2" && svc.storageProvider.provider === "r2" && svc.env.BLOBS == null) {
    return true;
  }
  return svc.storageProvider.exists(binding, storageKey);
}

/**
 * Snapshot finalization validates the whole manifest before any row is written:
 * unique paths/pack ids, storage objects that actually exist, and delta chains
 * whose base snapshot, base hash, and chain depth all line up.
 */
async function validateFinalizeSnapshotRequest(svc: ServiceContext, worldId: string, request: FinalizeSnapshotRequest): Promise<void> {
  const binding = await requireWorldStorageBinding(svc, worldId);
  const snapshotCache = new Map<string, SnapshotManifest | null>();
  const seenPaths = new Set<string>();
  const seenPackIds = new Set<string>();

  if (request.baseSnapshotId != null) {
    await requireSnapshotForValidation(svc, worldId, request.baseSnapshotId, snapshotCache);
  }

  for (const file of request.files) {
    validateManifestFileShape(file);
    if (seenPaths.has(file.path)) {
      throw new HttpError(400, "duplicate_snapshot_path", `Snapshot includes duplicate file path '${file.path}'.`);
    }
    seenPaths.add(file.path);
    await assertStorageKeyExists(svc, binding, file.storageKey);
    await validateManifestFileBase(svc, worldId, file, snapshotCache);
  }

  for (const pack of request.packs ?? []) {
    validateSnapshotPackShape(pack);
    if (seenPackIds.has(pack.packId)) {
      throw new HttpError(400, "duplicate_snapshot_pack", `Snapshot includes duplicate pack id '${pack.packId}'.`);
    }
    seenPackIds.add(pack.packId);
    await assertStorageKeyExists(svc, binding, pack.storageKey);
    for (const file of pack.files) {
      if (file.path.trim().length === 0) {
        throw new HttpError(400, "invalid_snapshot_path", "Snapshot packed file path is required.");
      }
      if (seenPaths.has(file.path)) {
        throw new HttpError(400, "duplicate_snapshot_path", `Snapshot includes duplicate file path '${file.path}'.`);
      }
      seenPaths.add(file.path);
    }
    await validateSnapshotPackBase(svc, worldId, pack, snapshotCache);
  }
}

async function validateManifestFileBase(
  svc: ServiceContext,
  worldId: string,
  file: ManifestFile,
  snapshotCache: Map<string, SnapshotManifest | null>
): Promise<void> {
  const transferMode = normalizeFileTransferMode(file.transferMode);
  if (transferMode === REGION_DELTA_TRANSFER_MODE) {
    if (!file.baseSnapshotId || !file.baseHash || file.chainDepth == null || file.chainDepth < 1) {
      throw new HttpError(400, "invalid_snapshot_delta", `Snapshot delta file '${file.path}' is missing base metadata.`);
    }
    const baseSnapshot = await requireSnapshotForValidation(svc, worldId, file.baseSnapshotId, snapshotCache);
    const baseFile = baseSnapshot.files.find((entry) => entry.path === file.path);
    if (!baseFile) {
      throw new HttpError(400, "snapshot_base_not_found", `Snapshot base file '${file.path}' was not found in '${file.baseSnapshotId}'.`);
    }
    if (file.baseHash !== baseFile.hash) {
      throw new HttpError(400, "snapshot_base_hash_mismatch", `Snapshot base hash for '${file.path}' does not match '${file.baseSnapshotId}'.`);
    }
    const expectedChainDepth = nextChainDepth(normalizeFileTransferMode(baseFile.transferMode), baseFile.chainDepth ?? null);
    if (file.chainDepth !== expectedChainDepth) {
      throw new HttpError(400, "snapshot_chain_depth_mismatch", `Snapshot chain depth for '${file.path}' does not match its base artifact.`);
    }
    return;
  }
  if (file.baseSnapshotId != null || file.baseHash != null || !isZeroOrNullChainDepth(file.chainDepth ?? null)) {
    throw new HttpError(400, "invalid_snapshot_base", `Non-delta file '${file.path}' cannot declare base snapshot metadata.`);
  }
}

async function validateSnapshotPackBase(
  svc: ServiceContext,
  worldId: string,
  pack: SnapshotPack,
  snapshotCache: Map<string, SnapshotManifest | null>
): Promise<void> {
  if (isDeltaPackTransferMode(pack.transferMode)) {
    if (!pack.baseSnapshotId || !pack.baseHash || pack.chainDepth == null || pack.chainDepth < 1) {
      throw new HttpError(400, "invalid_snapshot_delta", `Snapshot delta pack '${pack.packId}' is missing base metadata.`);
    }
    const baseSnapshot = await requireSnapshotForValidation(svc, worldId, pack.baseSnapshotId, snapshotCache);
    const basePack = baseSnapshot.packs.find((entry) => entry.packId === pack.packId);
    if (!basePack) {
      throw new HttpError(400, "snapshot_base_not_found", `Snapshot base pack '${pack.packId}' was not found in '${pack.baseSnapshotId}'.`);
    }
    if (pack.baseHash !== basePack.hash) {
      throw new HttpError(400, "snapshot_base_hash_mismatch", `Snapshot base hash for pack '${pack.packId}' does not match '${pack.baseSnapshotId}'.`);
    }
    const expectedChainDepth = nextChainDepth(basePack.transferMode, basePack.chainDepth ?? null);
    if (pack.chainDepth !== expectedChainDepth) {
      throw new HttpError(400, "snapshot_chain_depth_mismatch", `Snapshot chain depth for pack '${pack.packId}' does not match its base artifact.`);
    }
    return;
  }
  if (pack.baseSnapshotId != null || pack.baseHash != null || !isZeroOrNullChainDepth(pack.chainDepth ?? null)) {
    throw new HttpError(400, "invalid_snapshot_base", `Non-delta pack '${pack.packId}' cannot declare base snapshot metadata.`);
  }
}

async function requireSnapshotForValidation(
  svc: ServiceContext,
  worldId: string,
  snapshotId: string,
  snapshotCache: Map<string, SnapshotManifest | null>
): Promise<SnapshotManifest> {
  let snapshot = snapshotCache.get(snapshotId);
  if (snapshot === undefined) {
    snapshot = await svc.repository.getSnapshot(worldId, snapshotId);
    snapshotCache.set(snapshotId, snapshot);
  }
  if (!snapshot) {
    throw new HttpError(400, "snapshot_base_not_found", `Snapshot base '${snapshotId}' was not found for this world.`);
  }
  return snapshot;
}

async function assertStorageKeyExists(svc: ServiceContext, binding: WorldStorageBinding, storageKey: string): Promise<void> {
  const exists = await storageKeyExists(svc, binding, storageKey);
  if (!exists) {
    throw new HttpError(400, "snapshot_storage_missing", `Snapshot storage object '${storageKey}' was not found.`);
  }
}

function validateManifestFileShape(file: ManifestFile): void {
  if (file.path.trim().length === 0) {
    throw new HttpError(400, "invalid_snapshot_path", "Snapshot file path is required.");
  }
  if (file.storageKey.trim().length === 0) {
    throw new HttpError(400, "invalid_snapshot_storage_key", `Snapshot file '${file.path}' is missing a storage key.`);
  }
  const transferMode = normalizeFileTransferMode(file.transferMode);
  const allowed = transferMode === WHOLE_GZIP_TRANSFER_MODE
    || transferMode === REGION_FULL_TRANSFER_MODE
    || transferMode === REGION_DELTA_TRANSFER_MODE;
  if (!allowed) {
    throw new HttpError(400, "invalid_snapshot_transfer_mode", `Snapshot file '${file.path}' uses unsupported transfer mode '${file.transferMode}'.`);
  }
}

function validateSnapshotPackShape(pack: SnapshotPack): void {
  if (pack.packId.trim().length === 0) {
    throw new HttpError(400, "invalid_snapshot_pack", "Snapshot pack id is required.");
  }
  if (pack.storageKey.trim().length === 0) {
    throw new HttpError(400, "invalid_snapshot_storage_key", `Snapshot pack '${pack.packId}' is missing a storage key.`);
  }
  const allowed = isRegionBundleId(pack.packId)
    ? pack.transferMode === REGION_FULL_TRANSFER_MODE || pack.transferMode === REGION_DELTA_TRANSFER_MODE
    : pack.transferMode === PACK_FULL_TRANSFER_MODE || pack.transferMode === PACK_DELTA_TRANSFER_MODE;
  if (!allowed) {
    throw new HttpError(400, "invalid_snapshot_transfer_mode", `Snapshot pack '${pack.packId}' uses unsupported transfer mode '${pack.transferMode}'.`);
  }
}

export function isDeltaPackTransferMode(mode: FileTransferMode): boolean {
  return mode === REGION_DELTA_TRANSFER_MODE || mode === PACK_DELTA_TRANSFER_MODE;
}

export function normalizeFileTransferMode(mode: FileTransferMode | null | undefined): FileTransferMode {
  return mode ?? WHOLE_GZIP_TRANSFER_MODE;
}

function nextChainDepth(baseTransferMode: FileTransferMode, baseChainDepth: number | null): number {
  return isDeltaPackTransferMode(baseTransferMode)
    ? (baseChainDepth ?? 0) + 1
    : 1;
}

function isZeroOrNullChainDepth(value: number | null): boolean {
  return value == null || value === 0;
}

function snapshotNotFoundError(): HttpError {
  return new HttpError(404, "snapshot_not_found", "SharedWorld backup not found.");
}

function selectSnapshotsToKeep(
  snapshots: Array<{ snapshotId: string; createdAt: string }>,
  now: Date
): Set<string> {
  const keep = new Set<string>();
  const nowTime = now.getTime();
  const dailyBuckets = new Set<string>();
  const monthlyBuckets = new Set<string>();

  for (const snapshot of snapshots) {
    const snapshotTime = new Date(snapshot.createdAt).getTime();
    if (!Number.isFinite(snapshotTime)) {
      keep.add(snapshot.snapshotId);
      continue;
    }

    const ageMs = Math.max(0, nowTime - snapshotTime);
    if (keep.size === 0 || ageMs <= SNAPSHOT_RETENTION_ALL_RECENT_MS) {
      keep.add(snapshot.snapshotId);
      continue;
    }

    const dayBucket = snapshot.createdAt.slice(0, 10);
    if (ageMs <= SNAPSHOT_RETENTION_DAILY_MS) {
      if (!dailyBuckets.has(dayBucket)) {
        dailyBuckets.add(dayBucket);
        keep.add(snapshot.snapshotId);
      }
      continue;
    }

    const monthBucket = snapshot.createdAt.slice(0, 7);
    if (!monthlyBuckets.has(monthBucket)) {
      monthlyBuckets.add(monthBucket);
      keep.add(snapshot.snapshotId);
    }
  }

  return keep;
}
