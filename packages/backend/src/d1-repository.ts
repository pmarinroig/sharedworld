import type {
  AbandonFinalizationRequest,
  BeginFinalizationRequest,
  CancelWaitingRequest,
  CompleteFinalizationRequest,
  FileTransferMode,
  FinalizationActionResult,
  FinalizeSnapshotRequest,
  HostStartupProgressRequest,
  InviteCode,
  KickMemberResponse,
  PresenceHeartbeatRequest,
  RefreshWaitingRequest,
  StorageProviderType,
  StorageUsageSummary,
  SnapshotManifest,
  WorldDetails,
  WorldMembership,
  WorldSnapshotSummary,
  WorldSummary
} from "../../shared/src/index.ts";
import { HANDOFF_WAITER_TIMEOUT_MS, HOST_LEASE_TIMEOUT_MS, PLAYER_PRESENCE_TIMEOUT_MS, type SessionToken } from "../../shared/src/index.ts";

import type { D1Database } from "./env.ts";
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
  resolveRuntimeTimeout,
  runtimePhaseToWorldStatus,
  timedOutUncleanShutdownWarning,
  type RuntimeWaiter,
  type WorldRuntimeRecord
} from "./runtime-protocol.ts";
import {
  leaseExpired,
  runtimeToLease
} from "./runtime-repository-support.ts";
import {
  mapInvite,
  mapLease,
  mapRuntimeRow,
  mapStorageAccount,
  mapStorageLinkSession,
  mapStorageObject,
  mapUncleanShutdownWarning
} from "./repository/d1-row-mappers.ts";
import {
  asNullableString,
  clampFraction,
  joinMotdLines,
  normalizeBoundValues,
  sqlPlaceholders,
  type LegacyClaimHostRequest,
  type LegacyHostLease,
  type LegacyHostStatus,
  type Row
} from "./repository/d1-support.ts";

export class D1SharedWorldRepository implements SharedWorldRepository {
  constructor(private readonly db: D1Database) {}

  async createChallenge(challenge: AuthChallengeRecord): Promise<void> {
    await this.run(
      "INSERT INTO auth_challenges (nonce, expires_at, used_at) VALUES (?, ?, ?)",
      challenge.serverId,
      challenge.expiresAt,
      challenge.usedAt
    );
  }

  async getChallenge(serverId: string): Promise<AuthChallengeRecord | null> {
    const row = await this.first<Row>(
      "SELECT nonce, expires_at, used_at FROM auth_challenges WHERE nonce = ?",
      serverId
    );
    if (!row) {
      return null;
    }
    return {
      serverId: String(row.nonce),
      expiresAt: String(row.expires_at),
      usedAt: asNullableString(row.used_at)
    };
  }

  async markChallengeUsed(serverId: string, usedAt: string): Promise<void> {
    await this.run("UPDATE auth_challenges SET used_at = ? WHERE nonce = ?", usedAt, serverId);
  }

  async upsertUser(user: UserRecord): Promise<void> {
    await this.run(
      `INSERT INTO users (player_uuid, player_name, created_at)
       VALUES (?, ?, ?)
       ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name`,
      user.playerUuid,
      user.playerName,
      user.createdAt
    );
  }

  async createSession(session: SessionToken): Promise<void> {
    await this.run(
      "INSERT INTO user_sessions (token, player_uuid, player_name, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
      session.token,
      session.playerUuid,
      session.playerName,
      new Date().toISOString(),
      session.expiresAt
    );
  }

  async getSession(token: string): Promise<SessionToken | null> {
    const row = await this.first<Row>(
      "SELECT token, player_uuid, player_name, expires_at FROM user_sessions WHERE token = ?",
      token
    );
    if (!row) {
      return null;
    }
    return {
      token: String(row.token),
      playerUuid: String(row.player_uuid),
      playerName: String(row.player_name),
      expiresAt: String(row.expires_at)
    };
  }

  async listWorldsForPlayer(playerUuid: string): Promise<WorldSummary[]> {
    const memberships = await this.all<Row>(
      `SELECT w.id, w.slug, w.name, w.owner_uuid
       FROM worlds w
       JOIN world_memberships wm ON wm.world_id = w.id
       WHERE wm.player_uuid = ? AND wm.deleted_at IS NULL AND w.deleted_at IS NULL
       ORDER BY w.name ASC`,
      playerUuid
    );

    const summaries: WorldSummary[] = [];
    for (const row of memberships) {
      summaries.push(await this.buildWorldSummary(String(row.id)));
    }
    return summaries;
  }

  async hasActiveWorld(worldId: string): Promise<boolean> {
    const row = await this.first<Row>(
      "SELECT 1 AS found FROM worlds WHERE id = ? AND deleted_at IS NULL LIMIT 1",
      worldId
    );
    return row != null;
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
    const now = new Date().toISOString();
    const uniqueSlug = `${slug}-${id.slice(Math.max(0, id.length - 8))}`;
    await this.run(
      "INSERT INTO worlds (id, slug, name, motd, custom_icon_storage_key, owner_uuid, storage_provider, storage_account_id, created_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
      id,
      uniqueSlug,
      name,
      motd,
      customIconStorageKey,
      ctx.playerUuid,
      storage.provider,
      storage.storageAccountId,
      now
    );
    await this.run(
      `INSERT INTO world_memberships (world_id, player_uuid, player_name, role, joined_at, deleted_at)
       VALUES (?, ?, ?, 'owner', ?, NULL)`,
      id,
      ctx.playerUuid,
      ctx.playerName,
      now
    );
    const details = await this.getWorldDetails(id, ctx.playerUuid);
    if (!details) {
      throw new Error("World creation failed.");
    }
    return details;
  }

  async getWorldDetails(worldId: string, playerUuid: string): Promise<WorldDetails | null> {
    const member = await this.first<Row>(
      `SELECT w.id, w.slug, w.name, w.owner_uuid
       FROM worlds w
       JOIN world_memberships wm ON wm.world_id = w.id
       WHERE w.id = ? AND wm.player_uuid = ? AND wm.deleted_at IS NULL AND w.deleted_at IS NULL`,
      worldId,
      playerUuid
    );
    if (!member) {
      return null;
    }

    const summary = await this.buildWorldSummary(worldId);
    const memberships = await this.listMemberships(worldId);
    const membership = memberships.find((entry) => entry.playerUuid === playerUuid);
    if (!membership) {
      return null;
    }
    return {
      ...summary,
      membership,
      memberships,
      storageUsage: null,
      activeInviteCode: null
    };
  }

  async updateWorld(ctx: RequestContext, worldId: string, request: WorldUpdateRecord): Promise<WorldDetails> {
    const motd = joinMotdLines(request.motdLine1 ?? null, request.motdLine2 ?? null);
    await this.run(
      `UPDATE worlds
       SET name = ?, motd = ?, custom_icon_storage_key = ?
       WHERE id = ? AND owner_uuid = ? AND deleted_at IS NULL`,
      request.name,
      motd,
      request.clearCustomIcon ? null : (request.customIconStorageKey === undefined ? await this.currentCustomIconStorageKey(worldId) : (request.customIconStorageKey ?? null)),
      worldId,
      ctx.playerUuid
    );
    const details = await this.getWorldDetails(worldId, ctx.playerUuid);
    if (!details) {
      throw new Error("World update failed.");
    }
    return details;
  }

