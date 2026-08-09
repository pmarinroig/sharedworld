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
  publishWorldEvent,
  requireActiveMembership,
  requireHostAuthority,
  requireMembership,
  requireOwner,
  requireWorldDetails,
  requireWorldStorageBinding,
  sessionActorOf
} from "./runtime-access.ts";

const SNAPSHOT_RETENTION_ALL_RECENT_MS = 24 * 60 * 60_000;
const SNAPSHOT_RETENTION_DAILY_MS = 30 * 24 * 60 * 60_000;

export async function listSnapshots(svc: ServiceContext, ctx: RequestContext, worldId: string): Promise<WorldSnapshotSummary[]> {
  await requireMembership(svc, ctx, worldId);
  return svc.repository.listSnapshotSummaries(worldId);
}

export async function latestManifest(svc: ServiceContext, ctx: RequestContext, worldId: string): Promise<SnapshotManifest | null> {
  await requireActiveMembership(svc, ctx, worldId);
  return svc.repository.getLatestSnapshot(worldId);
}

/**
 * Restoring a backup republishes it as the newest snapshot rather than rewriting
 * history; the restored manifest keeps pointing at the already-stored artifacts.
 * The republished snapshot carries the original's game-version stamps so the
 * cross-version guardrail keeps working on restored worlds, and restore is
 * refused while any host runtime is active: changing the latest snapshot under
 * a live host would invalidate its in-flight delta bases.
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
  const actor = await sessionActorOf(svc, ctx, worldId);
  const runtime = await svc.realtime.coordinator(worldId).runtimeStatus(actor, now);
  if (runtime.phase === "host-starting" || runtime.phase === "host-live" || runtime.phase === "host-finalizing") {
    throw new HttpError(409, "world_busy", "SharedWorld backups cannot be restored while the world is being hosted.");
  }
  const snapshot = await svc.repository.getSnapshot(worldId, snapshotId);
  if (!snapshot) {
    throw snapshotNotFoundError();
  }
  const gameVersions = await svc.repository.getSnapshotGameVersions(worldId, snapshotId);
  await svc.repository.finalizeSnapshot(worldId, ctx, {
    baseSnapshotId: snapshot.snapshotId,
    dataVersion: gameVersions?.dataVersion ?? null,
    minecraftVersion: gameVersions?.minecraftVersion ?? null,
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
  const deltaBases = await svc.repository.listSnapshotDeltaBases(worldId);
  if (deltaBases.some((edge) => edge.baseSnapshotId === snapshotId && edge.snapshotId !== snapshotId)) {
    throw new HttpError(409, "snapshot_base_in_use", "Another backup still builds on this one, so it cannot be deleted.");
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
  await requireHostAuthority(
    svc,
    ctx,
    worldId,
    request.runtimeEpoch,
    request.hostToken,
    ["host-starting", "host-live", "host-finalizing"],
    now
  );
  await validateFinalizeSnapshotRequest(svc, worldId, request);
  const manifest = await svc.repository.finalizeSnapshot(worldId, ctx, request, now);
  await applySnapshotRetention(svc, worldId, now);
  await publishWorldEvent(svc, worldId, "snapshot-changed");
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
  await expandKeepSetWithDeltaBases(svc, worldId, keep);
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
  return (await storageKeysExist(svc, binding, [storageKey])).has(storageKey);
}

/**
 * Existence for a whole key set at once. Large worlds carry hundreds of packs;
 * checking them one query at a time put upload prepare/finalize past the
 * client's request timeout, so callers with more than one key must use this.
 *
 * `whenUnverifiable` picks the fallback when there is no object metadata to
 * check (unlinked world, R2 without a bucket binding): finalize validation
 * assumes keys are present (a missing check must not reject a snapshot), while
 * upload planning asks the provider so fresh worlds still get signed slots.
 */
