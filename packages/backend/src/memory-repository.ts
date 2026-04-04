import {
  type CancelWaitingRequest,
  HANDOFF_WAITER_TIMEOUT_MS,
  PLAYER_PRESENCE_TIMEOUT_MS,
  WHOLE_GZIP_TRANSFER_MODE,
  type FinalizeSnapshotRequest,
  type InviteCode,
  type KickMemberResponse,
  type PresenceHeartbeatRequest,
  type RefreshWaitingRequest,
  type SessionToken,
  type SnapshotManifest,
  type StorageProviderType,
  type StorageUsageSummary,
  type WorldDetails,
  type WorldMembership,
  type WorldSnapshotSummary,
  type WorldSummary
} from "../../shared/src/index.ts";

import type {
  AuthChallengeRecord,
  DeleteWorldResult,
  RequestContext,
  SnapshotDeletionResult,
  SnapshotRecord,
  SharedWorldRepository,
  StorageAccountRecord,
  StorageLinkSessionRecord,
  StorageObjectRecord,
  UserRecord,
  WorldUpdateRecord,
  WorldUncleanShutdownWarningRecord
} from "./repository.ts";
import {
  choosePreferredCandidate,
  type RuntimeWaiter,
  resolveRuntimeTimeout,
  runtimePhaseToWorldStatus,
  timedOutUncleanShutdownWarning,
  type WorldRuntimeRecord
} from "./runtime-protocol.ts";
interface WorldRecord {
  id: string;
  slug: string;
  name: string;
  motd: string | null;
  customIconStorageKey: string | null;
  ownerUuid: string;
  storageProvider: StorageProviderType;
  storageAccountId: string | null;
  uncleanShutdownWarning: WorldUncleanShutdownWarningRecord | null;
  lastRuntimeEpoch: number;
  createdAt: string;
  deletedAt: string | null;
}

export class MemorySharedWorldRepository implements SharedWorldRepository {
  private challenges = new Map<string, AuthChallengeRecord>();
  private users = new Map<string, UserRecord>();
  private sessions = new Map<string, SessionToken>();
  private worlds = new Map<string, WorldRecord>();
  private memberships = new Map<string, WorldMembership[]>();
  private invites = new Map<string, InviteCode>();
  private runtimes = new Map<string, WorldRuntimeRecord>();
  private waiters = new Map<string, Map<string, { waiterSessionId: string; playerName: string; waiting: boolean; updatedAt: string }>>();
  private presence = new Map<string, Map<string, {
    playerName: string;
    present: boolean;
    guestSessionEpoch: number;
    presenceSequence: number;
    updatedAt: string;
  }>>();
  private snapshots = new Map<string, SnapshotManifest[]>();
  private storageLinkSessions = new Map<string, StorageLinkSessionRecord>();
  private storageAccounts = new Map<string, StorageAccountRecord>();
  private storageObjects = new Map<string, StorageObjectRecord>();

  async createChallenge(challenge: AuthChallengeRecord): Promise<void> {
    this.challenges.set(challenge.serverId, challenge);
  }

  async getChallenge(serverId: string): Promise<AuthChallengeRecord | null> {
    return this.challenges.get(serverId) ?? null;
  }

  async markChallengeUsed(serverId: string, usedAt: string): Promise<void> {
    const record = this.challenges.get(serverId);
    if (record) {
      record.usedAt = usedAt;
    }
  }

  async upsertUser(user: UserRecord): Promise<void> {
    this.users.set(user.playerUuid, user);
  }

  async createSession(session: SessionToken): Promise<void> {
    this.sessions.set(session.token, session);
  }

  async getSession(token: string): Promise<SessionToken | null> {
    return this.sessions.get(token) ?? null;
  }

  async listWorldsForPlayer(playerUuid: string): Promise<WorldSummary[]> {
    const summaries: WorldSummary[] = [];
    for (const memberships of this.memberships.values()) {
      const membership = memberships.find((entry) => entry.playerUuid === playerUuid && entry.deletedAt === null);
      if (!membership) {
        continue;
      }
      const world = this.worlds.get(membership.worldId);
      if (!world || world.deletedAt !== null) {
        continue;
      }
      const lifecycle = await this.summaryLifecycle(world.id, new Date());
      const latestSnapshot = await this.getLatestSnapshot(world.id);
      const onlinePlayers = this.onlinePlayers(world.id, new Date());
      summaries.push({
        id: world.id,
        slug: world.slug,
        name: world.name,
        ownerUuid: world.ownerUuid,
        motd: world.motd,
        customIconStorageKey: world.customIconStorageKey,
        customIconDownload: null,
        memberCount: memberships.filter((entry) => entry.deletedAt === null).length,
        status: lifecycle.status,
        lastSnapshotId: latestSnapshot?.snapshotId ?? null,
        lastSnapshotAt: latestSnapshot?.createdAt ?? null,
        activeHostUuid: lifecycle.activeHostUuid,
        activeHostPlayerName: lifecycle.activeHostPlayerName,
        activeJoinTarget: lifecycle.activeJoinTarget,
        onlinePlayerCount: onlinePlayers.length,
        onlinePlayerNames: onlinePlayers.map((entry) => entry.playerName),
        storageProvider: world.storageProvider,
        storageLinked: world.storageAccountId !== null,
        storageAccountEmail: world.storageAccountId ? (this.storageAccounts.get(world.storageAccountId)?.email ?? null) : null
      });
    }
    return summaries.sort((a, b) => a.name.localeCompare(b.name));
  }

