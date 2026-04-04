import type {
  CancelWaitingRequest,
  RefreshWaitingRequest,
  FinalizeSnapshotRequest,
  InviteCode,
  KickMemberResponse,
  PresenceHeartbeatRequest,
  SessionToken,
  SnapshotManifest,
  StorageLinkSession,
  StorageProviderType,
  UncleanShutdownWarning,
  StorageUsageSummary,
  UpdateWorldRequest,
  WorldDetails,
  WorldMembership,
  WorldSnapshotSummary,
  WorldSummary
} from "../../shared/src/index.ts";
import type { RuntimeWaiter, WorldRuntimeRecord } from "./runtime-protocol.ts";

export interface AuthChallengeRecord {
  serverId: string;
  expiresAt: string;
  usedAt: string | null;
}

export interface UserRecord {
  playerUuid: string;
  playerName: string;
  createdAt: string;
}

export interface SnapshotRecord {
  snapshotId: string;
  worldId: string;
  createdAt: string;
  createdByUuid: string;
}

export interface StorageAccountRecord {
  id: string;
  provider: StorageProviderType;
  ownerPlayerUuid: string;
  externalAccountId: string;
  email: string | null;
  displayName: string | null;
  accessToken: string | null;
  refreshToken: string | null;
  tokenExpiresAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface StorageLinkSessionRecord extends StorageLinkSession {
  playerUuid: string;
  storageAccountId: string | null;
  state: string;
  createdAt: string;
  completedAt: string | null;
}

export interface StorageObjectRecord {
  provider: StorageProviderType;
  storageAccountId: string;
  storageKey: string;
  objectId: string;
  contentType: string;
  size: number;
  createdAt: string;
  updatedAt: string;
}

export interface SnapshotDeletionResult {
  deletedSnapshotIds: string[];
  unreferencedStorageKeys: string[];
}

export interface DeleteWorldResult {
  worldDeleted: boolean;
  deletedCustomIconStorageKey: string | null;
}

export interface WorldStorageBinding {
  provider: StorageProviderType;
  storageAccountId: string | null;
}

export interface WorldUncleanShutdownWarningRecord extends UncleanShutdownWarning {
  runtimeEpoch: number;
}

export interface RequestContext {
  playerUuid: string;
  playerName: string;
  requestOrigin?: string;
}

export interface WorldUpdateRecord extends UpdateWorldRequest {
  customIconStorageKey?: string | null;
}

export interface SessionRepository {
  createChallenge(challenge: AuthChallengeRecord): Promise<void>;
  getChallenge(serverId: string): Promise<AuthChallengeRecord | null>;
  markChallengeUsed(serverId: string, usedAt: string): Promise<void>;