export async function storageKeysExist(
  svc: ServiceContext,
  binding: WorldStorageBinding,
  storageKeys: readonly string[],
  whenUnverifiable: "assume-present" | "ask-provider" = "assume-present"
): Promise<Set<string>> {
  const unique = [...new Set(storageKeys)];
  if (unique.length === 0) {
    return new Set();
  }
  if (binding.provider === "google-drive" && binding.storageAccountId != null) {
    // Drive providers record every stored object in the repository; those rows are
    // the authoritative existence check (the real provider's exists() is the same lookup).
    return svc.repository.listExistingStorageKeys(binding.provider, binding.storageAccountId, unique);
  }
  if (whenUnverifiable === "assume-present") {
    if (binding.provider === "google-drive") {
      // Unlinked worlds do not have cheap object metadata to validate against.
      return new Set(unique);
    }
    if (binding.provider === "r2" && svc.storageProvider.provider === "r2" && svc.env.BLOBS == null) {
      return new Set(unique);
    }
  }
  const checks = await Promise.all(
    unique.map(async (key) => ({ key, exists: await svc.storageProvider.exists(binding, key) }))
  );
  return new Set(checks.filter((check) => check.exists).map((check) => check.key));
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
  const existingStorageKeys = await storageKeysExist(svc, binding, [
    ...request.files.map((file) => file.storageKey),
    ...(request.packs ?? []).map((pack) => pack.storageKey)
  ]);

  if (request.baseSnapshotId != null) {
    await requireSnapshotForValidation(svc, worldId, request.baseSnapshotId, snapshotCache);
  }

  for (const file of request.files) {
    validateManifestFileShape(file);
    if (seenPaths.has(file.path)) {
      throw new HttpError(400, "duplicate_snapshot_path", `Snapshot includes duplicate file path '${file.path}'.`);
    }
    seenPaths.add(file.path);
    assertStorageKeyExists(existingStorageKeys, file.storageKey);
    await validateManifestFileBase(svc, worldId, file, snapshotCache);
  }

  for (const pack of request.packs ?? []) {
    validateSnapshotPackShape(pack);
    if (seenPackIds.has(pack.packId)) {
      throw new HttpError(400, "duplicate_snapshot_pack", `Snapshot includes duplicate pack id '${pack.packId}'.`);
    }
    seenPackIds.add(pack.packId);
    assertStorageKeyExists(existingStorageKeys, pack.storageKey);
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

/**
 * Shared delta-base validation for the two artifact families (manifest files
 * and snapshot packs). The rules are identical; only the lookup into the base
 * snapshot and the human-readable labels differ.
 */
type DeltaBaseArtifact = {
  kind: "file" | "pack";
  ref: string;
  isDelta: boolean;
  baseSnapshotId: string | null | undefined;
  baseHash: string | null | undefined;
  chainDepth: number | null | undefined;
  findBase(base: SnapshotManifest): { hash: string; expectedChainDepth: number } | null;
};

async function validateDeltaArtifactBase(
  svc: ServiceContext,
  worldId: string,
  artifact: DeltaBaseArtifact,
  snapshotCache: Map<string, SnapshotManifest | null>
): Promise<void> {
  const hashRef = artifact.kind === "pack" ? `pack ${artifact.ref}` : artifact.ref;
  if (artifact.isDelta) {
    if (!artifact.baseSnapshotId || !artifact.baseHash || artifact.chainDepth == null || artifact.chainDepth < 1) {
      throw new HttpError(400, "invalid_snapshot_delta", `Snapshot delta ${artifact.kind} ${artifact.ref} is missing base metadata.`);
    }
    const baseSnapshot = await requireSnapshotForValidation(svc, worldId, artifact.baseSnapshotId, snapshotCache);
    const base = artifact.findBase(baseSnapshot);
    if (base == null) {
      throw new HttpError(400, "snapshot_base_not_found", `Snapshot base ${artifact.kind} ${artifact.ref} was not found in '${artifact.baseSnapshotId}'.`);
    }
    if (artifact.baseHash !== base.hash) {
      throw new HttpError(400, "snapshot_base_hash_mismatch", `Snapshot base hash for ${hashRef} does not match '${artifact.baseSnapshotId}'.`);
    }
    if (artifact.chainDepth !== base.expectedChainDepth) {
      throw new HttpError(400, "snapshot_chain_depth_mismatch", `Snapshot chain depth for ${hashRef} does not match its base artifact.`);
    }
    return;
  }
  if (artifact.baseSnapshotId != null || artifact.baseHash != null || !isZeroOrNullChainDepth(artifact.chainDepth ?? null)) {
    throw new HttpError(400, "invalid_snapshot_base", `Non-delta ${artifact.kind} ${artifact.ref} cannot declare base snapshot metadata.`);
  }
}

async function validateManifestFileBase(
  svc: ServiceContext,
  worldId: string,
  file: ManifestFile,
  snapshotCache: Map<string, SnapshotManifest | null>
): Promise<void> {
  await validateDeltaArtifactBase(svc, worldId, {
    kind: "file",
    ref: `'${file.path}'`,
    isDelta: normalizeFileTransferMode(file.transferMode) === REGION_DELTA_TRANSFER_MODE,
    baseSnapshotId: file.baseSnapshotId,
    baseHash: file.baseHash,
    chainDepth: file.chainDepth,
    findBase(base) {
      const baseFile = base.files.find((entry) => entry.path === file.path);
      return baseFile == null
        ? null
        : { hash: baseFile.hash, expectedChainDepth: nextChainDepth(normalizeFileTransferMode(baseFile.transferMode), baseFile.chainDepth ?? null) };
    }
  }, snapshotCache);
}

async function validateSnapshotPackBase(
  svc: ServiceContext,
  worldId: string,
  pack: SnapshotPack,
  snapshotCache: Map<string, SnapshotManifest | null>
): Promise<void> {
  await validateDeltaArtifactBase(svc, worldId, {
    kind: "pack",
    ref: `'${pack.packId}'`,
    isDelta: isDeltaPackTransferMode(pack.transferMode),
    baseSnapshotId: pack.baseSnapshotId,
    baseHash: pack.baseHash,
    chainDepth: pack.chainDepth,
    findBase(base) {
      const basePack = base.packs.find((entry) => entry.packId === pack.packId);
      return basePack == null
        ? null
        : { hash: basePack.hash, expectedChainDepth: nextChainDepth(basePack.transferMode, basePack.chainDepth ?? null) };
    }
  }, snapshotCache);
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

function assertStorageKeyExists(existingStorageKeys: ReadonlySet<string>, storageKey: string): void {
  if (!existingStorageKeys.has(storageKey)) {
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

/**
 * Retention buckets purely by age, but a delta snapshot is only reconstructable
 * while every base in its chain still exists: pruning a base would let
 * deleteSnapshots reclaim the base's blobs and leave the surviving delta
 * permanently unreconstructable. Keep the transitive closure of delta bases
 * reachable from every kept snapshot. (Inherited pack MEMBER rows need no
 * such protection: deleteSnapshots promotes them to a surviving heir, so
 * member donors are freely prunable — keeping them here would transitively
 * retain nearly every autosave and defeat retention entirely.)
 */
async function expandKeepSetWithDeltaBases(svc: ServiceContext, worldId: string, keep: Set<string>): Promise<void> {
  const edges = await svc.repository.listSnapshotDeltaBases(worldId);
  const basesByReferrer = new Map<string, string[]>();
  for (const edge of edges) {
    const bases = basesByReferrer.get(edge.snapshotId) ?? [];
    bases.push(edge.baseSnapshotId);
    basesByReferrer.set(edge.snapshotId, bases);
  }
  const pending = [...keep];
  while (pending.length > 0) {
    const snapshotId = pending.pop()!;
    for (const baseSnapshotId of basesByReferrer.get(snapshotId) ?? []) {
      if (!keep.has(baseSnapshotId)) {
        keep.add(baseSnapshotId);
        pending.push(baseSnapshotId);
      }
    }
  }
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