  async hasActiveWorld(worldId: string): Promise<boolean> {
    const world = this.worlds.get(worldId);
    return world != null && world.deletedAt === null;
  }

  async createWorld(
    ctx: RequestContext,
    name: string,
    slug: string,
    storage: { provider: StorageProviderType; storageAccountId: string | null } = { provider: "google-drive", storageAccountId: null },
    motd: string | null = null,
    customIconStorageKey: string | null = null
  ): Promise<WorldDetails> {
    const id = `world_${crypto.randomUUID().replace(/-/g, "")}`;
    const createdAt = new Date().toISOString();
    const world: WorldRecord = {
      id,
      slug: `${slug}-${id.slice(Math.max(0, id.length - 8))}`,
      name,
      motd,
      customIconStorageKey,
      ownerUuid: ctx.playerUuid,
      storageProvider: storage.provider,
      storageAccountId: storage.storageAccountId,
      uncleanShutdownWarning: null,
      lastRuntimeEpoch: 0,
      createdAt,
      deletedAt: null
    };
    this.worlds.set(id, world);
    const membership: WorldMembership = {
      worldId: id,
      playerUuid: ctx.playerUuid,
      playerName: ctx.playerName,
      role: "owner",
      joinedAt: createdAt,
      deletedAt: null
    };
    this.memberships.set(id, [membership]);
    return this.makeWorldDetails(world, membership);
  }

  async getWorldDetails(worldId: string, playerUuid: string): Promise<WorldDetails | null> {
    const world = this.worlds.get(worldId);
    if (!world) {
      return null;
    }
    if (world.deletedAt !== null) {
      return null;
    }
    const memberships = this.memberships.get(worldId) ?? [];
    const membership = memberships.find((entry) => entry.playerUuid === playerUuid && entry.deletedAt === null);
    if (!membership) {
      return null;
    }
    return this.makeWorldDetails(world, membership);
  }

  async updateWorld(ctx: RequestContext, worldId: string, request: WorldUpdateRecord): Promise<WorldDetails> {
    const world = this.worlds.get(worldId);
    if (!world || world.deletedAt !== null || world.ownerUuid !== ctx.playerUuid) {
      throw new Error("World update failed.");
    }
    world.name = request.name;
    world.motd = joinMotdLines(request.motdLine1 ?? null, request.motdLine2 ?? null);
    if (request.clearCustomIcon) {
      world.customIconStorageKey = null;
    } else if (request.customIconStorageKey !== undefined) {
      world.customIconStorageKey = request.customIconStorageKey;
    }
    const details = await this.getWorldDetails(worldId, ctx.playerUuid);
    if (!details) {
      throw new Error("World update failed.");
    }
    return details;
  }

  async deleteWorldForPlayer(ctx: RequestContext, worldId: string, now: Date): Promise<DeleteWorldResult> {
    const world = this.worlds.get(worldId);
    if (!world || world.deletedAt !== null) {
      return { worldDeleted: false, deletedCustomIconStorageKey: null };
    }

    const memberships = this.memberships.get(worldId) ?? [];
    const current = memberships.find((entry) => entry.playerUuid === ctx.playerUuid && entry.deletedAt === null);
    if (!current) {
      return { worldDeleted: false, deletedCustomIconStorageKey: null };
    }
    if (world.ownerUuid === ctx.playerUuid) {
      const deletedAt = now.toISOString();
      for (const membership of memberships) {
        if (membership.deletedAt === null) {
          membership.deletedAt = deletedAt;
        }
      }
      world.deletedAt = deletedAt;
      this.deleteInvitesForWorld(worldId);
      this.runtimes.delete(worldId);
      this.waiters.delete(worldId);
      this.presence.delete(worldId);
      return { worldDeleted: true, deletedCustomIconStorageKey: world.customIconStorageKey };
    }

    current.deletedAt = now.toISOString();

    const waiters = this.waiters.get(worldId);
    waiters?.delete(ctx.playerUuid);
    this.presence.get(worldId)?.delete(ctx.playerUuid);
    const runtime = this.runtimes.get(worldId);
    if (runtime?.hostUuid === ctx.playerUuid) {
      this.runtimes.delete(worldId);
    }

    const remaining = memberships
      .filter((entry) => entry.deletedAt === null)
      .sort((a, b) => a.joinedAt.localeCompare(b.joinedAt) || a.playerUuid.localeCompare(b.playerUuid));

    if (remaining.length === 0) {
      world.deletedAt = now.toISOString();
      this.deleteInvitesForWorld(worldId);
      this.runtimes.delete(worldId);
      this.waiters.delete(worldId);
      this.presence.delete(worldId);
      return { worldDeleted: true, deletedCustomIconStorageKey: world.customIconStorageKey };
    }

    return { worldDeleted: false, deletedCustomIconStorageKey: null };
  }

