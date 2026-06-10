import type {
  AbandonFinalizationRequest,
  BeginFinalizationRequest,
  CompleteFinalizationRequest,
  FinalizeSnapshotRequest,
  HostStartupProgressRequest,
  ReleaseHostRequest,
  UploadPlanRequest,
  WorldRuntimeStatus
} from "../../../shared/src/index.ts";

import type { Env, R2Bucket } from "../../src/env.ts";
import { createSqliteRepository } from "./sqlite-d1.ts";
import type { RequestContext, SharedWorldRepository } from "../../src/repository.ts";
import { R2StorageProvider } from "../../src/storage.ts";
import { SharedWorldService, type AuthVerifier, type BlobUrlSigner } from "../../src/service.ts";
import type { StorageProvider } from "../../src/storage.ts";

type TestHostLeaseView = {
  worldId: string;
  hostUuid: string;
  hostPlayerName: string;
  status: "idle" | "hosting" | "finalizing" | "handoff";
  runtimePhase?: string | null;
  runtimeEpoch?: number | null;
  claimedAt: string;
  expiresAt: string;
  updatedAt: string;
  joinTarget: string | null;
  handoffCandidateUuid: string | null;
  startupDeadlineAt?: string | null;
  runtimeTokenIssuedAt?: string | null;
  lastProgressAt?: string | null;
  startupProgress?: unknown;
  revokedAt?: string | null;
};

type TestHostStatusView = {
  worldId: string;
  activeLease: TestHostLeaseView | null;
  nextHostUuid: string | null;
  nextHostPlayerName: string | null;
};

type TestClaimHostResponse = {
  result: "claimed" | "already-hosted" | "busy";
  lease: TestHostLeaseView | null;
};

type TestJoinResolution = {
  action: Awaited<ReturnType<SharedWorldService["enterSession"]>>["action"];
  world: Awaited<ReturnType<SharedWorldService["enterSession"]>>["world"];
  lease: TestHostLeaseView | null;
  latestManifest: Awaited<ReturnType<SharedWorldService["latestManifest"]>>;
};

type RuntimeAuthorizedRequest = {
  runtimeEpoch?: number | null;
  hostToken?: string | null;
};

function runtimeToLeaseView(runtime: WorldRuntimeStatus): TestHostLeaseView | null {
  if (runtime.phase === "idle" || runtime.hostUuid == null || runtime.hostPlayerName == null) {
    return null;
  }
  return {
    worldId: runtime.worldId,
    hostUuid: runtime.hostUuid,
    hostPlayerName: runtime.hostPlayerName,
    status: runtime.phase === "host-finalizing" ? "finalizing" : runtime.phase === "handoff-waiting" ? "handoff" : "hosting",
    runtimePhase: runtime.phase,
    runtimeEpoch: runtime.runtimeEpoch,
    claimedAt: runtime.runtimeTokenIssuedAt ?? runtime.updatedAt ?? new Date(0).toISOString(),
    expiresAt: runtime.startupDeadlineAt ?? runtime.updatedAt ?? new Date(0).toISOString(),
    updatedAt: runtime.updatedAt ?? new Date(0).toISOString(),
    joinTarget: runtime.joinTarget,
    handoffCandidateUuid: runtime.candidateUuid,
    startupDeadlineAt: runtime.startupDeadlineAt,
    runtimeTokenIssuedAt: runtime.runtimeTokenIssuedAt,
    lastProgressAt: runtime.lastProgressAt,
    startupProgress: runtime.startupProgress,
    revokedAt: runtime.revokedAt
  };
}