  async deleteWorldForPlayer(ctx: RequestContext, worldId: string, now: Date): Promise<DeleteWorldResult> {
    const deletedAt = now.toISOString();
    const world = await this.first<Row>(
      "SELECT owner_uuid, custom_icon_storage_key FROM worlds WHERE id = ? AND deleted_at IS NULL",
      worldId
    );
    if (!world) {
      return { worldDeleted: false, deletedCustomIconStorageKey: null };
    }

    if (String(world.owner_uuid) === ctx.playerUuid) {
      await this.run(
        `UPDATE world_memberships
         SET deleted_at = ?
         WHERE world_id = ? AND deleted_at IS NULL`,
        deletedAt,
        worldId
      );
      await this.run("UPDATE worlds SET deleted_at = ? WHERE id = ?", deletedAt, worldId);
      await this.run("DELETE FROM invite_codes WHERE world_id = ?", worldId);
      await this.run("DELETE FROM handoff_waiters WHERE world_id = ?", worldId);
      await this.run("DELETE FROM host_leases WHERE world_id = ?", worldId);
      await this.run("DELETE FROM world_runtime WHERE world_id = ?", worldId);
      await this.run("DELETE FROM world_presence WHERE world_id = ?", worldId);
      return { worldDeleted: true, deletedCustomIconStorageKey: asNullableString(world.custom_icon_storage_key) };
    }

    await this.run(
      `UPDATE world_memberships
       SET deleted_at = ?
       WHERE world_id = ? AND player_uuid = ? AND deleted_at IS NULL`,
      deletedAt,
      worldId,
      ctx.playerUuid
    );
    await this.run("DELETE FROM handoff_waiters WHERE world_id = ? AND player_uuid = ?", worldId, ctx.playerUuid);
    await this.run("DELETE FROM host_leases WHERE world_id = ? AND host_uuid = ?", worldId, ctx.playerUuid);
    await this.run("DELETE FROM world_runtime WHERE world_id = ? AND host_uuid = ?", worldId, ctx.playerUuid);
    await this.run("DELETE FROM world_presence WHERE world_id = ? AND player_uuid = ?", worldId, ctx.playerUuid);

    const count = await this.first<Row>(
      "SELECT COUNT(*) AS count FROM world_memberships WHERE world_id = ? AND deleted_at IS NULL",
      worldId
    );
    if (Number(count?.count ?? 0) === 0) {
      await this.run("UPDATE worlds SET deleted_at = ? WHERE id = ?", deletedAt, worldId);
      await this.run("DELETE FROM invite_codes WHERE world_id = ?", worldId);
      await this.run("DELETE FROM handoff_waiters WHERE world_id = ?", worldId);
      await this.run("DELETE FROM host_leases WHERE world_id = ?", worldId);
      await this.run("DELETE FROM world_runtime WHERE world_id = ?", worldId);
      await this.run("DELETE FROM world_presence WHERE world_id = ?", worldId);
      return { worldDeleted: true, deletedCustomIconStorageKey: asNullableString(world.custom_icon_storage_key) };
    }

    return { worldDeleted: false, deletedCustomIconStorageKey: null };
  }

  async isStorageKeyReferenced(storageKey: string): Promise<boolean> {
    const snapshotReference = await this.first<Row>(
      "SELECT 1 AS found FROM snapshot_files WHERE storage_key = ? LIMIT 1",
      storageKey
    );
    if (snapshotReference) {
      return true;
    }
    const packReference = await this.first<Row>(
      "SELECT 1 AS found FROM snapshot_packs WHERE storage_key = ? LIMIT 1",
      storageKey
    );
    if (packReference) {
      return true;
    }
    const iconReference = await this.first<Row>(
      "SELECT 1 AS found FROM worlds WHERE custom_icon_storage_key = ? AND deleted_at IS NULL LIMIT 1",
      storageKey
    );
    return iconReference != null;
  }

  async getWorldStorageBinding(worldId: string) {
    const row = await this.first<Row>(
      "SELECT storage_provider, storage_account_id FROM worlds WHERE id = ? AND deleted_at IS NULL",
      worldId
    );
    if (!row) {
      return null;
    }
    return {
      provider: String(row.storage_provider ?? "google-drive") as StorageProviderType,
      storageAccountId: asNullableString(row.storage_account_id)
    };
  }

  async getStorageUsage(worldId: string): Promise<StorageUsageSummary> {
    const world = await this.first<Row>(
      "SELECT storage_provider, storage_account_id FROM worlds WHERE id = ? AND deleted_at IS NULL",
      worldId
    );
    if (!world) {
      throw new Error(`Unknown world ${worldId}`);
    }
    const usedRow = await this.first<Row>(
      `WITH referenced_keys AS (
         SELECT sf.storage_key AS storage_key
         FROM snapshot_files sf
         JOIN snapshots s ON s.id = sf.snapshot_id
         WHERE s.world_id = ?
         UNION
         SELECT sp.storage_key AS storage_key
         FROM snapshot_packs sp
         JOIN snapshots s ON s.id = sp.snapshot_id
         WHERE s.world_id = ?
         UNION
         SELECT w.custom_icon_storage_key AS storage_key
         FROM worlds w
         WHERE w.id = ? AND w.deleted_at IS NULL AND w.custom_icon_storage_key IS NOT NULL
       )
       SELECT COALESCE(SUM(so.size), 0) AS used
       FROM referenced_keys rk
       JOIN storage_objects so
         ON so.provider = ?
        AND so.storage_account_id = ?
        AND so.storage_key = rk.storage_key`,
      worldId,
      worldId,
      worldId,
      String(world.storage_provider ?? "google-drive"),
      String(world.storage_account_id ?? "")
    );
    const account = asNullableString(world.storage_account_id)
      ? await this.getStorageAccount(String(world.storage_account_id))
      : null;
    return {
      provider: String(world.storage_provider ?? "google-drive") as StorageProviderType,
      linked: asNullableString(world.storage_account_id) != null,
      usedBytes: Number(usedRow?.used ?? 0),
      quotaUsedBytes: null,
      quotaTotalBytes: null,
      accountEmail: account?.email ?? null
    };
  }