  async isStorageKeyReferenced(storageKey: string): Promise<boolean> {
    if ([...this.worlds.values()].some((world) => world.deletedAt === null && world.customIconStorageKey === storageKey)) {
      return true;
    }
    for (const manifests of this.snapshots.values()) {
      for (const manifest of manifests) {
        if (manifest.files.some((file) => file.storageKey === storageKey) || manifest.packs.some((pack) => pack.storageKey === storageKey)) {
          return true;
        }
      }
    }
    return false;
  }

  async getWorldStorageBinding(worldId: string) {
    const world = this.worlds.get(worldId);
    if (!world || world.deletedAt !== null) {
      return null;
    }
    return {
      provider: world.storageProvider,
      storageAccountId: world.storageAccountId
    };
  }

  async getStorageUsage(worldId: string): Promise<StorageUsageSummary> {
    const world = this.worlds.get(worldId);
    if (!world) {
      throw new Error(`Unknown world ${worldId}`);
    }
    const snapshots = this.snapshots.get(worldId) ?? [];
    const referencedKeys = new Set<string>();
    for (const manifest of snapshots) {
      for (const file of manifest.files) {
        referencedKeys.add(file.storageKey);
      }
      for (const pack of manifest.packs) {
        referencedKeys.add(pack.storageKey);
      }
    }
    if (world.customIconStorageKey) {
      referencedKeys.add(world.customIconStorageKey);
    }
    let usedBytes = 0;
    if (world.storageAccountId) {
      for (const storageKey of referencedKeys) {
        const record = this.storageObjects.get(storageObjectKey(world.storageProvider, world.storageAccountId, storageKey));
        if (record) {
          usedBytes += record.size;
        }
      }
    }
    const account = world.storageAccountId ? this.storageAccounts.get(world.storageAccountId) ?? null : null;
    return {
      provider: world.storageProvider,
      linked: world.storageAccountId !== null,
      usedBytes,
      quotaUsedBytes: account ? usedBytes : null,
      quotaTotalBytes: account ? 15 * 1024 * 1024 * 1024 : null,
      accountEmail: account?.email ?? null
    };
  }

  async createStorageLinkSession(session: StorageLinkSessionRecord): Promise<void> {
    this.storageLinkSessions.set(session.id, { ...session });
  }

  async getStorageLinkSession(sessionId: string): Promise<StorageLinkSessionRecord | null> {
    return this.storageLinkSessions.get(sessionId) ?? null;
  }

  async cancelStorageLinkSession(sessionId: string, completedAt: string): Promise<void> {
    const session = this.storageLinkSessions.get(sessionId);
    if (!session || session.status !== "pending") {
      return;
    }
    session.status = "cancelled";
    session.errorMessage = null;
    session.completedAt = completedAt;
  }

  async cancelPendingStorageLinkSessions(playerUuid: string, provider: StorageProviderType, exceptSessionId: string, completedAt: string): Promise<void> {
    for (const session of this.storageLinkSessions.values()) {
      if (session.id === exceptSessionId || session.playerUuid !== playerUuid || session.provider !== provider || session.status !== "pending") {
        continue;
      }
      session.status = "cancelled";
      session.errorMessage = null;
      session.completedAt = completedAt;
    }
  }

  async updateStorageLinkSession(sessionId: string, update: Partial<Pick<StorageLinkSessionRecord, "status" | "linkedAccountEmail" | "accountDisplayName" | "errorMessage" | "storageAccountId" | "completedAt">>): Promise<void> {
    const session = this.storageLinkSessions.get(sessionId);
    if (!session) {
      return;
    }
    Object.assign(session, update);
  }

