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
  WorldSettings,
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

export type { UncleanShutdownWarning };

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

  /** Single-row cache of Mojang's player-certificate key set (see auth/services-keys.ts). */
  getMojangServicesKeys(): Promise<{ fetchedAt: string; keysJson: string } | null>;
  putMojangServicesKeys(fetchedAt: string, keysJson: string): Promise<void>;

  upsertUser(user: UserRecord): Promise<void>;
  createSession(session: SessionToken): Promise<void>;
  getSession(token: string): Promise<SessionToken | null>;
}

export interface WorldRepository {
  listWorldsForPlayer(playerUuid: string): Promise<WorldSummary[]>;
  hasActiveWorld(worldId: string): Promise<boolean>;
  countActiveWorlds(): Promise<number>;
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
  /** Replace the world's settings JSON and bump its revision; false when no active world row exists. */
  updateWorldSettings(worldId: string, settingsJson: string): Promise<boolean>;
  /** Lightweight settings read for the host heartbeat; null when the world does not exist. */
  getWorldSettings(worldId: string): Promise<{ settings: WorldSettings | null; settingsRevision: number } | null>;
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
  /** All of a player's storage accounts for a provider, most recently updated first. */
  findStorageAccountsByOwner(provider: StorageProviderType, ownerPlayerUuid: string): Promise<StorageAccountRecord[]>;
  upsertStorageObject(record: StorageObjectRecord): Promise<void>;
  getStorageObject(provider: StorageProviderType, storageAccountId: string, storageKey: string): Promise<StorageObjectRecord | null>;
  deleteStorageObject(provider: StorageProviderType, storageAccountId: string, storageKey: string): Promise<void>;
}

export interface MembershipRepository {
  createInvite(worldId: string, ctx: RequestContext, invite: InviteCode): Promise<InviteCode>;
  getInviteByCode(code: string): Promise<InviteCode | null>;
  revokeActiveInvites(worldId: string): Promise<string[]>;
  revokeSupersededInvites(worldId: string): Promise<void>;
  getActiveInvite(worldId: string, now: Date): Promise<InviteCode | null>;
  addMembership(membership: WorldMembership): Promise<void>;
  isWorldMember(worldId: string, playerUuid: string): Promise<boolean>;
  hasWorldMembership(worldId: string, playerUuid: string): Promise<boolean>;
  kickMember(worldId: string, removedPlayerUuid: string, removedAt: string): Promise<KickMemberResponse | null>;
  listMemberships(worldId: string): Promise<WorldMembership[]>;
  /** Set the command-permission flag on an active membership; false when no active row exists. */
  setMembershipCommandPermission(worldId: string, playerUuid: string, canUseCommands: boolean): Promise<boolean>;
}

export interface RuntimeRepository {
  getRuntimeRecord(worldId: string, now: Date): Promise<WorldRuntimeRecord | null>;
  /** Unfenced write for test seeding; session flows use the fenced methods below. */
  upsertRuntimeRecord(runtime: WorldRuntimeRecord): Promise<void>;
  /** Install a fresh assignment; false when a same-or-newer epoch already holds the row. */
  claimRuntimeAssignment(runtime: WorldRuntimeRecord): Promise<boolean>;
  /** Refresh the record fenced on its own epoch/token; false means authority was lost. */
  updateAuthorizedRuntime(runtime: WorldRuntimeRecord): Promise<boolean>;
  /** Retire the epoch high-water mark, then delete only the expected epoch/token row. */
  deleteRuntimeRecord(worldId: string, expected: { runtimeEpoch: number; runtimeToken: string | null }): Promise<boolean>;
  getLastRuntimeEpoch(worldId: string): Promise<number>;
  getUncleanShutdownWarning(worldId: string): Promise<UncleanShutdownWarning | null>;
  setUncleanShutdownWarning(worldId: string, warning: UncleanShutdownWarning): Promise<void>;
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
  getSnapshotGameVersions(worldId: string, snapshotId: string): Promise<{ dataVersion: number | null; minecraftVersion: string | null } | null>;
  listSnapshotDeltaBases(worldId: string): Promise<Array<{ snapshotId: string; baseSnapshotId: string }>>;
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
