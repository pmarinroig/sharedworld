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
  storageKeyForPackDeltaV2,
  storageKeyForPackFull,
  storageKeyForRegionBundleDelta,
  storageKeyForRegionBundleDeltaV2,
  storageKeyForRegionBundleFull,
  DELTA_V2_FORMAT_VERSION,
  DELTA_V2_MAX_CHAIN_DEPTH,
  DELTA_CHAIN_BUDGET_FRACTION,
  type DownloadPackPlan,
  type DownloadPlan,
  type DownloadPlanStep,
  type LocalPackDescriptor,
  type SnapshotManifest,
  type SnapshotPack,
  type SyncPolicy,
  type UploadPlan,
  type UploadPlanRequest,
  type CreateBlobSessionRequest,
  type CreateBlobSessionResponse,
  type CommitBlobSessionRequest,
  type CommitBlobSessionResponse
} from "../../../shared/src/index.ts";

import { clientVersionAtLeast, HttpError } from "../http.ts";
import { verifyBlobStamp, verifyDownloadStamp } from "./blob-stamp.ts";
import { parseSingleByteRange, resumableCapable, type ResumableUploadCapable } from "../storage.ts";
import { randomId } from "../ids.ts";
import type { RequestContext, WorldStorageBinding } from "../repository.ts";
import type { WorldRuntimeRecord } from "../runtime-protocol.ts";
import {
  BLOB_STAMP_HEADER,
  HOST_TOKEN_HEADER,
  RUNTIME_EPOCH_HEADER,
  signDownloadForWorld,
  signUploadForWorld,
  type DownloadViewer,
  type ServiceContext
} from "./context.ts";
import {
  requireHostAuthority,
  requireMembership,
  requireSessionAccessAllowingRevokedHost,
  requireWorldStorageBinding
} from "./runtime-access.ts";
import { isDeltaPackTransferMode, storageKeysExist } from "./snapshots.ts";
import { cachedQuota } from "./worlds.ts";
import { driveStorageFullError } from "../storage/drive.ts";

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
  await requireHostAuthority(
    svc,
    ctx,
    worldId,
    request.runtimeEpoch,
    request.hostToken,
    ["host-starting", "host-live", "host-finalizing"],
    new Date()
  );
  // Validation passed, so the request's own epoch/token are the current
  // authority tuple: they stamp the signed upload URLs.
  const authorizedRuntime = {
    runtime: { runtimeEpoch: request.runtimeEpoch ?? 0, runtimeToken: request.hostToken ?? null }
  };
  // Headers-only: planning consumes pack headers and ids, never member
  // lists — no member rows, no 0027 manifest-document fetch. This also
  // guarantees a missing/corrupt manifest doc can never block the upload
  // pipeline (prepare → finalize keeps working, and the next finalize
  // becomes the new latest with a fresh doc).
  const latest = await svc.repository.getLatestSnapshotHeaders(worldId);
  // Packs whose latest header can no longer be honoured (unstamped delta
  // whose base snapshot row is gone) are planned as if the world had never
  // stored them: the host re-uploads the full artifact and the next
  // snapshot is whole again. Stamped packs over a missing base are fine —
  // their recipe is self-contained and finalize inherits it.
  const unreconstructable = await unreconstructablePackIds(svc, worldId, latest);
  const latestPack = latest?.packs.find((pack) => pack.packId === NON_REGION_PACK_ID && !unreconstructable.has(pack.packId)) ?? null;
  const latestRegionBundleById = new Map(
    (latest?.packs ?? [])
      .filter((pack) => isRegionBundleId(pack.packId) && !unreconstructable.has(pack.packId))
      .map((pack) => [pack.packId, pack])
  );
  const binding = await requireWorldStorageBinding(svc, worldId);
  // Quota preflight: 0.4.x direct uploads PUT straight to Google, so a full
  // Drive would otherwise fail client-side with an unclassifiable 403 that
  // the autosave loop retries forever. Every upload path starts here — the
  // one reliable backend surface for the terminal, actionable answer.
  await failIfDriveFull(svc, binding);
  // One batched existence lookup for every candidate full/delta key: large
  // worlds carry hundreds of packs, and a per-pack query here put upload
  // prepare past the client's request timeout (same shape as the manifest
  // load fix in loadSnapshotPacks).
  const supportsDeltaV2 = clientVersionAtLeast(ctx.clientVersion, 0, 4, 0);
  const regionBundleKeys = (request.regionBundles ?? []).map((bundle) =>
    groupedArtifactCandidateKeys(
      bundle,
      latestRegionBundleById.get(bundle.packId) ?? null,
      MAX_REGION_DELTA_CHAIN_DEPTH,
      storageKeyForRegionBundleFull,
      supportsDeltaV2 ? storageKeyForRegionBundleDeltaV2 : storageKeyForRegionBundleDelta,
      REGION_FULL_TRANSFER_MODE,
      REGION_DELTA_TRANSFER_MODE,
      supportsDeltaV2
    )
  );
  const nonRegionPackKeys = groupedArtifactCandidateKeys(
    request.nonRegionPack ?? null,
    latestPack,
    MAX_PACK_DELTA_CHAIN_DEPTH,
    storageKeyForPackFull,
    supportsDeltaV2 ? storageKeyForPackDeltaV2 : storageKeyForPackDelta,
    PACK_FULL_TRANSFER_MODE,
    PACK_DELTA_TRANSFER_MODE,
    supportsDeltaV2
  );
  const existingStorageKeys = await storageKeysExist(
    svc,
    binding,
    [...regionBundleKeys, nonRegionPackKeys].flatMap((keys) =>
      keys ? [keys.fullStorageKey, ...(keys.deltaStorageKey ? [keys.deltaStorageKey] : [])] : []
    ),
    "ask-provider"
  );
  const regionBundleUploads: NonNullable<Awaited<ReturnType<typeof prepareGroupedArtifactUpload>>>[] = [];
  for (const [index, bundle] of (request.regionBundles ?? []).entries()) {
    const plan = await prepareGroupedArtifactUpload(
      svc,
      ctx,
      worldId,
      bundle,
      latest?.snapshotId ?? null,
      latestRegionBundleById.get(bundle.packId) ?? null,
      authorizedRuntime.runtime,
      regionBundleKeys[index] ?? null,
      existingStorageKeys
    );
    if (plan != null) {
      regionBundleUploads.push(plan);
    }
  }
  const nonRegionPackUpload = await prepareGroupedArtifactUpload(
    svc,
    ctx,
    worldId,
    request.nonRegionPack ?? null,
    latest?.snapshotId ?? null,
    latestPack,
    authorizedRuntime.runtime,
    nonRegionPackKeys,
    existingStorageKeys
  );
  const directUploadAvailable = resumableCapable(svc.storageProvider) != null && binding.storageAccountId != null;
  failOnOversizedFullUpload(svc, ctx, [nonRegionPackUpload, ...regionBundleUploads], directUploadAvailable);
  return {
    worldId,
    snapshotBaseId: latest?.snapshotId ?? null,
    uploads: [],
    nonRegionPackUpload,
    regionBundleUploads,
    syncPolicy: syncPolicyForProvider(svc),
    latestPackIds: latest?.packs.map((pack) => pack.packId) ?? [],
    directUpload: directUploadAvailable
      ? { chunkSizeBytes: DIRECT_UPLOAD_CHUNK_BYTES, maxUploadBytes: null }
      : null
  };
}