  async createOrUpdateStorageAccount(account: StorageAccountRecord): Promise<StorageAccountRecord> {
    this.storageAccounts.set(account.id, { ...account });
    return this.storageAccounts.get(account.id)!;
  }

  async getStorageAccount(accountId: string): Promise<StorageAccountRecord | null> {
    return this.storageAccounts.get(accountId) ?? null;
  }

  async findStorageAccountByExternalId(provider: StorageProviderType, externalAccountId: string): Promise<StorageAccountRecord | null> {
    for (const account of this.storageAccounts.values()) {
      if (account.provider === provider && account.externalAccountId === externalAccountId) {
        return account;
      }
    }
    return null;
  }

  async upsertStorageObject(record: StorageObjectRecord): Promise<void> {
    this.storageObjects.set(storageObjectKey(record.provider, record.storageAccountId, record.storageKey), { ...record });
  }

  async getStorageObject(provider: StorageProviderType, storageAccountId: string, storageKey: string): Promise<StorageObjectRecord | null> {
    return this.storageObjects.get(storageObjectKey(provider, storageAccountId, storageKey)) ?? null;
  }

  async deleteStorageObject(provider: StorageProviderType, storageAccountId: string, storageKey: string): Promise<void> {
    this.storageObjects.delete(storageObjectKey(provider, storageAccountId, storageKey));
  }

  async createInvite(worldId: string, ctx: RequestContext, invite: InviteCode): Promise<InviteCode> {
    this.invites.set(invite.id, invite);
    return invite;
  }

  async getInviteByCode(code: string): Promise<InviteCode | null> {
    for (const invite of this.invites.values()) {
      if (invite.code === code) {
        return invite;
      }
    }
    return null;
  }

  async revokeActiveInvites(worldId: string): Promise<string[]> {
    const revoked: string[] = [];
    for (const invite of this.invites.values()) {
      if (invite.worldId === worldId && invite.status === "active") {
        invite.status = "revoked";
        revoked.push(invite.id);
      }
    }
    return revoked;
  }

  async getActiveInvite(worldId: string, now: Date): Promise<InviteCode | null> {
    let active: InviteCode | null = null;
    for (const invite of this.invites.values()) {
      if (invite.worldId !== worldId || invite.status !== "active") {
        continue;
      }
      if (new Date(invite.expiresAt).getTime() < now.getTime()) {
        invite.status = "expired";
        continue;
      }
      if (!active || invite.createdAt > active.createdAt) {
        active = invite;
      }
    }
    return active;
  }

  async addMembership(membership: WorldMembership): Promise<void> {
    const memberships = this.memberships.get(membership.worldId) ?? [];
    const existing = memberships.find((entry) => entry.playerUuid === membership.playerUuid);
    if (existing) {
      existing.deletedAt = null;
      existing.playerName = membership.playerName;
      return;
    }
    memberships.push(membership);
    this.memberships.set(membership.worldId, memberships);
  }

  async isWorldMember(worldId: string, playerUuid: string): Promise<boolean> {
    return (this.memberships.get(worldId) ?? []).some(
      (entry) => entry.playerUuid === playerUuid && entry.deletedAt === null
    );
  }

  async hasWorldMembership(worldId: string, playerUuid: string): Promise<boolean> {
    return (this.memberships.get(worldId) ?? []).some(
      (entry) => entry.playerUuid === playerUuid
    );
  }

  async kickMember(worldId: string, removedPlayerUuid: string, removedAt: string): Promise<KickMemberResponse | null> {
    const membership = (this.memberships.get(worldId) ?? []).find((entry) => entry.playerUuid === removedPlayerUuid && entry.deletedAt === null);
    if (!membership) {
      return null;
    }
    membership.deletedAt = removedAt;
    this.waiters.get(worldId)?.delete(removedPlayerUuid);
    this.presence.get(worldId)?.delete(removedPlayerUuid);
    const runtime = this.runtimes.get(worldId);
    if (runtime?.hostUuid === removedPlayerUuid) {
      runtime.revokedAt = removedAt;
    }
    return {
      worldId,
      removedPlayerUuid
    };
  }

  async listMemberships(worldId: string): Promise<WorldMembership[]> {
    return (this.memberships.get(worldId) ?? [])
      .filter((entry) => entry.deletedAt === null)
      .slice()
      .sort((left, right) => left.joinedAt.localeCompare(right.joinedAt) || left.playerUuid.localeCompare(right.playerUuid));
  }

  async getRuntimeRecord(worldId: string, _now: Date): Promise<WorldRuntimeRecord | null> {
    return this.runtimes.get(worldId) ?? null;
  }