  async createStorageLinkSession(session: StorageLinkSessionRecord): Promise<void> {
    await this.run(
      `INSERT INTO storage_link_sessions (
         id, player_uuid, provider, status, auth_url, state, linked_account_email,
         account_display_name, storage_account_id, error_message, created_at, expires_at, completed_at
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      session.id,
      session.playerUuid,
      session.provider,
      session.status,
      session.authUrl,
      session.state,
      session.linkedAccountEmail,
      session.accountDisplayName,
      session.storageAccountId,
      session.errorMessage,
      session.createdAt,
      session.expiresAt,
      session.completedAt
    );
  }

  async getStorageLinkSession(sessionId: string): Promise<StorageLinkSessionRecord | null> {
    const row = await this.first<Row>(
      `SELECT id, player_uuid, provider, status, auth_url, state, linked_account_email,
              account_display_name, storage_account_id, error_message, created_at, expires_at, completed_at
       FROM storage_link_sessions WHERE id = ?`,
      sessionId
    );
    return row ? mapStorageLinkSession(row) : null;
  }

  async updateStorageLinkSession(sessionId: string, update: Partial<Pick<StorageLinkSessionRecord, "status" | "linkedAccountEmail" | "accountDisplayName" | "errorMessage" | "storageAccountId" | "completedAt">>): Promise<void> {
    const current = await this.getStorageLinkSession(sessionId);
    if (!current) {
      return;
    }
    await this.run(
      `UPDATE storage_link_sessions
       SET status = ?, linked_account_email = ?, account_display_name = ?, error_message = ?, storage_account_id = ?, completed_at = ?
       WHERE id = ?`,
      update.status ?? current.status,
      update.linkedAccountEmail ?? current.linkedAccountEmail,
      update.accountDisplayName ?? current.accountDisplayName,
      update.errorMessage ?? current.errorMessage,
      update.storageAccountId ?? current.storageAccountId,
      update.completedAt ?? current.completedAt,
      sessionId
    );
  }

  async createOrUpdateStorageAccount(account: StorageAccountRecord): Promise<StorageAccountRecord> {
    await this.run(
      `INSERT INTO storage_accounts (
         id, provider, owner_player_uuid, external_account_id, email, display_name,
         access_token, refresh_token, token_expires_at, created_at, updated_at
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET
         provider = excluded.provider,
         owner_player_uuid = excluded.owner_player_uuid,
         external_account_id = excluded.external_account_id,
         email = excluded.email,
         display_name = excluded.display_name,
         access_token = excluded.access_token,
         refresh_token = excluded.refresh_token,
         token_expires_at = excluded.token_expires_at,
         updated_at = excluded.updated_at`,
      account.id,
      account.provider,
      account.ownerPlayerUuid,
      account.externalAccountId,
      account.email,
      account.displayName,
      account.accessToken,
      account.refreshToken,
      account.tokenExpiresAt,
      account.createdAt,
      account.updatedAt
    );
    return account;
  }

  async getStorageAccount(accountId: string): Promise<StorageAccountRecord | null> {
    const row = await this.first<Row>(
      `SELECT id, provider, owner_player_uuid, external_account_id, email, display_name,
              access_token, refresh_token, token_expires_at, created_at, updated_at
       FROM storage_accounts WHERE id = ?`,
      accountId
    );
    return row ? mapStorageAccount(row) : null;
  }

  async findStorageAccountByExternalId(provider: StorageProviderType, externalAccountId: string): Promise<StorageAccountRecord | null> {
    const row = await this.first<Row>(
      `SELECT id, provider, owner_player_uuid, external_account_id, email, display_name,
              access_token, refresh_token, token_expires_at, created_at, updated_at
       FROM storage_accounts
       WHERE provider = ? AND external_account_id = ?`,
      provider,
      externalAccountId
    );
    return row ? mapStorageAccount(row) : null;
  }

  async upsertStorageObject(record: StorageObjectRecord): Promise<void> {
    await this.run(
      `INSERT INTO storage_objects (
         provider, storage_account_id, storage_key, object_id, content_type, size, created_at, updated_at
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(provider, storage_account_id, storage_key) DO UPDATE SET
         object_id = excluded.object_id,
         content_type = excluded.content_type,
         size = excluded.size,
         updated_at = excluded.updated_at`,
      record.provider,
      record.storageAccountId,
      record.storageKey,
      record.objectId,
      record.contentType,
      record.size,
      record.createdAt,
      record.updatedAt
    );
  }

  async getStorageObject(provider: StorageProviderType, storageAccountId: string, storageKey: string): Promise<StorageObjectRecord | null> {
    const row = await this.first<Row>(
      `SELECT provider, storage_account_id, storage_key, object_id, content_type, size, created_at, updated_at
       FROM storage_objects
       WHERE provider = ? AND storage_account_id = ? AND storage_key = ?`,
      provider,
      storageAccountId,
      storageKey
    );
    return row ? mapStorageObject(row) : null;
  }

  async deleteStorageObject(provider: StorageProviderType, storageAccountId: string, storageKey: string): Promise<void> {
    await this.run(
      "DELETE FROM storage_objects WHERE provider = ? AND storage_account_id = ? AND storage_key = ?",
      provider,
      storageAccountId,
      storageKey
    );
  }

  async createInvite(worldId: string, _ctx: RequestContext, invite: InviteCode): Promise<InviteCode> {
    await this.run(
      `INSERT INTO invite_codes (
        id, world_id, code, created_by_uuid, created_at, expires_at, status
      ) VALUES (?, ?, ?, ?, ?, ?, ?)`,
      invite.id,
      invite.worldId,
      invite.code,
      invite.createdByUuid,
      invite.createdAt,
      invite.expiresAt,
      invite.status
    );
    return invite;
  }

  async getInviteByCode(code: string): Promise<InviteCode | null> {
    const row = await this.first<Row>(
      `SELECT id, world_id, code, created_by_uuid, created_at, expires_at,
              redeemed_by_uuid, redeemed_at, status
       FROM invite_codes WHERE code = ?`,
      code
    );
    return row ? mapInvite(row) : null;
  }

  async revokeActiveInvites(worldId: string): Promise<string[]> {
    const rows = await this.all<Row>(
      "SELECT id FROM invite_codes WHERE world_id = ? AND status = 'active'",
      worldId
    );
    await this.run(
      "UPDATE invite_codes SET status = 'revoked' WHERE world_id = ? AND status = 'active'",
      worldId
    );
    return rows.map((row) => String(row.id));
  }

  async getActiveInvite(worldId: string, now: Date): Promise<InviteCode | null> {
    await this.run(
      "UPDATE invite_codes SET status = 'expired' WHERE world_id = ? AND status = 'active' AND expires_at < ?",
      worldId,
      now.toISOString()
    );
    const row = await this.first<Row>(
      `SELECT id, world_id, code, created_by_uuid, created_at, expires_at, redeemed_by_uuid, redeemed_at, status
       FROM invite_codes
       WHERE world_id = ? AND status = 'active'
       ORDER BY created_at DESC
       LIMIT 1`,
      worldId
    );
    return row ? mapInvite(row) : null;
  }

  async addMembership(membership: WorldMembership): Promise<void> {
    await this.run(
      `INSERT INTO world_memberships (world_id, player_uuid, player_name, role, joined_at, deleted_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(world_id, player_uuid) DO UPDATE SET
         player_name = excluded.player_name,
         role = excluded.role,
         deleted_at = NULL`,
      membership.worldId,
      membership.playerUuid,
      membership.playerName,
      membership.role,
      membership.joinedAt,
      membership.deletedAt
    );
  }

  async isWorldMember(worldId: string, playerUuid: string): Promise<boolean> {
    const row = await this.first<Row>(
      `SELECT 1 AS present
       FROM world_memberships
       WHERE world_id = ? AND player_uuid = ? AND deleted_at IS NULL`,
      worldId,
      playerUuid
    );
    return Boolean(row);
  }

  async hasWorldMembership(worldId: string, playerUuid: string): Promise<boolean> {
    const row = await this.first<Row>(
      `SELECT 1 AS present
       FROM world_memberships
       WHERE world_id = ? AND player_uuid = ?`,
      worldId,
      playerUuid
    );
    return Boolean(row);
  }

  async kickMember(worldId: string, removedPlayerUuid: string, removedAt: string): Promise<KickMemberResponse | null> {
    const member = await this.first<Row>(
      `SELECT player_uuid
       FROM world_memberships
       WHERE world_id = ? AND player_uuid = ? AND deleted_at IS NULL`,
      worldId,
      removedPlayerUuid
    );
    if (!member) {
      return null;
    }
    await this.run(
      "UPDATE world_memberships SET deleted_at = ? WHERE world_id = ? AND player_uuid = ? AND deleted_at IS NULL",
      removedAt,
      worldId,
      removedPlayerUuid
    );
    await this.run("DELETE FROM handoff_waiters WHERE world_id = ? AND player_uuid = ?", worldId, removedPlayerUuid);
    await this.run("DELETE FROM world_presence WHERE world_id = ? AND player_uuid = ?", worldId, removedPlayerUuid);
    await this.run(
      `UPDATE host_leases
       SET revoked_at = COALESCE(revoked_at, ?), updated_at = ?
       WHERE world_id = ? AND host_uuid = ?`,
      removedAt,
      removedAt,
      worldId,
      removedPlayerUuid
    );
    await this.run(
      `UPDATE world_runtime
       SET revoked_at = COALESCE(revoked_at, ?), updated_at = ?
       WHERE world_id = ? AND host_uuid = ?`,
      removedAt,
      removedAt,
      worldId,
      removedPlayerUuid
    );
    return {
      worldId,
      removedPlayerUuid
    };
  }

  async getHostStatus(worldId: string): Promise<LegacyHostStatus> {
    const runtime = await this.getDisplayRuntimeRecord(worldId, new Date());
    const next = await this.chooseNextHost(worldId);
    return {
      worldId,
      activeLease: runtime == null ? null : runtimeToLease(runtime),
      nextHostUuid: next?.playerUuid ?? null,
      nextHostPlayerName: next?.playerName ?? null
    };
  }

  async getRuntimeRecord(worldId: string, _now: Date): Promise<WorldRuntimeRecord | null> {
    const row = await this.first<Row>(
      `SELECT world_id, host_uuid, host_player_name, runtime_phase, runtime_epoch, runtime_token,
              claimed_at, expires_at, join_target, candidate_uuid, revoked_at,
              startup_deadline_at, runtime_token_issued_at, last_progress_at,
              startup_progress_label, startup_progress_mode, startup_progress_fraction, startup_progress_updated_at, updated_at
       FROM world_runtime WHERE world_id = ?`,
      worldId
    );
    return row ? mapRuntimeRow(row) : null;
  }

  async upsertRuntimeRecord(runtime: WorldRuntimeRecord): Promise<void> {
    await this.run(
      `INSERT INTO world_runtime (
         world_id, host_uuid, host_player_name, runtime_phase, runtime_epoch, runtime_token,
         claimed_at, expires_at, join_target, candidate_uuid, revoked_at,
         startup_deadline_at, runtime_token_issued_at, last_progress_at,
         startup_progress_label, startup_progress_mode, startup_progress_fraction, startup_progress_updated_at, updated_at
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(world_id) DO UPDATE SET
         host_uuid = excluded.host_uuid,
         host_player_name = excluded.host_player_name,
         runtime_phase = excluded.runtime_phase,
         runtime_epoch = excluded.runtime_epoch,
         runtime_token = excluded.runtime_token,
         claimed_at = excluded.claimed_at,
         expires_at = excluded.expires_at,
         join_target = excluded.join_target,
         candidate_uuid = excluded.candidate_uuid,
         revoked_at = excluded.revoked_at,
         startup_deadline_at = excluded.startup_deadline_at,
         runtime_token_issued_at = excluded.runtime_token_issued_at,
         last_progress_at = excluded.last_progress_at,
         startup_progress_label = excluded.startup_progress_label,
         startup_progress_mode = excluded.startup_progress_mode,
         startup_progress_fraction = excluded.startup_progress_fraction,
         startup_progress_updated_at = excluded.startup_progress_updated_at,
         updated_at = excluded.updated_at`,
      runtime.worldId,
      runtime.hostUuid,
      runtime.hostPlayerName,
      runtime.phase,
      runtime.runtimeEpoch,
      runtime.runtimeToken ?? null,
      runtime.claimedAt ?? runtime.updatedAt,
      runtime.expiresAt ?? null,
      runtime.joinTarget,
      runtime.candidateUuid,
      runtime.revokedAt ?? null,
      runtime.startupDeadlineAt ?? null,
      runtime.runtimeTokenIssuedAt ?? null,
      runtime.lastProgressAt ?? null,
      runtime.startupProgress?.label ?? null,
      runtime.startupProgress?.mode ?? null,
      runtime.startupProgress?.fraction ?? null,
      runtime.startupProgress?.updatedAt ?? null,
      runtime.updatedAt
    );
  }

  async deleteRuntimeRecord(worldId: string): Promise<void> {
    await this.run(
      `UPDATE worlds
       SET last_runtime_epoch = MAX(
         COALESCE(last_runtime_epoch, 0),
         COALESCE((SELECT runtime_epoch FROM world_runtime WHERE world_id = ?), 0)
       )
       WHERE id = ?`,
      worldId,
      worldId
    );
    await this.run("DELETE FROM world_runtime WHERE world_id = ?", worldId);
  }

  async getLastRuntimeEpoch(worldId: string): Promise<number> {
    const row = await this.first<Row>(
      "SELECT last_runtime_epoch FROM worlds WHERE id = ?",
      worldId
    );
    return row?.last_runtime_epoch == null ? 0 : Number(row.last_runtime_epoch);
  }

  async getUncleanShutdownWarning(worldId: string): Promise<WorldUncleanShutdownWarningRecord | null> {
    const row = await this.first<Row>(
      `SELECT unclean_shutdown_host_uuid, unclean_shutdown_host_player_name, unclean_shutdown_phase, unclean_shutdown_runtime_epoch, unclean_shutdown_recorded_at
       FROM worlds
       WHERE id = ?`,
      worldId
    );
    return mapUncleanShutdownWarning(row);
  }

  async setUncleanShutdownWarning(worldId: string, warning: WorldUncleanShutdownWarningRecord): Promise<void> {
    await this.run(
      `UPDATE worlds
       SET unclean_shutdown_host_uuid = ?,
           unclean_shutdown_host_player_name = ?,
           unclean_shutdown_phase = ?,
           unclean_shutdown_runtime_epoch = ?,
           unclean_shutdown_recorded_at = ?
       WHERE id = ?`,
      warning.hostUuid,
      warning.hostPlayerName,
      warning.phase,
      warning.runtimeEpoch,
      warning.recordedAt,
      worldId
    );
  }

  async clearUncleanShutdownWarning(worldId: string): Promise<void> {
    await this.run(
      `UPDATE worlds
       SET unclean_shutdown_host_uuid = NULL,
           unclean_shutdown_host_player_name = NULL,
           unclean_shutdown_phase = NULL,
           unclean_shutdown_runtime_epoch = NULL,
           unclean_shutdown_recorded_at = NULL
       WHERE id = ?`,
      worldId
    );
  }

  async listActiveWaiters(worldId: string, now: Date): Promise<RuntimeWaiter[]> {
    await this.run(
      "DELETE FROM handoff_waiters WHERE world_id = ? AND updated_at < ?",
      worldId,
      new Date(now.getTime() - HANDOFF_WAITER_TIMEOUT_MS).toISOString()
    );
    const rows = await this.all<Row>(
      `SELECT player_uuid, player_name, waiter_session_id, waiting, updated_at
       FROM handoff_waiters
       WHERE world_id = ?
       ORDER BY updated_at DESC`,
      worldId
    );
    return rows.map((row) => ({
      playerUuid: String(row.player_uuid),
      waiterSessionId: String(row.waiter_session_id),
      playerName: String(row.player_name),
      waiting: Number(row.waiting) === 1,
      updatedAt: String(row.updated_at)
    }));
  }

  async upsertWaiterSession(worldId: string, ctx: RequestContext, waiterSessionId: string, now: Date): Promise<void> {
    await this.run(
      `INSERT INTO handoff_waiters (world_id, player_uuid, player_name, waiter_session_id, waiting, updated_at)
       VALUES (?, ?, ?, ?, 1, ?)
       ON CONFLICT(world_id, player_uuid) DO UPDATE SET
         player_name = excluded.player_name,
         waiter_session_id = excluded.waiter_session_id,
         waiting = 1,
         updated_at = excluded.updated_at`,
      worldId,
      ctx.playerUuid,
      ctx.playerName,
      waiterSessionId,
      now.toISOString()
    );
  }

  async refreshWaiterSession(worldId: string, ctx: RequestContext, request: RefreshWaitingRequest, now: Date): Promise<boolean> {
    await this.run(
      `UPDATE handoff_waiters
       SET player_name = ?, waiting = 1, updated_at = ?
       WHERE world_id = ? AND player_uuid = ? AND waiter_session_id = ?`,
      ctx.playerName,
      now.toISOString(),
      worldId,
      ctx.playerUuid,
      request.waiterSessionId
    );
    const row = await this.first<Row>(
      "SELECT 1 AS present FROM handoff_waiters WHERE world_id = ? AND player_uuid = ? AND waiter_session_id = ?",
      worldId,
      ctx.playerUuid,
      request.waiterSessionId
    );
    return Boolean(row);
  }

  async cancelWaiterSession(worldId: string, ctx: RequestContext, request: CancelWaitingRequest): Promise<boolean> {
    const row = await this.first<Row>(
      "SELECT 1 AS present FROM handoff_waiters WHERE world_id = ? AND player_uuid = ? AND waiter_session_id = ?",
      worldId,
      ctx.playerUuid,
      request.waiterSessionId
    );
    await this.run(
      "DELETE FROM handoff_waiters WHERE world_id = ? AND player_uuid = ? AND waiter_session_id = ?",
      worldId,
      ctx.playerUuid,
      request.waiterSessionId
    );
    return Boolean(row);
  }

  async clearWaitersForPlayer(worldId: string, playerUuid: string): Promise<void> {
    await this.run("DELETE FROM handoff_waiters WHERE world_id = ? AND player_uuid = ?", worldId, playerUuid);
  }

  async clearWaiters(worldId: string): Promise<void> {
    await this.run("DELETE FROM handoff_waiters WHERE world_id = ?", worldId);
  }

  async claimHost(worldId: string, ctx: RequestContext, request: LegacyClaimHostRequest, now: Date): Promise<LegacyHostLease | null> {
    const claimedAt = now.toISOString();
    const expiresAt = new Date(now.getTime() + HOST_LEASE_TIMEOUT_MS).toISOString();
    const existing = await this.getValidLease(worldId, now);

    if (existing) {
      if (existing.status !== "handoff") {
        return null;
      }

      const nextHost = await this.resolveNextHost(worldId, existing);
      if (!nextHost || nextHost.playerUuid !== ctx.playerUuid) {
        return null;
      }

      await this.run(
        `UPDATE host_leases
         SET host_uuid = ?, host_player_name = ?, status = 'hosting', claimed_at = ?,
             expires_at = ?, join_target = ?, handoff_candidate_uuid = NULL, revoked_at = NULL,
             startup_progress_label = NULL, startup_progress_mode = NULL, startup_progress_fraction = NULL, startup_progress_updated_at = NULL,
             updated_at = ?
         WHERE world_id = ?`,
        ctx.playerUuid,
        ctx.playerName,
        claimedAt,
        expiresAt,
        request.joinTarget ?? null,
        claimedAt,
        worldId
      );
      await this.run("DELETE FROM handoff_waiters WHERE world_id = ? AND player_uuid = ?", worldId, ctx.playerUuid);
      await this.run("DELETE FROM world_presence WHERE world_id = ? AND player_uuid = ?", worldId, ctx.playerUuid);
      return this.getRequiredLease(worldId);
    }

    const nextHost = await this.chooseNextHost(worldId);
    if (nextHost && nextHost.playerUuid !== ctx.playerUuid) {
      return null;
    }

    await this.run(
      `INSERT INTO host_leases (
         world_id, host_uuid, host_player_name, status, claimed_at,
         expires_at, join_target, handoff_candidate_uuid, revoked_at,
         startup_progress_label, startup_progress_mode, startup_progress_fraction, startup_progress_updated_at,
         updated_at
       ) VALUES (?, ?, ?, 'hosting', ?, ?, ?, NULL, NULL, NULL, NULL, NULL, NULL, ?)
       ON CONFLICT(world_id) DO UPDATE SET
         host_uuid = excluded.host_uuid,
         host_player_name = excluded.host_player_name,
         status = excluded.status,
         claimed_at = excluded.claimed_at,
         expires_at = excluded.expires_at,
         join_target = excluded.join_target,
         handoff_candidate_uuid = NULL,
         revoked_at = NULL,
         startup_progress_label = NULL,
         startup_progress_mode = NULL,
         startup_progress_fraction = NULL,
         startup_progress_updated_at = NULL,
         updated_at = excluded.updated_at
       WHERE host_leases.expires_at < excluded.claimed_at`,
      worldId,
      ctx.playerUuid,
      ctx.playerName,
      claimedAt,
      expiresAt,
      request.joinTarget ?? null,
      claimedAt
    );

    const lease = await this.getValidLease(worldId, now);
    if (!lease || lease.hostUuid !== ctx.playerUuid) {
      return null;
    }
    await this.run("DELETE FROM handoff_waiters WHERE world_id = ? AND player_uuid = ?", worldId, ctx.playerUuid);
    await this.run("DELETE FROM world_presence WHERE world_id = ? AND player_uuid = ?", worldId, ctx.playerUuid);
    return lease;
  }

  async heartbeatHost(worldId: string, ctx: RequestContext, joinTarget: string | null, now: Date): Promise<LegacyHostLease> {
    await this.run("DELETE FROM world_presence WHERE world_id = ? AND player_uuid = ?", worldId, ctx.playerUuid);
    const expiresAt = new Date(now.getTime() + HOST_LEASE_TIMEOUT_MS).toISOString();
    await this.run(
      `UPDATE host_leases
       SET expires_at = ?, join_target = COALESCE(?, join_target), updated_at = ?
       WHERE world_id = ? AND host_uuid = ?`,
      expiresAt,
      joinTarget,
      now.toISOString(),
      worldId,
      ctx.playerUuid
    );
    const row = await this.first<Row>(
      `SELECT world_id, host_uuid, host_player_name, status, claimed_at, expires_at,
              join_target, handoff_candidate_uuid, revoked_at,
              startup_progress_label, startup_progress_mode, startup_progress_fraction, startup_progress_updated_at, updated_at
       FROM host_leases WHERE world_id = ? AND host_uuid = ?`,
      worldId,
      ctx.playerUuid
    );
    if (!row) {
      throw new Error("Host lease not found.");
    }
    return mapLease(row);
  }

  async setHostStartupProgress(worldId: string, ctx: RequestContext, request: HostStartupProgressRequest, now: Date): Promise<LegacyHostLease | null> {
    const hasProgress = request.label != null && request.mode != null;
    await this.run(
      `UPDATE host_leases
       SET startup_progress_label = ?, startup_progress_mode = ?, startup_progress_fraction = ?, startup_progress_updated_at = ?, updated_at = ?
       WHERE world_id = ? AND host_uuid = ? AND status IN ('hosting', 'finalizing')`,
      hasProgress ? request.label : null,
      hasProgress ? request.mode : null,
      hasProgress ? clampFraction(request.fraction ?? null) : null,
      hasProgress ? now.toISOString() : null,
      now.toISOString(),
      worldId,
      ctx.playerUuid
    );
    const row = await this.first<Row>(
      `SELECT world_id, host_uuid, host_player_name, status, claimed_at, expires_at,
              join_target, handoff_candidate_uuid, revoked_at,
              startup_progress_label, startup_progress_mode, startup_progress_fraction, startup_progress_updated_at, updated_at
       FROM host_leases WHERE world_id = ? AND host_uuid = ? AND status IN ('hosting', 'finalizing')`,
      worldId,
      ctx.playerUuid
    );
    if (!row) {
      return null;
    }
    return mapLease(row);
  }

  async setPlayerPresence(worldId: string, ctx: RequestContext, request: PresenceHeartbeatRequest, now: Date): Promise<void> {
    await this.run(
      `INSERT INTO world_presence (world_id, player_uuid, player_name, present, guest_session_epoch, presence_sequence, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(world_id, player_uuid) DO UPDATE SET
         player_name = excluded.player_name,
         present = excluded.present,
         guest_session_epoch = excluded.guest_session_epoch,
         presence_sequence = excluded.presence_sequence,
         updated_at = excluded.updated_at
       WHERE excluded.guest_session_epoch > world_presence.guest_session_epoch
         OR (
           excluded.guest_session_epoch = world_presence.guest_session_epoch
           AND excluded.presence_sequence >= world_presence.presence_sequence
         )`,
      worldId,
      ctx.playerUuid,
      ctx.playerName,
      request.present ? 1 : 0,
      request.guestSessionEpoch,
      request.presenceSequence,
      now.toISOString()
    );
  }

  async clearWorldPresence(worldId: string): Promise<void> {
    await this.run("DELETE FROM world_presence WHERE world_id = ?", worldId);
  }

  async beginFinalization(worldId: string, ctx: RequestContext, _request: BeginFinalizationRequest, now: Date): Promise<FinalizationActionResult> {
    await this.run(
      `UPDATE host_leases
       SET status = 'finalizing',
           join_target = NULL,
           handoff_candidate_uuid = NULL,
           startup_progress_label = NULL,
           startup_progress_mode = NULL,
           startup_progress_fraction = NULL,
           startup_progress_updated_at = NULL,
           updated_at = ?
       WHERE world_id = ? AND host_uuid = ? AND status IN ('hosting', 'finalizing')`,
      now.toISOString(),
      worldId,
      ctx.playerUuid
    );
    return this.finalizationActionResult(worldId);
  }

  async completeFinalization(worldId: string, ctx: RequestContext, _request: CompleteFinalizationRequest, now: Date): Promise<FinalizationActionResult> {
    const lease = await this.getValidLease(worldId, now);
    if (!lease || lease.hostUuid !== ctx.playerUuid || lease.status !== "finalizing") {
      throw new Error("SharedWorld is not currently finalizing.");
    }

    const nextHost = await this.chooseNextHost(worldId);
    if (!nextHost) {
      await this.run("DELETE FROM host_leases WHERE world_id = ? AND host_uuid = ?", worldId, ctx.playerUuid);
      return {
        worldId,
        nextHostUuid: null,
        nextHostPlayerName: null,
        status: "idle"
      };
    }

    await this.run(
      `UPDATE host_leases
       SET status = 'handoff',
           expires_at = ?,
           join_target = NULL,
           handoff_candidate_uuid = ?,
           revoked_at = NULL,
           startup_progress_label = NULL,
           startup_progress_mode = NULL,
           startup_progress_fraction = NULL,
           startup_progress_updated_at = NULL,
           updated_at = ?
       WHERE world_id = ? AND host_uuid = ? AND status = 'finalizing'`,
      new Date(now.getTime() + HOST_LEASE_TIMEOUT_MS).toISOString(),
      nextHost.playerUuid,
      now.toISOString(),
      worldId,
      ctx.playerUuid
    );
    return this.finalizationActionResult(worldId);
  }

  async abandonFinalization(worldId: string, _ctx: RequestContext, _request: AbandonFinalizationRequest, now: Date): Promise<FinalizationActionResult> {
    const lease = await this.getValidLease(worldId, now);
    if (!lease || lease.status !== "finalizing") {
      const status = await this.getHostStatus(worldId);
      return {
        worldId,
        nextHostUuid: status.nextHostUuid,
        nextHostPlayerName: status.nextHostPlayerName,
        status: status.activeLease?.status ?? "idle"
      };
    }

    const nextHost = await this.chooseNextHost(worldId);
    if (!nextHost) {
      await this.run("DELETE FROM host_leases WHERE world_id = ?", worldId);
      return {
        worldId,
        nextHostUuid: null,
        nextHostPlayerName: null,
        status: "idle"
      };
    }

    await this.run(
      `UPDATE host_leases
       SET status = 'handoff',
           expires_at = ?,
           join_target = NULL,
           handoff_candidate_uuid = ?,
           revoked_at = NULL,
           startup_progress_label = NULL,
           startup_progress_mode = NULL,
           startup_progress_fraction = NULL,
           startup_progress_updated_at = NULL,
           updated_at = ?
       WHERE world_id = ? AND status = 'finalizing'`,
      new Date(now.getTime() + HOST_LEASE_TIMEOUT_MS).toISOString(),
      nextHost.playerUuid,
      now.toISOString(),
      worldId
    );
    return this.finalizationActionResult(worldId);
  }

  async releaseHost(worldId: string, ctx: RequestContext, graceful: boolean, now: Date): Promise<void> {
    if (!graceful) {
      await this.run("DELETE FROM host_leases WHERE world_id = ? AND host_uuid = ?", worldId, ctx.playerUuid);
      return;
    }

    const nextHost = await this.chooseNextHost(worldId);
    if (!nextHost) {
      await this.run("DELETE FROM host_leases WHERE world_id = ? AND host_uuid = ?", worldId, ctx.playerUuid);
      return;
    }
    await this.run(
      `UPDATE host_leases
       SET status = 'handoff',
           expires_at = ?,
           join_target = NULL,
           handoff_candidate_uuid = ?,
           revoked_at = NULL,
           startup_progress_label = NULL,
           startup_progress_mode = NULL,
           startup_progress_fraction = NULL,
           startup_progress_updated_at = NULL,
           updated_at = ?
       WHERE world_id = ? AND host_uuid = ?`,
      new Date(now.getTime() + HOST_LEASE_TIMEOUT_MS).toISOString(),
      nextHost.playerUuid,
      now.toISOString(),
      worldId,
      ctx.playerUuid
    );
  }

  async setHandoffWaiting(worldId: string, ctx: RequestContext, waiting: boolean, now: Date): Promise<void> {
    await this.run(
      `INSERT INTO handoff_waiters (world_id, player_uuid, player_name, waiter_session_id, waiting, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(world_id, player_uuid) DO UPDATE SET
         player_name = excluded.player_name,
         waiter_session_id = excluded.waiter_session_id,
         waiting = excluded.waiting,
         updated_at = excluded.updated_at`,
      worldId,
      ctx.playerUuid,
      ctx.playerName,
      `legacy_${ctx.playerUuid}`,
      waiting ? 1 : 0,
      now.toISOString()
    );
  }

  async chooseNextHost(worldId: string): Promise<{ playerUuid: string; playerName: string } | null> {
    await this.run(
      "DELETE FROM handoff_waiters WHERE world_id = ? AND updated_at < ?",
      worldId,
      new Date(Date.now() - HANDOFF_WAITER_TIMEOUT_MS).toISOString()
    );
    const row = await this.first<Row>(
      `SELECT wm.player_uuid, wm.player_name
       FROM world_memberships wm
       JOIN handoff_waiters hw ON hw.world_id = wm.world_id AND hw.player_uuid = wm.player_uuid
       WHERE wm.world_id = ? AND wm.deleted_at IS NULL AND hw.waiting = 1
       ORDER BY CASE WHEN wm.role = 'owner' THEN 0 ELSE 1 END,
                wm.joined_at ASC,
                wm.player_uuid ASC
       LIMIT 1`,
      worldId
    );
    if (!row) {
      return null;
    }
    return {
      playerUuid: String(row.player_uuid),
      playerName: String(row.player_name)
    };
  }

  async getLatestSnapshot(worldId: string): Promise<SnapshotManifest | null> {
    const snapshot = await this.first<Row>(
      `SELECT id, world_id, created_at, created_by_uuid
       FROM snapshots
       WHERE world_id = ?
       ORDER BY created_at DESC
       LIMIT 1`,
      worldId
    );
    if (!snapshot) {
      return null;
    }
    return this.loadSnapshot(String(snapshot.id), worldId, String(snapshot.created_at), String(snapshot.created_by_uuid));
  }

  async getSnapshot(worldId: string, snapshotId: string): Promise<SnapshotManifest | null> {
    const row = await this.first<Row>(
      `SELECT id, world_id, created_at, created_by_uuid
       FROM snapshots
       WHERE world_id = ? AND id = ?`,
      worldId,
      snapshotId
    );
    if (!row) {
      return null;
    }
    return this.loadSnapshot(String(row.id), String(row.world_id), String(row.created_at), String(row.created_by_uuid));
  }

  async listSnapshotSummaries(worldId: string): Promise<WorldSnapshotSummary[]> {
    const world = await this.first<Row>(
      "SELECT storage_provider, storage_account_id FROM worlds WHERE id = ? AND deleted_at IS NULL",
      worldId
    );
    if (!world) {
      return [];
    }
    const latestSnapshotId = await this.first<Row>(
      "SELECT id FROM snapshots WHERE world_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
      worldId
    );
    const rows = await this.all<Row>(
      `SELECT s.id, s.created_at, s.created_by_uuid
       FROM snapshots s
       WHERE s.world_id = ?
       ORDER BY s.created_at DESC, s.id DESC`,
      worldId
    );
    const summaries: WorldSnapshotSummary[] = [];
    for (const row of rows) {
      const snapshot = await this.loadSnapshot(String(row.id), worldId, String(row.created_at), String(row.created_by_uuid));
      const packedFiles = snapshot.packs.flatMap((pack) => pack.files);
      const storedBytes = await this.first<Row>(
        `WITH referenced_keys AS (
           SELECT sf.storage_key AS storage_key, MAX(sf.compressed_size) AS fallback_size
           FROM snapshot_files sf
           WHERE sf.snapshot_id = ? AND sf.pack_id IS NULL
           GROUP BY sf.storage_key
           UNION
           SELECT sp.storage_key AS storage_key, NULL AS fallback_size
           FROM snapshot_packs sp
           WHERE sp.snapshot_id = ?
         ),
         deduped_keys AS (
           SELECT storage_key, MAX(fallback_size) AS fallback_size
           FROM referenced_keys
           GROUP BY storage_key
         )
         SELECT COALESCE(SUM(COALESCE(so.size, dk.fallback_size, 0)), 0) AS used
         FROM deduped_keys dk
         LEFT JOIN storage_objects so
           ON so.provider = ?
          AND so.storage_account_id = ?
          AND so.storage_key = dk.storage_key`,
        String(row.id),
        String(row.id),
        String(world.storage_provider ?? "google-drive"),
        String(world.storage_account_id ?? "")
      );
      summaries.push({
        snapshotId: String(row.id),
        createdAt: String(row.created_at),
        createdByUuid: String(row.created_by_uuid),
        fileCount: snapshot.files.length + packedFiles.length,
        totalSize: snapshot.files.reduce((sum, file) => sum + file.size, 0) + packedFiles.reduce((sum, file) => sum + file.size, 0),
        totalCompressedSize: Number(storedBytes?.used ?? 0),
        isLatest: String(row.id) === String(latestSnapshotId?.id ?? "")
      });
    }
    return summaries;
  }

  async listSnapshotsForWorld(worldId: string): Promise<SnapshotRecord[]> {
    const rows = await this.all<Row>(
      `SELECT id, world_id, created_at, created_by_uuid
       FROM snapshots
       WHERE world_id = ?
       ORDER BY created_at DESC`,
      worldId
    );
    return rows.map((row) => ({
      snapshotId: String(row.id),
      worldId: String(row.world_id),
      createdAt: String(row.created_at),
      createdByUuid: String(row.created_by_uuid)
    }));
  }

  async finalizeSnapshot(worldId: string, ctx: RequestContext, request: FinalizeSnapshotRequest, now: Date): Promise<SnapshotManifest> {
    const snapshotId = `snapshot_${crypto.randomUUID().replace(/-/g, "")}`;
    await this.run(
      `INSERT INTO snapshots (id, world_id, created_at, created_by_uuid, base_snapshot_id)
       VALUES (?, ?, ?, ?, ?)`,
      snapshotId,
      worldId,
      now.toISOString(),
      ctx.playerUuid,
      request.baseSnapshotId ?? null
    );
    for (const file of request.files) {
      await this.run(
        `INSERT INTO snapshot_files (
          snapshot_id, path, hash, size, compressed_size, pack_id, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        snapshotId,
        file.path,
        file.hash,
        file.size,
        file.compressedSize,
        null,
        file.storageKey,
        file.contentType ?? "application/octet-stream",
        file.transferMode ?? "whole-gzip",
        file.baseSnapshotId ?? null,
        file.baseHash ?? null,
        file.chainDepth ?? null
      );
    }
    for (const pack of request.packs ?? []) {
      await this.run(
        `INSERT INTO snapshot_packs (
          snapshot_id, pack_id, hash, size, storage_key, transfer_mode, base_snapshot_id, base_hash, chain_depth
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        snapshotId,
        pack.packId,
        pack.hash,
        pack.size,
        pack.storageKey,
        pack.transferMode,
        pack.baseSnapshotId ?? null,
        pack.baseHash ?? null,
        pack.chainDepth ?? null
      );
      for (const file of pack.files) {
        await this.run(
          `INSERT INTO snapshot_files (
            snapshot_id, path, hash, size, compressed_size, pack_id, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          snapshotId,
          file.path,
          file.hash,
          file.size,
          file.size,
          pack.packId,
          pack.storageKey,
          file.contentType ?? "application/octet-stream",
          pack.transferMode,
          pack.baseSnapshotId ?? null,
          pack.baseHash ?? null,
          pack.chainDepth ?? null
        );
      }
    }
    return this.loadSnapshot(snapshotId, worldId, now.toISOString(), ctx.playerUuid);
  }

  async deleteSnapshots(worldId: string, snapshotIds: string[]): Promise<SnapshotDeletionResult> {
    if (snapshotIds.length === 0) {
      return {
        deletedSnapshotIds: [],
        unreferencedStorageKeys: []
      };
    }

    const requestedPlaceholders = sqlPlaceholders(snapshotIds.length);
    const deletedRows = await this.all<Row>(
      `SELECT id
       FROM snapshots
       WHERE world_id = ? AND id IN (${requestedPlaceholders})`,
      worldId,
      ...snapshotIds
    );
    const deletedSnapshotIds = deletedRows.map((row) => String(row.id));
    if (deletedSnapshotIds.length === 0) {
      return {
        deletedSnapshotIds: [],
        unreferencedStorageKeys: []
      };
    }

    const deletePlaceholders = sqlPlaceholders(deletedSnapshotIds.length);
    const candidateRows = await this.all<Row>(
      `SELECT DISTINCT storage_key
       FROM snapshot_files
       WHERE snapshot_id IN (${deletePlaceholders})`,
      ...deletedSnapshotIds
    );
    const packCandidateRows = await this.all<Row>(
      `SELECT DISTINCT storage_key
       FROM snapshot_packs
       WHERE snapshot_id IN (${deletePlaceholders})`,
      ...deletedSnapshotIds
    );
    const candidateStorageKeys = [...candidateRows, ...packCandidateRows].map((row) => String(row.storage_key));

    await this.run(
      `DELETE FROM snapshot_files
       WHERE snapshot_id IN (${deletePlaceholders})`,
      ...deletedSnapshotIds
    );
    await this.run(
      `DELETE FROM snapshot_packs
       WHERE snapshot_id IN (${deletePlaceholders})`,
      ...deletedSnapshotIds
    );
    await this.run(
      `DELETE FROM snapshots
       WHERE world_id = ? AND id IN (${deletePlaceholders})`,
      worldId,
      ...deletedSnapshotIds
    );

    let unreferencedStorageKeys: string[] = [];
    if (candidateStorageKeys.length > 0) {
      const keyPlaceholders = sqlPlaceholders(candidateStorageKeys.length);
      const referencedRows = await this.all<Row>(
        `SELECT DISTINCT storage_key
         FROM snapshot_files
         WHERE storage_key IN (${keyPlaceholders})`,
        ...candidateStorageKeys
      );
      const referencedPackRows = await this.all<Row>(
        `SELECT DISTINCT storage_key
         FROM snapshot_packs
         WHERE storage_key IN (${keyPlaceholders})`,
        ...candidateStorageKeys
      );
      const stillReferenced = new Set([...referencedRows, ...referencedPackRows].map((row) => String(row.storage_key)));
      unreferencedStorageKeys = candidateStorageKeys.filter((key) => !stillReferenced.has(key)).sort();
    }

    return {
      deletedSnapshotIds,
      unreferencedStorageKeys
    };
  }

  private async buildWorldSummary(worldId: string): Promise<WorldSummary> {
    const world = await this.first<Row>(
      "SELECT id, slug, name, motd, custom_icon_storage_key, owner_uuid, storage_provider, storage_account_id FROM worlds WHERE id = ?",
      worldId
    );
    if (!world) {
      throw new Error(`Unknown world ${worldId}`);
    }
    const memberCountRow = await this.first<Row>(
      "SELECT COUNT(*) AS count FROM world_memberships WHERE world_id = ? AND deleted_at IS NULL",
      worldId
    );
    const lifecycle = await this.summaryLifecycle(worldId, new Date());
    const latest = await this.getLatestSnapshot(worldId);
    const onlinePlayers = await this.listOnlinePlayers(worldId, new Date());
    return {
      id: String(world.id),
      slug: String(world.slug),
      name: String(world.name),
      ownerUuid: String(world.owner_uuid),
      motd: asNullableString(world.motd),
      customIconStorageKey: asNullableString(world.custom_icon_storage_key),
      customIconDownload: null,
      memberCount: Number(memberCountRow?.count ?? 0),
      status: lifecycle.status,
      lastSnapshotId: latest?.snapshotId ?? null,
      lastSnapshotAt: latest?.createdAt ?? null,
      activeHostUuid: lifecycle.activeHostUuid,
      activeHostPlayerName: lifecycle.activeHostPlayerName,
      activeJoinTarget: lifecycle.activeJoinTarget,
      onlinePlayerCount: onlinePlayers.length,
      onlinePlayerNames: onlinePlayers.map((entry) => entry.playerName),
      storageProvider: String(world.storage_provider ?? "google-drive") as StorageProviderType,
      storageLinked: asNullableString(world.storage_account_id) != null,
      storageAccountEmail: asNullableString(
        (await this.first<Row>("SELECT email FROM storage_accounts WHERE id = ?", asNullableString(world.storage_account_id)))?.email
      )
    };
  }

  async listMemberships(worldId: string): Promise<WorldMembership[]> {
    const rows = await this.all<Row>(
      `SELECT world_id, player_uuid, player_name, role, joined_at, deleted_at
       FROM world_memberships
       WHERE world_id = ? AND deleted_at IS NULL
       ORDER BY joined_at ASC`,
      worldId
    );
    return rows.map((row) => ({
      worldId: String(row.world_id),
      playerUuid: String(row.player_uuid),
      playerName: String(row.player_name),
      role: String(row.role) as WorldMembership["role"],
      joinedAt: String(row.joined_at),
      deletedAt: asNullableString(row.deleted_at)
    }));
  }

  private async loadSnapshot(snapshotId: string, worldId: string, createdAt: string, createdByUuid: string): Promise<SnapshotManifest> {
    const rows = await this.all<Row>(
      `SELECT path, hash, size, compressed_size, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
       FROM snapshot_files
       WHERE snapshot_id = ? AND pack_id IS NULL
       ORDER BY path ASC`,
      snapshotId
    );
    const packRows = await this.all<Row>(
      `SELECT pack_id, hash, size, storage_key, transfer_mode, base_snapshot_id, base_hash, chain_depth
       FROM snapshot_packs
       WHERE snapshot_id = ?
       ORDER BY pack_id ASC`,
      snapshotId
    );
    return {
      worldId,
      snapshotId,
      createdAt,
      createdByUuid,
      files: rows.map((row) => ({
        path: String(row.path),
        hash: String(row.hash),
        size: Number(row.size),
        compressedSize: Number(row.compressed_size),
        storageKey: String(row.storage_key),
        contentType: String(row.content_type),
        transferMode: String(row.transfer_mode ?? "whole-gzip") as FileTransferMode,
        baseSnapshotId: asNullableString(row.base_snapshot_id),
        baseHash: asNullableString(row.base_hash),
        chainDepth: row.chain_depth == null ? null : Number(row.chain_depth)
      })),
      packs: await Promise.all(packRows.map(async (row) => ({
        packId: String(row.pack_id),
        hash: String(row.hash),
        size: Number(row.size),
        storageKey: String(row.storage_key),
        transferMode: String(row.transfer_mode) as FileTransferMode,
        baseSnapshotId: asNullableString(row.base_snapshot_id),
        baseHash: asNullableString(row.base_hash),
        chainDepth: row.chain_depth == null ? null : Number(row.chain_depth),
        files: (await this.all<Row>(
          `SELECT path, hash, size, content_type
           FROM snapshot_files
           WHERE snapshot_id = ? AND pack_id = ?
           ORDER BY path ASC`,
          snapshotId,
          String(row.pack_id)
        )).map((fileRow) => ({
          path: String(fileRow.path),
          hash: String(fileRow.hash),
          size: Number(fileRow.size),
          contentType: String(fileRow.content_type)
        }))
      })))
    };
  }

  private async first<T extends Row>(query: string, ...values: unknown[]): Promise<T | null> {
    return this.db.prepare(query).bind(...normalizeBoundValues(values)).first<T>();
  }

  private async all<T extends Row>(query: string, ...values: unknown[]): Promise<T[]> {
    const result = await this.db.prepare(query).bind(...normalizeBoundValues(values)).all<T>();
    return result.results;
  }

  private async run(query: string, ...values: unknown[]): Promise<void> {
    await this.db.prepare(query).bind(...normalizeBoundValues(values)).run();
  }

  private async currentCustomIconStorageKey(worldId: string): Promise<string | null> {
    const row = await this.first<Row>(
      "SELECT custom_icon_storage_key FROM worlds WHERE id = ?",
      worldId
    );
    return asNullableString(row?.custom_icon_storage_key);
  }

  private async getValidLease(worldId: string, now: Date): Promise<LegacyHostLease | null> {
    // The D1 adapter must enforce the same runtime validity semantics as the memory repository.
    // In particular, host-starting expires by startupDeadlineAt, while host-finalizing never expires here.
    const row = await this.first<Row>(
      `SELECT world_id, host_uuid, host_player_name, status, runtime_phase, runtime_epoch, runtime_token,
              claimed_at, expires_at, join_target, handoff_candidate_uuid, revoked_at,
              startup_deadline_at, runtime_token_issued_at, last_progress_at,
              startup_progress_label, startup_progress_mode, startup_progress_fraction, startup_progress_updated_at, updated_at
       FROM host_leases WHERE world_id = ?`,
      worldId
    );
    const lease = row ? mapLease(row) : null;
    if (lease && leaseExpired(lease, now)) {
      await this.run("DELETE FROM host_leases WHERE world_id = ?", worldId);
      return null;
    }
    return lease;
  }

  private async finalizationActionResult(worldId: string): Promise<{ worldId: string; nextHostUuid: string | null; nextHostPlayerName: string | null; status: "idle" | "hosting" | "finalizing" | "handoff" }> {
    const status = await this.getHostStatus(worldId);
    return {
      worldId,
      nextHostUuid: status.nextHostUuid,
      nextHostPlayerName: status.nextHostPlayerName,
      status: status.activeLease?.status ?? "idle"
    };
  }

  private async getRequiredLease(worldId: string): Promise<LegacyHostLease> {
    const lease = await this.getValidLease(worldId, new Date());
    if (!lease) {
      throw new Error("Host lease not found.");
    }
    return lease;
  }

  private async resolveNextHost(worldId: string, lease: LegacyHostLease | null): Promise<{ playerUuid: string; playerName: string } | null> {
    if (lease?.status === "handoff" && lease.handoffCandidateUuid) {
      const row = await this.first<Row>(
        `SELECT wm.player_uuid, wm.player_name
         FROM world_memberships wm
         JOIN handoff_waiters hw ON hw.world_id = wm.world_id AND hw.player_uuid = wm.player_uuid
         WHERE wm.world_id = ? AND wm.player_uuid = ? AND wm.deleted_at IS NULL AND hw.waiting = 1`,
        worldId,
        lease.handoffCandidateUuid
      );
      if (row) {
        return {
          playerUuid: String(row.player_uuid),
          playerName: String(row.player_name)
        };
      }
    }
    return this.chooseNextHost(worldId);
  }

  private async summaryLifecycle(worldId: string, now: Date): Promise<{
    status: WorldSummary["status"];
    activeHostUuid: string | null;
    activeHostPlayerName: string | null;
    activeJoinTarget: string | null;
  }> {
    const runtime = await this.getDisplayRuntimeRecord(worldId, now);
    if (runtime != null) {
      return {
        status: runtimePhaseToWorldStatus(runtime.phase),
        activeHostUuid: runtime.hostUuid,
        activeHostPlayerName: runtime.hostPlayerName,
        activeJoinTarget: runtime.joinTarget
      };
    }
    const candidate = await this.chooseNextHost(worldId);
    return {
      status: candidate == null ? "idle" : "handoff",
      activeHostUuid: null,
      activeHostPlayerName: null,
      activeJoinTarget: null
    };
  }

  private async listOnlinePlayers(worldId: string, now: Date): Promise<Array<{ playerUuid: string; playerName: string }>> {
    await this.run(
      "DELETE FROM world_presence WHERE world_id = ? AND present = 1 AND updated_at < ?",
      worldId,
      new Date(now.getTime() - PLAYER_PRESENCE_TIMEOUT_MS).toISOString()
    );

    const players = new Map<string, string>();
    const runtime = await this.getDisplayRuntimeRecord(worldId, now);
    if ((runtime?.phase === "host-starting" || runtime?.phase === "host-live")
      && runtime.hostUuid != null
      && runtime.hostPlayerName != null) {
      players.set(runtime.hostUuid, runtime.hostPlayerName);
    }

    const rows = await this.all<Row>(
      `SELECT wp.player_uuid, wp.player_name
       FROM world_presence wp
       JOIN world_memberships wm ON wm.world_id = wp.world_id AND wm.player_uuid = wp.player_uuid
       WHERE wp.world_id = ? AND wm.deleted_at IS NULL
         AND wp.present = 1
       ORDER BY wp.player_name ASC, wp.player_uuid ASC`,
      worldId
    );
    for (const row of rows) {
      const playerUuid = String(row.player_uuid);
      if (!players.has(playerUuid)) {
        players.set(playerUuid, String(row.player_name));
      }
    }
    return [...players.entries()].map(([playerUuid, playerName]) => ({ playerUuid, playerName }));
  }

  private async getDisplayRuntimeRecord(worldId: string, now: Date): Promise<WorldRuntimeRecord | null> {
    const runtime = await this.getRuntimeRecord(worldId, now);
    if (!runtime) {
      return null;
    }
    const warning = timedOutUncleanShutdownWarning(runtime, now);
    if (warning != null) {
      await this.setUncleanShutdownWarning(worldId, {
        ...warning,
        runtimeEpoch: runtime.runtimeEpoch
      });
      await this.deleteRuntimeRecord(worldId);
      await this.clearWaiters(worldId);
      return null;
    }
    if (resolveRuntimeTimeout(runtime, null, now) == null) {
      await this.deleteRuntimeRecord(worldId);
      return null;
    }
    return runtime;
  }
}