/**
 * Latest-snapshot packs that are delta artifacts with NO chainSteps recipe
 * AND whose base snapshot row no longer exists: nothing can rebuild them
 * (downloads would report snapshot_chain_broken, and a finalize carrying
 * them forward used to fail with snapshot_base_not_found). Bases become
 * deletable by design since S1, so this state is reachable through a manual
 * backup delete or retention on a legacy (pre-stamping) chain.
 */
async function unreconstructablePackIds(
  svc: ServiceContext,
  worldId: string,
  latest: SnapshotManifest | null
): Promise<Set<string>> {
  const candidates = (latest?.packs ?? []).filter((pack) =>
    isDeltaPackTransferMode(pack.transferMode)
    && pack.baseSnapshotId != null
    && (pack.chainSteps == null || pack.chainSteps.length === 0));
  if (candidates.length === 0) {
    return new Set();
  }
  const existing = await svc.repository.existingSnapshotIds(worldId, candidates.map((pack) => pack.baseSnapshotId as string));
  const broken = new Set<string>();
  for (const pack of candidates) {
    if (!existing.has(pack.baseSnapshotId as string)) {
      broken.add(pack.packId);
    }
  }
  if (broken.size > 0) {
    console.warn("SharedWorld upload plan forcing full re-upload of unreconstructable packs", { worldId, packs: [...broken] });
  }
  return broken;
}

