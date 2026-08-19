import type {
  FinalizeSnapshotRequest,
  InviteCode,
  KickMemberResponse,
  PackChainStep,
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

import type { SnapshotManifestDocumentReader } from "./manifest-doc.ts";

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

export interface StorageUploadSessionRecord {
  uploadId: string;
  provider: StorageProviderType;
  storageAccountId: string;
  worldId: string;
  storageKey: string;
  sessionUrl: string;
  contentType: string;
  expectedSize: number;
  createdAt: string;
  confirmedAt: string | null;
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

export interface PendingBlobDeleteRecord {
  provider: StorageProviderType;
  storageAccountId: string;
  storageKey: string;
  attempts: number;
  enqueuedAt: string;
}

/**
 * Where a storage key could still be referenced from. Blobs live in one
 * storage account (worlds never change binding), so only that account's
 * worlds can reference them; `snapshotsCreatedSince` narrows the pack-directory
 * scan further to snapshots that did not exist when the key was last verified
 * unreferenced (see the GC retry sweep). Both bounds exist because the
 * pack-directory legs are `json_each` scans with no index behind them.
 */
export interface StorageReferenceScope {
  provider: StorageProviderType;
  storageAccountId: string | null;
  snapshotsCreatedSince?: string | null;
}

/**
 * Slack applied to `created_at` bounds on snapshots: the column is stamped
 * from the request's start time and the row lands at the end of finalize,
 * so a snapshot committed after some instant can carry a created_at up to
 * a couple of minutes before it. Read as "created no earlier than".
 */
export const SNAPSHOT_CREATED_AT_SLACK_MS = 15 * 60_000;

export type { UncleanShutdownWarning };

export interface RequestContext {
  playerUuid: string;
  playerName: string;
  requestOrigin?: string;
  /** Contents of the x-sharedworld-version request header (sent by 0.2.2+ clients). */
  clientVersion?: string | null;
  /**
   * Runs housekeeping AFTER the response is sent (Workers `waitUntil`).
   * Absent outside the worker runtime (tests, tools), where callers must
   * simply await the work inline. Deferred work has a bounded post-response
   * lifetime, so it must be safe to be cut off mid-way.
   */
  defer?: (task: Promise<unknown>) => void;
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
  /**
   * Membership facts for a session call in one query; null when no active
   * world exists (the caller's 404). Replaces the hasActiveWorld +
   * isWorldMember + hasWorldMembership triple on every runtime route.
   */
  sessionActorFacts(worldId: string, playerUuid: string): Promise<{ membershipActive: boolean; everMember: boolean } | null>;
  /** Cheap ETag inputs for GET /worlds — see D1 implementation for coverage notes. */
  worldsChangeFacts(playerUuid: string): Promise<unknown>;
  /** Cheap ETag inputs for GET /worlds/:id; null when the caller has no access. */
  worldChangeFacts(worldId: string, playerUuid: string, now: Date): Promise<unknown | null>;
  updateWorld(ctx: RequestContext, worldId: string, request: WorldUpdateRecord): Promise<WorldDetails>;
  /** Replace the world's settings JSON and bump its revision; false when no active world row exists. */
  updateWorldSettings(worldId: string, settingsJson: string): Promise<boolean>;
  /** Compare-and-set variant for host-reported settings: writes only when the stored revision still matches. */
  updateWorldSettingsIfRevision(worldId: string, settingsJson: string, expectedRevision: number): Promise<boolean>;
  /** Lightweight settings read for the host heartbeat; null when the world does not exist. */
  getWorldSettings(worldId: string): Promise<{ settings: WorldSettings | null; settingsRevision: number } | null>;
  deleteWorldForPlayer(ctx: RequestContext, worldId: string, now: Date): Promise<DeleteWorldResult>;
  /** Single-key form of filterReferencedStorageKeys. */
  isStorageKeyReferenced(storageKey: string, scope?: StorageReferenceScope | null): Promise<boolean>;
  /**
   * The subset of `storageKeys` some surviving row still points at (snapshot
   * files, legacy pack rows, pack directories + chain recipes, manifest
   * documents, world icons). One query per leg for the whole set. Production
   * callers pass a scope; unscoped calls scan every world's directories.
   */
  filterReferencedStorageKeys(storageKeys: readonly string[], scope?: StorageReferenceScope | null): Promise<Set<string>>;
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
  /** Which of the given storage keys have object rows — one batched query, not one per key. */
  listExistingStorageKeys(provider: StorageProviderType, storageAccountId: string, storageKeys: readonly string[]): Promise<Set<string>>;
  deleteStorageObject(provider: StorageProviderType, storageAccountId: string, storageKey: string): Promise<void>;
  createUploadSession(record: StorageUploadSessionRecord): Promise<void>;
  getUploadSession(uploadId: string): Promise<StorageUploadSessionRecord | null>;
  markUploadSessionConfirmed(uploadId: string, confirmedAt: string): Promise<void>;
  deleteUploadSession(uploadId: string): Promise<void>;
  /** Oldest unconfirmed sessions created before the cutoff, for the orphan sweep. */
  listUnconfirmedUploadSessionsBefore(provider: StorageProviderType, storageAccountId: string, createdBefore: string, limit: number): Promise<StorageUploadSessionRecord[]>;
  /** Bounded delete of confirmed sessions past their idempotent-retry window. */
  deleteConfirmedUploadSessionsBefore(provider: StorageProviderType, storageAccountId: string, confirmedBefore: string, limit: number): Promise<void>;
  /** 0028 GC retry queue: a provider delete that failed, to be retried by the bounded sweep. */
  enqueuePendingBlobDelete(provider: StorageProviderType, storageAccountId: string, storageKey: string, enqueuedAt: string): Promise<void>;
  /** Batch form of enqueuePendingBlobDelete (one D1 batch, duplicates ignored). */
  enqueuePendingBlobDeletes(provider: StorageProviderType, storageAccountId: string, storageKeys: readonly string[], enqueuedAt: string): Promise<void>;
  /** Oldest pending deletes for the account, up to limit. */
  listPendingBlobDeletes(provider: StorageProviderType, storageAccountId: string, limit: number): Promise<Array<{ storageKey: string; attempts: number; enqueuedAt: string }>>;
  /**
   * 0.4.5 cron drain: pending deletes across every account that are due at
   * `now` — never attempted, or past their attempt-based backoff (5 min
   * doubling per attempt, capped at a day). Fewest attempts first so a
   * stuck account cannot starve fresh work.
   */
  listDuePendingBlobDeletes(now: string, limit: number): Promise<PendingBlobDeleteRecord[]>;
  deletePendingBlobDelete(provider: StorageProviderType, storageAccountId: string, storageKey: string): Promise<void>;
  bumpPendingBlobDeleteAttempt(provider: StorageProviderType, storageAccountId: string, storageKey: string, attemptedAt: string): Promise<void>;
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

/**
 * 0.3.0: runtime truth lives in the per-world coordinator Durable Object.
 * D1 keeps only a single-writer display mirror — written by the coordinator,
 * read by summaries and legacy polling paths. Null fields leave the stored
 * column untouched.
 */
export interface RuntimeRepository {
  upsertRuntimeMirror(worldId: string, statusJson: string | null, roomPlayersJson: string | null): Promise<void>;
  getRuntimeMirror(worldId: string): Promise<{ statusJson: string | null; roomPlayersJson: string | null } | null>;
}

export interface SnapshotRepository {
  getLatestSnapshot(worldId: string): Promise<SnapshotManifest | null>;
  /** Latest snapshot id in one row — for beats that only compare ids. */
  getLatestSnapshotStamp(worldId: string): Promise<{ id: string } | null>;
  /** CAS claim of the world's retention slot; true = this caller runs retention. */
  claimRetentionSlot(worldId: string, now: Date, intervalMs: number): Promise<boolean>;
  getSnapshot(worldId: string, snapshotId: string): Promise<SnapshotManifest | null>;
  /**
   * Headers-only manifests (loose files + pack headers, EMPTY member lists;
   * uncached, never touches the 0027 manifest document). For consumers that
   * only read headers — upload planning and finalize validation — so the
   * write pipeline stays independent of document availability.
   */
  getLatestSnapshotHeaders(worldId: string): Promise<SnapshotManifest | null>;
  getSnapshotHeaders(worldId: string, snapshotId: string): Promise<SnapshotManifest | null>;
  /** Which of the given snapshot ids still exist for this world (one query). */
  existingSnapshotIds(worldId: string, snapshotIds: readonly string[]): Promise<Set<string>>;
  /** Headers for many snapshot ids of one world in a fixed number of queries; unknown ids are absent. */
  getSnapshotHeadersBatch(worldId: string, snapshotIds: readonly string[]): Promise<Map<string, SnapshotManifest>>;
  listSnapshotSummaries(worldId: string): Promise<WorldSnapshotSummary[]>;
  listSnapshotsForWorld(worldId: string): Promise<SnapshotRecord[]>;
  getSnapshotGameVersions(worldId: string, snapshotId: string): Promise<{ dataVersion: number | null; minecraftVersion: string | null } | null>;
  listSnapshotDeltaBases(worldId: string): Promise<Array<{ snapshotId: string; baseSnapshotId: string }>>;
  /**
   * options.manifestStorageKey (0027): the snapshot's pack member lists live
   * in the pointed-at manifest document — write zero member rows. Absent =
   * legacy row-based snapshot.
   */
  finalizeSnapshot(
    worldId: string,
    ctx: RequestContext,
    request: FinalizeSnapshotRequest,
    now: Date,
    options?: { manifestStorageKey?: string | null }
  ): Promise<SnapshotManifest>;
  deleteSnapshots(worldId: string, snapshotIds: string[]): Promise<SnapshotDeletionResult>;
  /** S1 lazy upgrade: merge chainSteps recipes into an existing snapshot's pack directory (directory-only, cache-safe). */
  stampSnapshotChainSteps(snapshotId: string, stepsByPackId: ReadonlyMap<string, PackChainStep[]>): Promise<void>;
  /** 0027: resolver for manifest documents; attached post-construction (provider is built over this repository). */
  attachManifestDocumentReader(reader: SnapshotManifestDocumentReader): void;
}

export interface SharedWorldRepository extends
  SessionRepository,
  WorldRepository,
  StorageRepository,
  MembershipRepository,
  RuntimeRepository,
  SnapshotRepository {}
