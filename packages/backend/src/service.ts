import {
  type AbandonFinalizationRequest,
  type BeginFinalizationRequest,
  INVITE_TTL_MS,
  MAX_PACK_DELTA_CHAIN_DEPTH,
  MAX_REGION_DELTA_CHAIN_DEPTH,
  NON_REGION_PACK_ID,
  PACK_DELTA_TRANSFER_MODE,
  PACK_FULL_TRANSFER_MODE,
  PLAYER_PRESENCE_TIMEOUT_MS,
  REGION_DELTA_TRANSFER_MODE,
  REGION_FULL_TRANSFER_MODE,
  STORAGE_LINK_TTL_MS,
  WHOLE_GZIP_TRANSFER_MODE,
  isRegionBundleId,
  storageKeyForPackDelta,
  storageKeyForPackFull,
  storageKeyForRegionBundleDelta,
  storageKeyForRegionBundleFull,
  type AuthCompleteRequest,
  type DevAuthCompleteRequest,
  type CancelWaitingRequest,
  type CompleteFinalizationRequest,
  type CreateStorageLinkRequest,
  type CreateWorldResult,
  type EnterSessionRequest,
  type EnterSessionResponse,
  type ObserveWaitingRequest,
  type ObserveWaitingResponse,
  type DownloadPlanEntry,
  type DownloadPackPlan,
  type DownloadPlanStep,
  type CreateWorldRequest,
  type DownloadPlan,
  type FileTransferMode,
  type FinalizationActionResult,
  type FinalizeSnapshotRequest,
  type HeartbeatRequest,
  type HostStartupProgressRequest,
  type InviteCode,
  type KickMemberResponse,
  type LocalPackDescriptor,
  type ManifestFile,
  type SnapshotPack,
  type PresenceHeartbeatRequest,
  type RedeemInviteRequest,
  type ReleaseHostRequest,
  type RefreshWaitingRequest,
  type ResetInviteResponse,
  type SnapshotActionResult,
  type SnapshotManifest,
  type StorageLinkCompleteRequest,
  type SyncPolicy,
  type StorageUsageSummary,
  type UpdateWorldRequest,
  type UploadPlanEntry,
  type UploadPlanRequest,
  type WorldDetails,
  type WorldRuntimeStatus,
  type WorldSnapshotSummary,
  type WorldSummary
} from "../../shared/src/index.ts";

import { AuthDomainService } from "./auth/service.ts";
import { HttpError } from "./http.ts";
import { randomId, inviteCode as generateInviteCode, slugify } from "./ids.ts";
import type { Env } from "./env.ts";
import type {
  RequestContext,
  SharedWorldRepository,
  WorldStorageBinding,
  WorldUpdateRecord
} from "./repository.ts";
import {
  assignHostStarting,
  choosePreferredCandidate,
  matchesHostAuthorization,
  moveToFinalizing,
  refreshLiveRuntime,
  runtimePhaseToWorldStatus,
  resolveRuntimeTimeout,
  setHostProgress,
  timedOutUncleanShutdownWarning,
  toRuntimeStatus,
  type RuntimeCandidate,
  type WorldRuntimeRecord
} from "./runtime-protocol.ts";
import {
  type AuthorizedRuntime,
  type ResolvedRuntimeState,
  hostAssignmentForCurrentRuntime,
  runtimeAllowsDirectConnect,
  runtimeRequiresWaiting
} from "./runtime-service-support.ts";
import type { StorageBinding, StorageProvider } from "./storage.ts";
import { StorageLinkDomainService } from "./storage/link-service.ts";

export interface AuthVerifier {
  verifyJoin(playerName: string, serverId: string): Promise<{ playerUuid: string; playerName: string } | null>;
}

export interface BlobUrlSigner {
  signUpload(worldId: string, storageKey: string, requestOrigin?: string): Promise<{ method: "PUT"; url: string; headers: Record<string, string>; expiresAt: string }>;
  signDownload(worldId: string, storageKey: string, requestOrigin?: string): Promise<{ method: "GET"; url: string; headers: Record<string, string>; expiresAt: string }>;
  deleteBlob?(storageKey: string): Promise<void>;
}

const SNAPSHOT_RETENTION_ALL_RECENT_MS = 24 * 60 * 60_000;
const SNAPSHOT_RETENTION_DAILY_MS = 30 * 24 * 60 * 60_000;
const RUNTIME_EPOCH_HEADER = "x-sharedworld-runtime-epoch";
const HOST_TOKEN_HEADER = "x-sharedworld-host-token";

export class SharedWorldService {
  private readonly storageProvider: StorageProvider;
  private readonly env: Env;
  private readonly authDomain: AuthDomainService;
  private readonly storageLinks: StorageLinkDomainService;

  constructor(
    private readonly repository: SharedWorldRepository,
    private readonly authVerifier: AuthVerifier,
    private readonly blobSigner: BlobUrlSigner,
    storageProviderOrEnv: StorageProvider | Env,
    maybeEnv?: Env
  ) {
    if (maybeEnv) {
      this.storageProvider = storageProviderOrEnv as StorageProvider;
      this.env = maybeEnv;
    } else {
      this.env = storageProviderOrEnv as Env;
      this.storageProvider = {
        provider: "r2",
        exists: async (_binding, storageKey) => (await this.env.BLOBS?.head(storageKey)) != null,
        put: async (_binding, storageKey, body, contentType) => {
          await this.env.BLOBS?.put(storageKey, body instanceof Uint8Array ? body : body, {
            httpMetadata: { contentType }
          });
        },
        get: async (_binding, storageKey) => {
          const object = await this.env.BLOBS?.get(storageKey);
          if (!object) {
            return null;
          }
          return {
            body: object.body,
            contentType: object.httpMetadata?.contentType ?? "application/octet-stream",
            size: object.size,
            arrayBuffer: () => object.arrayBuffer()
          };
        },
        delete: async (_binding, storageKey) => {
          await this.env.BLOBS?.delete(storageKey);
        },
        quota: async () => ({ usedBytes: null, totalBytes: null })
      };
    }
    this.authDomain = new AuthDomainService(this.repository, this.authVerifier, this.env);
    this.storageLinks = new StorageLinkDomainService(this.repository, this.env, this.storageProvider.provider);
  }

  async createChallenge(now = new Date()) {
    return this.authDomain.createChallenge(now);
  }

  async completeAuth(request: AuthCompleteRequest, now = new Date()) {
    return this.authDomain.completeAuth(request, now);
  }

  async completeDevAuth(request: DevAuthCompleteRequest, now = new Date()) {
    return this.authDomain.completeDevAuth(request, now);
  }

  async listWorlds(ctx: RequestContext): Promise<WorldSummary[]> {
    const worlds = await this.repository.listWorldsForPlayer(ctx.playerUuid);
    return Promise.all(worlds.map((world) => this.hydrateWorldSummary(world, ctx.requestOrigin)));
  }

  async getSession(token: string) {
    return this.authDomain.getSession(token);
  }

  async createStorageLink(ctx: RequestContext, request: CreateStorageLinkRequest, now = new Date()) {
    return this.storageLinks.createStorageLink(ctx, request, now);
  }

  async getStorageLinkSession(ctx: RequestContext, sessionId: string, now = new Date()) {
    return this.storageLinks.getStorageLinkSession(ctx, sessionId, now);
  }

  async completeStorageLink(sessionId: string, request: StorageLinkCompleteRequest, now = new Date()) {
    return this.storageLinks.completeStorageLink(sessionId, request, now);
  }

  async createWorld(ctx: RequestContext, request: CreateWorldRequest, now = new Date()): Promise<CreateWorldResult> {
    const name = request.name.trim();
    if (name.length < 3) {
      throw new HttpError(400, "invalid_world_name", "World name must be at least 3 characters.");
    }
    if (request.storageLinkSessionId && (request.importSource?.type !== "local-save" || !request.importSource.id.trim())) {
      throw new HttpError(400, "invalid_import_source", "A local save import source is required.");
    }
    const link = request.storageLinkSessionId
      ? await this.storageLinks.requireCompletedLinkSession(ctx, request.storageLinkSessionId)
      : {
        id: "legacy",
        playerUuid: ctx.playerUuid,
        provider: this.storageProvider.provider,
        status: "linked",
        authUrl: "",
        expiresAt: new Date(now.getTime() + STORAGE_LINK_TTL_MS).toISOString(),
        linkedAccountEmail: null,
        accountDisplayName: null,
        errorMessage: null,
        storageAccountId: null,
        state: "",
        createdAt: now.toISOString(),
        completedAt: now.toISOString()
      };
    const motd = normalizeMotd(request.motdLine1 ?? null, request.motdLine2 ?? null);
    const world = await this.repository.createWorld(
      ctx,
      name,
      slugify(name),
      {
        provider: link.provider,
        storageAccountId: link.storageAccountId
      },
      motd,
      null
    );
    if (request.customIconPngBase64) {
      const customIconStorageKey = await this.storeCustomIcon(
        { provider: world.storageProvider, storageAccountId: link.storageAccountId },
        request.customIconPngBase64
      );
      const updated = await this.repository.updateWorld(ctx, world.id, {
        name: world.name,
        motdLine1: splitMotd(world.motd)[0],
        motdLine2: splitMotd(world.motd)[1],
        customIconStorageKey,
        customIconPngBase64: null,
        clearCustomIcon: false
      });
      return this.createSeededWorldResult(ctx, updated, now);
    }
    return this.createSeededWorldResult(ctx, world, now);
  }