function runtimeToCandidateLeaseView(runtime: WorldRuntimeStatus): TestHostLeaseView | null {
  if ((runtime.phase !== "idle" && runtime.phase !== "handoff-waiting")
    || runtime.candidateUuid == null
    || runtime.candidatePlayerName == null) {
    return null;
  }
  return {
    worldId: runtime.worldId,
    hostUuid: runtime.candidateUuid,
    hostPlayerName: runtime.candidatePlayerName,
    status: "handoff",
    runtimePhase: "handoff-waiting",
    runtimeEpoch: runtime.runtimeEpoch,
    claimedAt: runtime.updatedAt ?? new Date(0).toISOString(),
    expiresAt: runtime.updatedAt ?? new Date(0).toISOString(),
    updatedAt: runtime.updatedAt ?? new Date(0).toISOString(),
    joinTarget: null,
    handoffCandidateUuid: runtime.candidateUuid,
    startupDeadlineAt: runtime.startupDeadlineAt,
    runtimeTokenIssuedAt: runtime.runtimeTokenIssuedAt,
    lastProgressAt: runtime.lastProgressAt,
    startupProgress: runtime.startupProgress,
    revokedAt: runtime.revokedAt
  };
}

function runtimeToTestHostStatusView(runtime: WorldRuntimeStatus): TestHostStatusView {
  return {
    worldId: runtime.worldId,
    activeLease: runtimeToLeaseView(runtime) ?? runtimeToCandidateLeaseView(runtime),
    nextHostUuid: runtime.candidateUuid,
    nextHostPlayerName: runtime.candidatePlayerName
  };
}

async function authorizeFromCurrentRuntime(
  repository: SharedWorldRepository,
  ctx: { playerUuid: string; playerName: string },
  worldId: string,
  request: RuntimeAuthorizedRequest,
  now: Date
): Promise<RuntimeAuthorizedRequest> {
  if (request.runtimeEpoch != null && request.runtimeEpoch >= 0 && request.hostToken != null) {
    return request;
  }
  const resolved = await repository.getRuntimeRecord(worldId, now);
  if (resolved == null || resolved.hostUuid !== ctx.playerUuid || resolved.runtimeToken == null) {
    return request;
  }
  return {
    ...request,
    runtimeEpoch: resolved.runtimeEpoch,
    hostToken: resolved.runtimeToken
  };
}

/**
 * Test-only convenience layer over the production SharedWorldService. It adds
 * shorthand flows (claim host, observe status as a lease-shaped view) and
 * auto-fills epoch/token authorization from the current runtime so protocol
 * tests stay readable. It never bypasses the production service logic.
 */
export class TestDriverSharedWorldService extends SharedWorldService {
  constructor(
    private readonly runtimeRepository: SharedWorldRepository,
    authVerifier: AuthVerifier,
    blobSigner: BlobUrlSigner,
    storageProviderOrEnv: StorageProvider | Env,
    maybeEnv?: Env
  ) {
    const [storageProvider, env] = resolveStorageProviderAndEnv(storageProviderOrEnv, maybeEnv);
    super(
      runtimeRepository,
      authVerifier,
      blobSigner,
      storageProvider,
      env
    );
  }

  async claimHost(
    ctx: RequestContext,
    worldId: string,
    request: { joinTarget?: string | null },
    now = new Date()
  ): Promise<TestClaimHostResponse> {
    const entered = await this.enterSession(ctx, worldId, {}, now);
    let runtime = entered.runtime;
    if (entered.action === "host" && entered.assignment != null && runtime.phase === "host-starting") {
      const current = await this.runtimeRepository.getRuntimeRecord(worldId, now);
      if (current != null && current.runtimeToken != null) {
        const extended = new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString();
        await this.runtimeRepository.upsertRuntimeRecord({
          ...current,
          phase: request.joinTarget == null ? current.phase : "host-live",
          joinTarget: request.joinTarget ?? current.joinTarget,
          expiresAt: extended,
          startupDeadlineAt: request.joinTarget == null ? extended : null,
          updatedAt: now.toISOString()
        });
        runtime = await this.runtimeStatus(ctx, worldId, now);
      }
    }
    if (entered.action === "host" && entered.assignment != null && runtime.hostUuid === ctx.playerUuid) {
      return { result: "claimed", lease: runtimeToLeaseView(runtime) };
    }
    if (entered.action === "connect") {
      return { result: "already-hosted", lease: runtimeToLeaseView(runtime) };
    }
    return { result: "busy", lease: runtimeToLeaseView(runtime) };
  }