  async upsertRuntimeRecord(runtime: WorldRuntimeRecord): Promise<void> {
    this.runtimes.set(runtime.worldId, { ...runtime });
  }

  async deleteRuntimeRecord(worldId: string): Promise<void> {
    const runtime = this.runtimes.get(worldId);
    const world = this.worlds.get(worldId);
    if (runtime != null && world != null) {
      world.lastRuntimeEpoch = Math.max(world.lastRuntimeEpoch, runtime.runtimeEpoch);
    }
    this.runtimes.delete(worldId);
  }

  async getLastRuntimeEpoch(worldId: string): Promise<number> {
    return this.worlds.get(worldId)?.lastRuntimeEpoch ?? 0;
  }

  async getUncleanShutdownWarning(worldId: string): Promise<WorldUncleanShutdownWarningRecord | null> {
    return this.worlds.get(worldId)?.uncleanShutdownWarning ?? null;
  }

  async setUncleanShutdownWarning(worldId: string, warning: WorldUncleanShutdownWarningRecord): Promise<void> {
    const world = this.worlds.get(worldId);
    if (!world) {
      throw new Error(`Unknown world ${worldId}`);
    }
    world.uncleanShutdownWarning = { ...warning };
  }

  async clearUncleanShutdownWarning(worldId: string): Promise<void> {
    const world = this.worlds.get(worldId);
    if (world) {
      world.uncleanShutdownWarning = null;
    }
  }

  async listActiveWaiters(worldId: string, now: Date): Promise<RuntimeWaiter[]> {
    this.pruneStaleWaiters(worldId, now);
    const waiters = this.waiters.get(worldId);
    if (!waiters) {
      return [];
    }
    return [...waiters.entries()].map(([playerUuid, waiter]) => ({
      playerUuid,
      waiterSessionId: waiter.waiterSessionId,
      playerName: waiter.playerName,
      waiting: waiter.waiting,
      updatedAt: waiter.updatedAt
    }));
  }

  async upsertWaiterSession(worldId: string, ctx: RequestContext, waiterSessionId: string, now: Date): Promise<void> {
    const entries = this.waiters.get(worldId) ?? new Map();
    entries.set(ctx.playerUuid, {
      waiterSessionId,
      playerName: ctx.playerName,
      waiting: true,
      updatedAt: now.toISOString()
    });
    this.waiters.set(worldId, entries);
  }

  async refreshWaiterSession(worldId: string, ctx: RequestContext, request: RefreshWaitingRequest, now: Date): Promise<boolean> {
    const current = this.waiters.get(worldId)?.get(ctx.playerUuid);
    if (!current || current.waiterSessionId !== request.waiterSessionId) {
      return false;
    }
    current.playerName = ctx.playerName;
    current.waiting = true;
    current.updatedAt = now.toISOString();
    return true;
  }

  async cancelWaiterSession(worldId: string, ctx: RequestContext, request: CancelWaitingRequest): Promise<boolean> {
    const current = this.waiters.get(worldId)?.get(ctx.playerUuid);
    if (!current || current.waiterSessionId !== request.waiterSessionId) {
      return false;
    }
    this.waiters.get(worldId)?.delete(ctx.playerUuid);
    return true;
  }

  async clearWaitersForPlayer(worldId: string, playerUuid: string): Promise<void> {
    this.waiters.get(worldId)?.delete(playerUuid);
  }

  async clearWaiters(worldId: string): Promise<void> {
    this.waiters.delete(worldId);
  }

  async setHandoffWaiting(worldId: string, ctx: RequestContext, waiting: boolean, now: Date): Promise<void> {
    if (!waiting) {
      await this.clearWaitersForPlayer(worldId, ctx.playerUuid);
      return;
    }
    await this.upsertWaiterSession(worldId, ctx, `legacy_${ctx.playerUuid}`, now);
  }

  async chooseNextHost(worldId: string, now = new Date()): Promise<{ playerUuid: string; playerName: string } | null> {
    const memberships = await this.listMemberships(worldId);
    const waiters = await this.listActiveWaiters(worldId, now);
    const candidate = choosePreferredCandidate(waiters.filter((waiter) => waiter.waiting), memberships);
    return candidate == null
      ? null
      : {
          playerUuid: candidate.playerUuid,
          playerName: candidate.playerName
        };
  }