  async getWorld(ctx: RequestContext, worldId: string, now = new Date()): Promise<WorldDetails> {
    const world = await this.repository.getWorldDetails(worldId, ctx.playerUuid);
    if (!world) {
      throw new HttpError(404, "world_not_found", "SharedWorld server not found.");
    }
    const hydrated = await this.hydrateWorldDetails(world, ctx.requestOrigin);
    hydrated.storageUsage = await this.getStorageUsage(ctx, worldId);
    hydrated.activeInviteCode = world.ownerUuid === ctx.playerUuid
      ? await this.repository.getActiveInvite(worldId, now)
      : null;
    return hydrated;
  }

  async updateWorld(ctx: RequestContext, worldId: string, request: UpdateWorldRequest): Promise<WorldDetails> {
    const name = request.name.trim();
    if (name.length < 3) {
      throw new HttpError(400, "invalid_world_name", "World name must be at least 3 characters.");
    }

    const world = await this.getWorld(ctx, worldId);
    if (world.ownerUuid !== ctx.playerUuid) {
      throw new HttpError(403, "forbidden", "Only the SharedWorld owner can edit this world.");
    }

    const motd = normalizeMotd(request.motdLine1 ?? null, request.motdLine2 ?? null);
    const binding = await this.requireWorldStorageBinding(worldId);
    let customIconStorageKey = world.customIconStorageKey;
    if (request.clearCustomIcon) {
      customIconStorageKey = null;
    } else if (request.customIconPngBase64) {
      customIconStorageKey = request.customIconPngBase64
        ? await this.storeCustomIcon(binding, request.customIconPngBase64)
        : customIconStorageKey;
    }
    const updated = await this.repository.updateWorld(ctx, worldId, {
      name,
      motdLine1: splitMotd(motd)[0],
      motdLine2: splitMotd(motd)[1],
      customIconStorageKey,
      customIconPngBase64: null,
      clearCustomIcon: Boolean(request.clearCustomIcon)
    } satisfies WorldUpdateRecord);
    await this.maybeDeleteUnreferencedBlob(
      binding,
      world.customIconStorageKey !== updated.customIconStorageKey ? world.customIconStorageKey : null
    );
    return this.hydrateWorldDetails(updated, ctx.requestOrigin);
  }

  async deleteWorld(ctx: RequestContext, worldId: string, now = new Date()): Promise<void> {
    await this.getWorld(ctx, worldId);
    const binding = await this.requireWorldStorageBinding(worldId);
    const result = await this.repository.deleteWorldForPlayer(ctx, worldId, now);
    if (result.worldDeleted) {
      await this.purgeWorldSnapshots(binding, worldId);
      await this.maybeDeleteUnreferencedBlob(binding, result.deletedCustomIconStorageKey);
    }
  }

  async createInvite(ctx: RequestContext, worldId: string, now = new Date()): Promise<InviteCode> {
    const world = await this.getWorld(ctx, worldId, now);
    if (world.ownerUuid !== ctx.playerUuid) {
      throw new HttpError(403, "forbidden", "Only the SharedWorld owner can manage invite codes.");
    }
    const activeInvite = await this.repository.getActiveInvite(worldId, now);
    if (activeInvite) {
      return activeInvite;
    }
    const invite: InviteCode = {
      id: randomId("invite"),
      worldId,
      code: generateInviteCode(),
      createdByUuid: ctx.playerUuid,
      createdAt: now.toISOString(),
      expiresAt: new Date(now.getTime() + INVITE_TTL_MS).toISOString(),
      status: "active"
    };
    return this.repository.createInvite(worldId, ctx, invite);
  }

  async redeemInvite(ctx: RequestContext, request: RedeemInviteRequest, now = new Date()): Promise<WorldDetails> {
    const code = request.code.trim().toUpperCase();
    const invite = await this.repository.getInviteByCode(code);
    if (!invite) {
      throw new HttpError(404, "invite_not_found", "Invite code not found.");
    }
    if (invite.status !== "active") {
      throw new HttpError(409, "invite_inactive", "Invite code is no longer active.");
    }
    if (new Date(invite.expiresAt).getTime() < now.getTime()) {
      throw new HttpError(410, "invite_expired", "Invite code has expired.");
    }

    await this.repository.addMembership({
      worldId: invite.worldId,
      playerUuid: ctx.playerUuid,
      playerName: ctx.playerName,
      role: "member",
      joinedAt: now.toISOString(),
      deletedAt: null
    });

    return this.getWorld(ctx, invite.worldId, now);
  }

  async resetInvite(ctx: RequestContext, worldId: string, now = new Date()): Promise<ResetInviteResponse> {
    const world = await this.getWorld(ctx, worldId, now);
    if (world.ownerUuid !== ctx.playerUuid) {
      throw new HttpError(403, "forbidden", "Only the SharedWorld owner can reset invite codes.");
    }
    const revokedInviteIds = await this.repository.revokeActiveInvites(worldId, now.toISOString());
    const invite = await this.createInvite(ctx, worldId, now);
    return {
      revokedInviteIds,
      invite
    };
  }

  async kickMember(ctx: RequestContext, worldId: string, removedPlayerUuid: string, now = new Date()): Promise<KickMemberResponse> {
    const world = await this.getWorld(ctx, worldId, now);
    if (world.ownerUuid !== ctx.playerUuid) {
      throw new HttpError(403, "forbidden", "Only the SharedWorld owner can remove members.");
    }
    if (removedPlayerUuid === world.ownerUuid) {
      throw new HttpError(400, "cannot_remove_owner", "The SharedWorld owner cannot be removed.");
    }
    const result = await this.repository.kickMember(worldId, removedPlayerUuid, now.toISOString());
    if (!result) {
      throw new HttpError(404, "member_not_found", "SharedWorld member not found.");
    }
    return result;
  }

  async listSnapshots(ctx: RequestContext, worldId: string): Promise<WorldSnapshotSummary[]> {
    await this.requireMembership(ctx, worldId);
    return this.repository.listSnapshotSummaries(worldId);
  }

  async restoreSnapshot(ctx: RequestContext, worldId: string, snapshotId: string, now = new Date()): Promise<SnapshotActionResult> {
    const world = await this.getWorld(ctx, worldId);
    if (world.ownerUuid !== ctx.playerUuid) {
      throw new HttpError(403, "forbidden", "Only the SharedWorld owner can restore backups.");
    }
    const snapshot = await this.repository.getSnapshot(worldId, snapshotId);
    if (!snapshot) {
      throw new HttpError(404, "snapshot_not_found", "SharedWorld backup not found.");
    }
    await this.repository.finalizeSnapshot(worldId, ctx, {
      baseSnapshotId: snapshot.snapshotId,
      files: snapshot.files,
      packs: snapshot.packs
    }, now);
    await this.applySnapshotRetention(worldId, now);
    return {
      worldId,
      snapshotId
    };
  }

  async deleteSnapshot(ctx: RequestContext, worldId: string, snapshotId: string): Promise<SnapshotActionResult> {
    const world = await this.getWorld(ctx, worldId);
    if (world.ownerUuid !== ctx.playerUuid) {
      throw new HttpError(403, "forbidden", "Only the SharedWorld owner can delete backups.");
    }
    const binding = await this.requireWorldStorageBinding(worldId);
    const snapshot = await this.repository.getSnapshot(worldId, snapshotId);
    if (!snapshot) {
      throw new HttpError(404, "snapshot_not_found", "SharedWorld backup not found.");
    }
    if (world.lastSnapshotId === snapshotId) {
      throw new HttpError(409, "cannot_delete_latest_snapshot", "The latest backup cannot be deleted.");
    }
    const deletion = await this.repository.deleteSnapshots(worldId, [snapshotId]);
    await this.deleteUnreferencedBlobs(binding, deletion.unreferencedStorageKeys);
    return {
      worldId,
      snapshotId
    };
  }

  async getStorageUsage(ctx: RequestContext, worldId: string): Promise<StorageUsageSummary> {
    await this.requireMembership(ctx, worldId);
    const usage = await this.repository.getStorageUsage(worldId);
    const binding = await this.requireWorldStorageBinding(worldId);
    const quota = await this.storageProvider.quota(binding);
    return {
      ...usage,
      quotaUsedBytes: quota.usedBytes,
      quotaTotalBytes: quota.totalBytes
    };
  }

