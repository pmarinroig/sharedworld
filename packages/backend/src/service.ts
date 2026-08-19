import type {
  AbandonFinalizationRequest,
  AuthCompleteCertRequest,
  AuthCompleteRequest,
  BeginFinalizationRequest,
  CancelWaitingRequest,
  CompleteFinalizationRequest,
  CreateStorageLinkRequest,
  CreateWorldRequest,
  CreateWorldResult,
  DevAuthCompleteRequest,
  DownloadPlan,
  EnterSessionRequest,
  EnterSessionResponse,
  FinalizeSnapshotRequest,
  HeartbeatRequest,
  HostGameRulesReportRequest,
  HostGameRulesReportResponse,
  HostHeartbeatResponse,
  HostStartupProgressRequest,
  InviteCode,
  KickMemberResponse,
  ObserveWaitingRequest,
  ObserveWaitingResponse,
  PresenceHeartbeatRequest,
  RedeemInviteRequest,
  ReleaseHostRequest,
  ResetInviteResponse,
  DeleteSnapshotsRequest,
  DeleteSnapshotsResult,
  SnapshotActionResult,
  SnapshotManifest,
  StorageAccountSummary,
  StorageLinkCompleteRequest,
  StorageUsageSummary,
  UpdateMemberPermissionsRequest,
  UpdateWorldRequest,
  UpdateWorldSettingsRequest,
  UploadPlanRequest,
  WorldDetails,
  WorldMembership,
  WorldRuntimeStatus,
  WorldSnapshotSummary,
  WorldSummary,
  CreateBlobSessionRequest,
  CreateBlobSessionResponse,
  CommitBlobSessionRequest,
  CommitBlobSessionResponse
} from "../../shared/src/index.ts";

import { AuthDomainService } from "./auth/service.ts";
import type { Env } from "./env.ts";
import type { RequestContext, SharedWorldRepository } from "./repository.ts";
import type { StorageProvider } from "./storage.ts";
import { workersStorageUsageCache } from "./storage-usage-cache.ts";
import { StorageLinkDomainService } from "./storage/link-service.ts";
import type { BlobUrlSigner, ServiceContext } from "./service/context.ts";
import type { RealtimeService } from "./realtime/service.ts";
import * as members from "./service/members.ts";
import * as session from "./service/session.ts";
import * as snapshots from "./service/snapshots.ts";
import * as syncPlan from "./service/sync-plan.ts";
import * as worlds from "./service/worlds.ts";

export type { BlobUrlSigner } from "./service/context.ts";

/**
 * The single entry point the router talks to. Each method delegates to one
 * domain module; this class only wires dependencies and preserves the public
 * API shape. See src/service/ for the actual behavior.
 */
export class SharedWorldService {
  private readonly svc: ServiceContext;
  private readonly authDomain: AuthDomainService;

  constructor(
    repository: SharedWorldRepository,
    blobSigner: BlobUrlSigner,
    storageProvider: StorageProvider,
    env: Env,
    realtime: RealtimeService
  ) {
    this.svc = {
      repository,
      blobSigner,
      storageProvider,
      storageLinks: new StorageLinkDomainService(repository, env, storageProvider.provider),
      realtime,
      env,
      storageUsageCache: workersStorageUsageCache()
    };
    this.authDomain = new AuthDomainService(repository, env);
  }

  // --- auth ---

  async createChallenge(now = new Date()) {
    return this.authDomain.createChallenge(now);
  }

  async completeAuth(request: AuthCompleteRequest) {
    return this.authDomain.completeAuth(request);
  }

  async completeCertAuth(request: AuthCompleteCertRequest, now = new Date()) {
    return this.authDomain.completeCertAuth(request, now);
  }

  async completeDevAuth(request: DevAuthCompleteRequest, now = new Date()) {
    return this.authDomain.completeDevAuth(request, now);
  }

  async getSession(token: string) {
    return this.authDomain.getSession(token);
  }