/**
 * Bodies over the relay's limit die as unexplained 413s at the Cloudflare edge
 * before any worker code runs, so a plan that would force such a full upload
 * must fail here with the explanation attached. Fires only when no delta slot
 * exists — 0.3.1+ clients preflight their actual bodies themselves, and this
 * is what a pre-sharding client with an oversized superpack gets instead of
 * silence.
 */
function failOnOversizedFullUpload(
  svc: ServiceContext,
  ctx: RequestContext,
  plans: (Awaited<ReturnType<typeof prepareGroupedArtifactUpload>> | null)[],
  directUploadAvailable: boolean
): void {
  if (directUploadAvailable && clientVersionAtLeast(ctx.clientVersion, 0, 4, 0)) {
    // 0.4.0+ clients on a direct-capable world upload any size via resumable
    // sessions; the relay ceiling does not apply to them.
    return;
  }
  const limitBytes = maxUploadBodyBytes(svc);
  for (const plan of plans) {
    if (plan == null || plan.alreadyPresent || plan.deltaStorageKey != null || plan.pack.size <= limitBytes) {
      continue;
    }
    const sizeMb = Math.max(1, Math.round(plan.pack.size / 1_000_000));
    const limitMb = Math.max(1, Math.round(limitBytes / 1_000_000));
    // "Update the mod" is only honest advice for clients that predate direct
    // uploads; a current client landing here is on a relay-only world.
    const advice = clientVersionAtLeast(ctx.clientVersion, 0, 4, 0)
      ? "This world's storage only supports relayed transfers; shrink the world or re-link its storage."
      : "Update the SharedWorld mod to the latest version (it uploads large files directly), or shrink the world.";
    throw new HttpError(
      413,
      "blob_too_large",
      `This world's "${plan.pack.packId}" data is ${sizeMb} MB, but relayed SharedWorld uploads are limited to ${limitMb} MB per blob. ${advice}`
    );
  }
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
  const viewer: DownloadViewer = { playerUuid: ctx.playerUuid, requestOrigin: ctx.requestOrigin };
  const supportsDeltaV2 = clientVersionAtLeast(ctx.clientVersion, 0, 4, 0);
  // Chain recipes live only in the directory (headers path, uncached) —
  // served manifests stay byte-stable while retention lazily upgrades
  // legacy directories in place.
  const latestHeaders = await svc.repository.getLatestSnapshotHeaders(worldId);
  const chainStepsByPackId = new Map(
    (latestHeaders?.snapshotId === latest.snapshotId ? latestHeaders.packs : [])
      .filter((pack) => pack.chainSteps != null && pack.chainSteps.length > 0)
      .map((pack) => [pack.packId, pack.chainSteps!] as const)
  );

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
          chainStepsByPackId.get(latestPack.packId) ?? null,
          request.nonRegionPack?.hash ?? null,
          viewer,
          snapshotCache,
          PACK_DELTA_TRANSFER_MODE,
          supportsDeltaV2
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
          chainStepsByPackId.get(bundle.packId) ?? null,
          request.regionBundles?.find((entry) => entry.packId === bundle.packId)?.hash ?? null,
          viewer,
          snapshotCache,
          REGION_DELTA_TRANSFER_MODE,
          supportsDeltaV2
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
 * True when a valid blob stamp scoped to (worldId, storageKey) names an
 * epoch that is still the live runtime per the D1 mirror. This replaces the
 * coordinator round-trip on the per-artifact routes: the stamp was minted
 * only after full DO authority validation at plan time, and the mirror —
 * single-writer, coordinator-maintained — pins the epoch to the present.
 * Mirror `revokedAt` is deliberately ignored, matching validateHostAuthority
 * (a revoked host may finish its uploads; finalize stays the real gate).
 * Any miss returns false and the caller falls back to the DO path.
 */
async function stampAuthorized(
  svc: ServiceContext,
  worldId: string,
  stamp: string | null | undefined,
  storageKey: string
): Promise<boolean> {
  if (stamp == null || stamp.length === 0) {
    return false;
  }
  const claims = await verifyBlobStamp(svc.env, stamp, { worldId, storageKey }, new Date());
  if (claims == null) {
    return false;
  }
  const mirror = await svc.repository.getRuntimeMirror(worldId);
  if (mirror?.statusJson == null) {
    return false;
  }
  try {
    const status = JSON.parse(mirror.statusJson) as { phase?: string; runtimeEpoch?: number };
    return (status.phase === "host-starting" || status.phase === "host-live" || status.phase === "host-finalizing")
      && status.runtimeEpoch === claims.runtimeEpoch;
  } catch {
    return false;
  }
}

/**
 * Blob bytes flow through the worker; host authority for uploads is re-checked
 * from the runtime headers stamped onto the signed upload URL — via the HMAC
 * blob stamp when present and current (no coordinator call), else the
 * coordinator path.
 */
export async function uploadStorageBlob(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  storageKey: string,
  request: Request
): Promise<void> {
  if (!await stampAuthorized(svc, worldId, request.headers.get(BLOB_STAMP_HEADER), storageKey)) {
    const runtimeEpochHeader = request.headers.get(RUNTIME_EPOCH_HEADER);
    await requireHostAuthority(
      svc,
      ctx,
      worldId,
      runtimeEpochHeader == null ? null : Number(runtimeEpochHeader),
      request.headers.get(HOST_TOKEN_HEADER),
      ["host-starting", "host-live", "host-finalizing"],
      new Date()
    );
  }
  const contentType = request.headers.get("content-type") ?? "application/octet-stream";
  const contentLengthHeader = request.headers.get("content-length");
  const declaredLength = contentLengthHeader == null ? Number.NaN : Number(contentLengthHeader);
  const limitBytes = maxUploadBodyBytes(svc);
  const oversized = (bytes: number) => {
    const advice = clientVersionAtLeast(ctx.clientVersion, 0, 4, 0)
      ? "This world's storage only supports relayed transfers; shrink the world or re-link its storage."
      : "Update the SharedWorld mod to the latest version (it uploads large files directly), or shrink the world.";
    return new HttpError(
      413,
      "blob_too_large",
      `This blob is ${Math.max(1, Math.round(bytes / 1_000_000))} MB, but relayed SharedWorld uploads are limited to ${Math.max(1, Math.round(limitBytes / 1_000_000))} MB per blob. ${advice}`
    );
  };
  let body: ReadableStream<Uint8Array> | Uint8Array | string = request.body ?? "";
  let contentLength: number;
  if (Number.isSafeInteger(declaredLength) && declaredLength >= 0) {
    if (declaredLength > limitBytes) {
      throw oversized(declaredLength);
    }
    contentLength = declaredLength;
  } else {
    // Chunked upload with no Content-Length: shipped clients (Java
    // HttpClient with a progress-wrapped InputStream publisher) send these,
    // so a 411 here breaks real relays. Buffer ONCE — a single copy stays
    // well under the isolate limit because the relay ceiling does — and
    // stream to the provider with the now-known length.
    const buffered = new Uint8Array(await request.arrayBuffer());
    if (buffered.byteLength > limitBytes) {
      throw oversized(buffered.byteLength);
    }
    // One-chunk stream so the provider takes its streaming path (the
    // buffered multipart path would copy the body 2-3x — the original OOM).
    body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(buffered);
        controller.close();
      }
    });
    contentLength = buffered.byteLength;
  }
  await svc.storageProvider.put(await requireWorldStorageBinding(svc, worldId), storageKey, body, contentType, contentLength);
}