  /**
   * Responsibility:
   * Refresh the currently authorized host runtime while preserving epoch/token authority.
   *
   * Preconditions:
   * The caller still owns the current host-starting or host-live runtime.
   *
   * Postconditions:
   * The authoritative runtime deadline is extended, and host-starting may become host-live.
   *
   * Stale-work rule:
   * Old epochs/tokens are rejected through requireAuthorizedRuntime.
   *
   * Authority source:
   * The resolved backend runtime record.
   */
  async heartbeatHost(ctx: RequestContext, worldId: string, request: HeartbeatRequest, now = new Date()) {
    await this.requireSessionAccess(ctx, worldId);
    const authorized = await this.requireAuthorizedRuntime(
      ctx,
      worldId,
      now,
      request.runtimeEpoch,
      request.hostToken,
      ["host-starting", "host-live"]
    );
    const updated = refreshLiveRuntime(authorized.runtime, request.joinTarget ?? null, now);
    await this.repository.upsertRuntimeRecord(updated);
    return toRuntimeStatus(
      worldId,
      updated,
      this.runtimeCandidateFromRuntime(updated)
    );
  }

  /**
   * Responsibility:
   * Publish host-controlled startup/finalization progress for the current authoritative runtime.
   *
   * Preconditions:
   * The caller is the current host and supplies the current runtime epoch/token.
   *
   * Postconditions:
   * The runtime carries the latest visible progress payload, or null when progress is cleared.
   *
   * Stale-work rule:
   * Old epochs/tokens fail closed when authority moved on.
   *
   * Authority source:
   * The resolved backend runtime record.
   */
  async setHostStartupProgress(
    ctx: RequestContext,
    worldId: string,
    request: HostStartupProgressRequest,
    now = new Date()
  ): Promise<WorldRuntimeStatus | null> {
    await this.requireSessionAccess(ctx, worldId, { allowRevokedHost: true });
    const authorized = await this.resolveProgressRuntime(ctx, worldId, request, now);
    const progress = request.label != null && request.mode != null
      ? {
          label: request.label,
          mode: request.mode,
          fraction: clampFraction(request.fraction ?? null),
          updatedAt: now.toISOString()
        }
      : null;
    const updated = setHostProgress(authorized.runtime, progress, now);
    await this.repository.upsertRuntimeRecord(updated);
    return toRuntimeStatus(
      worldId,
      updated,
      this.runtimeCandidateFromRuntime(updated)
    );
  }

  async setPlayerPresence(ctx: RequestContext, worldId: string, request: PresenceHeartbeatRequest, now = new Date()) {
    await this.requireSessionAccess(ctx, worldId);
    await this.repository.setPlayerPresence(worldId, ctx, request, now);
    return {
      worldId,
      present: request.present,
      updatedAt: now.toISOString(),
      expiresAt: new Date(now.getTime() + PLAYER_PRESENCE_TIMEOUT_MS).toISOString()
    };
  }

  /**
   * Responsibility:
   * Freeze the authoritative host runtime into host-finalizing before the final snapshot upload.
   *
   * Preconditions:
   * The caller still owns the current host-starting or host-live runtime.
   *
   * Postconditions:
   * The runtime moves to host-finalizing and stops exposing a join target.
   *
   * Stale-work rule:
   * Old epochs/tokens cannot begin finalization once authority moved on.
   *
   * Authority source:
   * The resolved backend runtime record.
   */
  async beginFinalization(ctx: RequestContext, worldId: string, request: BeginFinalizationRequest, now = new Date()) {
    await this.requireSessionAccess(ctx, worldId, { allowRevokedHost: true });
    const authorized = await this.requireAuthorizedRuntime(
      ctx,
      worldId,
      now,
      request.runtimeEpoch,
      request.hostToken,
      ["host-starting", "host-live"]
    );
    const updated = moveToFinalizing(authorized.runtime, now);
    await this.repository.upsertRuntimeRecord(updated);
    return this.runtimeToFinalizationResult(worldId, updated, null, now);
  }

  /**
   * Responsibility:
   * Complete a host-finalizing runtime by handing off to the next candidate or returning to idle.
   *
   * Preconditions:
   * The runtime is currently host-finalizing and the caller still owns that runtime.
   *
   * Postconditions:
   * The world is either idle or waiting on the next elected host candidate.
   *
   * Stale-work rule:
   * A completion request from an older epoch/token is rejected even if the caller was host before.
   *
   * Authority source:
   * The resolved backend runtime record plus the current preferred waiter candidate.
   */
  async completeFinalization(ctx: RequestContext, worldId: string, request: CompleteFinalizationRequest, now = new Date()) {
    await this.requireSessionAccess(ctx, worldId, { allowRevokedHost: true });
    const resolved = await this.resolveRuntimeState(worldId, now);
    const runtime = this.requireCompletableFinalizationRuntime(ctx, request, resolved);
    if (runtime == null) {
      throw new HttpError(409, "not_finalizing", "SharedWorld is not currently finalizing.");
    }
    await this.repository.deleteRuntimeRecord(worldId);
    await this.repository.clearWorldPresence(worldId);
    await this.repository.clearUncleanShutdownWarning(worldId);
    return this.runtimeToFinalizationResult(worldId, null, resolved.candidate, now);
  }

  async abandonFinalization(ctx: RequestContext, worldId: string, request: AbandonFinalizationRequest, now = new Date()) {
    const world = await this.getWorld(ctx, worldId);
    if (world.ownerUuid !== ctx.playerUuid) {
      throw new HttpError(403, "forbidden", "Only the SharedWorld owner can discard stranded finalization state.");
    }
    const resolved = await this.resolveRuntimeState(worldId, now);
    const current = resolved.runtime;
    if (current == null || current.phase !== "host-finalizing") {
      return this.runtimeToFinalizationResult(worldId, current, resolved.candidate, now);
    }
    await this.repository.deleteRuntimeRecord(worldId);
    await this.repository.clearWorldPresence(worldId);
    return this.runtimeToFinalizationResult(worldId, null, resolved.candidate, now);
  }

  async releaseHost(ctx: RequestContext, worldId: string, request: ReleaseHostRequest, now = new Date()) {
    await this.requireSessionAccess(ctx, worldId, { allowRevokedHost: true });
    await this.requireAuthorizedRuntime(
      ctx,
      worldId,
      now,
      request.runtimeEpoch,
      request.hostToken,
      ["host-starting", "host-live", "host-finalizing"]
    );
    await this.repository.clearWaitersForPlayer(worldId, ctx.playerUuid);
    await this.repository.deleteRuntimeRecord(worldId);
    await this.repository.clearWorldPresence(worldId);
    if (request.graceful) {
      await this.repository.clearUncleanShutdownWarning(worldId);
    }
    const resolvedStatus = await this.resolveRuntimeState(worldId, now);
    const status = toRuntimeStatus(worldId, resolvedStatus.runtime, resolvedStatus.candidate, resolvedStatus.warning);
    return {
      worldId,
      releasedAt: now.toISOString(),
      graceful: request.graceful,
      // Ungraceful release relinquishes authority immediately, but it does not erase other
      // waiters. They may still be elected by the normal backend-owned waiting flow.
      nextHostUuid: request.graceful ? status.candidateUuid : null,
      nextHostPlayerName: request.graceful ? status.candidatePlayerName : null
    };
  }

  async latestManifest(ctx: RequestContext, worldId: string): Promise<SnapshotManifest | null> {
    await this.requireSessionAccess(ctx, worldId);
    return this.repository.getLatestSnapshot(worldId);
  }

