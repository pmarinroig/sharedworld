import type {
  CancelWaitingRequest,
  FileTransferMode,
  FinalizeSnapshotRequest,
  InviteCode,
  KickMemberResponse,
  PresenceHeartbeatRequest,
  RefreshWaitingRequest,
  StorageProviderType,
  StorageUsageSummary,
  SnapshotManifest,
  WorldDetails,
  WorldMembership,
  WorldSettings,
  WorldSnapshotSummary,
  WorldSummary
} from "../../shared/src/index.ts";
import { HANDOFF_WAITER_TIMEOUT_MS, PLAYER_PRESENCE_TIMEOUT_MS, type SessionToken } from "../../shared/src/index.ts";

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
  UncleanShutdownWarning
} from "./repository.ts";
import {
  runtimePhaseToWorldStatus,
  type RuntimeWaiter,
  type WorldRuntimeRecord
} from "./runtime-protocol.ts";
import { reconcileRuntimeState } from "./runtime-reconciliation.ts";
import {
  mapInvite,
  mapRuntimeRow,
  mapStorageAccount,
  mapStorageLinkSession,
  mapStorageObject,
  mapUncleanShutdownWarning
} from "./repository/d1-row-mappers.ts";
import {
  asNullableString,
  joinMotdLines,
  normalizeBoundValues,
  sqlPlaceholders,
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

  async getMojangServicesKeys(): Promise<{ fetchedAt: string; keysJson: string } | null> {
    const row = await this.first<Row>("SELECT fetched_at, keys_json FROM mojang_services_keys WHERE id = 1");
    if (!row) {
      return null;
    }
    return {
      fetchedAt: String(row.fetched_at),
      keysJson: String(row.keys_json)
    };
  }

  async putMojangServicesKeys(fetchedAt: string, keysJson: string): Promise<void> {
    await this.run(
      `INSERT INTO mojang_services_keys (id, fetched_at, keys_json)
       VALUES (1, ?, ?)
       ON CONFLICT(id) DO UPDATE SET fetched_at = excluded.fetched_at, keys_json = excluded.keys_json`,
      fetchedAt,
      keysJson
    );
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

  async countActiveWorlds(): Promise<number> {
    const row = await this.first<Row>(
      "SELECT COUNT(*) AS count FROM worlds WHERE deleted_at IS NULL"
    );
    return Number(row?.count ?? 0);
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

  async updateWorldSettings(worldId: string, settingsJson: string): Promise<boolean> {
    const changes = await this.runWithChanges(
      `UPDATE worlds
       SET settings = ?, settings_revision = settings_revision + 1
       WHERE id = ? AND deleted_at IS NULL`,
      settingsJson,
      worldId
    );
    return changes > 0;
  }

  async getWorldSettings(worldId: string): Promise<{ settings: WorldSettings | null; settingsRevision: number } | null> {
    const row = await this.first<Row>(
      "SELECT settings, settings_revision FROM worlds WHERE id = ? AND deleted_at IS NULL",
      worldId
    );
    if (!row) {
      return null;
    }
    return {
      settings: parseWorldSettings(row.settings),
      settingsRevision: Number(row.settings_revision ?? 0)
    };
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
      return this.tearDownWorld(worldId, deletedAt, asNullableString(world.custom_icon_storage_key));
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
    await this.retireWorldRuntime(worldId, ctx.playerUuid);
    await this.run("DELETE FROM world_presence WHERE world_id = ? AND player_uuid = ?", worldId, ctx.playerUuid);

    const count = await this.first<Row>(
      "SELECT COUNT(*) AS count FROM world_memberships WHERE world_id = ? AND deleted_at IS NULL",
      worldId
    );
    if (Number(count?.count ?? 0) === 0) {
      return this.tearDownWorld(worldId, deletedAt, asNullableString(world.custom_icon_storage_key));
    }

    return { worldDeleted: false, deletedCustomIconStorageKey: null };
  }

  /** Full-world teardown once the last (or owner) membership is gone. */
  private async tearDownWorld(worldId: string, deletedAt: string, deletedCustomIconStorageKey: string | null): Promise<DeleteWorldResult> {
    await this.run("UPDATE worlds SET deleted_at = ? WHERE id = ?", deletedAt, worldId);
    await this.run("DELETE FROM invite_codes WHERE world_id = ?", worldId);
    await this.run("DELETE FROM handoff_waiters WHERE world_id = ?", worldId);
    await this.retireWorldRuntime(worldId, null);
    await this.run("DELETE FROM world_presence WHERE world_id = ?", worldId);
    return { worldDeleted: true, deletedCustomIconStorageKey };
  }

  /**
   * Deleting or leaving a world must never reset the epoch high-water mark
   * (protocol invariant I3): retire the runtime's epoch into
   * worlds.last_runtime_epoch before dropping the row.
   */
  private async retireWorldRuntime(worldId: string, hostUuid: string | null): Promise<void> {
    if (hostUuid == null) {
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
      return;
    }
    await this.run(
      `UPDATE worlds
       SET last_runtime_epoch = MAX(
         COALESCE(last_runtime_epoch, 0),
         COALESCE((SELECT runtime_epoch FROM world_runtime WHERE world_id = ? AND host_uuid = ?), 0)
       )
       WHERE id = ?`,
      worldId,
      hostUuid,
      worldId
    );
    await this.run("DELETE FROM world_runtime WHERE world_id = ? AND host_uuid = ?", worldId, hostUuid);
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

  async cancelStorageLinkSession(sessionId: string, completedAt: string): Promise<void> {
    await this.run(
      `UPDATE storage_link_sessions
       SET status = 'cancelled', error_message = NULL, completed_at = ?
       WHERE id = ? AND status = 'pending'`,
      completedAt,
      sessionId
    );
  }

  async cancelPendingStorageLinkSessions(playerUuid: string, provider: StorageProviderType, exceptSessionId: string, completedAt: string): Promise<void> {
    await this.run(
      `UPDATE storage_link_sessions
       SET status = 'cancelled', error_message = NULL, completed_at = ?
       WHERE player_uuid = ? AND provider = ? AND id <> ? AND status = 'pending'`,
      completedAt,
      playerUuid,
      provider,
      exceptSessionId
    );
  }

  async updateStorageLinkSession(sessionId: string, update: Partial<Pick<StorageLinkSessionRecord, "status" | "linkedAccountEmail" | "accountDisplayName" | "errorMessage" | "storageAccountId" | "completedAt">>): Promise<void> {
    const current = await this.getStorageLinkSession(sessionId);
    if (!current) {
      return;
    }
    // Present-but-null fields are explicit clears; only absent fields keep
    // their current value.
    const pick = <K extends keyof typeof update>(key: K, fallback: StorageLinkSessionRecord[K & keyof StorageLinkSessionRecord]) =>
      key in update ? update[key] ?? null : fallback;
    await this.run(
      `UPDATE storage_link_sessions
       SET status = ?, linked_account_email = ?, account_display_name = ?, error_message = ?, storage_account_id = ?, completed_at = ?
       WHERE id = ?`,
      pick("status", current.status),
      pick("linkedAccountEmail", current.linkedAccountEmail),
      pick("accountDisplayName", current.accountDisplayName),
      pick("errorMessage", current.errorMessage),
      pick("storageAccountId", current.storageAccountId),
      pick("completedAt", current.completedAt),
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

  async findStorageAccountsByOwner(provider: StorageProviderType, ownerPlayerUuid: string): Promise<StorageAccountRecord[]> {
    const rows = await this.all<Row>(
      `SELECT id, provider, owner_player_uuid, external_account_id, email, display_name,
              access_token, refresh_token, token_expires_at, created_at, updated_at
       FROM storage_accounts
       WHERE provider = ? AND owner_player_uuid = ?
       ORDER BY updated_at DESC, id DESC`,
      provider,
      ownerPlayerUuid
    );
    return rows.map(mapStorageAccount);
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

  /**
   * Self-healing guard for concurrent invite resets: whatever interleaving
   * produced multiple active codes, only the newest survives.
   */
  async revokeSupersededInvites(worldId: string): Promise<void> {
    await this.run(
      `UPDATE invite_codes SET status = 'revoked'
       WHERE world_id = ? AND status = 'active'
         AND id != (
           SELECT id FROM invite_codes
           WHERE world_id = ? AND status = 'active'
           ORDER BY created_at DESC, id DESC
           LIMIT 1
         )`,
      worldId,
      worldId
    );
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
       ORDER BY created_at DESC, id DESC
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
         deleted_at = NULL,
         can_use_commands = 0`,
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

  async getRuntimeRecord(worldId: string, _now: Date): Promise<WorldRuntimeRecord | null> {
    const row = await this.first<Row>(
      `SELECT world_id, host_uuid, host_player_name, runtime_phase, runtime_epoch, runtime_token,
              claimed_at, expires_at, join_target, candidate_uuid, revoked_at,
              startup_deadline_at, runtime_token_issued_at, last_progress_at,
              startup_progress_label, startup_progress_mode, startup_progress_fraction, startup_progress_updated_at, updated_at,
              host_minecraft_version
       FROM world_runtime WHERE world_id = ?`,
      worldId
    );
    return row ? mapRuntimeRow(row) : null;
  }

  /**
   * Unfenced write used only for test seeding; production session flows go
   * through claimRuntimeAssignment / updateAuthorizedRuntime so a stale writer
   * can never overwrite a newer runtime epoch.
   */
  async upsertRuntimeRecord(runtime: WorldRuntimeRecord): Promise<void> {
    await this.run(RUNTIME_INSERT_SQL, ...runtimeInsertValues(runtime));
  }

  /**
   * Fresh host assignment. The conditional upsert only lands when no runtime row
   * exists or the existing row carries a strictly older epoch, so two racing
   * acquires can never both install the same epoch; the loser sees false.
   */
  async claimRuntimeAssignment(runtime: WorldRuntimeRecord): Promise<boolean> {
    const changes = await this.runWithChanges(
      `${RUNTIME_INSERT_SQL}
       WHERE world_runtime.runtime_epoch < excluded.runtime_epoch`,
      ...runtimeInsertValues(runtime)
    );
    return changes > 0;
  }

  /**
   * Refresh-style write (heartbeat, progress, phase transition) fenced on the
   * record's own epoch/token. A no-op means the caller's runtime was replaced
   * between its read and this write, and the caller must treat that as lost
   * authority instead of resurrecting the old row.
   */
  async updateAuthorizedRuntime(runtime: WorldRuntimeRecord): Promise<boolean> {
    if (runtime.runtimeToken == null) {
      return false;
    }
    const changes = await this.runWithChanges(RUNTIME_UPDATE_SQL, ...runtimeUpdateValues(runtime));
    return changes > 0;
  }

  async deleteRuntimeRecord(worldId: string, expected: { runtimeEpoch: number; runtimeToken: string | null }): Promise<boolean> {
    const changes = await this.runWithChanges(
      `DELETE FROM world_runtime
       WHERE world_id = ? AND runtime_epoch = ? AND COALESCE(runtime_token, '') = COALESCE(?, '')`,
      worldId,
      expected.runtimeEpoch,
      expected.runtimeToken
    );
    // The epoch high-water mark feeds release-replay detection ([P1]/[I3]).
    // A successful fenced delete records its own epoch. A delete that lost the
    // fence may still record its epoch as retired history, but only strictly
    // below the currently active runtime's epoch — a stale or hostile caller
    // must never move the mark past (or up to) the active authority.
    await this.run(
      `UPDATE worlds
       SET last_runtime_epoch = MAX(COALESCE(last_runtime_epoch, 0), ?)
       WHERE id = ?
         AND (? > 0 OR ? < COALESCE((SELECT runtime_epoch FROM world_runtime WHERE world_id = worlds.id), 0))`,
      expected.runtimeEpoch,
      worldId,
      changes,
      expected.runtimeEpoch
    );
    return changes > 0;
  }

  async getLastRuntimeEpoch(worldId: string): Promise<number> {
    const row = await this.first<Row>(
      "SELECT last_runtime_epoch FROM worlds WHERE id = ?",
      worldId
    );
    return row?.last_runtime_epoch == null ? 0 : Number(row.last_runtime_epoch);
  }

  async getUncleanShutdownWarning(worldId: string): Promise<UncleanShutdownWarning | null> {
    const row = await this.first<Row>(
      `SELECT unclean_shutdown_host_uuid, unclean_shutdown_host_player_name, unclean_shutdown_phase, unclean_shutdown_runtime_epoch, unclean_shutdown_recorded_at
       FROM worlds
       WHERE id = ?`,
      worldId
    );
    return mapUncleanShutdownWarning(row);
  }

  async setUncleanShutdownWarning(worldId: string, warning: UncleanShutdownWarning): Promise<void> {
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

  async getLatestSnapshot(worldId: string): Promise<SnapshotManifest | null> {
    const snapshot = await this.first<Row>(
      `SELECT id, world_id, created_at, created_by_uuid
       FROM snapshots
       WHERE world_id = ?
       ORDER BY created_at DESC, id DESC
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
      `SELECT s.id, s.created_at, s.created_by_uuid, s.data_version, s.minecraft_version
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
        dataVersion: row.data_version == null ? null : Number(row.data_version),
        minecraftVersion: asNullableString(row.minecraft_version),
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
       ORDER BY created_at DESC, id DESC`,
      worldId
    );
    return rows.map((row) => ({
      snapshotId: String(row.id),
      worldId: String(row.world_id),
      createdAt: String(row.created_at),
      createdByUuid: String(row.created_by_uuid)
    }));
  }

  async getSnapshotGameVersions(worldId: string, snapshotId: string): Promise<{ dataVersion: number | null; minecraftVersion: string | null } | null> {
    const row = await this.first<Row>(
      "SELECT data_version, minecraft_version FROM snapshots WHERE world_id = ? AND id = ?",
      worldId,
      snapshotId
    );
    if (!row) {
      return null;
    }
    return {
      dataVersion: row.data_version == null ? null : Number(row.data_version),
      minecraftVersion: asNullableString(row.minecraft_version)
    };
  }

  async listSnapshotDeltaBases(worldId: string): Promise<Array<{ snapshotId: string; baseSnapshotId: string }>> {
    const fileRows = await this.all<Row>(
      `SELECT DISTINCT sf.snapshot_id, sf.base_snapshot_id
       FROM snapshot_files sf
       JOIN snapshots s ON s.id = sf.snapshot_id
       WHERE s.world_id = ? AND sf.base_snapshot_id IS NOT NULL`,
      worldId
    );
    const packRows = await this.all<Row>(
      `SELECT DISTINCT sp.snapshot_id, sp.base_snapshot_id
       FROM snapshot_packs sp
       JOIN snapshots s ON s.id = sp.snapshot_id
       WHERE s.world_id = ? AND sp.base_snapshot_id IS NOT NULL`,
      worldId
    );
    // Member-donor pointers (members_snapshot_id) are deliberately NOT edges
    // here: deleteSnapshots promotes inherited member rows to a surviving
    // heir, so donors never need to be kept alive for retention or deletion.
    return [...fileRows, ...packRows].map((row) => ({
      snapshotId: String(row.snapshot_id),
      baseSnapshotId: String(row.base_snapshot_id)
    }));
  }

  /**
   * Pack rows of a base snapshot, keyed by pack id, for member-row
   * inheritance during finalize. `membersSnapshotId` is the snapshot that
   * physically holds the pack's member rows (NULL = the base itself).
   */
  private async basePackRowsForInheritance(worldId: string, baseSnapshotId: string): Promise<Map<string, {
    hash: string;
    size: number;
    storageKey: string;
    transferMode: string;
    baseSnapshotId: string | null;
    baseHash: string | null;
    chainDepth: number | null;
    membersSnapshotId: string | null;
  }>> {
    const rows = await this.all<Row>(
      `SELECT sp.pack_id, sp.hash, sp.size, sp.storage_key, sp.transfer_mode,
              sp.base_snapshot_id, sp.base_hash, sp.chain_depth, sp.members_snapshot_id
       FROM snapshot_packs sp
       JOIN snapshots s ON s.id = sp.snapshot_id
       WHERE sp.snapshot_id = ? AND s.world_id = ?`,
      baseSnapshotId,
      worldId
    );
    return new Map(rows.map((row) => [String(row.pack_id), {
      hash: String(row.hash),
      size: Number(row.size),
      storageKey: String(row.storage_key),
      transferMode: String(row.transfer_mode),
      baseSnapshotId: asNullableString(row.base_snapshot_id),
      baseHash: asNullableString(row.base_hash),
      chainDepth: row.chain_depth == null ? null : Number(row.chain_depth),
      membersSnapshotId: asNullableString(row.members_snapshot_id)
    }]));
  }

  async finalizeSnapshot(worldId: string, ctx: RequestContext, request: FinalizeSnapshotRequest, now: Date): Promise<SnapshotManifest> {
    const snapshotId = `snapshot_${crypto.randomUUID().replace(/-/g, "")}`;
    const basePacks = request.baseSnapshotId != null
      ? await this.basePackRowsForInheritance(worldId, request.baseSnapshotId)
      : null;
    // One transactional batch: a failure mid-write must not leave a partial
    // snapshot behind, because a partial row would become the world's
    // "latest" manifest.
    const statements = [
      this.prepared(
        `INSERT INTO snapshots (id, world_id, created_at, created_by_uuid, base_snapshot_id, data_version, minecraft_version)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
        snapshotId,
        worldId,
        now.toISOString(),
        ctx.playerUuid,
        request.baseSnapshotId ?? null,
        request.dataVersion ?? null,
        request.minecraftVersion ?? null
      )
    ];
    const fileInsert = `INSERT INTO snapshot_files (
          snapshot_id, path, hash, size, compressed_size, pack_id, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`;
    for (const file of request.files) {
      statements.push(this.prepared(
        fileInsert,
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
      ));
    }
    for (const pack of request.packs ?? []) {
      // A pack identical to the base snapshot's pack inherits that pack's
      // member rows instead of re-inserting them, flattened to the snapshot
      // that physically holds them (one hop, never a chain). Equality is
      // judged on the same fields the pack row stores — the same trust model
      // as materialized inserts, which never verify member lists either.
      const base = basePacks?.get(pack.packId);
      const inheritFrom = base != null
        && pack.hash === base.hash
        && pack.size === base.size
        && pack.storageKey === base.storageKey
        && pack.transferMode === base.transferMode
        && (pack.baseSnapshotId ?? null) === base.baseSnapshotId
        && (pack.baseHash ?? null) === base.baseHash
        && (pack.chainDepth ?? null) === base.chainDepth
        ? (base.membersSnapshotId ?? request.baseSnapshotId ?? null)
        : null;
      statements.push(this.prepared(
        `INSERT INTO snapshot_packs (
          snapshot_id, pack_id, hash, size, storage_key, transfer_mode, base_snapshot_id, base_hash, chain_depth, members_snapshot_id
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        snapshotId,
        pack.packId,
        pack.hash,
        pack.size,
        pack.storageKey,
        pack.transferMode,
        pack.baseSnapshotId ?? null,
        pack.baseHash ?? null,
        pack.chainDepth ?? null,
        inheritFrom
      ));
      if (inheritFrom != null) {
        continue;
      }
      for (const file of pack.files) {
        statements.push(this.prepared(
          fileInsert,
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
        ));
      }
    }
    await this.batch(statements);
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

    // Member-row promotion: surviving packs that inherit their member rows
    // from a doomed snapshot get those rows copied to the OLDEST surviving
    // heir before the donor is deleted; every other heir is repointed at the
    // new physical holder. This keeps every surviving manifest loadable
    // without retention ever having to keep donor snapshots alive.
    const referrerRows = await this.all<Row>(
      `SELECT sp.snapshot_id, sp.pack_id, sp.members_snapshot_id
       FROM snapshot_packs sp
       JOIN snapshots s ON s.id = sp.snapshot_id
       WHERE s.world_id = ?
         AND sp.members_snapshot_id IN (${deletePlaceholders})
         AND sp.snapshot_id NOT IN (${deletePlaceholders})
       ORDER BY s.created_at ASC, s.id ASC`,
      worldId,
      ...deletedSnapshotIds,
      ...deletedSnapshotIds
    );
    const statements: ReturnType<D1Database["prepare"]>[] = [];
    const promotionTargets = new Set<string>();
    for (const row of referrerRows) {
      const donorId = String(row.members_snapshot_id);
      const packId = String(row.pack_id);
      const key = `${donorId} ${packId}`;
      if (promotionTargets.has(key)) {
        continue;
      }
      promotionTargets.add(key);
      const targetId = String(row.snapshot_id);
      statements.push(this.prepared(
        `INSERT INTO snapshot_files (
           snapshot_id, path, hash, size, compressed_size, pack_id, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
         )
         SELECT ?, path, hash, size, compressed_size, pack_id, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
         FROM snapshot_files
         WHERE snapshot_id = ? AND pack_id = ?`,
        targetId,
        donorId,
        packId
      ));
      statements.push(this.prepared(
        "UPDATE snapshot_packs SET members_snapshot_id = ? WHERE pack_id = ? AND members_snapshot_id = ?",
        targetId,
        packId,
        donorId
      ));
      statements.push(this.prepared(
        "UPDATE snapshot_packs SET members_snapshot_id = NULL WHERE snapshot_id = ? AND pack_id = ?",
        targetId,
        packId
      ));
    }
    statements.push(this.prepared(
      `DELETE FROM snapshot_files
       WHERE snapshot_id IN (${deletePlaceholders})`,
      ...deletedSnapshotIds
    ));
    statements.push(this.prepared(
      `DELETE FROM snapshot_packs
       WHERE snapshot_id IN (${deletePlaceholders})`,
      ...deletedSnapshotIds
    ));
    statements.push(this.prepared(
      `DELETE FROM snapshots
       WHERE world_id = ? AND id IN (${deletePlaceholders})`,
      worldId,
      ...deletedSnapshotIds
    ));
    // One transactional batch: promotion and deletion land together, so a
    // failure can never leave an heir pointing at a vanished donor.
    await this.batch(statements);

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
      "SELECT id, slug, name, motd, custom_icon_storage_key, owner_uuid, storage_provider, storage_account_id, settings, settings_revision FROM worlds WHERE id = ?",
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
    const latestVersions = await this.first<Row>(
      "SELECT data_version, minecraft_version FROM snapshots WHERE world_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
      worldId
    );
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
      lastSnapshotDataVersion: latestVersions == null ? null : (latestVersions.data_version == null ? null : Number(latestVersions.data_version)),
      lastSnapshotMinecraftVersion: latestVersions == null ? null : asNullableString(latestVersions.minecraft_version),
      activeHostUuid: lifecycle.activeHostUuid,
      activeHostPlayerName: lifecycle.activeHostPlayerName,
      activeJoinTarget: lifecycle.activeJoinTarget,
      onlinePlayerCount: onlinePlayers.length,
      onlinePlayerNames: onlinePlayers.map((entry) => entry.playerName),
      storageProvider: String(world.storage_provider ?? "google-drive") as StorageProviderType,
      storageLinked: asNullableString(world.storage_account_id) != null,
      storageAccountEmail: asNullableString(
        (await this.first<Row>("SELECT email FROM storage_accounts WHERE id = ?", asNullableString(world.storage_account_id)))?.email
      ),
      settings: parseWorldSettings(world.settings),
      settingsRevision: Number(world.settings_revision ?? 0)
    };
  }

  async listMemberships(worldId: string): Promise<WorldMembership[]> {
    const rows = await this.all<Row>(
      `SELECT world_id, player_uuid, player_name, role, joined_at, deleted_at, can_use_commands
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
      deletedAt: asNullableString(row.deleted_at),
      canUseCommands: Number(row.can_use_commands) !== 0
    }));
  }

  async setMembershipCommandPermission(worldId: string, playerUuid: string, canUseCommands: boolean): Promise<boolean> {
    const changes = await this.runWithChanges(
      `UPDATE world_memberships
       SET can_use_commands = ?
       WHERE world_id = ? AND player_uuid = ? AND deleted_at IS NULL`,
      canUseCommands ? 1 : 0,
      worldId,
      playerUuid
    );
    return changes > 0;
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
      `SELECT pack_id, hash, size, storage_key, transfer_mode, base_snapshot_id, base_hash, chain_depth, members_snapshot_id
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
      packs: await Promise.all(packRows.map(async (row) => {
        // Inherited packs resolve member rows from the donor snapshot that
        // physically holds them (members_snapshot_id, always one hop).
        const membersSnapshotId = asNullableString(row.members_snapshot_id) ?? snapshotId;
        const memberRows = await this.all<Row>(
          `SELECT path, hash, size, content_type
           FROM snapshot_files
           WHERE snapshot_id = ? AND pack_id = ?
           ORDER BY path ASC`,
          membersSnapshotId,
          String(row.pack_id)
        );
        if (memberRows.length === 0 && membersSnapshotId !== snapshotId) {
          console.warn("SharedWorld snapshot pack inherited zero member rows — donor missing?", {
            snapshotId,
            packId: String(row.pack_id),
            membersSnapshotId
          });
        }
        return {
          packId: String(row.pack_id),
          hash: String(row.hash),
          size: Number(row.size),
          storageKey: String(row.storage_key),
          transferMode: String(row.transfer_mode) as FileTransferMode,
          baseSnapshotId: asNullableString(row.base_snapshot_id),
          baseHash: asNullableString(row.base_hash),
          chainDepth: row.chain_depth == null ? null : Number(row.chain_depth),
          files: memberRows.map((fileRow) => ({
            path: String(fileRow.path),
            hash: String(fileRow.hash),
            size: Number(fileRow.size),
            contentType: String(fileRow.content_type)
          }))
        };
      }))
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

  private prepared(query: string, ...values: unknown[]) {
    return this.db.prepare(query).bind(...normalizeBoundValues(values));
  }

  /** All statements land or none do (D1 batches are transactional). */
  private async batch(statements: ReturnType<D1Database["prepare"]>[]): Promise<void> {
    if (statements.length === 0) {
      return;
    }
    await this.db.batch(statements);
  }

  private async runWithChanges(query: string, ...values: unknown[]): Promise<number> {
    const result = await this.db.prepare(query).bind(...normalizeBoundValues(values)).run();
    return Number(result.meta?.changes ?? 0);
  }

  private async currentCustomIconStorageKey(worldId: string): Promise<string | null> {
    const row = await this.first<Row>(
      "SELECT custom_icon_storage_key FROM worlds WHERE id = ?",
      worldId
    );
    return asNullableString(row?.custom_icon_storage_key);
  }

  private async summaryLifecycle(worldId: string, now: Date): Promise<{
    status: WorldSummary["status"];
    activeHostUuid: string | null;
    activeHostPlayerName: string | null;
    activeJoinTarget: string | null;
  }> {
    const resolved = await reconcileRuntimeState(this, worldId, now);
    if (resolved.runtime != null) {
      return {
        status: runtimePhaseToWorldStatus(resolved.runtime.phase),
        activeHostUuid: resolved.runtime.hostUuid,
        activeHostPlayerName: resolved.runtime.hostPlayerName,
        activeJoinTarget: resolved.runtime.joinTarget
      };
    }
    return {
      status: resolved.candidate == null ? "idle" : "handoff",
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
    const runtime = (await reconcileRuntimeState(this, worldId, now)).runtime;
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

}

function parseWorldSettings(raw: unknown): WorldSettings | null {
  const text = asNullableString(raw);
  if (text == null) {
    return null;
  }
  try {
    const parsed = JSON.parse(text) as unknown;
    return typeof parsed === "object" && parsed != null && !Array.isArray(parsed) ? parsed as WorldSettings : null;
  } catch {
    return null;
  }
}

/**
 * Single source of truth for the world_runtime column set. All three runtime
 * writes (seed upsert, fenced claim, fenced refresh) are generated from this
 * list, so adding a runtime column means editing it, runtimeRowValues, and
 * mapRuntimeRow — nowhere else.
 */
const RUNTIME_COLUMNS = [
  "world_id",
  "host_uuid",
  "host_player_name",
  "runtime_phase",
  "runtime_epoch",
  "runtime_token",
  "claimed_at",
  "expires_at",
  "join_target",
  "candidate_uuid",
  "revoked_at",
  "startup_deadline_at",
  "runtime_token_issued_at",
  "last_progress_at",
  "startup_progress_label",
  "startup_progress_mode",
  "startup_progress_fraction",
  "startup_progress_updated_at",
  "updated_at",
  "host_minecraft_version"
] as const;

const RUNTIME_FENCE_COLUMNS: ReadonlyArray<string> = ["world_id", "runtime_epoch", "runtime_token"];

const RUNTIME_INSERT_SQL = `INSERT INTO world_runtime (${RUNTIME_COLUMNS.join(", ")})
       VALUES (${RUNTIME_COLUMNS.map(() => "?").join(", ")})
       ON CONFLICT(world_id) DO UPDATE SET
         ${RUNTIME_COLUMNS.filter((column) => column !== "world_id").map((column) => `${column} = excluded.${column}`).join(",\n         ")}`;

const RUNTIME_UPDATE_SQL = `UPDATE world_runtime SET
         ${RUNTIME_COLUMNS.filter((column) => !RUNTIME_FENCE_COLUMNS.includes(column)).map((column) => `${column} = ?`).join(",\n         ")}
       WHERE world_id = ? AND runtime_epoch = ? AND runtime_token = ?`;

function runtimeRowValues(runtime: WorldRuntimeRecord): Record<(typeof RUNTIME_COLUMNS)[number], unknown> {
  return {
    world_id: runtime.worldId,
    host_uuid: runtime.hostUuid,
    host_player_name: runtime.hostPlayerName,
    runtime_phase: runtime.phase,
    runtime_epoch: runtime.runtimeEpoch,
    runtime_token: runtime.runtimeToken ?? null,
    claimed_at: runtime.claimedAt ?? runtime.updatedAt,
    expires_at: runtime.expiresAt ?? null,
    join_target: runtime.joinTarget,
    candidate_uuid: runtime.candidateUuid,
    revoked_at: runtime.revokedAt ?? null,
    startup_deadline_at: runtime.startupDeadlineAt ?? null,
    runtime_token_issued_at: runtime.runtimeTokenIssuedAt ?? null,
    last_progress_at: runtime.lastProgressAt ?? null,
    startup_progress_label: runtime.startupProgress?.label ?? null,
    startup_progress_mode: runtime.startupProgress?.mode ?? null,
    startup_progress_fraction: runtime.startupProgress?.fraction ?? null,
    startup_progress_updated_at: runtime.startupProgress?.updatedAt ?? null,
    updated_at: runtime.updatedAt,
    host_minecraft_version: runtime.hostMinecraftVersion ?? null
  };
}

function runtimeInsertValues(runtime: WorldRuntimeRecord): unknown[] {
  const values = runtimeRowValues(runtime);
  return RUNTIME_COLUMNS.map((column) => values[column]);
}

function runtimeUpdateValues(runtime: WorldRuntimeRecord): unknown[] {
  const values = runtimeRowValues(runtime);
  return [
    ...RUNTIME_COLUMNS.filter((column) => !RUNTIME_FENCE_COLUMNS.includes(column)).map((column) => values[column]),
    values.world_id,
    values.runtime_epoch,
    values.runtime_token
  ];
}