const DIRECT_UPLOAD_CHUNK_BYTES = 16 * 1024 * 1024;
const UPLOAD_SESSION_TTL_MS = 7 * 24 * 60 * 60_000;
const UPLOAD_SESSION_SWEEP_AFTER_MS = 8 * 24 * 60 * 60_000;
const UPLOAD_SESSION_SWEEP_LIMIT = 3;
const CONFIRMED_SESSION_RETAIN_MS = 24 * 60 * 60_000;

/**
 * Starts a direct-to-provider resumable upload for one storage key. Same
 * authority gate as the relay blob PUT; the returned session URL is the
 * provider's own resumable URI, which the client feeds bytes without any
 * SharedWorld credential.
 */
export async function createBlobUploadSession(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: CreateBlobSessionRequest
): Promise<CreateBlobSessionResponse> {
  if (!await stampAuthorized(svc, worldId, request.blobStamp, request.storageKey ?? "")) {
    await requireHostAuthority(svc, ctx, worldId, request.runtimeEpoch, request.hostToken, ["host-starting", "host-live", "host-finalizing"], new Date());
  }
  const binding = await requireWorldStorageBinding(svc, worldId);
  const capable = resumableCapable(svc.storageProvider);
  if (!capable || binding.storageAccountId == null) {
    throw new HttpError(409, "direct_upload_unsupported", "This world's storage does not support direct uploads.");
  }
  if (!request.storageKey || request.storageKey.trim().length === 0) {
    throw new HttpError(400, "invalid_storage_key", "Storage key is required.");
  }
  if (!Number.isFinite(request.contentLength) || request.contentLength <= 0) {
    throw new HttpError(400, "invalid_upload_size", "Upload size must be a positive byte count.");
  }
  const now = new Date();
  await failIfDriveFull(svc, binding);
  await sweepExpiredUploadSessions(svc, capable, binding, now);
  // No GC retry sweep here: this runs once per BLOB (a big world opens
  // hundreds of sessions per snapshot, several in parallel), so a queue that
  // filled after a retention prune used to bill its reference checks per
  // upload and add seconds to each session request. The cron drain and the
  // hourly retention slot own the queue.
  const sessionUrl = await capable.createResumableSession(
    binding,
    request.storageKey,
    request.contentType || "application/octet-stream",
    request.contentLength
  );
  const uploadId = randomId("upl");
  await svc.repository.createUploadSession({
    uploadId,
    provider: binding.provider,
    storageAccountId: binding.storageAccountId,
    worldId,
    storageKey: request.storageKey,
    sessionUrl,
    contentType: request.contentType || "application/octet-stream",
    expectedSize: request.contentLength,
    createdAt: now.toISOString(),
    confirmedAt: null
  });
  return {
    uploadId,
    sessionUrl,
    chunkSizeBytes: DIRECT_UPLOAD_CHUNK_BYTES,
    expiresAt: new Date(now.getTime() + UPLOAD_SESSION_TTL_MS).toISOString()
  };
}