  async prepareUploads(ctx: RequestContext, worldId: string, request: UploadPlanRequest) {
    await this.requireSessionAccess(ctx, worldId, { allowRevokedHost: true });
    const authorizedRuntime = await this.requireAuthorizedRuntime(
      ctx,
      worldId,
      new Date(),
      request.runtimeEpoch,
      request.hostToken,
      ["host-starting", "host-live", "host-finalizing"]
    );
    const latest = await this.repository.getLatestSnapshot(worldId);
    const latestPack = latest?.packs.find((pack) => pack.packId === NON_REGION_PACK_ID) ?? null;
    const latestRegionBundles = latest?.packs.filter((pack) => isRegionBundleId(pack.packId)) ?? [];
    const latestRegionBundleById = new Map(latestRegionBundles.map((pack) => [pack.packId, pack]));
    const binding = await this.requireWorldStorageBinding(worldId);
    const uploads: UploadPlanEntry[] = [];
    const regionBundleUploads = [];
    for (const bundle of request.regionBundles ?? []) {
      regionBundleUploads.push(await this.prepareGroupedArtifactUpload(
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
      ));
    }
    return {
      worldId,
      snapshotBaseId: latest?.snapshotId ?? null,
      uploads,
      nonRegionPackUpload: await this.prepareGroupedArtifactUpload(
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
      syncPolicy: this.syncPolicyForProvider()
    };
  }

  async finalizeSnapshot(ctx: RequestContext, worldId: string, request: FinalizeSnapshotRequest, now = new Date()) {
    await this.requireSessionAccess(ctx, worldId, { allowRevokedHost: true });
    await this.requireAuthorizedRuntime(
      ctx,
      worldId,
      now,
      request.runtimeEpoch,
      request.hostToken,
      ["host-starting", "host-live", "host-finalizing"]
    );
    await this.validateFinalizeSnapshotRequest(worldId, request);
    const manifest = await this.repository.finalizeSnapshot(worldId, ctx, request, now);
    await this.applySnapshotRetention(worldId, now);
    return manifest;
  }

  async downloadPlan(ctx: RequestContext, worldId: string, requestOrFiles: UploadPlanRequest | UploadPlanRequest["files"]): Promise<DownloadPlan> {
    await this.requireMembership(ctx, worldId);
    const request: UploadPlanRequest = Array.isArray(requestOrFiles)
      ? { files: requestOrFiles, nonRegionPack: null }
      : requestOrFiles;
    const latest = await this.repository.getLatestSnapshot(worldId);
    if (!latest) {
      return {
        worldId,
        snapshotId: null,
        downloads: [],
        nonRegionPackDownload: null,
        regionBundleDownloads: [],
        retainedPaths: request.files.map((file) => file.path),
        syncPolicy: this.syncPolicyForProvider()
      };
    }

    const localByPath = new Map(request.files.map((file) => [file.path, file]));
    const retainedPaths: string[] = [];
    const downloads: DownloadPlanEntry[] = [];
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
          steps: await this.buildPackDownloadSteps(
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
          steps: await this.buildPackDownloadSteps(
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
      downloads,
      nonRegionPackDownload,
      regionBundleDownloads,
      retainedPaths,
      syncPolicy: this.syncPolicyForProvider()
    };
  }

  async uploadStorageBlob(ctx: RequestContext, worldId: string, storageKey: string, request: Request): Promise<void> {
    await this.requireSessionAccess(ctx, worldId, { allowRevokedHost: true });
    const uploadAuthorization = this.runtimeAuthorizationFromHeaders(request);
    await this.requireAuthorizedRuntime(
      ctx,
      worldId,
      new Date(),
      uploadAuthorization.runtimeEpoch,
      uploadAuthorization.hostToken,
      ["host-starting", "host-live", "host-finalizing"]
    );
    const contentType = request.headers.get("content-type") ?? "application/octet-stream";
    await this.storageProvider.put(await this.requireWorldStorageBinding(worldId), storageKey, request.body ?? "", contentType);
  }

  async downloadStorageBlob(ctx: RequestContext, worldId: string, storageKey: string): Promise<Response> {
    await this.requireSessionAccess(ctx, worldId, { allowRevokedHost: true });
    const blob = await this.storageProvider.get(await this.requireWorldStorageBinding(worldId), storageKey);
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
   * Responsibility:
   * Resolve a player's authoritative session entry outcome: connect, wait, or host assignment.
   *
   * Preconditions:
   * The caller has session access and has opted into waiting for this world.
   *
   * Postconditions:
   * Exactly one entry action is returned, based on the resolved runtime and waiter candidate.
   *
   * Stale-work rule:
   * The backend never trusts client-side host eligibility; it derives the answer from the
   * current runtime record and waiter set each time.
   *
   * Authority source:
   * The resolved backend runtime record plus the preferred waiter candidate.
   */
  async enterSession(
    ctx: RequestContext,
    worldId: string,
    requestOrNow: EnterSessionRequest | Date = {},
    nowArg?: Date
  ): Promise<EnterSessionResponse> {
    const request = requestOrNow instanceof Date ? {} : requestOrNow;
    const now = requestOrNow instanceof Date ? requestOrNow : nowArg ?? new Date();
    await this.requireSessionAccess(ctx, worldId);
    const world = await this.getWorld(ctx, worldId);
    const latestManifest = await this.repository.getLatestSnapshot(worldId);
    const requestedWaiterSessionId = sanitizeWaiterSessionId(request.waiterSessionId);
    const resolved = await this.resolveRuntimeState(worldId, now);
    if (runtimeAllowsDirectConnect(resolved)) {
      if (requestedWaiterSessionId != null) {
        await this.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId: requestedWaiterSessionId });
      }
      return {
        action: "connect",
        world,
        latestManifest,
        runtime: toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning),
        assignment: null,
        waiterSessionId: null
      };
    }
    const currentAssignment = hostAssignmentForCurrentRuntime(resolved, ctx.playerUuid);
    if (currentAssignment != null) {
      if (requestedWaiterSessionId != null) {
        await this.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId: requestedWaiterSessionId });
      }
      return {
        action: "host",
        world,
        latestManifest,
        runtime: toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning),
        assignment: currentAssignment,
        waiterSessionId: null
      };
    }
    if (resolved.runtime == null && resolved.candidate == null) {
      if (resolved.warning != null && !request.acknowledgeUncleanShutdown) {
        return {
          action: "warn-host",
          world,
          latestManifest,
          runtime: toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning),
          assignment: null,
          waiterSessionId: null
        };
      }
      const assigned = assignHostStarting(
        worldId,
        { playerUuid: ctx.playerUuid, playerName: ctx.playerName },
        this.runtimeEpochBaseline(resolved),
        now,
        () => randomId("rt")
      );
      if (requestedWaiterSessionId != null) {
        await this.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId: requestedWaiterSessionId });
      }
      await this.repository.upsertRuntimeRecord(assigned.runtime);
      return {
        action: "host",
        world,
        latestManifest,
        runtime: toRuntimeStatus(worldId, assigned.runtime, resolved.candidate, resolved.warning),
        assignment: assigned.assignment,
        waiterSessionId: null
      };
    }
    const waiterSessionId = requestedWaiterSessionId ?? randomId("wait");
    const waiterSessionActive = requestedWaiterSessionId != null
      ? await this.repository.refreshWaiterSession(worldId, ctx, { waiterSessionId }, now)
      : (await this.repository.upsertWaiterSession(worldId, ctx, waiterSessionId, now), true);
    const waitingResolved = await this.resolveRuntimeState(worldId, now);
    if (runtimeRequiresWaiting(waitingResolved)) {
      return {
        action: "wait",
        world,
        latestManifest,
        runtime: toRuntimeStatus(worldId, waitingResolved.runtime, waitingResolved.candidate, waitingResolved.warning),
        assignment: null,
        waiterSessionId: waiterSessionActive ? waiterSessionId : null
      };
    }
    if (waitingResolved.runtime == null && waiterSessionActive && waitingResolved.candidate?.playerUuid === ctx.playerUuid) {
      const assigned = assignHostStarting(
        worldId,
        waitingResolved.candidate,
        this.runtimeEpochBaseline(waitingResolved),
        now,
        () => randomId("rt")
      );
      await this.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId });
      await this.repository.upsertRuntimeRecord(assigned.runtime);
      return {
        action: "host",
        world,
        latestManifest,
        runtime: toRuntimeStatus(worldId, assigned.runtime, waitingResolved.candidate, waitingResolved.warning),
        assignment: assigned.assignment,
        waiterSessionId: null
      };
    }
    return {
      action: "wait",
      world,
      latestManifest,
      runtime: toRuntimeStatus(worldId, waitingResolved.runtime, waitingResolved.candidate, waitingResolved.warning),
      assignment: null,
      waiterSessionId: waiterSessionActive ? waiterSessionId : null
    };
  }

  async runtimeStatus(ctx: RequestContext, worldId: string, now = new Date()): Promise<WorldRuntimeStatus> {
    await this.requireSessionAccess(ctx, worldId, { allowRevokedHost: true });
    const resolved = await this.resolveRuntimeState(worldId, now);
    return toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning);
  }

  async refreshWaiting(ctx: RequestContext, worldId: string, request: RefreshWaitingRequest, now = new Date()): Promise<WorldRuntimeStatus> {
    await this.requireSessionAccess(ctx, worldId);
    await this.repository.refreshWaiterSession(worldId, ctx, request, now);
    const resolved = await this.resolveRuntimeState(worldId, now);
    return toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning);
  }

  /**
   * Responsibility:
   * Advance a single waiting attempt atomically so the client can react to one authoritative
   * action instead of inferring session transitions from raw runtime state.
   *
   * Preconditions:
   * The caller already owns an active waiter session for this world, or wants the backend to
   * tell it that the waiter session must be restarted.
   *
   * Postconditions:
   * Exactly one action is returned: connect, wait, or restart.
   *
   * Stale-work rule:
   * A missing or stale waiter session never reanimates waiting; it yields restart instead.
   *
   * Authority source:
   * The backend waiter session plus the resolved runtime/candidate state.
   */
  async observeWaiting(ctx: RequestContext, worldId: string, request: ObserveWaitingRequest, now = new Date()): Promise<ObserveWaitingResponse> {
    await this.requireSessionAccess(ctx, worldId);
    const waiterSessionId = sanitizeWaiterSessionId(request.waiterSessionId);
    if (!waiterSessionId) {
      throw new HttpError(400, "invalid_waiter_session", "SharedWorld waiting session id is required.");
    }
    const waiterSessionActive = await this.repository.refreshWaiterSession(worldId, ctx, { waiterSessionId }, now);
    const resolved = await this.resolveRuntimeState(worldId, now);
    const runtime = toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning);
    if (runtimeAllowsDirectConnect(resolved)) {
      if (waiterSessionActive) {
        await this.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId });
      }
      return {
        action: "connect",
        runtime,
        assignment: null,
        waiterSessionId: null
      };
    }
    const currentAssignment = hostAssignmentForCurrentRuntime(resolved, ctx.playerUuid);
    if (currentAssignment != null) {
      if (waiterSessionActive) {
        await this.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId });
      }
      return {
        action: "restart",
        runtime,
        assignment: null,
        waiterSessionId: null
      };
    }
    if (!waiterSessionActive) {
      return {
        action: "restart",
        runtime,
        assignment: null,
        waiterSessionId: null
      };
    }
    if (resolved.runtime == null && resolved.candidate?.playerUuid === ctx.playerUuid) {
      const assigned = assignHostStarting(
        worldId,
        resolved.candidate,
        this.runtimeEpochBaseline(resolved),
        now,
        () => randomId("rt")
      );
      await this.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId });
      await this.repository.upsertRuntimeRecord(assigned.runtime);
      return {
        action: "restart",
        runtime: toRuntimeStatus(worldId, assigned.runtime, resolved.candidate, resolved.warning),
        assignment: null,
        waiterSessionId: null
      };
    }
    if (resolved.runtime == null && resolved.candidate == null) {
      return {
        action: "restart",
        runtime,
        assignment: null,
        waiterSessionId: null
      };
    }
    return {
      action: "wait",
      runtime,
      assignment: null,
      waiterSessionId
    };
  }

  async cancelWaiting(ctx: RequestContext, worldId: string, request: CancelWaitingRequest, now = new Date()): Promise<WorldRuntimeStatus> {
    await this.requireSessionAccess(ctx, worldId);
    await this.repository.cancelWaiterSession(worldId, ctx, request);
    const resolved = await this.resolveRuntimeState(worldId, now);
    return toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning);
  }

  /**
   * Responsibility:
   * Resolve the single authoritative runtime record for a world after applying timeout and
   * current-candidate reconciliation.
   *
   * Preconditions:
   * The repository contains the current runtime record and waiter set for this world.
   *
   * Postconditions:
   * The returned runtime reflects timeout expiry and current preferred candidate selection.
   *
   * Stale-work rule:
   * Timeout and candidate reconciliation happen before any caller reasons about the runtime.
   *
   * Authority source:
   * Repository runtime storage plus the current waiter set.
   */
  private async resolveRuntimeState(worldId: string, now: Date): Promise<ResolvedRuntimeState> {
    const memberships = await this.repository.listMemberships(worldId);
    const waiters = await this.repository.listActiveWaiters(worldId, now);
    const candidate = choosePreferredCandidate(waiters.filter((waiter) => waiter.waiting), memberships);
    const before = await this.repository.getRuntimeRecord(worldId, now);
    const timeoutWarning = timedOutUncleanShutdownWarning(before, now);
    if (timeoutWarning != null) {
      const warning = {
        ...timeoutWarning,
        runtimeEpoch: before?.runtimeEpoch ?? 0
      };
      await this.repository.setUncleanShutdownWarning(worldId, warning);
      await this.repository.deleteRuntimeRecord(worldId);
      await this.repository.clearWaiters(worldId);
      await this.repository.clearWorldPresence(worldId);
      return {
        runtime: null,
        candidate: null,
        warning,
        retiredRuntimeEpoch: before?.runtimeEpoch ?? null
      };
    }
    const afterTimeout = resolveRuntimeTimeout(before, candidate, now);
    if (before !== afterTimeout) {
      if (afterTimeout == null) {
        await this.repository.deleteRuntimeRecord(worldId);
      } else {
        await this.repository.upsertRuntimeRecord(afterTimeout);
      }
    }
    const warning = await this.repository.getUncleanShutdownWarning(worldId);
    const retiredRuntimeEpoch = afterTimeout == null
      ? before?.runtimeEpoch ?? warning?.runtimeEpoch ?? await this.repository.getLastRuntimeEpoch(worldId)
      : null;
    return {
      runtime: afterTimeout,
      candidate,
      warning,
      retiredRuntimeEpoch
    };
  }

  private runtimeEpochBaseline(resolved: ResolvedRuntimeState): Pick<WorldRuntimeRecord, "runtimeEpoch"> | null {
    if (resolved.runtime != null) {
      return resolved.runtime;
    }
    if (resolved.warning != null) {
      return { runtimeEpoch: resolved.warning.runtimeEpoch };
    }
    if (resolved.retiredRuntimeEpoch != null) {
      return { runtimeEpoch: resolved.retiredRuntimeEpoch };
    }
    return null;
  }

  private async createSeededWorldResult(ctx: RequestContext, world: WorldDetails, now: Date): Promise<CreateWorldResult> {
    const initialUpload = assignHostStarting(
      world.id,
      {
        playerUuid: ctx.playerUuid,
        playerName: ctx.playerName
      },
      null,
      now,
      () => randomId("rt")
    );
    await this.repository.clearWaitersForPlayer(world.id, ctx.playerUuid);
    await this.repository.upsertRuntimeRecord(initialUpload.runtime);
    return {
      world: await this.hydrateWorldDetails(world, ctx.requestOrigin),
      initialUploadAssignment: initialUpload.assignment
    };
  }

  /**
   * Responsibility:
   * Enforce epoch/token authority for host-owned runtime mutations.
   *
   * Preconditions:
   * The caller is attempting to mutate a host-owned runtime phase.
   *
   * Postconditions:
   * A current authoritative runtime is returned or host_not_active is thrown.
   *
   * Stale-work rule:
   * Any mismatched epoch/token is rejected, even if the same player used to be host.
   *
   * Authority source:
   * The resolved backend runtime record.
   */
  private async requireAuthorizedRuntime(
    ctx: RequestContext,
    worldId: string,
    now: Date,
    runtimeEpoch: number | null | undefined,
    hostToken: string | null | undefined,
    allowedPhases: WorldRuntimeRecord["phase"][]
  ): Promise<AuthorizedRuntime> {
    const resolved = await this.resolveRuntimeState(worldId, now);
    if (!resolved.runtime
      || !allowedPhases.includes(resolved.runtime.phase)
      || !matchesHostAuthorization(resolved.runtime, ctx.playerUuid, runtimeEpoch, hostToken)) {
      throw new HttpError(409, "host_not_active", "SharedWorld host lease is no longer active for snapshot upload.");
    }
    return {
      runtime: resolved.runtime
    };
  }

  private async resolveProgressRuntime(
    ctx: RequestContext,
    worldId: string,
    request: HostStartupProgressRequest,
    now: Date
  ): Promise<AuthorizedRuntime> {
    if (request.runtimeEpoch == null || request.runtimeEpoch < 0 || request.hostToken == null) {
      throw new HttpError(409, "host_not_active", "SharedWorld host lease is no longer active for snapshot upload.");
    }
    return this.requireAuthorizedRuntime(
      ctx,
      worldId,
      now,
      request.runtimeEpoch,
      request.hostToken,
      ["host-starting", "host-finalizing"]
    );
  }

  private requireCompletableFinalizationRuntime(
    ctx: RequestContext,
    request: CompleteFinalizationRequest,
    resolved: ResolvedRuntimeState
  ): WorldRuntimeRecord | null {
    const runtime = resolved.runtime;
    if (runtime == null || runtime.phase !== "host-finalizing") {
      return null;
    }
    if (!matchesHostAuthorization(runtime, ctx.playerUuid, request.runtimeEpoch, request.hostToken)) {
      throw new HttpError(409, "host_not_active", "SharedWorld host lease is no longer active for snapshot upload.");
    }
    return runtime;
  }

  private runtimeToFinalizationResult(
    worldId: string,
    runtime: WorldRuntimeRecord | null,
    candidate: RuntimeCandidate | null,
    _now: Date
  ): FinalizationActionResult {
    const status = toRuntimeStatus(worldId, runtime, candidate);
    return {
      worldId,
      nextHostUuid: status.candidateUuid,
      nextHostPlayerName: status.candidatePlayerName,
      status: runtime != null
        ? runtimePhaseToWorldStatus(runtime.phase)
        : candidate != null
        ? "handoff"
        : "idle"
    };
  }

  private runtimeCandidateFromRuntime(runtime: WorldRuntimeRecord): RuntimeCandidate | null {
    if (runtime.candidateUuid == null || runtime.hostUuid == null || runtime.hostPlayerName == null) {
      return null;
    }
    if (runtime.candidateUuid !== runtime.hostUuid) {
      return null;
    }
    return {
      playerUuid: runtime.candidateUuid,
      playerName: runtime.hostPlayerName
    };
  }

  private async requireMembership(ctx: RequestContext, worldId: string): Promise<void> {
    if (!await this.repository.hasActiveWorld(worldId)) {
      throw new HttpError(404, "world_not_found", "SharedWorld server not found.");
    }
    const isMember = await this.repository.isWorldMember(worldId, ctx.playerUuid);
    if (!isMember) {
      throw new HttpError(403, "forbidden", "You do not have access to this SharedWorld server.");
    }
  }

  private async requireSessionAccess(
    ctx: RequestContext,
    worldId: string,
    options: { allowRevokedHost?: boolean } = {}
  ): Promise<void> {
    if (!await this.repository.hasActiveWorld(worldId)) {
      throw new HttpError(404, "world_not_found", "SharedWorld server not found.");
    }
    if (await this.repository.isWorldMember(worldId, ctx.playerUuid)) {
      return;
    }
    if (options.allowRevokedHost) {
      const resolved = await this.resolveRuntimeState(worldId, new Date());
      if (resolved.runtime?.hostUuid === ctx.playerUuid && resolved.runtime.revokedAt != null) {
        return;
      }
    }
    if (!await this.repository.hasWorldMembership(worldId, ctx.playerUuid)) {
      throw new HttpError(403, "forbidden", "You do not have access to this SharedWorld server.");
    }
    throw new HttpError(403, "membership_revoked", "You were removed from this SharedWorld.");
  }

  private async validateFinalizeSnapshotRequest(worldId: string, request: FinalizeSnapshotRequest): Promise<void> {
    const binding = await this.requireWorldStorageBinding(worldId);
    const snapshotCache = new Map<string, SnapshotManifest | null>();
    const seenPaths = new Set<string>();
    const seenPackIds = new Set<string>();

    if (request.baseSnapshotId != null) {
      await this.requireSnapshotForValidation(worldId, request.baseSnapshotId, snapshotCache);
    }

    for (const file of request.files) {
      validateManifestFileShape(file);
      if (seenPaths.has(file.path)) {
        throw new HttpError(400, "duplicate_snapshot_path", `Snapshot includes duplicate file path '${file.path}'.`);
      }
      seenPaths.add(file.path);
      await this.assertStorageKeyExists(binding, file.storageKey);
      await this.validateManifestFileBase(worldId, file, snapshotCache);
    }

    for (const pack of request.packs ?? []) {
      validateSnapshotPackShape(pack);
      if (seenPackIds.has(pack.packId)) {
        throw new HttpError(400, "duplicate_snapshot_pack", `Snapshot includes duplicate pack id '${pack.packId}'.`);
      }
      seenPackIds.add(pack.packId);
      await this.assertStorageKeyExists(binding, pack.storageKey);
      for (const file of pack.files) {
        validatePackedManifestFileShape(file);
        if (seenPaths.has(file.path)) {
          throw new HttpError(400, "duplicate_snapshot_path", `Snapshot includes duplicate file path '${file.path}'.`);
        }
        seenPaths.add(file.path);
      }
      await this.validateSnapshotPackBase(worldId, pack, snapshotCache);
    }
  }

  private async validateManifestFileBase(
    worldId: string,
    file: ManifestFile,
    snapshotCache: Map<string, SnapshotManifest | null>
  ): Promise<void> {
    const transferMode = normalizeFileTransferMode(file.transferMode);
    if (isDeltaFileTransferMode(transferMode)) {
      if (!file.baseSnapshotId || !file.baseHash || file.chainDepth == null || file.chainDepth < 1) {
        throw new HttpError(400, "invalid_snapshot_delta", `Snapshot delta file '${file.path}' is missing base metadata.`);
      }
      const baseSnapshot = await this.requireSnapshotForValidation(worldId, file.baseSnapshotId, snapshotCache);
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

  private async validateSnapshotPackBase(
    worldId: string,
    pack: SnapshotPack,
    snapshotCache: Map<string, SnapshotManifest | null>
  ): Promise<void> {
    if (isDeltaPackTransferMode(pack.transferMode)) {
      if (!pack.baseSnapshotId || !pack.baseHash || pack.chainDepth == null || pack.chainDepth < 1) {
        throw new HttpError(400, "invalid_snapshot_delta", `Snapshot delta pack '${pack.packId}' is missing base metadata.`);
      }
      const baseSnapshot = await this.requireSnapshotForValidation(worldId, pack.baseSnapshotId, snapshotCache);
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

  private async requireSnapshotForValidation(
    worldId: string,
    snapshotId: string,
    snapshotCache: Map<string, SnapshotManifest | null>
  ): Promise<SnapshotManifest> {
    let snapshot = snapshotCache.get(snapshotId);
    if (snapshot === undefined) {
      snapshot = await this.repository.getSnapshot(worldId, snapshotId);
      snapshotCache.set(snapshotId, snapshot);
    }
    if (!snapshot) {
      throw new HttpError(400, "snapshot_base_not_found", `Snapshot base '${snapshotId}' was not found for this world.`);
    }
    return snapshot;
  }

  private async assertStorageKeyExists(binding: WorldStorageBinding, storageKey: string): Promise<void> {
    const exists = await this.storageKeyExists(binding, storageKey);
    if (!exists) {
      throw new HttpError(400, "snapshot_storage_missing", `Snapshot storage object '${storageKey}' was not found.`);
    }
  }

  private async storageKeyExists(binding: WorldStorageBinding, storageKey: string): Promise<boolean> {
    if (binding.provider === "google-drive") {
      if (binding.storageAccountId == null) {
        // Legacy/unlinked worlds do not have cheap object metadata to validate against.
        return true;
      }
      return (await this.repository.getStorageObject(binding.provider, binding.storageAccountId, storageKey)) != null;
    }
    if (binding.provider === "r2" && this.storageProvider.provider === "r2" && this.env.BLOBS == null) {
      return true;
    }
    return this.storageProvider.exists(binding, storageKey);
  }

  private runtimeAuthorizationFromHeaders(request: Request): {
    runtimeEpoch: number | null;
    hostToken: string | null;
  } {
    const runtimeEpochHeader = request.headers.get(RUNTIME_EPOCH_HEADER);
    return {
      runtimeEpoch: runtimeEpochHeader == null ? null : Number(runtimeEpochHeader),
      hostToken: request.headers.get(HOST_TOKEN_HEADER)
    };
  }

  private async applySnapshotRetention(worldId: string, now: Date): Promise<void> {
    const snapshots = await this.repository.listSnapshotsForWorld(worldId);
    const keep = selectSnapshotsToKeep(snapshots, now);
    const deleteIds = snapshots
      .map((snapshot) => snapshot.snapshotId)
      .filter((snapshotId) => !keep.has(snapshotId));
    if (deleteIds.length === 0) {
      return;
    }

    try {
      const binding = await this.requireWorldStorageBinding(worldId);
      const deletion = await this.repository.deleteSnapshots(worldId, deleteIds);
      await this.deleteUnreferencedBlobs(binding, deletion.unreferencedStorageKeys);
    } catch (error) {
      console.warn("SharedWorld snapshot retention cleanup failed", error);
    }
  }

  private async purgeWorldSnapshots(binding: StorageBinding, worldId: string): Promise<void> {
    try {
      const snapshots = await this.repository.listSnapshotsForWorld(worldId);
      const deletion = await this.repository.deleteSnapshots(
        worldId,
        snapshots.map((snapshot) => snapshot.snapshotId)
      );
      await this.deleteUnreferencedBlobs(binding, deletion.unreferencedStorageKeys);
    } catch (error) {
      console.warn("SharedWorld world storage cleanup failed", error);
    }
  }

  private async deleteUnreferencedBlobs(binding: StorageBinding, storageKeys: string[]): Promise<void> {
    for (const storageKey of storageKeys) {
      try {
        await this.storageProvider.delete(binding, storageKey);
        if (this.storageProvider.provider === "r2") {
          await this.blobSigner.deleteBlob?.(storageKey);
        }
      } catch (error) {
        console.warn("SharedWorld blob cleanup failed for", storageKey, error);
      }
    }
  }

  private async hydrateWorldSummary(world: WorldSummary, requestOrigin?: string): Promise<WorldSummary> {
    if (!world.customIconStorageKey) {
      return world;
    }
    return {
      ...world,
      customIconDownload: await this.signDownloadForWorld(world.id, world.customIconStorageKey, requestOrigin)
    };
  }

  private async hydrateWorldDetails(world: WorldDetails, requestOrigin?: string): Promise<WorldDetails> {
    return this.hydrateWorldSummary(world, requestOrigin) as Promise<WorldDetails>;
  }

  private async storeCustomIcon(binding: StorageBinding, iconBase64: string): Promise<string> {
    const bytes = Uint8Array.from(atob(iconBase64), (value) => value.charCodeAt(0));
    if (!isPng(bytes) || pngWidth(bytes) !== 64 || pngHeight(bytes) !== 64) {
      throw new HttpError(400, "invalid_custom_icon", "Custom icon must be a 64x64 PNG.");
    }
    const hash = await sha256Hex(bytes);
    const storageKey = iconStorageKey(hash);
    if (!(await this.storageProvider.exists(binding, storageKey))) {
      await this.storageProvider.put(binding, storageKey, bytes, "image/png");
    }
    return storageKey;
  }

  private async maybeDeleteUnreferencedBlob(binding: StorageBinding, storageKey: string | null): Promise<void> {
    if (!storageKey) {
      return;
    }
    const stillReferenced = await this.repository.isStorageKeyReferenced(storageKey);
    if (!stillReferenced) {
      await this.deleteUnreferencedBlobs(binding, [storageKey]);
    }
  }

  private async requireWorldStorageBinding(worldId: string): Promise<StorageBinding> {
    const binding = await this.repository.getWorldStorageBinding(worldId);
    if (!binding) {
      throw new HttpError(404, "world_not_found", "SharedWorld server not found.");
    }
    return binding;
  }

  private async signUploadForWorld(
    worldId: string,
    storageKey: string,
    runtime: WorldRuntimeRecord,
    requestOrigin?: string
  ) {
    const signer = this.blobSigner as BlobUrlSigner & {
      signUpload(storageKey: string, requestOrigin?: string): Promise<{ method: "PUT"; url: string; headers: Record<string, string>; expiresAt: string }>;
    };
    const signedUpload = await (signer.signUpload.length <= 2
      ? signer.signUpload(storageKey, requestOrigin)
      : this.blobSigner.signUpload(worldId, storageKey, requestOrigin));
    return {
      ...signedUpload,
      headers: {
        ...(signedUpload.headers ?? {}),
        [RUNTIME_EPOCH_HEADER]: String(runtime.runtimeEpoch),
        [HOST_TOKEN_HEADER]: runtime.runtimeToken ?? ""
      }
    };
  }

  private async signDownloadForWorld(worldId: string, storageKey: string, requestOrigin?: string) {
    const signer = this.blobSigner as BlobUrlSigner & {
      signDownload(storageKey: string, requestOrigin?: string): Promise<{ method: "GET"; url: string; headers: Record<string, string>; expiresAt: string }>;
    };
    return signer.signDownload.length <= 2
      ? signer.signDownload(storageKey, requestOrigin)
      : this.blobSigner.signDownload(worldId, storageKey, requestOrigin);
  }

  private async buildDownloadSteps(
    worldId: string,
    latestFile: ManifestFile,
    localHash: string | null,
    requestOrigin: string | undefined,
    snapshotCache: Map<string, SnapshotManifest>
  ): Promise<DownloadPlanStep[]> {
    if (latestFile.transferMode !== REGION_DELTA_TRANSFER_MODE) {
      return [await this.downloadStepForFile(worldId, latestFile, requestOrigin)];
    }

    const steps: DownloadPlanStep[] = [];
    let cursor: ManifestFile | null = latestFile;
    while (cursor) {
      if (localHash != null && localHash === cursor.hash) {
        break;
      }

      steps.push(await this.downloadStepForFile(worldId, cursor, requestOrigin));
      if (cursor.transferMode !== REGION_DELTA_TRANSFER_MODE || !cursor.baseSnapshotId) {
        break;
      }
      if (localHash != null && cursor.baseHash != null && localHash === cursor.baseHash) {
        break;
      }
      cursor = await this.loadSnapshotFile(worldId, cursor.baseSnapshotId, cursor.path, snapshotCache);
    }

    return steps.reverse();
  }

  private async prepareGroupedArtifactUpload(
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
    const fullExists = await this.storageProvider.exists(binding, fullStorageKey);
    const baseChainDepth = latestPack?.transferMode === deltaTransferMode
      ? (latestPack.chainDepth ?? 0)
      : 0;
    const deltaAvailable = latestPack != null
      && (latestPack.transferMode === fullTransferMode || latestPack.transferMode === deltaTransferMode)
      && baseChainDepth < maxChainDepth;
    const deltaStorageKey = deltaAvailable ? deltaStorageKeyForHashes(latestPack.hash, pack.hash) : null;
    const deltaExists = deltaStorageKey ? await this.storageProvider.exists(binding, deltaStorageKey) : false;

    return {
      pack,
      alreadyPresent: false,
      transferMode: fullTransferMode,
      storageKey: null,
      upload: undefined,
      fullStorageKey,
      fullUpload: fullExists ? undefined : await this.signUploadForWorld(worldId, fullStorageKey, runtime, ctx.requestOrigin),
      deltaStorageKey,
      deltaUpload: deltaStorageKey == null || deltaExists ? undefined : await this.signUploadForWorld(worldId, deltaStorageKey, runtime, ctx.requestOrigin),
      baseSnapshotId: latestSnapshotId,
      baseHash: latestPack?.hash ?? null,
      baseChainDepth
    };
  }

  private async buildPackDownloadSteps(
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
        download: await this.signDownloadForWorld(worldId, cursor.storageKey, requestOrigin)
      });
      if (cursor.transferMode !== deltaTransferMode || !cursor.baseSnapshotId) {
        break;
      }
      if (localPackHash != null && cursor.baseHash != null && localPackHash === cursor.baseHash) {
        break;
      }
      cursor = await this.loadSnapshotPack(worldId, cursor.baseSnapshotId, cursor.packId, snapshotCache);
    }
    return steps.reverse();
  }

  private async downloadStepForFile(worldId: string, file: ManifestFile, requestOrigin?: string): Promise<DownloadPlanStep> {
    return {
      transferMode: normalizeFileTransferMode(file.transferMode),
      storageKey: file.storageKey,
      artifactSize: file.compressedSize,
      baseSnapshotId: file.baseSnapshotId ?? null,
      baseHash: file.baseHash ?? null,
      download: await this.signDownloadForWorld(worldId, file.storageKey, requestOrigin)
    };
  }

  private async loadSnapshotFile(
    worldId: string,
    snapshotId: string,
    path: string,
    snapshotCache: Map<string, SnapshotManifest>
  ): Promise<ManifestFile | null> {
    let snapshot: SnapshotManifest | undefined | null = snapshotCache.get(snapshotId);
    if (!snapshot) {
      snapshot = await this.repository.getSnapshot(worldId, snapshotId);
      if (!snapshot) {
        return null;
      }
      snapshotCache.set(snapshotId, snapshot);
    }
    return snapshot.files.find((file) => file.path === path) ?? null;
  }

  private async loadSnapshotPack(
    worldId: string,
    snapshotId: string,
    packId: string,
    snapshotCache: Map<string, SnapshotManifest>
  ): Promise<SnapshotPack | null> {
    let snapshot: SnapshotManifest | undefined | null = snapshotCache.get(snapshotId);
    if (!snapshot) {
      snapshot = await this.repository.getSnapshot(worldId, snapshotId);
      if (!snapshot) {
        return null;
      }
      snapshotCache.set(snapshotId, snapshot);
    }
    return snapshot.packs.find((pack) => pack.packId === packId) ?? null;
  }

  private syncPolicyForProvider(): SyncPolicy {
    if (this.storageProvider.provider === "google-drive") {
      return {
        maxParallelDownloads: parsePositiveInt(this.env.DRIVE_MAX_PARALLEL_DOWNLOADS, 8),
        maxConcurrentUploadPreparations: parsePositiveInt(this.env.DRIVE_MAX_UPLOAD_PREPARATIONS, 2),
        maxConcurrentUploads: parsePositiveInt(this.env.DRIVE_MAX_CONCURRENT_UPLOADS, 3),
        maxUploadStartsPerSecond: parsePositiveInt(this.env.DRIVE_MAX_UPLOAD_STARTS_PER_SECOND, 3),
        retryBaseDelayMs: parsePositiveInt(this.env.DRIVE_RETRY_BASE_DELAY_MS, 750),
        retryMaxDelayMs: parsePositiveInt(this.env.DRIVE_RETRY_MAX_DELAY_MS, 8_000)
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
}

function sanitizeWaiterSessionId(waiterSessionId: string | null | undefined): string | null {
  if (waiterSessionId == null) {
    return null;
  }
  const trimmed = waiterSessionId.trim();
  return trimmed.length === 0 ? null : trimmed;
}

function parsePositiveInt(value: string | undefined, fallback: number): number {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export class MinecraftSessionServerAuthVerifier implements AuthVerifier {
  constructor(private readonly endpoint: string) {}

  async verifyJoin(playerName: string, serverId: string): Promise<{ playerUuid: string; playerName: string } | null> {
    const url = new URL(this.endpoint);
    url.searchParams.set("username", playerName);
    url.searchParams.set("serverId", serverId);

    let response: Response;
    try {
      response = await fetch(url, {
        headers: {
          accept: "application/json"
        }
      });
    } catch {
      throw new HttpError(503, "identity_verification_unavailable", "Minecraft identity verification is unavailable.");
    }

    if (response.status === 204 || response.status === 404) {
      return null;
    }
    if (!response.ok) {
      throw new HttpError(503, "identity_verification_unavailable", "Minecraft identity verification is unavailable.");
    }

    const text = await response.text();
    if (text.trim().length === 0) {
      return null;
    }

    let payload: { id?: string; name?: string };
    try {
      payload = JSON.parse(text) as { id?: string; name?: string };
    } catch {
      throw new HttpError(503, "identity_verification_unavailable", "Minecraft identity verification returned an invalid response.");
    }
    if (!payload.id || !payload.name) {
      throw new HttpError(503, "identity_verification_unavailable", "Minecraft identity verification returned an invalid response.");
    }
    return {
      playerUuid: payload.id,
      playerName: payload.name
    };
  }
}

export class WorkerSignedUrlSigner implements BlobUrlSigner {
  constructor(private readonly env: Env) {}

  async signUpload(worldIdOrStorageKey: string, storageKeyOrRequestOrigin?: string, requestOrigin?: string) {
    const { worldId, storageKey, origin } = normalizeSignArgs(worldIdOrStorageKey, storageKeyOrRequestOrigin, requestOrigin);
    return this.sign("PUT", worldId, storageKey, origin);
  }

  async signDownload(worldIdOrStorageKey: string, storageKeyOrRequestOrigin?: string, requestOrigin?: string) {
    const { worldId, storageKey, origin } = normalizeSignArgs(worldIdOrStorageKey, storageKeyOrRequestOrigin, requestOrigin);
    return this.sign("GET", worldId, storageKey, origin);
  }

  private async sign<TMethod extends "PUT" | "GET">(method: TMethod, worldId: string, storageKey: string, requestOrigin?: string): Promise<{
    method: TMethod;
    url: string;
    headers: Record<string, string>;
    expiresAt: string;
  }> {
    const configuredBase = this.env.PUBLIC_BASE_URL;
    const base = configuredBase && !configuredBase.includes("sharedworld.example.workers.dev")
      ? configuredBase
      : (requestOrigin ?? configuredBase ?? "https://sharedworld.example.workers.dev");
    const ttlSeconds = Number(this.env.SIGNED_URL_TTL_SECONDS ?? "900");
    const expiresAt = new Date(Date.now() + ttlSeconds * 1000).toISOString();
    const path = worldId === "legacy"
      ? `/storage/blob/${encodeURIComponent(storageKey)}`
      : `/worlds/${encodeURIComponent(worldId)}/storage/blob/${encodeURIComponent(storageKey)}`;
    // These URLs describe authenticated SharedWorld blob routes. Access is enforced by
    // bearer auth plus any runtime headers, while expiresAt remains advisory for clients.
    return {
      method,
      url: `${base}${path}`,
      headers: {},
      expiresAt
    };
  }
}

function normalizeMotd(line1: string | null, line2: string | null): string | null {
  const lines = [line1 ?? "", line2 ?? ""]
    .flatMap((line) => line.replace(/\r/g, "").split("\n"))
    .map((line) => line.trimEnd())
    .filter((line) => line.length > 0);
  if (lines.length > 2) {
    throw new HttpError(400, "invalid_motd", "Shared World MOTD can use at most 2 lines.");
  }
  return lines.length > 0 ? lines.join("\n") : null;
}

function splitMotd(motd: string | null): [string | null, string | null] {
  if (!motd) {
    return [null, null];
  }
  const lines = motd.split("\n");
  return [lines[0] ?? null, lines[1] ?? null];
}

function iconStorageKey(hash: string): string {
  return `icons/${hash.slice(0, 2)}/${hash}.png`;
}

function isPng(bytes: Uint8Array): boolean {
  const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  return signature.every((value, index) => bytes[index] === value);
}

function pngWidth(bytes: Uint8Array): number {
  return bytes.length >= 24 ? readPngInt(bytes, 16) : 0;
}

function pngHeight(bytes: Uint8Array): number {
  return bytes.length >= 24 ? readPngInt(bytes, 20) : 0;
}

function readPngInt(bytes: Uint8Array, offset: number): number {
  return ((bytes[offset] ?? 0) << 24)
    | ((bytes[offset + 1] ?? 0) << 16)
    | ((bytes[offset + 2] ?? 0) << 8)
    | (bytes[offset + 3] ?? 0);
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", copyArrayBuffer(bytes));
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, "0")).join("");
}

function copyArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

function normalizeSignArgs(worldIdOrStorageKey: string, storageKeyOrRequestOrigin?: string, requestOrigin?: string) {
  if (requestOrigin === undefined) {
    return {
      worldId: "legacy",
      storageKey: worldIdOrStorageKey,
      origin: storageKeyOrRequestOrigin
    };
  }
  return {
    worldId: worldIdOrStorageKey,
    storageKey: storageKeyOrRequestOrigin ?? "",
    origin: requestOrigin
  };
}

function clampFraction(value: number | null): number | null {
  if (value == null || !Number.isFinite(value)) {
    return null;
  }
  return Math.max(0, Math.min(1, value));
}

function validateManifestFileShape(file: ManifestFile): void {
  if (file.path.trim().length === 0) {
    throw new HttpError(400, "invalid_snapshot_path", "Snapshot file path is required.");
  }
  if (file.storageKey.trim().length === 0) {
    throw new HttpError(400, "invalid_snapshot_storage_key", `Snapshot file '${file.path}' is missing a storage key.`);
  }
  const transferMode = normalizeFileTransferMode(file.transferMode);
  if (!isAllowedFileTransferMode(transferMode)) {
    throw new HttpError(400, "invalid_snapshot_transfer_mode", `Snapshot file '${file.path}' uses unsupported transfer mode '${file.transferMode}'.`);
  }
}

function validatePackedManifestFileShape(file: { path: string }): void {
  if (file.path.trim().length === 0) {
    throw new HttpError(400, "invalid_snapshot_path", "Snapshot packed file path is required.");
  }
}

function validateSnapshotPackShape(pack: SnapshotPack): void {
  if (pack.packId.trim().length === 0) {
    throw new HttpError(400, "invalid_snapshot_pack", "Snapshot pack id is required.");
  }
  if (pack.storageKey.trim().length === 0) {
    throw new HttpError(400, "invalid_snapshot_storage_key", `Snapshot pack '${pack.packId}' is missing a storage key.`);
  }
  if (!isAllowedPackTransferMode(pack.packId, pack.transferMode)) {
    throw new HttpError(400, "invalid_snapshot_transfer_mode", `Snapshot pack '${pack.packId}' uses unsupported transfer mode '${pack.transferMode}'.`);
  }
}

function isAllowedFileTransferMode(mode: FileTransferMode): boolean {
  return mode === WHOLE_GZIP_TRANSFER_MODE
    || mode === REGION_FULL_TRANSFER_MODE
    || mode === REGION_DELTA_TRANSFER_MODE;
}

function isAllowedPackTransferMode(packId: string, mode: FileTransferMode): boolean {
  if (isRegionBundleId(packId)) {
    return mode === REGION_FULL_TRANSFER_MODE || mode === REGION_DELTA_TRANSFER_MODE;
  }
  return mode === PACK_FULL_TRANSFER_MODE || mode === PACK_DELTA_TRANSFER_MODE;
}

function isDeltaFileTransferMode(mode: FileTransferMode): boolean {
  return mode === REGION_DELTA_TRANSFER_MODE;
}

function isDeltaPackTransferMode(mode: FileTransferMode): boolean {
  return mode === REGION_DELTA_TRANSFER_MODE || mode === PACK_DELTA_TRANSFER_MODE;
}

function normalizeFileTransferMode(mode: FileTransferMode | null | undefined): FileTransferMode {
  return mode ?? WHOLE_GZIP_TRANSFER_MODE;
}

function nextChainDepth(baseTransferMode: FileTransferMode, baseChainDepth: number | null): number {
  return (baseTransferMode === REGION_DELTA_TRANSFER_MODE || baseTransferMode === PACK_DELTA_TRANSFER_MODE)
    ? (baseChainDepth ?? 0) + 1
    : 1;
}

function isZeroOrNullChainDepth(value: number | null): boolean {
  return value == null || value === 0;
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