  async setPlayerPresence(worldId: string, ctx: RequestContext, request: PresenceHeartbeatRequest, now: Date): Promise<void> {
    const entries = this.presence.get(worldId) ?? new Map<string, {
      playerName: string;
      present: boolean;
      guestSessionEpoch: number;
      presenceSequence: number;
      updatedAt: string;
    }>();
    const current = entries.get(ctx.playerUuid);
    if (current != null) {
      const staleSession = request.guestSessionEpoch < current.guestSessionEpoch;
      const staleSequence = request.guestSessionEpoch === current.guestSessionEpoch
        && request.presenceSequence < current.presenceSequence;
      if (staleSession || staleSequence) {
        return;
      }
    }

    entries.set(ctx.playerUuid, {
      playerName: ctx.playerName,
      present: request.present,
      guestSessionEpoch: request.guestSessionEpoch,
      presenceSequence: request.presenceSequence,
      updatedAt: now.toISOString()
    });
    this.presence.set(worldId, entries);
  }

  async clearWorldPresence(worldId: string): Promise<void> {
    this.presence.delete(worldId);
  }


  async getLatestSnapshot(worldId: string): Promise<SnapshotManifest | null> {
    const snapshots = this.snapshots.get(worldId) ?? [];
    return snapshots[snapshots.length - 1] ?? null;
  }

  async getSnapshot(worldId: string, snapshotId: string): Promise<SnapshotManifest | null> {
    return (this.snapshots.get(worldId) ?? []).find((snapshot) => snapshot.snapshotId === snapshotId) ?? null;
  }

  async listSnapshotSummaries(worldId: string): Promise<WorldSnapshotSummary[]> {
    const latestSnapshotId = (this.snapshots.get(worldId) ?? []).at(-1)?.snapshotId ?? null;
    const world = this.worlds.get(worldId);
    if (!world) {
      return [];
    }
    return [...(this.snapshots.get(worldId) ?? [])]
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map((snapshot) => {
        const referencedKeys = new Map<string, number>();
        for (const file of snapshot.files) {
          referencedKeys.set(file.storageKey, file.compressedSize);
        }
        for (const pack of snapshot.packs) {
          if (!referencedKeys.has(pack.storageKey)) {
            referencedKeys.set(pack.storageKey, 0);
          }
        }
        let totalCompressedSize = 0;
        for (const [storageKey, fallbackSize] of referencedKeys.entries()) {
          const record = world.storageAccountId == null
            ? null
            : this.storageObjects.get(storageObjectKey(world.storageProvider, world.storageAccountId, storageKey));
          totalCompressedSize += record?.size ?? fallbackSize;
        }
        return {
          snapshotId: snapshot.snapshotId,
          createdAt: snapshot.createdAt,
          createdByUuid: snapshot.createdByUuid,
          fileCount: snapshot.files.length + snapshot.packs.reduce((sum, pack) => sum + pack.files.length, 0),
          totalSize: snapshot.files.reduce((sum, file) => sum + file.size, 0) + snapshot.packs.flatMap((pack) => pack.files).reduce((sum, file) => sum + file.size, 0),
          totalCompressedSize,
          isLatest: snapshot.snapshotId === latestSnapshotId
        };
      });
  }

  async listSnapshotsForWorld(worldId: string): Promise<SnapshotRecord[]> {
    const snapshots = this.snapshots.get(worldId) ?? [];
    return [...snapshots].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map((snapshot) => ({
      snapshotId: snapshot.snapshotId,
      worldId: snapshot.worldId,
      createdAt: snapshot.createdAt,
      createdByUuid: snapshot.createdByUuid
    }));
  }

  async finalizeSnapshot(worldId: string, ctx: RequestContext, request: FinalizeSnapshotRequest, now: Date): Promise<SnapshotManifest> {
    const snapshot: SnapshotManifest = {
      worldId,
      snapshotId: `snapshot_${crypto.randomUUID().replace(/-/g, "")}`,
      createdAt: now.toISOString(),
      createdByUuid: ctx.playerUuid,
      files: request.files.map((file) => ({
        ...file,
        transferMode: file.transferMode ?? WHOLE_GZIP_TRANSFER_MODE
      })),
      packs: (request.packs ?? []).map((pack) => ({
        ...pack,
        files: [...pack.files]
      }))
    };
    const snapshots = this.snapshots.get(worldId) ?? [];
    snapshots.push(snapshot);
    this.snapshots.set(worldId, snapshots);
    return snapshot;
  }