/**
 * Confirms a finished direct upload. The worker never trusts the client's
 * word: it probes the provider session itself and records the provider's
 * reported file id and size. Idempotent — a lost response is safely retried.
 */
export async function commitBlobUploadSession(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: CommitBlobSessionRequest
): Promise<CommitBlobSessionResponse> {
  // Stamp scope-check runs against the session's own storage key; error
  // ordering for stampless callers is unchanged (authority before the 410).
  const session = await svc.repository.getUploadSession(request.uploadId ?? "");
  const stamped = session != null && session.worldId === worldId
    && await stampAuthorized(svc, worldId, request.blobStamp, session.storageKey);
  if (!stamped) {
    await requireHostAuthority(svc, ctx, worldId, request.runtimeEpoch, request.hostToken, ["host-starting", "host-live", "host-finalizing"], new Date());
  }
  const binding = await requireWorldStorageBinding(svc, worldId);
  const capable = resumableCapable(svc.storageProvider);
  if (!capable || binding.storageAccountId == null) {
    throw new HttpError(409, "direct_upload_unsupported", "This world's storage does not support direct uploads.");
  }
  if (!session || session.worldId !== worldId) {
    throw new HttpError(410, "upload_session_expired", "This upload session is no longer active. Start the upload again.");
  }
  if (session.confirmedAt != null) {
    const object = await svc.repository.getStorageObject(session.provider, session.storageAccountId, session.storageKey);
    return { storageKey: session.storageKey, size: object?.size ?? session.expectedSize };
  }
  const probe = await capable.probeResumableSession(binding, session.sessionUrl, session.expectedSize);
  if (probe.status === "incomplete") {
    throw new HttpError(409, "upload_incomplete", `The upload has only ${probe.receivedUpTo} of ${session.expectedSize} bytes. Finish uploading, then commit again.`);
  }
  if (probe.status === "expired") {
    await svc.repository.deleteUploadSession(session.uploadId);
    throw new HttpError(410, "upload_session_expired", "This upload session expired. Start the upload again.");
  }
  if (probe.size !== session.expectedSize) {
    await capable.deleteObjectById(binding, probe.fileId);
    await svc.repository.deleteUploadSession(session.uploadId);
    throw new HttpError(409, "upload_size_mismatch", `The stored upload is ${probe.size} bytes but ${session.expectedSize} were expected. Start the upload again.`);
  }
  await capable.registerUploadedObject(binding, session.storageKey, probe.fileId, probe.size, session.contentType);
  await svc.repository.markUploadSessionConfirmed(session.uploadId, new Date().toISOString());
  return { storageKey: session.storageKey, size: probe.size };
}

/**
 * Bounded, opportunistic reclaim of stale unconfirmed sessions for this
 * account. Never-completed resumable sessions leave no Drive file behind;
 * completed-but-unconfirmed ones do, so those get deleted unless the object
 * row already adopted the file. No cron exists — session init is the natural
 * moment because it proves the account is active.
 */