  async activeHost(ctx: RequestContext, worldId: string): Promise<TestHostStatusView> {
    const runtime = await this.runtimeStatus(ctx, worldId, new Date());
    return runtimeToTestHostStatusView(runtime);
  }

  async resolveJoin(ctx: RequestContext, worldId: string): Promise<TestJoinResolution> {
    const entered = await this.enterSession(ctx, worldId, {});
    return {
      action: entered.action,
      world: entered.world,
      lease: runtimeToLeaseView(entered.runtime),
      latestManifest: entered.latestManifest
    };
  }

  async handoffReady(
    ctx: RequestContext,
    worldId: string,
    request: { waiting: boolean },
    now = new Date()
  ): Promise<TestHostStatusView> {
    if (request.waiting) {
      await this.runtimeRepository.upsertWaiterSession(worldId, ctx, `legacy_${ctx.playerUuid}`, now);
    } else {
      await this.runtimeRepository.clearWaitersForPlayer(worldId, ctx.playerUuid);
    }
    const runtime = await this.runtimeStatus(ctx, worldId, now);
    return runtimeToTestHostStatusView(runtime);
  }

  override async beginFinalization(ctx: RequestContext, worldId: string, request: BeginFinalizationRequest, now = new Date()) {
    return super.beginFinalization(
      ctx,
      worldId,
      await authorizeFromCurrentRuntime(this.runtimeRepository, ctx, worldId, request, now) as BeginFinalizationRequest,
      now
    );
  }

  override async completeFinalization(ctx: RequestContext, worldId: string, request: CompleteFinalizationRequest, now = new Date()) {
    return super.completeFinalization(
      ctx,
      worldId,
      await authorizeFromCurrentRuntime(this.runtimeRepository, ctx, worldId, request, now) as CompleteFinalizationRequest,
      now
    );
  }

  override async abandonFinalization(ctx: RequestContext, worldId: string, request: AbandonFinalizationRequest, now = new Date()) {
    return super.abandonFinalization(ctx, worldId, request, now);
  }

  override async releaseHost(ctx: RequestContext, worldId: string, request: ReleaseHostRequest, now = new Date()) {
    return super.releaseHost(
      ctx,
      worldId,
      await authorizeFromCurrentRuntime(this.runtimeRepository, ctx, worldId, request, now) as ReleaseHostRequest,
      now
    );
  }

  override async setHostStartupProgress(ctx: RequestContext, worldId: string, request: HostStartupProgressRequest, now = new Date()) {
    try {
      return await super.setHostStartupProgress(
        ctx,
        worldId,
        await authorizeFromCurrentRuntime(this.runtimeRepository, ctx, worldId, request, now) as HostStartupProgressRequest,
        now
      );
    } catch (error) {
      if ((request.runtimeEpoch == null || request.hostToken == null) && typeof error === "object" && error != null && "code" in error && error.code === "host_not_active") {
        return null;
      }
      throw error;
    }
  }

  override async prepareUploads(ctx: RequestContext, worldId: string, request: UploadPlanRequest) {
    return super.prepareUploads(
      ctx,
      worldId,
      await authorizeFromCurrentRuntime(this.runtimeRepository, ctx, worldId, request, new Date()) as UploadPlanRequest
    );
  }

  override async finalizeSnapshot(ctx: RequestContext, worldId: string, request: FinalizeSnapshotRequest, now = new Date()) {
    return super.finalizeSnapshot(
      ctx,
      worldId,
      await authorizeFromCurrentRuntime(this.runtimeRepository, ctx, worldId, request, now) as FinalizeSnapshotRequest,
      now
    );
  }
}

export const authVerifier: AuthVerifier = {
  async verifyJoin() {
    return {
      playerUuid: "player-owner",
      playerName: "Owner"
    };
  }
};