  async deleteSnapshots(worldId: string, snapshotIds: string[]): Promise<SnapshotDeletionResult> {
    if (snapshotIds.length === 0) {
      return {
        deletedSnapshotIds: [],
        unreferencedStorageKeys: []
      };
    }

    const existing = this.snapshots.get(worldId) ?? [];
    const deleteSet = new Set(snapshotIds);
    const removed = existing.filter((snapshot) => deleteSet.has(snapshot.snapshotId));
    const kept = existing.filter((snapshot) => !deleteSet.has(snapshot.snapshotId));
    this.snapshots.set(worldId, kept);

    const candidateStorageKeys = new Set(
      removed.flatMap((snapshot) => [
        ...snapshot.files.map((file) => file.storageKey),
        ...snapshot.packs.map((pack) => pack.storageKey)
      ])
    );
    const stillReferenced = new Set<string>();
    for (const snapshots of this.snapshots.values()) {
      for (const snapshot of snapshots) {
        for (const file of snapshot.files) {
          if (candidateStorageKeys.has(file.storageKey)) {
            stillReferenced.add(file.storageKey);
          }
        }
        for (const pack of snapshot.packs) {
          if (candidateStorageKeys.has(pack.storageKey)) {
            stillReferenced.add(pack.storageKey);
          }
        }
      }
    }

    return {
      deletedSnapshotIds: removed.map((snapshot) => snapshot.snapshotId),
      unreferencedStorageKeys: [...candidateStorageKeys].filter((key) => !stillReferenced.has(key)).sort()
    };
  }

  private async makeWorldDetails(world: WorldRecord, membership: WorldMembership): Promise<WorldDetails> {
    const memberships = this.memberships.get(world.id) ?? [];
    const lifecycle = await this.summaryLifecycle(world.id, new Date());
    const latestSnapshot = (this.snapshots.get(world.id) ?? []).at(-1) ?? null;
    const onlinePlayers = this.onlinePlayers(world.id, new Date());
    return {
      id: world.id,
      slug: world.slug,
      name: world.name,
      ownerUuid: world.ownerUuid,
      motd: world.motd,
      customIconStorageKey: world.customIconStorageKey,
      customIconDownload: null,
      memberCount: memberships.filter((entry) => entry.deletedAt === null).length,
      status: lifecycle.status,
      lastSnapshotId: latestSnapshot?.snapshotId ?? null,
      lastSnapshotAt: latestSnapshot?.createdAt ?? null,
      activeHostUuid: lifecycle.activeHostUuid,
      activeHostPlayerName: lifecycle.activeHostPlayerName,
      activeJoinTarget: lifecycle.activeJoinTarget,
      onlinePlayerCount: onlinePlayers.length,
      onlinePlayerNames: onlinePlayers.map((entry) => entry.playerName),
      storageProvider: world.storageProvider,
      storageLinked: world.storageAccountId !== null,
      storageAccountEmail: world.storageAccountId ? (this.storageAccounts.get(world.storageAccountId)?.email ?? null) : null,
      membership,
      memberships: memberships.filter((entry) => entry.deletedAt === null),
      storageUsage: {
        provider: world.storageProvider,
        linked: world.storageAccountId !== null,
        usedBytes: (latestSnapshot?.files.reduce((sum, file) => sum + file.compressedSize, 0) ?? 0)
          + (latestSnapshot?.packs.reduce((sum, pack) => sum + pack.size, 0) ?? 0),
        quotaUsedBytes: world.storageAccountId
          ? (latestSnapshot?.files.reduce((sum, file) => sum + file.compressedSize, 0) ?? 0)
            + (latestSnapshot?.packs.reduce((sum, pack) => sum + pack.size, 0) ?? 0)
          : null,
        quotaTotalBytes: world.storageAccountId ? 15 * 1024 * 1024 * 1024 : null,
        accountEmail: world.storageAccountId ? (this.storageAccounts.get(world.storageAccountId)?.email ?? null) : null
      },
      activeInviteCode: null
    };
  }

  private getValidRuntime(worldId: string, now: Date): WorldRuntimeRecord | null {
    const runtime = this.runtimes.get(worldId) ?? null;
    if (!runtime) {
      return null;
    }
    const warning = timedOutUncleanShutdownWarning(runtime, now);
    if (warning != null) {
      const world = this.worlds.get(worldId);
      if (world != null) {
        world.uncleanShutdownWarning = {
          ...warning,
          runtimeEpoch: runtime.runtimeEpoch
        };
      }
      this.deleteExpiredRuntime(worldId, runtime);
      this.waiters.delete(worldId);
      return null;
    }
    if (resolveRuntimeTimeout(runtime, null, now) == null) {
      this.deleteExpiredRuntime(worldId, runtime);
      return null;
    }
    return runtime;
  }

  private deleteExpiredRuntime(worldId: string, runtime: WorldRuntimeRecord): void {
    const world = this.worlds.get(worldId);
    if (world != null) {
      world.lastRuntimeEpoch = Math.max(world.lastRuntimeEpoch, runtime.runtimeEpoch);
    }
    this.runtimes.delete(worldId);
  }