  // --- storage linking ---

  async createStorageLink(ctx: RequestContext, request: CreateStorageLinkRequest, now = new Date()) {
    return this.svc.storageLinks.createStorageLink(ctx, request, now);
  }

  async getStorageLinkSession(ctx: RequestContext, sessionId: string, now = new Date()) {
    return this.svc.storageLinks.getStorageLinkSession(ctx, sessionId, now);
  }

  async cancelStorageLink(ctx: RequestContext, sessionId: string, now = new Date()) {
    return this.svc.storageLinks.cancelStorageLink(ctx, sessionId, now);
  }

  async completeStorageLink(sessionId: string, request: StorageLinkCompleteRequest, now = new Date()) {
    return this.svc.storageLinks.completeStorageLink(sessionId, request, now);
  }

  async getStorageAccount(ctx: RequestContext): Promise<StorageAccountSummary> {
    return this.svc.storageLinks.getStorageAccountSummary(ctx);
  }

  // --- worlds ---

  async listWorlds(ctx: RequestContext): Promise<WorldSummary[]> {
    return worlds.listWorlds(this.svc, ctx);
  }

  async createWorld(ctx: RequestContext, request: CreateWorldRequest, now = new Date()): Promise<CreateWorldResult> {
    return worlds.createWorld(this.svc, ctx, request, now);
  }

  async getWorld(ctx: RequestContext, worldId: string, now = new Date()): Promise<WorldDetails> {
    return worlds.getWorld(this.svc, ctx, worldId, now);
  }

  async updateWorld(ctx: RequestContext, worldId: string, request: UpdateWorldRequest): Promise<WorldDetails> {
    return worlds.updateWorld(this.svc, ctx, worldId, request);
  }

  async updateWorldSettings(ctx: RequestContext, worldId: string, request: UpdateWorldSettingsRequest): Promise<WorldDetails> {
    return worlds.updateWorldSettings(this.svc, ctx, worldId, request);
  }

  async reportHostGameRules(ctx: RequestContext, worldId: string, request: HostGameRulesReportRequest, now = new Date()): Promise<HostGameRulesReportResponse> {
    return worlds.reportHostGameRules(this.svc, ctx, worldId, request, now);
  }

  async deleteWorld(ctx: RequestContext, worldId: string, now = new Date()): Promise<void> {
    return worlds.deleteWorld(this.svc, ctx, worldId, now);
  }

  /**
   * Routed again since the efficiency release: GET /worlds/:id/storage-usage
   * serves 0.4.1+ edit screens on demand, now that world details no longer
   * compute usage inline for them.
   */
  async getStorageUsage(ctx: RequestContext, worldId: string): Promise<StorageUsageSummary> {
    return worlds.getStorageUsage(this.svc, ctx, worldId);
  }

  /** Weak ETag for GET /worlds; always present (an empty list is a valid body). */
  async worldsEtag(ctx: RequestContext): Promise<string> {
    return worlds.worldsEtag(this.svc, ctx);
  }

  /** Weak ETag for GET /worlds/:id; null when the caller has no access. */
  async worldEtag(ctx: RequestContext, worldId: string, now = new Date()): Promise<string | null> {
    return worlds.worldEtag(this.svc, ctx, worldId, now);
  }

  // --- membership ---

  async createInvite(ctx: RequestContext, worldId: string, now = new Date()): Promise<InviteCode> {
    return members.createInvite(this.svc, ctx, worldId, now);
  }

  async redeemInvite(ctx: RequestContext, request: RedeemInviteRequest, now = new Date()): Promise<WorldDetails> {
    return members.redeemInvite(this.svc, ctx, request, now);
  }

  async resetInvite(ctx: RequestContext, worldId: string, now = new Date()): Promise<ResetInviteResponse> {
    return members.resetInvite(this.svc, ctx, worldId, now);
  }

