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
import { HttpError } from "./http.ts";
import type { RequestContext, SharedWorldRepository } from "./repository.ts";
import type { StorageProvider } from "./storage.ts";
import { workersStorageUsageCache } from "./storage-usage-cache.ts";
import { StorageLinkDomainService } from "./storage/link-service.ts";
import type { AuthVerifier, BlobUrlSigner, ServiceContext } from "./service/context.ts";
import type { RealtimeService } from "./realtime/service.ts";
import * as members from "./service/members.ts";
import * as session from "./service/session.ts";
import * as snapshots from "./service/snapshots.ts";
import * as syncPlan from "./service/sync-plan.ts";
import * as worlds from "./service/worlds.ts";

export type { AuthVerifier, BlobUrlSigner } from "./service/context.ts";

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
    authVerifier: AuthVerifier,
    blobSigner: BlobUrlSigner,
    storageProvider: StorageProvider,
    env: Env,
    realtime: RealtimeService
  ) {
    this.svc = {
      repository,
      authVerifier,
      blobSigner,
      storageProvider,
      storageLinks: new StorageLinkDomainService(repository, env, storageProvider.provider),
      realtime,
      env,
      storageUsageCache: workersStorageUsageCache()
    };
    this.authDomain = new AuthDomainService(repository, authVerifier, env);
  }

  // --- auth ---

  async createChallenge(now = new Date()) {
    return this.authDomain.createChallenge(now);
  }

  async completeAuth(request: AuthCompleteRequest, now = new Date()) {
    return this.authDomain.completeAuth(request, now);
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

export class MinecraftSessionServerAuthVerifier implements AuthVerifier {
  constructor(
    private readonly endpoint: string,
    private readonly attemptTimeoutMs = 5_000
  ) {}

  async verifyJoin(playerName: string, serverId: string): Promise<{ playerUuid: string; playerName: string } | null> {
    const url = new URL(this.endpoint);
    url.searchParams.set("username", playerName);
    url.searchParams.set("serverId", serverId);

    let response: Response;
    try {
      response = await fetch(url, {
        headers: {
          accept: "application/json"
        },
        signal: AbortSignal.timeout(this.attemptTimeoutMs)
      });
    } catch (error) {
      console.warn("SharedWorld Mojang hasJoined request failed", { playerName, serverId, cause: String(error) });
      throw new HttpError(
        503,
        "identity_verification_unavailable",
        "Minecraft's identity service is unreachable right now. Please try again in a minute."
      );
    }

    if (response.status === 204 || response.status === 404) {
      return null;
    }
    if (!response.ok) {
      const bodyHead = (await response.text().catch(() => "")).slice(0, 200);
      console.warn("SharedWorld Mojang hasJoined returned an error status", {
        playerName,
        serverId,
        status: response.status,
        bodyHead
      });
      const error = new HttpError(503, "identity_verification_unavailable", unavailableMessageForStatus(response.status));
      error.upstreamStatus = response.status;
      if (response.status === 429) {
        error.retryAfterSeconds = clampRetryAfterSeconds(response.headers.get("retry-after"));
      }
      throw error;
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

/**
 * Shipped clients render these messages verbatim, so each upstream cause gets
 * an actionable text while the error code stays identity_verification_unavailable
 * (shipped retry handling and parity tests key on the code, never the text).
 */
function unavailableMessageForStatus(status: number): string {
  if (status === 429) {
    return "Minecraft's identity service is rate-limiting the SharedWorld server. Please wait a minute and try again.";
  }
  if (status === 403) {
    // Mojang's standing block on this egress: only the certificate flow
    // (0.2.1+) gets around it, so point stale clients at the update.
    return "Minecraft no longer accepts the sign-in method used by SharedWorld 0.2.0 and older. Please update SharedWorld to 0.2.1 or newer. If you are already updated, a mod that blocks chat signing may be hiding your Minecraft profile keys.";
  }
  return "Minecraft identity verification is unavailable.";
}

function clampRetryAfterSeconds(header: string | null): number {
  const seconds = header !== null && /^\d+$/.test(header.trim()) ? Number(header.trim()) : Number.NaN;
  if (Number.isNaN(seconds)) {
    return 10;
  }
  return Math.min(120, Math.max(10, seconds));
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