async function sweepExpiredUploadSessions(
  svc: ServiceContext,
  capable: ResumableUploadCapable,
  binding: WorldStorageBinding,
  now: Date
): Promise<void> {
  if (binding.storageAccountId == null) {
    return;
  }
  // Confirmed rows outlive their commit only to serve idempotent commit
  // retries; after a day they are pure growth (859 rows in prod before this
  // sweep existed). Plain bounded DELETE — no probes needed.
  await svc.repository.deleteConfirmedUploadSessionsBefore(
    binding.provider,
    binding.storageAccountId,
    new Date(now.getTime() - CONFIRMED_SESSION_RETAIN_MS).toISOString(),
    20
  );
  const cutoff = new Date(now.getTime() - UPLOAD_SESSION_SWEEP_AFTER_MS).toISOString();
  const stale = await svc.repository.listUnconfirmedUploadSessionsBefore(binding.provider, binding.storageAccountId, cutoff, UPLOAD_SESSION_SWEEP_LIMIT);
  for (const session of stale) {
    try {
      const probe = await capable.probeResumableSession(binding, session.sessionUrl, session.expectedSize);
      if (probe.status === "complete") {
        const object = await svc.repository.getStorageObject(session.provider, session.storageAccountId, session.storageKey);
        if (object?.objectId !== probe.fileId) {
          await capable.deleteObjectById(binding, probe.fileId);
        }
      }
    } catch (error) {
      console.warn("SharedWorld upload-session sweep probe failed", { uploadId: session.uploadId, cause: String(error) });
    }
    await svc.repository.deleteUploadSession(session.uploadId);
  }
}

/**
 * Blob bytes flow through the worker; read access is re-checked from the
 * download stamp on the signed URL when present and current (no coordinator
 * call, no membership query), else via the coordinator path with the
 * revoked-host exception.
 */
export async function downloadStorageBlob(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  storageKey: string,
  request?: Request
): Promise<Response> {
  const stamp = request?.headers.get(BLOB_STAMP_HEADER);
  const stamped = stamp != null && stamp.length > 0
    && await verifyDownloadStamp(svc.env, stamp, { worldId, storageKey, playerUuid: ctx.playerUuid }, new Date());
  if (!stamped) {
    await requireSessionAccessAllowingRevokedHost(svc, ctx, worldId);
  }
  const range = parseSingleByteRange(request?.headers.get("range"));
  const blob = await svc.storageProvider.get(await requireWorldStorageBinding(svc, worldId), storageKey, range);
  if (!blob) {
    throw new HttpError(404, "blob_not_found", "Blob not found.");
  }
  // A provider that ignored the range (test doubles, future providers) still
  // answers a correct 200 with the whole blob; clients treat 200-after-Range
  // as "restart from scratch". No ETags needed: storage keys are content
  // addressed, so the bytes behind a key can never change between attempts.
  const ranged = blob.status === 206 && blob.contentRange != null;
  const headers = new Headers({
    "content-type": blob.contentType,
    "accept-ranges": "bytes"
  });
  if (blob.size != null) {
    headers.set("content-length", String(blob.size));
  }
  if (ranged) {
    headers.set("content-range", blob.contentRange as string);
  }
  return new Response(blob.body, {
    status: ranged ? 206 : 200,
    headers
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
      retryMaxDelayMs: parsePositiveInt(svc.env.DRIVE_RETRY_MAX_DELAY_MS, 8_000),
      maxUploadBodyBytes: maxUploadBodyBytes(svc)
    };
  }

  return {
    maxParallelDownloads: 16,
    maxConcurrentUploadPreparations: 4,
    maxConcurrentUploads: 4,
    maxUploadStartsPerSecond: 8,
    retryBaseDelayMs: 250,
    retryMaxDelayMs: 4_000,
    maxUploadBodyBytes: maxUploadBodyBytes(svc)
  };
}

export function maxUploadBodyBytes(svc: ServiceContext): number {
  return parsePositiveInt(svc.env.UPLOAD_MAX_BODY_BYTES, 95_000_000);
}

type GroupedArtifactCandidateKeys = {
  fullStorageKey: string;
  deltaStorageKey: string | null;
  baseChainDepth: number;
  fullTransferMode: typeof PACK_FULL_TRANSFER_MODE | typeof REGION_FULL_TRANSFER_MODE;
  deltaFormatVersion: number | null;
};