  async kickMember(ctx: RequestContext, worldId: string, removedPlayerUuid: string, now = new Date()): Promise<KickMemberResponse> {
    return members.kickMember(this.svc, ctx, worldId, removedPlayerUuid, now);
  }

  async setMemberCommandPermission(
    ctx: RequestContext,
    worldId: string,
    targetPlayerUuid: string,
    request: UpdateMemberPermissionsRequest
  ): Promise<WorldMembership> {
    return members.setMemberCommandPermission(this.svc, ctx, worldId, targetPlayerUuid, request.canUseCommands === true);
  }

  // --- snapshots ---

  async listSnapshots(ctx: RequestContext, worldId: string): Promise<WorldSnapshotSummary[]> {
    return snapshots.listSnapshots(this.svc, ctx, worldId);
  }

  async latestManifest(ctx: RequestContext, worldId: string): Promise<SnapshotManifest | null> {
    return snapshots.latestManifest(this.svc, ctx, worldId);
  }

  async restoreSnapshot(ctx: RequestContext, worldId: string, snapshotId: string, now = new Date()): Promise<SnapshotActionResult> {
    return snapshots.restoreSnapshot(this.svc, ctx, worldId, snapshotId, now);
  }

  async deleteSnapshot(ctx: RequestContext, worldId: string, snapshotId: string): Promise<SnapshotActionResult> {
    return snapshots.deleteSnapshot(this.svc, ctx, worldId, snapshotId);
  }

  async deleteSnapshots(ctx: RequestContext, worldId: string, request: DeleteSnapshotsRequest): Promise<DeleteSnapshotsResult> {
    return snapshots.deleteSnapshots(this.svc, ctx, worldId, Array.isArray(request?.snapshotIds) ? request.snapshotIds : []);
  }

  /** Cron entry point: drains due entries of the blob GC retry queue. */
  async sweepDuePendingBlobDeletes(now = new Date()): Promise<number> {
    return snapshots.sweepDuePendingBlobDeletes(this.svc, now);
  }

  async finalizeSnapshot(ctx: RequestContext, worldId: string, request: FinalizeSnapshotRequest, now = new Date()) {
    return snapshots.finalizeSnapshot(this.svc, ctx, worldId, request, now);
  }

  // --- sync planning and blob transfer ---

  async prepareUploads(ctx: RequestContext, worldId: string, request: UploadPlanRequest) {
    return syncPlan.prepareUploads(this.svc, ctx, worldId, request);
  }

  async downloadPlan(ctx: RequestContext, worldId: string, request: UploadPlanRequest): Promise<DownloadPlan> {
    return syncPlan.downloadPlan(this.svc, ctx, worldId, request);
  }

  async uploadStorageBlob(ctx: RequestContext, worldId: string, storageKey: string, request: Request): Promise<void> {
    return syncPlan.uploadStorageBlob(this.svc, ctx, worldId, storageKey, request);
  }

  async createBlobUploadSession(ctx: RequestContext, worldId: string, request: CreateBlobSessionRequest): Promise<CreateBlobSessionResponse> {
    return syncPlan.createBlobUploadSession(this.svc, ctx, worldId, request);
  }

  async commitBlobUploadSession(ctx: RequestContext, worldId: string, request: CommitBlobSessionRequest): Promise<CommitBlobSessionResponse> {
    return syncPlan.commitBlobUploadSession(this.svc, ctx, worldId, request);
  }

  async downloadStorageBlob(ctx: RequestContext, worldId: string, storageKey: string, request?: Request): Promise<Response> {
    return syncPlan.downloadStorageBlob(this.svc, ctx, worldId, storageKey, request);
  }

  // --- session and runtime protocol ---

  async enterSession(ctx: RequestContext, worldId: string, request: EnterSessionRequest = {}, now = new Date()): Promise<EnterSessionResponse> {
    return session.enterSession(this.svc, ctx, worldId, request, now);
  }