  private async summaryLifecycle(worldId: string, now: Date): Promise<{
    status: WorldSummary["status"];
    activeHostUuid: string | null;
    activeHostPlayerName: string | null;
    activeJoinTarget: string | null;
  }> {
    const runtime = this.getValidRuntime(worldId, now);
    if (runtime != null) {
      return {
        status: runtimePhaseToWorldStatus(runtime.phase),
        activeHostUuid: runtime.hostUuid,
        activeHostPlayerName: runtime.hostPlayerName,
        activeJoinTarget: runtime.joinTarget
      };
    }
    const candidate = this.findPreferredWaitingHost(worldId);
    return {
      status: candidate == null ? "idle" : "handoff",
      activeHostUuid: null,
      activeHostPlayerName: null,
      activeJoinTarget: null
    };
  }

  private findPreferredWaitingHost(worldId: string): { playerUuid: string; playerName: string } | null {
    const memberships = (this.memberships.get(worldId) ?? []).filter((entry) => entry.deletedAt === null);
    const waiting = this.waiters.get(worldId);
    const candidates = memberships.filter((entry) => waiting?.get(entry.playerUuid)?.waiting);
    if (candidates.length === 0) {
      return null;
    }

    candidates.sort((a, b) => {
      const ownerScore = Number(b.role === "owner") - Number(a.role === "owner");
      if (ownerScore !== 0) {
        return ownerScore;
      }
      if (a.joinedAt !== b.joinedAt) {
        return a.joinedAt.localeCompare(b.joinedAt);
      }
      return a.playerUuid.localeCompare(b.playerUuid);
    });

    return {
      playerUuid: candidates[0].playerUuid,
      playerName: candidates[0].playerName
    };
  }

  private pruneStaleWaiters(worldId: string, now: Date): void {
    const entries = this.waiters.get(worldId);
    if (!entries) {
      return;
    }
    const cutoff = now.getTime() - HANDOFF_WAITER_TIMEOUT_MS;
    for (const [playerUuid, entry] of entries.entries()) {
      if (new Date(entry.updatedAt).getTime() < cutoff) {
        entries.delete(playerUuid);
      }
    }
    if (entries.size === 0) {
      this.waiters.delete(worldId);
    }
  }

  private findWaitingCandidate(worldId: string, playerUuid: string): { playerUuid: string; playerName: string } | null {
    const waiting = this.waiters.get(worldId)?.get(playerUuid);
    const membership = (this.memberships.get(worldId) ?? []).find((entry) => entry.playerUuid === playerUuid && entry.deletedAt === null);
    if (!waiting?.waiting || !membership) {
      return null;
    }
    return {
      playerUuid,
      playerName: membership.playerName
    };
  }

  private onlinePlayers(worldId: string, now: Date): Array<{ playerUuid: string; playerName: string }> {
    const players = new Map<string, string>();
    const runtime = this.getValidRuntime(worldId, now);
    if ((runtime?.phase === "host-live" || runtime?.phase === "host-starting")
      && runtime.hostUuid != null
      && runtime.hostPlayerName != null) {
      players.set(runtime.hostUuid, runtime.hostPlayerName);
    }

    const entries = this.presence.get(worldId);
    if (entries) {
      const cutoff = now.getTime() - PLAYER_PRESENCE_TIMEOUT_MS;
      for (const [playerUuid, entry] of entries.entries()) {
        if (entry.present && new Date(entry.updatedAt).getTime() < cutoff) {
          entries.delete(playerUuid);
          continue;
        }
        if (entry.present && !players.has(playerUuid)) {
          players.set(playerUuid, entry.playerName);
        }
      }
      if (entries.size === 0) {
        this.presence.delete(worldId);
      }
    }

    return [...players.entries()]
      .map(([playerUuid, playerName]) => ({ playerUuid, playerName }))
      .sort((a, b) => a.playerName.localeCompare(b.playerName) || a.playerUuid.localeCompare(b.playerUuid));
  }

  private deleteInvitesForWorld(worldId: string): void {
    for (const [inviteId, invite] of this.invites.entries()) {
      if (invite.worldId === worldId) {
        this.invites.delete(inviteId);
      }
    }
  }
}

function joinMotdLines(line1: string | null, line2: string | null): string | null {
  const lines = [line1 ?? "", line2 ?? ""]
    .flatMap((line) => line.split("\n"))
    .map((line) => line.trimEnd())
    .filter((line) => line.length > 0);
  return lines.length > 0 ? lines.join("\n") : null;
}

function storageObjectKey(provider: StorageProviderType, storageAccountId: string, storageKey: string): string {
  return `${provider}:${storageAccountId}:${storageKey}`;
}