  upsertUser(user: UserRecord): Promise<void>;
  createSession(session: SessionToken): Promise<void>;
  getSession(token: string): Promise<SessionToken | null>;
}

export interface WorldRepository {
  listWorldsForPlayer(playerUuid: string): Promise<WorldSummary[]>;
  hasActiveWorld(worldId: string): Promise<boolean>;
  createWorld(
    ctx: RequestContext,
    name: string,
    slug: string,
    storage: WorldStorageBinding,
    motd?: string | null,
    customIconStorageKey?: string | null
  ): Promise<WorldDetails>;
  getWorldDetails(worldId: string, playerUuid: string): Promise<WorldDetails | null>;
  updateWorld(ctx: RequestContext, worldId: string, request: WorldUpdateRecord): Promise<WorldDetails>;
  deleteWorldForPlayer(ctx: RequestContext, worldId: string, now: Date): Promise<DeleteWorldResult>;
  isStorageKeyReferenced(storageKey: string): Promise<boolean>;
  getWorldStorageBinding(worldId: string): Promise<WorldStorageBinding | null>;
  getStorageUsage(worldId: string): Promise<StorageUsageSummary>;
}

export interface StorageRepository {
  createStorageLinkSession(session: StorageLinkSessionRecord): Promise<void>;
  getStorageLinkSession(sessionId: string): Promise<StorageLinkSessionRecord | null>;
  cancelStorageLinkSession(sessionId: string, completedAt: string): Promise<void>;
  cancelPendingStorageLinkSessions(playerUuid: string, provider: StorageProviderType, exceptSessionId: string, completedAt: string): Promise<void>;
  updateStorageLinkSession(
    sessionId: string,
    update: Partial<Pick<StorageLinkSessionRecord, "status" | "linkedAccountEmail" | "accountDisplayName" | "errorMessage" | "storageAccountId" | "completedAt">>
  ): Promise<void>;
  createOrUpdateStorageAccount(account: StorageAccountRecord): Promise<StorageAccountRecord>;
  getStorageAccount(accountId: string): Promise<StorageAccountRecord | null>;
  findStorageAccountByExternalId(provider: StorageProviderType, externalAccountId: string): Promise<StorageAccountRecord | null>;
  upsertStorageObject(record: StorageObjectRecord): Promise<void>;
  getStorageObject(provider: StorageProviderType, storageAccountId: string, storageKey: string): Promise<StorageObjectRecord | null>;
  deleteStorageObject(provider: StorageProviderType, storageAccountId: string, storageKey: string): Promise<void>;
}

export interface MembershipRepository {
  createInvite(worldId: string, ctx: RequestContext, invite: InviteCode): Promise<InviteCode>;
  getInviteByCode(code: string): Promise<InviteCode | null>;
  revokeActiveInvites(worldId: string, revokedAt: string): Promise<string[]>;
  getActiveInvite(worldId: string, now: Date): Promise<InviteCode | null>;
  addMembership(membership: WorldMembership): Promise<void>;
  isWorldMember(worldId: string, playerUuid: string): Promise<boolean>;
  hasWorldMembership(worldId: string, playerUuid: string): Promise<boolean>;
  kickMember(worldId: string, removedPlayerUuid: string, removedAt: string): Promise<KickMemberResponse | null>;
  listMemberships(worldId: string): Promise<WorldMembership[]>;
}

export interface RuntimeRepository {
  getRuntimeRecord(worldId: string, now: Date): Promise<WorldRuntimeRecord | null>;
  upsertRuntimeRecord(runtime: WorldRuntimeRecord): Promise<void>;
  deleteRuntimeRecord(worldId: string): Promise<void>;
  getLastRuntimeEpoch(worldId: string): Promise<number>;
  getUncleanShutdownWarning(worldId: string): Promise<WorldUncleanShutdownWarningRecord | null>;
  setUncleanShutdownWarning(worldId: string, warning: WorldUncleanShutdownWarningRecord): Promise<void>;
  clearUncleanShutdownWarning(worldId: string): Promise<void>;
  listActiveWaiters(worldId: string, now: Date): Promise<RuntimeWaiter[]>;
  upsertWaiterSession(worldId: string, ctx: RequestContext, waiterSessionId: string, now: Date): Promise<void>;
  refreshWaiterSession(worldId: string, ctx: RequestContext, request: RefreshWaitingRequest, now: Date): Promise<boolean>;
  cancelWaiterSession(worldId: string, ctx: RequestContext, request: CancelWaitingRequest): Promise<boolean>;
  clearWaitersForPlayer(worldId: string, playerUuid: string): Promise<void>;
  clearWaiters(worldId: string): Promise<void>;
  setPlayerPresence(worldId: string, ctx: RequestContext, request: PresenceHeartbeatRequest, now: Date): Promise<void>;
  clearWorldPresence(worldId: string): Promise<void>;
}

export interface SnapshotRepository {
  getLatestSnapshot(worldId: string): Promise<SnapshotManifest | null>;
  getSnapshot(worldId: string, snapshotId: string): Promise<SnapshotManifest | null>;
  listSnapshotSummaries(worldId: string): Promise<WorldSnapshotSummary[]>;
  listSnapshotsForWorld(worldId: string): Promise<SnapshotRecord[]>;
  finalizeSnapshot(worldId: string, ctx: RequestContext, request: FinalizeSnapshotRequest, now: Date): Promise<SnapshotManifest>;
  deleteSnapshots(worldId: string, snapshotIds: string[]): Promise<SnapshotDeletionResult>;
}

export interface SharedWorldRepository extends
  SessionRepository,
  WorldRepository,
  StorageRepository,
  MembershipRepository,
  RuntimeRepository,
  SnapshotRepository {}