  async observeWaiting(ctx: RequestContext, worldId: string, request: ObserveWaitingRequest, now = new Date()): Promise<ObserveWaitingResponse> {
    return session.observeWaiting(this.svc, ctx, worldId, request, now);
  }

  async runtimeStatus(ctx: RequestContext, worldId: string, now = new Date()): Promise<WorldRuntimeStatus> {
    return session.runtimeStatus(this.svc, ctx, worldId, now);
  }

  async cancelWaiting(ctx: RequestContext, worldId: string, request: CancelWaitingRequest, now = new Date()): Promise<WorldRuntimeStatus> {
    return session.cancelWaiting(this.svc, ctx, worldId, request, now);
  }

  async heartbeatHost(ctx: RequestContext, worldId: string, request: HeartbeatRequest, now = new Date()): Promise<HostHeartbeatResponse> {
    return session.heartbeatHost(this.svc, ctx, worldId, request, now);
  }

  async setHostStartupProgress(ctx: RequestContext, worldId: string, request: HostStartupProgressRequest, now = new Date()) {
    return session.setHostStartupProgress(this.svc, ctx, worldId, request, now);
  }

  async setPlayerPresence(ctx: RequestContext, worldId: string, request: PresenceHeartbeatRequest, now = new Date()) {
    return session.setPlayerPresence(this.svc, ctx, worldId, request, now);
  }

  /** 0.3.0 realtime: upgrade the caller's WebSocket onto their gateway. */
  async connectRealtime(ctx: RequestContext, request: Request): Promise<Response> {
    return this.svc.realtime.connect(ctx.playerUuid, request);
  }

  async beginFinalization(ctx: RequestContext, worldId: string, request: BeginFinalizationRequest, now = new Date()) {
    return session.beginFinalization(this.svc, ctx, worldId, request, now);
  }

  async completeFinalization(ctx: RequestContext, worldId: string, request: CompleteFinalizationRequest, now = new Date()) {
    return session.completeFinalization(this.svc, ctx, worldId, request, now);
  }

  async abandonFinalization(ctx: RequestContext, worldId: string, request: AbandonFinalizationRequest = {}, now = new Date()) {
    return session.abandonFinalization(this.svc, ctx, worldId, request, now);
  }

  async releaseHost(ctx: RequestContext, worldId: string, request: ReleaseHostRequest, now = new Date()) {
    return session.releaseHost(this.svc, ctx, worldId, request, now);
  }
}

/**
 * Signs blob transfer URLs that point back at the worker's authenticated blob
 * routes. Access is enforced by bearer auth plus runtime headers; expiresAt is
 * advisory for clients.
 */
export class WorkerSignedUrlSigner implements BlobUrlSigner {
  constructor(private readonly env: Env) {}

  async signUpload(worldId: string, storageKey: string, requestOrigin?: string) {
    return this.sign("PUT" as const, worldId, storageKey, requestOrigin);
  }

  async signDownload(worldId: string, storageKey: string, requestOrigin?: string) {
    return this.sign("GET" as const, worldId, storageKey, requestOrigin);
  }

  private sign<TMethod extends "PUT" | "GET">(method: TMethod, worldId: string, storageKey: string, requestOrigin?: string): {
    method: TMethod;
    url: string;
    headers: Record<string, string>;
    expiresAt: string;
  } {
    const configuredBase = this.env.PUBLIC_BASE_URL;
    const base = configuredBase && !configuredBase.includes("sharedworld.example.workers.dev")
      ? configuredBase
      : (requestOrigin ?? configuredBase ?? "https://sharedworld.example.workers.dev");
    const ttlSeconds = Number(this.env.SIGNED_URL_TTL_SECONDS ?? "900");
    const expiresAt = new Date(Date.now() + ttlSeconds * 1000).toISOString();
    return {
      method,
      url: `${base}/worlds/${encodeURIComponent(worldId)}/storage/blob/${encodeURIComponent(storageKey)}`,
      headers: {},
      expiresAt
    };
  }
}