/**
 * The storage keys a pack upload could target (full slot, plus a delta slot
 * when the chain depth allows). Computed for every pack up front so their
 * existence resolves in one batched query; null when the pack needs no upload.
 */
function groupedArtifactCandidateKeys(
  pack: LocalPackDescriptor | null,
  latestPack: SnapshotPack | null,
  maxChainDepth: number,
  fullStorageKeyForHash: (hash: string) => string,
  deltaStorageKeyForHashes: (baseHash: string, hash: string) => string,
  fullTransferMode: typeof PACK_FULL_TRANSFER_MODE | typeof REGION_FULL_TRANSFER_MODE,
  deltaTransferMode: typeof PACK_DELTA_TRANSFER_MODE | typeof REGION_DELTA_TRANSFER_MODE,
  supportsDeltaV2: boolean
): GroupedArtifactCandidateKeys | null {
  if (!pack || latestPack?.hash === pack.hash) {
    return null;
  }
  const baseChainDepth = latestPack?.transferMode === deltaTransferMode
    ? (latestPack.chainDepth ?? 0)
    : 0;
  const chainableBase = latestPack != null
    && (latestPack.transferMode === fullTransferMode || latestPack.transferMode === deltaTransferMode);
  let deltaAvailable: boolean;
  if (supportsDeltaV2) {
    // Byte-budget policy (O(1), no chain walk): keep offering deltas while
    // the chain's cumulative delta bytes stay under the budget fraction of
    // the full artifact. A NULL accumulator (legacy/v1 base) forces one full
    // upload, which restarts accounting and keeps v2 deltas off unaccounted
    // chains. Base full artifacts have accumulator 0 (set at finalize).
    const chainDeltaBytes = latestPack?.transferMode === deltaTransferMode
      ? (latestPack.chainDeltaBytes ?? null)
      : 0;
    deltaAvailable = chainableBase
      && baseChainDepth < DELTA_V2_MAX_CHAIN_DEPTH
      && chainDeltaBytes != null
      && chainDeltaBytes <= DELTA_CHAIN_BUDGET_FRACTION * latestPack.size;
  } else {
    deltaAvailable = chainableBase && baseChainDepth < maxChainDepth;
  }
  return {
    fullStorageKey: fullStorageKeyForHash(pack.hash),
    deltaStorageKey: deltaAvailable ? deltaStorageKeyForHashes((latestPack as SnapshotPack).hash, pack.hash) : null,
    baseChainDepth,
    fullTransferMode,
    deltaFormatVersion: deltaAvailable && supportsDeltaV2 ? DELTA_V2_FORMAT_VERSION : null
  };
}

async function prepareGroupedArtifactUpload(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  pack: LocalPackDescriptor | null,
  latestSnapshotId: string | null,
  latestPack: SnapshotPack | null,
  runtime: Pick<WorldRuntimeRecord, "runtimeEpoch" | "runtimeToken">,
  candidateKeys: GroupedArtifactCandidateKeys | null,
  existingStorageKeys: ReadonlySet<string>
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
  if (!candidateKeys) {
    return null;
  }

  const { fullStorageKey, deltaStorageKey, baseChainDepth, fullTransferMode, deltaFormatVersion } = candidateKeys;
  const fullExists = existingStorageKeys.has(fullStorageKey);
  const deltaExists = deltaStorageKey != null && existingStorageKeys.has(deltaStorageKey);

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
    baseChainDepth,
    deltaFormatVersion
  };
}