export function createBlobSigner() {
  const deleted: string[] = [];
  const signer: BlobUrlSigner = {
    async signUpload(_worldId, storageKey, _requestOrigin) {
      return { method: "PUT", url: `https://example.invalid/upload/${storageKey}`, headers: {}, expiresAt: new Date().toISOString() };
    },
    async signDownload(_worldId, storageKey, _requestOrigin) {
      return { method: "GET", url: `https://example.invalid/download/${storageKey}`, headers: {}, expiresAt: new Date().toISOString() };
    },
    async deleteBlob(storageKey) {
      deleted.push(storageKey);
    }
  };
  return { signer, deleted };
}

export function createTestService(
  repository: SharedWorldRepository = createSqliteRepository(),
  verifier: AuthVerifier = authVerifier,
  signer: BlobUrlSigner = createBlobSigner().signer,
  storageProviderOrEnv: StorageProvider | Env = { SESSION_TTL_HOURS: "24" },
  maybeEnv?: Env
) {
  return new TestDriverSharedWorldService(repository, verifier, signer, storageProviderOrEnv, maybeEnv);
}

function resolveStorageProviderAndEnv(storageProviderOrEnv: StorageProvider | Env, maybeEnv?: Env): [StorageProvider, Env] {
  if ("provider" in storageProviderOrEnv) {
    return [storageProviderOrEnv, maybeEnv ?? {}];
  }
  return [new R2StorageProvider(storageProviderOrEnv), storageProviderOrEnv];
}

export function service() {
  return createTestService();
}

export function createPng64Bytes() {
  const bytes = new Uint8Array(24);
  bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a], 0);
  bytes.set([0x00, 0x00, 0x00, 0x0d], 8);
  bytes.set([0x49, 0x48, 0x44, 0x52], 12);
  bytes.set([0x00, 0x00, 0x00, 0x40], 16);
  bytes.set([0x00, 0x00, 0x00, 0x40], 20);
  return bytes;
}

export function createBlobBucket(entries: Record<string, Uint8Array>): R2Bucket {
  return {
    async head(key) {
      return entries[key] ? { key, size: entries[key].byteLength } : null;
    },
    async get(key) {
      const value = entries[key];
      if (!value) {
        return null;
      }
      return {
        key,
        size: value.byteLength,
        body: null,
        httpMetadata: { contentType: "image/png" },
        async arrayBuffer() {
          return copyArrayBuffer(value);
        }
      };
    },
    async delete(key) {
      delete entries[key];
    },
    async put(key, value) {
      if (value instanceof Uint8Array) {
        entries[key] = value;
      }
    }
  };
}

function copyArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

export function googleDriveStorageProvider(): StorageProvider {
  return {
    provider: "google-drive",
    async exists() {
      return false;
    },
    async put() {
    },
    async get() {
      return null;
    },
    async delete() {
    },
    async quota() {
      return { usedBytes: null, totalBytes: null };
    }
  };
}

export function createStorageProviderSpy(
  provider: "google-drive" | "r2" = "google-drive",
  options: { failDeletesFor?: string[] } = {}
) {
  const deleted: string[] = [];
  const failDeletesFor = new Set(options.failDeletesFor ?? []);
  const storageProvider: StorageProvider = {
    provider,
    async exists() {
      return false;
    },
    async put() {
    },
    async get() {
      return null;
    },
    async delete(_binding, storageKey) {
      deleted.push(storageKey);
      if (failDeletesFor.has(storageKey)) {
        throw new Error(`delete failed for ${storageKey}`);
      }
    },
    async quota() {
      return { usedBytes: null, totalBytes: null };
    }
  };
  return { storageProvider, deleted };
}

export async function claimHostForTest(
  instance: TestDriverSharedWorldService,
  player: { playerUuid: string; playerName: string },
  worldId: string,
  now = new Date("2099-01-01T00:00:00.000Z")
) {
  await instance.claimHost(player, worldId, {}, now);
}
