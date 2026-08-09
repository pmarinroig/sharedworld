import type {
  FileTransferMode,
  FinalizeSnapshotRequest,
  InviteCode,
  KickMemberResponse,
  StorageProviderType,
  StorageUsageSummary,
  SnapshotManifest,
  WorldDetails,
  WorldMembership,
  WorldSettings,
  WorldSnapshotSummary,
  WorldSummary
} from "../../shared/src/index.ts";
import { type SessionToken } from "../../shared/src/index.ts";

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
  WorldUpdateRecord
} from "./repository.ts";
import { runtimePhaseToWorldStatus } from "./runtime-protocol.ts";
import {
  mapInvite,
  mapStorageAccount,
  mapStorageLinkSession,
  mapStorageObject
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

  async updateWorldSettingsIfRevision(worldId: string, settingsJson: string, expectedRevision: number): Promise<boolean> {
    const changes = await this.runWithChanges(
      `UPDATE worlds
       SET settings = ?, settings_revision = settings_revision + 1
       WHERE id = ? AND deleted_at IS NULL AND settings_revision = ?`,
      settingsJson,
      worldId,
      expectedRevision
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
    return { worldDeleted: true, deletedCustomIconStorageKey };
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

  async listExistingStorageKeys(
    provider: StorageProviderType,
    storageAccountId: string,
    storageKeys: readonly string[]
  ): Promise<Set<string>> {
    const existing = new Set<string>();
    // D1 caps bound parameters per query; stay comfortably under it. Large
    // worlds carry hundreds of packs, and a per-key query here put upload
    // prepare/finalize over the client's request timeout.
    const CHUNK = 80;
    for (let offset = 0; offset < storageKeys.length; offset += CHUNK) {
      const chunk = storageKeys.slice(offset, offset + CHUNK);
      const rows = await this.all<Row>(
        `SELECT storage_key
         FROM storage_objects
         WHERE provider = ? AND storage_account_id = ? AND storage_key IN (${sqlPlaceholders(chunk.length)})`,
        provider,
        storageAccountId,
        ...chunk
      );
      for (const row of rows) {
        existing.add(String(row.storage_key));
      }
    }
    return existing;
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
    return {
      worldId,
      removedPlayerUuid
    };
  }

  async upsertRuntimeMirror(worldId: string, statusJson: string | null, roomPlayersJson: string | null): Promise<void> {
    await this.run(
      `INSERT INTO world_runtime_mirror (world_id, status_json, room_players_json, updated_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT(world_id) DO UPDATE SET
         status_json = COALESCE(excluded.status_json, world_runtime_mirror.status_json),
         room_players_json = COALESCE(excluded.room_players_json, world_runtime_mirror.room_players_json),
         updated_at = excluded.updated_at`,
      worldId,
      statusJson,
      roomPlayersJson,
      new Date().toISOString()
    );
  }

  async getRuntimeMirror(worldId: string): Promise<{ statusJson: string | null; roomPlayersJson: string | null } | null> {
    const row = await this.first<Row>(
      "SELECT status_json, room_players_json FROM world_runtime_mirror WHERE world_id = ?",
      worldId
    );
    if (!row) {
      return null;
    }
    return {
      statusJson: row.status_json == null ? null : String(row.status_json),
      roomPlayersJson: row.room_players_json == null ? null : String(row.room_players_json)
    };
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

  /**
   * Set-based on purpose: the old shape loaded every snapshot's full manifest
   * plus a per-snapshot storage query (O(snapshots × packs) D1 round-trips),
   * which blew the Worker CPU budget on large worlds. The whole list now
   * costs four fixed queries regardless of world or history size.
   */
  async listSnapshotSummaries(worldId: string): Promise<WorldSnapshotSummary[]> {
    const world = await this.first<Row>(
      "SELECT storage_provider, storage_account_id FROM worlds WHERE id = ? AND deleted_at IS NULL",
      worldId
    );
    if (!world) {
      return [];
    }
    const rows = await this.all<Row>(
      `SELECT s.id, s.created_at, s.created_by_uuid, s.data_version, s.minecraft_version
       FROM snapshots s
       WHERE s.world_id = ?
       ORDER BY s.created_at DESC, s.id DESC`,
      worldId
    );
    if (rows.length === 0) {
      return [];
    }
    // Same ordering the dedicated latest-snapshot query uses, so the first
    // row is the latest snapshot.
    const latestSnapshotId = String(rows[0].id);

    const looseAggregates = await this.all<Row>(
      `SELECT sf.snapshot_id AS sid, COUNT(*) AS n, COALESCE(SUM(sf.size), 0) AS total
       FROM snapshot_files sf
       JOIN snapshots s ON s.id = sf.snapshot_id
       WHERE s.world_id = ? AND sf.pack_id IS NULL
       GROUP BY sf.snapshot_id`,
      worldId
    );
    // Pack members resolve through the donor snapshot that physically holds
    // them (members_snapshot_id, always one hop); LEFT JOIN so a pack with a
    // missing donor degrades to zero members instead of dropping the pack.
    const packAggregates = await this.all<Row>(
      `SELECT sp.snapshot_id AS sid, COUNT(sf.path) AS n, COALESCE(SUM(sf.size), 0) AS total
       FROM snapshot_packs sp
       JOIN snapshots s ON s.id = sp.snapshot_id
       LEFT JOIN snapshot_files sf
         ON sf.snapshot_id = COALESCE(sp.members_snapshot_id, sp.snapshot_id)
        AND sf.pack_id = sp.pack_id
       WHERE s.world_id = ?
       GROUP BY sp.snapshot_id`,
      worldId
    );
    // Identical per-snapshot dedupe semantics as the old per-snapshot CTE —
    // the grouping key just gains the snapshot id so one query answers all.
    const storedAggregates = await this.all<Row>(
      `WITH referenced_keys AS (
         SELECT sf.snapshot_id AS sid, sf.storage_key AS storage_key, MAX(sf.compressed_size) AS fallback_size
         FROM snapshot_files sf
         JOIN snapshots s ON s.id = sf.snapshot_id
         WHERE s.world_id = ? AND sf.pack_id IS NULL
         GROUP BY sf.snapshot_id, sf.storage_key
         UNION
         SELECT sp.snapshot_id AS sid, sp.storage_key AS storage_key, NULL AS fallback_size
         FROM snapshot_packs sp
         JOIN snapshots s ON s.id = sp.snapshot_id
         WHERE s.world_id = ?
       ),
       deduped_keys AS (
         SELECT sid, storage_key, MAX(fallback_size) AS fallback_size
         FROM referenced_keys
         GROUP BY sid, storage_key
       )
       SELECT dk.sid AS sid, COALESCE(SUM(COALESCE(so.size, dk.fallback_size, 0)), 0) AS used
       FROM deduped_keys dk
       LEFT JOIN storage_objects so
         ON so.provider = ?
        AND so.storage_account_id = ?
        AND so.storage_key = dk.storage_key
       GROUP BY dk.sid`,
      worldId,
      worldId,
      String(world.storage_provider ?? "google-drive"),
      String(world.storage_account_id ?? "")
    );

    const looseBySnapshot = new Map(looseAggregates.map((row) => [String(row.sid), row]));
    const packsBySnapshot = new Map(packAggregates.map((row) => [String(row.sid), row]));
    const storedBySnapshot = new Map(storedAggregates.map((row) => [String(row.sid), row]));
    return rows.map((row) => {
      const loose = looseBySnapshot.get(String(row.id));
      const packs = packsBySnapshot.get(String(row.id));
      const stored = storedBySnapshot.get(String(row.id));
      return {
        snapshotId: String(row.id),
        createdAt: String(row.created_at),
        createdByUuid: String(row.created_by_uuid),
        dataVersion: row.data_version == null ? null : Number(row.data_version),
        minecraftVersion: asNullableString(row.minecraft_version),
        fileCount: Number(loose?.n ?? 0) + Number(packs?.n ?? 0),
        totalSize: Number(loose?.total ?? 0) + Number(packs?.total ?? 0),
        totalCompressedSize: Number(stored?.used ?? 0),
        isLatest: String(row.id) === latestSnapshotId
      };
    });
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
      const key = `${donorId}\u0000${packId}`;
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
    const lifecycle = await this.summaryLifecycle(worldId);
    const latest = await this.getLatestSnapshot(worldId);
    const latestVersions = await this.first<Row>(
      "SELECT data_version, minecraft_version FROM snapshots WHERE world_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
      worldId
    );
    const onlinePlayers = await this.listOnlinePlayers(worldId);
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
      packs: await this.loadSnapshotPacks(snapshotId, packRows)
    };
  }

  /**
   * Resolves every pack's member rows in one query instead of one per pack —
   * large worlds carry 100+ capped bundle/shard packs, and a per-pack query
   * here put whole-manifest loads (session enter, upload/download plans,
   * backup lists) over the Worker CPU budget. Inherited packs resolve their
   * members from the donor snapshot that physically holds them
   * (members_snapshot_id, always one hop).
   */
  private async loadSnapshotPacks(snapshotId: string, packRows: Row[]): Promise<SnapshotManifest["packs"]> {
    if (packRows.length === 0) {
      return [];
    }
    const memberSnapshotIds = [...new Set(packRows.map((row) => asNullableString(row.members_snapshot_id) ?? snapshotId))];
    const memberRows = await this.all<Row>(
      `SELECT snapshot_id, pack_id, path, hash, size, content_type
       FROM snapshot_files
       WHERE pack_id IS NOT NULL AND snapshot_id IN (${sqlPlaceholders(memberSnapshotIds.length)})
       ORDER BY path ASC`,
      ...memberSnapshotIds
    );
    const membersByPack = new Map<string, Array<{ path: string; hash: string; size: number; contentType: string }>>();
    for (const fileRow of memberRows) {
      const key = `${String(fileRow.snapshot_id)}\u0000${String(fileRow.pack_id)}`;
      let members = membersByPack.get(key);
      if (!members) {
        members = [];
        membersByPack.set(key, members);
      }
      members.push({
        path: String(fileRow.path),
        hash: String(fileRow.hash),
        size: Number(fileRow.size),
        contentType: String(fileRow.content_type)
      });
    }
    return packRows.map((row) => {
      const membersSnapshotId = asNullableString(row.members_snapshot_id) ?? snapshotId;
      const members = membersByPack.get(`${membersSnapshotId}\u0000${String(row.pack_id)}`) ?? [];
      if (members.length === 0 && membersSnapshotId !== snapshotId) {
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
        files: members
      };
    });
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

  private async mirroredRuntime(worldId: string): Promise<{
    status: import("../../shared/src/index.ts").WorldRuntimeStatus | null;
    roomPlayers: Array<{ playerUuid: string; playerName: string }>;
  }> {
    const mirror = await this.getRuntimeMirror(worldId);
    return {
      status: mirror?.statusJson == null
        ? null
        : JSON.parse(mirror.statusJson) as import("../../shared/src/index.ts").WorldRuntimeStatus,
      roomPlayers: mirror?.roomPlayersJson == null
        ? []
        : JSON.parse(mirror.roomPlayersJson) as Array<{ playerUuid: string; playerName: string }>
    };
  }

  private async summaryLifecycle(worldId: string): Promise<{
    status: WorldSummary["status"];
    activeHostUuid: string | null;
    activeHostPlayerName: string | null;
    activeJoinTarget: string | null;
  }> {
    const { status } = await this.mirroredRuntime(worldId);
    if (status != null && (status.phase === "host-starting" || status.phase === "host-live" || status.phase === "host-finalizing")) {
      return {
        status: runtimePhaseToWorldStatus(status.phase),
        activeHostUuid: status.hostUuid,
        activeHostPlayerName: status.hostPlayerName,
        activeJoinTarget: status.joinTarget
      };
    }
    return {
      status: status?.phase === "handoff-waiting" ? "handoff" : "idle",
      activeHostUuid: null,
      activeHostPlayerName: null,
      activeJoinTarget: null
    };
  }

  /**
   * Online players come straight from the coordinator-maintained mirror: the
   * room roster (host-reported, or legacy self-reports), plus the active
   * host itself while a hosting session is up.
   */
  private async listOnlinePlayers(worldId: string): Promise<Array<{ playerUuid: string; playerName: string }>> {
    const { status, roomPlayers } = await this.mirroredRuntime(worldId);
    // Identities arrive in mixed shapes (the in-game roster reports
    // hyphenated UUIDs, backend records may be bare 32-char) — the project
    // rule is hyphen-insensitive comparison, so the dedupe key must be too.
    const canonical = (uuid: string) => uuid.replace(/-/g, "").toLowerCase();
    const players = new Map<string, { playerUuid: string; playerName: string }>();
    if (status != null
      && (status.phase === "host-starting" || status.phase === "host-live")
      && status.hostUuid != null
      && status.hostPlayerName != null) {
      players.set(canonical(status.hostUuid), { playerUuid: status.hostUuid, playerName: status.hostPlayerName });
    }
    for (const player of roomPlayers) {
      const key = canonical(player.playerUuid);
      if (!players.has(key)) {
        players.set(key, { playerUuid: player.playerUuid, playerName: player.playerName });
      }
    }
    return [...players.values()];
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
