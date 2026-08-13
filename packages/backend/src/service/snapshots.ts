import {
  DELTA_V2_FORMAT_VERSION,
  DELTA_V2_MAX_CHAIN_DEPTH,
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
import { buildManifestDocument } from "../manifest-doc.ts";
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
  await persistSnapshot(svc, worldId, ctx, {
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
  // S1: edges come only from non-self-contained referrers, so stamped
  // snapshots pin nothing and old backups become individually deletable.
  // The residual 409 covers only legacy snapshots that still resolve their
  // chains by walking base snapshot rows.
  const deltaBases = await svc.repository.listSnapshotDeltaBases(worldId);
  if (deltaBases.some((edge) => edge.baseSnapshotId === snapshotId && edge.snapshotId !== snapshotId)) {
    throw new HttpError(
      409,
      "snapshot_base_in_use",
      "A newer backup still needs this one to stay restorable. It will become deletable automatically as backups refresh."
    );
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
  await computeChainDeltaBytes(svc, worldId, request);
  const manifest = await persistSnapshot(svc, worldId, ctx, request, now);
  // Retention runs at most hourly per world (CAS claim): it only ever
  // deletes >24h-old snapshots, so per-finalize cadence bought nothing but
  // delete/promotion writes on every autosave. Manual delete/restore keep
  // their immediate retention passes.
  if (await svc.repository.claimRetentionSlot(worldId, now, SNAPSHOT_RETENTION_INTERVAL_MS)) {
    await applySnapshotRetention(svc, worldId, now);
  }
  await publishWorldEvent(svc, worldId, "snapshot-changed");
  return manifest;
}

const SNAPSHOT_RETENTION_INTERVAL_MS = 60 * 60_000;

/**
 * 0027 write path: persist the snapshot with its pack member lists as one
 * content-addressed manifest document in the world's storage instead of
 * per-file D1 rows, falling back to legacy rows when the document cannot be
 * written (autosave availability beats format purity — Drive was necessarily
 * reachable seconds earlier for the artifact uploads, but a flake here must
 * not fail the snapshot). The document upload strictly precedes the D1
 * batch: an orphaned doc left by a failed batch is inert content-addressed
 * garbage that the retried finalize adopts via the existence check, whereas
 * the reverse order could commit a snapshot whose manifest can never load.
 */
export async function persistSnapshot(
  svc: ServiceContext,
  worldId: string,
  ctx: RequestContext,
  request: FinalizeSnapshotRequest,
  now: Date
): Promise<SnapshotManifest> {
  // Stamped here so BOTH producers (finalize and restore) emit
  // self-contained snapshots — restore republishes packs whose recipes
  // inherit from the restored-from snapshot's directory.
  await stampChainSteps(svc, worldId, request);
  let manifestStorageKey: string | null = null;
  const packs = request.packs ?? [];
  if (packs.length > 0) {
    const binding = await requireWorldStorageBinding(svc, worldId);
    if (manifestDocCapable(svc, binding)) {
      try {
        const built = await buildManifestDocument(packs);
        if (!(await storageKeyExists(svc, binding, built.storageKey))) {
          await svc.storageProvider.put(binding, built.storageKey, built.bytes, "application/json");
        }
        manifestStorageKey = built.storageKey;
      } catch (error) {
        console.warn("SharedWorld manifest document write failed; falling back to row manifest", {
          worldId,
          cause: String(error)
        });
      }
    }
  }
  // D1 failures propagate as today — only the doc write itself falls back.
  return svc.repository.finalizeSnapshot(
    worldId,
    ctx,
    request,
    now,
    manifestStorageKey != null ? { manifestStorageKey } : undefined
  );
}

// Doc writes are unconditional where capable: deploying this code IS the
// enablement, and the in-code row fallback covers transient Drive failures.
// Never roll the worker back below migration 0027 once doc snapshots exist.
function manifestDocCapable(svc: ServiceContext, binding: WorldStorageBinding): boolean {
  if (binding.provider === "google-drive") {
    // Unlinked worlds have nowhere to put the doc (and no object rows to
    // validate against) — they stay row-based. The live provider must match
    // the binding: a mismatched provider (drive-bound world served by the
    // R2 provider, as in unit fixtures) would "store" the doc into nothing.
    return binding.storageAccountId != null && svc.storageProvider.provider === "google-drive";
  }
  return binding.provider === "r2" && svc.storageProvider.provider === "r2" && svc.env.BLOBS != null;
}

/**
 * Retention keeps every snapshot from the last day, one per day for a month, and
 * one per month beyond that; the newest snapshot is always kept. Cleanup failures
 * are logged, never propagated: retention must not fail a successful snapshot.
 */
export async function applySnapshotRetention(svc: ServiceContext, worldId: string, now: Date): Promise<void> {
  const snapshots = await svc.repository.listSnapshotsForWorld(worldId);
  const maxBackups = (await svc.repository.getWorldSettings(worldId))?.settings?.maxBackups ?? null;
  const keep = selectSnapshotsToKeep(snapshots, now, maxBackups);
  // S1 lazy upgrade: make the KEPT snapshots self-contained first, so the
  // closure below stops protecting their whole ancestry and the rest of the
  // history becomes deletable. One-time per legacy snapshot; no-op after.
  try {
    await upgradeKeptSnapshotsToSelfContained(svc, worldId, keep);
  } catch (error) {
    console.warn("SharedWorld chain-steps upgrade failed; retention stays conservative", { worldId, cause: String(error) });
  }
  await expandKeepSetWithDeltaBases(svc, worldId, keep);
  const deleteIds = snapshots
    .map((snapshot) => snapshot.snapshotId)
    .filter((snapshotId) => !keep.has(snapshotId));

  try {
    const binding = await requireWorldStorageBinding(svc, worldId);
    if (deleteIds.length > 0) {
      const deletion = await svc.repository.deleteSnapshots(worldId, deleteIds);
      await deleteUnreferencedBlobs(svc, binding, deletion.unreferencedStorageKeys);
    }
    // Piggybacked 0028 retry sweep: rides the same hourly retention slot.
    await sweepPendingBlobDeletes(svc, binding, now);
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
      // 0028: the unreferenced-key computation runs exactly once (candidates
      // come from rows that are already deleted), so a dropped delete used
      // to orphan the bytes permanently. Enqueue for the bounded sweep.
      if (binding.storageAccountId != null) {
        try {
          await svc.repository.enqueuePendingBlobDelete(binding.provider, binding.storageAccountId, storageKey, new Date().toISOString());
        } catch (enqueueError) {
          console.warn("SharedWorld pending-delete enqueue failed", { storageKey, cause: String(enqueueError) });
        }
      }
    }
  }
}

const PENDING_BLOB_DELETE_SWEEP_LIMIT = 3;

/**
 * Bounded retry of previously-failed blob deletes (0028). Request-driven —
 * no cron exists — from the upload-session path and the hourly retention
 * slot. Re-referenced keys are dropped without deleting: content-addressed
 * dedupe can legitimately resurrect a key between enqueue and sweep.
 */
export async function sweepPendingBlobDeletes(svc: ServiceContext, binding: StorageBinding, now: Date): Promise<void> {
  if (binding.storageAccountId == null) {
    return;
  }
  try {
    const pending = await svc.repository.listPendingBlobDeletes(binding.provider, binding.storageAccountId, PENDING_BLOB_DELETE_SWEEP_LIMIT);
    for (const entry of pending) {
      if (await svc.repository.isStorageKeyReferenced(entry.storageKey)) {
        await svc.repository.deletePendingBlobDelete(binding.provider, binding.storageAccountId, entry.storageKey);
        continue;
      }
      try {
        await svc.storageProvider.delete(binding, entry.storageKey);
        if (svc.storageProvider.provider === "r2") {
          await svc.blobSigner.deleteBlob?.(entry.storageKey);
        }
        await svc.repository.deletePendingBlobDelete(binding.provider, binding.storageAccountId, entry.storageKey);
      } catch (error) {
        console.warn("SharedWorld pending blob delete retry failed", { storageKey: entry.storageKey, attempts: entry.attempts, cause: String(error) });
        await svc.repository.bumpPendingBlobDeleteAttempt(binding.provider, binding.storageAccountId, entry.storageKey, now.toISOString());
      }
    }
  } catch (error) {
    console.warn("SharedWorld pending blob delete sweep failed", { cause: String(error) });
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
  validateSnapshotPackDeltaV2Fields(pack);
}

/**
 * Server-side accumulator: for every pack in the request, stamp
 * chainDeltaBytes before persisting. Full packs restart the chain at 0; v2
 * delta packs extend their base's accumulator by their own blob size; v1
 * delta packs stay NULL (unaccounted — the planner will force a re-full).
 * Never trusts a client-sent accumulator.
 */
async function computeChainDeltaBytes(svc: ServiceContext, worldId: string, request: FinalizeSnapshotRequest): Promise<void> {
  const snapshotCache = new Map<string, SnapshotManifest | null>();
  for (const pack of request.packs ?? []) {
    if (!isDeltaPackTransferMode(pack.transferMode)) {
      pack.chainDeltaBytes = 0;
      continue;
    }
    if ((pack.deltaFormatVersion ?? null) !== DELTA_V2_FORMAT_VERSION) {
      pack.chainDeltaBytes = null;
      continue;
    }
    const baseSnapshot = await requireSnapshotForValidation(svc, worldId, pack.baseSnapshotId as string, snapshotCache);
    const basePack = baseSnapshot.packs.find((entry) => entry.packId === pack.packId);
    const baseAccumulator = basePack == null || isDeltaPackTransferMode(basePack.transferMode)
      ? (basePack?.chainDeltaBytes ?? null)
      : 0;
    if (baseAccumulator == null) {
      // The planner never offers a v2 slot over an unaccounted chain; a
      // client claiming one anyway is broken or hostile.
      throw new HttpError(400, "invalid_snapshot_delta", `Snapshot pack '${pack.packId}' chains a v2 delta onto an unaccounted base.`);
    }
    pack.chainDeltaBytes = baseAccumulator + (pack.deltaBlobSize as number);
  }
}

/**
 * v2 delta bookkeeping rules: a v2 delta pack must report its true blob size
 * (the accumulator's input) and stay under the depth ceiling; non-delta packs
 * must not claim a delta format. chain_delta_bytes is never accepted from the
 * client — finalize computes it from the base row.
 */
function validateSnapshotPackDeltaV2Fields(pack: SnapshotPack): void {
  const version = pack.deltaFormatVersion ?? null;
  if (version == null) {
    return;
  }
  if (version !== DELTA_V2_FORMAT_VERSION) {
    throw new HttpError(400, "invalid_snapshot_delta", `Snapshot pack '${pack.packId}' declares unsupported delta format ${version}.`);
  }
  if (!isDeltaPackTransferMode(pack.transferMode)) {
    throw new HttpError(400, "invalid_snapshot_delta", `Snapshot pack '${pack.packId}' declares a delta format on a non-delta transfer mode.`);
  }
  if (pack.deltaBlobSize == null || !Number.isFinite(pack.deltaBlobSize) || pack.deltaBlobSize <= 0) {
    throw new HttpError(400, "invalid_snapshot_delta", `Snapshot pack '${pack.packId}' is missing its delta blob size.`);
  }
  if ((pack.chainDepth ?? 0) > DELTA_V2_MAX_CHAIN_DEPTH) {
    throw new HttpError(400, "snapshot_chain_depth_mismatch", `Snapshot pack '${pack.packId}' exceeds the delta chain ceiling.`);
  }
}


/**
 * Server-stamped self-contained chains (S1): every pack in the request gets
 * a chainSteps recipe — full packs anchor a fresh chain, delta packs extend
 * their base pack's steps. Client-sent values are always overwritten (same
 * trust model as chainDeltaBytes). When the base is a legacy snapshot with
 * no steps of its own, the chain is synthesized once by walking the legacy
 * base headers here, so the FIRST stamped snapshot is already independent
 * of every older snapshot row. A broken/unresolvable legacy chain leaves
 * chainSteps null — that pack keeps the walk-based download path.
 */
async function stampChainSteps(svc: ServiceContext, worldId: string, request: FinalizeSnapshotRequest): Promise<void> {
  const headersCache = new Map<string, SnapshotManifest | null>();
  for (const pack of request.packs ?? []) {
    if (!isDeltaPackTransferMode(pack.transferMode)) {
      pack.chainSteps = [selfChainStep(pack, null)];
      continue;
    }
    const baseSteps = await chainStepsOfBasePack(svc, worldId, pack, headersCache);
    pack.chainSteps = baseSteps == null
      ? null
      : [...baseSteps, selfChainStep(pack, pack.baseHash ?? null)];
  }
}

/**
 * Synthesizes chainSteps recipes for kept snapshots that predate stamping.
 * Reads and rewrites directories only (never cached manifests); a pack whose
 * legacy chain cannot be resolved simply stays unstamped and keeps
 * contributing conservative edges.
 */
async function upgradeKeptSnapshotsToSelfContained(svc: ServiceContext, worldId: string, keep: ReadonlySet<string>): Promise<void> {
  const headersCache = new Map<string, SnapshotManifest | null>();
  for (const snapshotId of keep) {
    const snapshot = await snapshotHeadersCached(svc, worldId, snapshotId, headersCache);
    if (snapshot == null) {
      continue;
    }
    const stepsByPackId = new Map<string, NonNullable<SnapshotPack["chainSteps"]>>();
    for (const pack of snapshot.packs) {
      if (pack.chainSteps != null) {
        continue;
      }
      if (!isDeltaPackTransferMode(pack.transferMode)) {
        stepsByPackId.set(pack.packId, [selfChainStep(pack, null)]);
        continue;
      }
      const steps = await synthesizeLegacyChainSteps(svc, worldId, pack, pack.packId, headersCache);
      if (steps != null) {
        stepsByPackId.set(pack.packId, steps);
      }
    }
    if (stepsByPackId.size > 0) {
      await svc.repository.stampSnapshotChainSteps(snapshotId, stepsByPackId);
    }
  }
}

function selfChainStep(pack: SnapshotPack, baseHash: string | null): NonNullable<SnapshotPack["chainSteps"]>[number] {
  return {
    storageKey: pack.storageKey,
    hash: pack.hash,
    baseHash,
    transferMode: pack.transferMode,
    size: pack.size,
    deltaFormatVersion: pack.deltaFormatVersion ?? null
  };
}

async function chainStepsOfBasePack(
  svc: ServiceContext,
  worldId: string,
  pack: SnapshotPack,
  headersCache: Map<string, SnapshotManifest | null>
): Promise<SnapshotPack["chainSteps"]> {
  if (pack.baseSnapshotId == null) {
    return null;
  }
  const baseSnapshot = await snapshotHeadersCached(svc, worldId, pack.baseSnapshotId, headersCache);
  const basePack = baseSnapshot?.packs.find((entry) => entry.packId === pack.packId);
  if (basePack == null) {
    return null;
  }
  if (basePack.chainSteps != null) {
    return basePack.chainSteps;
  }
  return synthesizeLegacyChainSteps(svc, worldId, basePack, pack.packId, headersCache);
}

/** Walks a legacy (pre-stamping) chain once to its anchor full. */
async function synthesizeLegacyChainSteps(
  svc: ServiceContext,
  worldId: string,
  legacyBasePack: SnapshotPack,
  packId: string,
  headersCache: Map<string, SnapshotManifest | null>
): Promise<SnapshotPack["chainSteps"]> {
  const steps: NonNullable<SnapshotPack["chainSteps"]> = [];
  let cursor = legacyBasePack;
  // Existing depth ceilings bound real chains at 64; the margin guards
  // against malformed cycles.
  for (let hops = 0; hops < 80; hops += 1) {
    if (!isDeltaPackTransferMode(cursor.transferMode)) {
      steps.unshift(selfChainStep(cursor, null));
      return steps;
    }
    steps.unshift(selfChainStep(cursor, cursor.baseHash ?? null));
    if (cursor.baseSnapshotId == null) {
      break;
    }
    const baseSnapshot = await snapshotHeadersCached(svc, worldId, cursor.baseSnapshotId, headersCache);
    const next = baseSnapshot?.packs.find((entry) => entry.packId === packId);
    if (next == null) {
      break;
    }
    if (next.chainSteps != null) {
      return [...next.chainSteps, ...steps];
    }
    cursor = next;
  }
  console.warn("SharedWorld could not synthesize legacy chain steps", { worldId, packId });
  return null;
}

async function snapshotHeadersCached(
  svc: ServiceContext,
  worldId: string,
  snapshotId: string,
  cache: Map<string, SnapshotManifest | null>
): Promise<SnapshotManifest | null> {
  let snapshot = cache.get(snapshotId);
  if (snapshot === undefined) {
    snapshot = await svc.repository.getSnapshotHeaders(worldId, snapshotId);
    cache.set(snapshotId, snapshot);
  }
  return snapshot;
}

async function requireSnapshotForValidation(
  svc: ServiceContext,
  worldId: string,
  snapshotId: string,
  snapshotCache: Map<string, SnapshotManifest | null>
): Promise<SnapshotManifest> {
  let snapshot = snapshotCache.get(snapshotId);
  if (snapshot === undefined) {
    // Headers-only on purpose: delta validation and chainDeltaBytes read
    // base HEADERS (hash/transferMode/chainDepth/chainDeltaBytes) plus loose
    // rows, never pack member lists — so finalize stays independent of the
    // 0027 manifest document and a missing doc can never block the next
    // snapshot (the world heals by snapshotting again).
    snapshot = await svc.repository.getSnapshotHeaders(worldId, snapshotId);
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
  now: Date,
  maxBackups: number | null = null
): Set<string> {
  const keep = selectSnapshotsToKeepByAge(snapshots, now);
  if (maxBackups == null || keep.size <= maxBackups) {
    return keep;
  }
  // Owner cap (0.4.2 maxBackups): drop the OLDEST age-kept snapshots beyond
  // the cap. `snapshots` is newest-first, so taking the first N kept ids
  // always retains the latest.
  const capped = new Set<string>();
  for (const snapshot of snapshots) {
    if (keep.has(snapshot.snapshotId)) {
      capped.add(snapshot.snapshotId);
      if (capped.size >= maxBackups) {
        break;
      }
    }
  }
  return capped;
}

function selectSnapshotsToKeepByAge(
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