async function buildPackDownloadSteps(
  svc: ServiceContext,
  worldId: string,
  latestPack: SnapshotPack,
  chainSteps: SnapshotPack["chainSteps"],
  localPackHash: string | null,
  viewer: DownloadViewer,
  snapshotCache: Map<string, SnapshotManifest>,
  deltaTransferMode: typeof PACK_DELTA_TRANSFER_MODE | typeof REGION_DELTA_TRANSFER_MODE,
  supportsDeltaV2: boolean
): Promise<DownloadPlanStep[]> {
  if (chainSteps != null && chainSteps.length > 0) {
    // S1 self-contained chains: the plan builds from the pack's own recipe —
    // no base snapshot rows, no chain walk, no snapshot_chain_broken class.
    return buildStepsFromChainRecipe(svc, worldId, chainSteps, localPackHash, viewer, supportsDeltaV2);
  }
  const steps: DownloadPlanStep[] = [];
  let cursor: SnapshotPack | null = latestPack;
  while (cursor) {
    if (localPackHash != null && localPackHash === cursor.hash) {
      break;
    }
    if ((cursor.deltaFormatVersion ?? null) != null && !supportsDeltaV2) {
      // No full-root fallback exists: the latest full blob was never uploaded
      // once a delta chain started, and serving the retained chain root alone
      // would reconstruct STALE content whose hash cannot match the manifest.
      // An explicit refusal is the only honest answer for a pre-v2 client.
      throw new HttpError(
        409,
        "client_update_required",
        "This world was uploaded by a newer SharedWorld version. Update the SharedWorld mod to download it."
      );
    }
    steps.push({
      transferMode: cursor.transferMode,
      storageKey: cursor.storageKey,
      artifactSize: cursor.size,
      baseSnapshotId: cursor.baseSnapshotId ?? null,
      baseHash: cursor.baseHash ?? null,
      deltaFormatVersion: cursor.deltaFormatVersion ?? null,
      download: await signDownloadForWorld(svc, worldId, cursor.storageKey, viewer)
    });
    if (cursor.transferMode !== deltaTransferMode || !cursor.baseSnapshotId) {
      break;
    }
    if (localPackHash != null && cursor.baseHash != null && localPackHash === cursor.baseHash) {
      break;
    }
    const base = await loadSnapshotPack(svc, worldId, cursor.baseSnapshotId, cursor.packId, snapshotCache);
    if (base == null) {
      // The chain needs a base artifact whose snapshot row no longer exists. A
      // truncated plan would fail client-side mid-apply with a confusing
      // missing-delta-base error; refuse loudly instead.
      throw new HttpError(
        409,
        "snapshot_chain_broken",
        `SharedWorld backup data for '${latestPack.packId}' is missing a delta base artifact.`
      );
    }
    cursor = base;
  }
  return steps.reverse();
}

/**
 * Mirror of the legacy walk, driven by the stamped recipe: newest step
 * backwards until the client's local hash matches an intermediate chain
 * state or the anchor full is reached, then served oldest-first. Step
 * baseSnapshotId is null on purpose — the recipe is snapshot-independent
 * and no shipped client reads that field from download steps.
 */
async function buildStepsFromChainRecipe(
  svc: ServiceContext,
  worldId: string,
  chainSteps: NonNullable<SnapshotPack["chainSteps"]>,
  localPackHash: string | null,
  viewer: DownloadViewer,
  supportsDeltaV2: boolean
): Promise<DownloadPlanStep[]> {
  const steps: DownloadPlanStep[] = [];
  for (let index = chainSteps.length - 1; index >= 0; index -= 1) {
    const step = chainSteps[index];
    if (localPackHash != null && localPackHash === step.hash) {
      break;
    }
    if (step.deltaFormatVersion != null && !supportsDeltaV2) {
      throw new HttpError(
        409,
        "client_update_required",
        "This world was uploaded by a newer SharedWorld version. Update the SharedWorld mod to download it."
      );
    }
    steps.push({
      transferMode: step.transferMode,
      storageKey: step.storageKey,
      artifactSize: step.size,
      baseSnapshotId: null,
      baseHash: step.baseHash,
      deltaFormatVersion: step.deltaFormatVersion,
      download: await signDownloadForWorld(svc, worldId, step.storageKey, viewer)
    });
    if (step.baseHash == null) {
      break;
    }
    if (localPackHash != null && localPackHash === step.baseHash) {
      break;
    }
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

/**
 * Terminal preflight for a full Drive. Uses the 15-min cached quota — the
 * check only fires when the account is genuinely at capacity, and clears
 * within one cache TTL of the user freeing space. Unlinked worlds and
 * unknown quotas pass (a missing check must not block uploads).
 */
async function failIfDriveFull(svc: ServiceContext, binding: WorldStorageBinding): Promise<void> {
  if (binding.provider !== "google-drive" || binding.storageAccountId == null) {
    return;
  }
  try {
    const quota = await cachedQuota(svc, binding);
    if (quota.usedBytes != null && quota.totalBytes != null && quota.totalBytes > 0 && quota.usedBytes >= quota.totalBytes) {
      throw driveStorageFullError();
    }
  } catch (error) {
    if (error instanceof HttpError && error.code === "drive_storage_full") {
      throw error;
    }
    // Quota lookups are best-effort; an unreachable /about must not block
    // uploads (the classified 403 still catches a truly full Drive).
    console.warn("SharedWorld quota preflight failed", { cause: String(error) });
  }
}

export function parsePositiveInt(value: string | undefined, fallback: number): number {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
