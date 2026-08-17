import type {
  FileTransferMode,
  FinalizeSnapshotRequest,
  InviteCode,
  KickMemberResponse,
  ManifestFile,
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
  StorageUploadSessionRecord,
  UserRecord,
  WorldUpdateRecord
} from "./repository.ts";
import type { SnapshotManifestCache } from "./manifest-cache.ts";
import { manifestUnavailable, type SnapshotManifestDocumentReader } from "./manifest-doc.ts";
import { runtimePhaseToWorldStatus } from "./runtime-protocol.ts";
import {
  mapInvite,
  mapStorageAccount,
  mapStorageLinkSession,
  mapStorageObject,
  mapUploadSession
} from "./repository/d1-row-mappers.ts";
import {
  asNullableString,
  joinMotdLines,
  normalizeBoundValues,
  sqlPlaceholders,
  type Row
} from "./repository/d1-support.ts";

/**
 * Newest snapshot id for one world as a correlated scalar. Relies on
 * idx_snapshots_world_created_id (world_id, created_at, id): with the `id`
 * tiebreak covered, SQLite answers this with a single reverse index step
 * instead of sorting the world's whole snapshot partition.
 */
const LATEST_SNAPSHOT_ID_SUBQUERY = (worldIdExpr: string): string =>
  `(SELECT s.id FROM snapshots s WHERE s.world_id = ${worldIdExpr} ORDER BY s.created_at DESC, s.id DESC LIMIT 1)`;

function looseFileOfRow(row: Row): ManifestFile {
  return {
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
  };
}

export class D1SharedWorldRepository implements SharedWorldRepository {
  /**
   * Resolves 0027 manifest documents from the world's storage provider.
   * Attached after construction (the provider itself is built over this
   * repository, so constructor injection would be a cycle). Null in
   * contexts that never read doc snapshots.
   */
  private manifestDocumentReader: SnapshotManifestDocumentReader | null = null;

  constructor(
    private readonly db: D1Database,
    private readonly manifestCache: SnapshotManifestCache | null = null
  ) {}

  attachManifestDocumentReader(reader: SnapshotManifestDocumentReader): void {
    this.manifestDocumentReader = reader;
  }

  async createChallenge(challenge: AuthChallengeRecord): Promise<void> {
    // Piggybacked bounded sweep: challenges are 5-minute one-shots and there
    // is no cron — without this the table grows forever. A matched-zero
    // DELETE costs nothing.
    await this.run(
      `DELETE FROM auth_challenges WHERE nonce IN (
         SELECT nonce FROM auth_challenges WHERE expires_at < ? LIMIT 25
       )`,
      new Date(Date.now() - 60 * 60_000).toISOString()
    );
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
    // Conditional update: a same-name login must not count as a row write.
    await this.run(
      `INSERT INTO users (player_uuid, player_name, created_at)
       VALUES (?, ?, ?)
       ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name
       WHERE excluded.player_name <> users.player_name`,
      user.playerUuid,
      user.playerName,
      user.createdAt
    );
  }

  async createSession(session: SessionToken): Promise<void> {
    // Piggybacked bounded sweep of long-expired sessions (no cron exists).
    await this.run(
      `DELETE FROM user_sessions WHERE token IN (
         SELECT token FROM user_sessions WHERE expires_at < ? LIMIT 25
       )`,
      new Date(Date.now() - 24 * 60 * 60_000).toISOString()
    );
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

    const worldIds = memberships.map((row) => String(row.id));
    const summaries = await this.buildWorldSummaries(worldIds);
    return worldIds
      .map((worldId) => summaries.get(worldId))
      .filter((summary): summary is WorldSummary => summary != null);
  }

  /**
   * Every D1 input that feeds the GET /worlds list response, as small
   * deterministic fact rows — no manifests, no summary building. The service
   * hashes this into the weak ETag; a matching If-None-Match then skips the
   * whole response build. storageUsage is deliberately absent: only 0.4.1+
   * clients send conditional requests, and their world bodies carry
   * storageUsage: null.
   */
  async worldsChangeFacts(playerUuid: string): Promise<unknown> {
    const worlds = await this.all<Row>(
      `SELECT w.id, w.name, w.motd, w.custom_icon_storage_key, w.storage_account_id, w.settings_revision, w.owner_uuid
       FROM worlds w
       JOIN world_memberships wm ON wm.world_id = w.id
       WHERE wm.player_uuid = ? AND wm.deleted_at IS NULL AND w.deleted_at IS NULL
       ORDER BY w.id ASC`,
      playerUuid
    );
    if (worlds.length === 0) {
      return { worlds: [] };
    }
    const memberWorldsFilter = `world_id IN (
       SELECT world_id FROM world_memberships WHERE player_uuid = ? AND deleted_at IS NULL
     )`;
    const memberships = await this.all<Row>(
      `SELECT world_id, player_uuid, player_name, role, can_use_commands, joined_at
       FROM world_memberships
       WHERE deleted_at IS NULL AND ${memberWorldsFilter}
       ORDER BY world_id ASC, player_uuid ASC`,
      playerUuid
    );
    const mirrors = await this.all<Row>(
      `SELECT world_id, updated_at FROM world_runtime_mirror
       WHERE ${memberWorldsFilter}
       ORDER BY world_id ASC`,
      playerUuid
    );
    // Latest snapshot per member world as a correlated 1-row index walk (see
    // migration 0029) — the former window query read every snapshot of every
    // member world on each poll.
    const latest = await this.all<Row>(
      `SELECT wm.world_id AS world_id, ${LATEST_SNAPSHOT_ID_SUBQUERY("wm.world_id")} AS id
       FROM world_memberships wm
       WHERE wm.player_uuid = ? AND wm.deleted_at IS NULL
       ORDER BY wm.world_id ASC`,
      playerUuid
    );
    const accountIds = [...new Set(worlds.map((row) => asNullableString(row.storage_account_id)).filter((id): id is string => id != null))].sort();
    const accounts = accountIds.length === 0 ? [] : await this.all<Row>(
      `SELECT id, email FROM storage_accounts WHERE id IN (${accountIds.map(() => "?").join(", ")}) ORDER BY id ASC`,
      ...accountIds
    );
    return { worlds, memberships, mirrors, latest, accounts };
  }

  /**
   * The single-world variant for GET /worlds/:id, including the owner-only
   * invite facts (folded to an is-valid boolean so a purely time-based
   * expiry still moves the token). Null when the caller has no access —
   * the handler then skips conditional handling and lets the service
   * produce its fresh 403/404.
   */
  async worldChangeFacts(worldId: string, playerUuid: string, now: Date): Promise<unknown | null> {
    const world = await this.first<Row>(
      `SELECT w.id, w.name, w.motd, w.custom_icon_storage_key, w.storage_account_id, w.settings_revision, w.owner_uuid
       FROM worlds w
       JOIN world_memberships wm ON wm.world_id = w.id AND wm.player_uuid = ? AND wm.deleted_at IS NULL
       WHERE w.id = ? AND w.deleted_at IS NULL`,
      playerUuid,
      worldId
    );
    if (!world) {
      return null;
    }
    const memberships = await this.all<Row>(
      `SELECT player_uuid, player_name, role, can_use_commands, joined_at
       FROM world_memberships
       WHERE world_id = ? AND deleted_at IS NULL
       ORDER BY player_uuid ASC`,
      worldId
    );
    const mirror = await this.first<Row>(
      "SELECT updated_at FROM world_runtime_mirror WHERE world_id = ?",
      worldId
    );
    const latest = await this.first<Row>(
      `SELECT id FROM snapshots WHERE world_id = ? ORDER BY created_at DESC, id DESC LIMIT 1`,
      worldId
    );
    const accountId = asNullableString(world.storage_account_id);
    const account = accountId == null ? null : await this.first<Row>(
      "SELECT email FROM storage_accounts WHERE id = ?",
      accountId
    );
    let invite: unknown = null;
    if (String(world.owner_uuid) === playerUuid) {
      const inviteRow = await this.first<Row>(
        `SELECT id, expires_at FROM invite_codes
         WHERE world_id = ? AND status = 'active'
         ORDER BY created_at DESC, id DESC
         LIMIT 1`,
        worldId
      );
      invite = inviteRow == null
        ? null
        : { id: String(inviteRow.id), valid: String(inviteRow.expires_at) >= now.toISOString() };
    }
    return {
      world,
      memberships,
      mirrorUpdatedAt: asNullableString(mirror?.updated_at),
      latestSnapshotId: latest == null ? null : String(latest.id),
      accountEmail: asNullableString(account?.email),
      invite
    };
  }

  async sessionActorFacts(worldId: string, playerUuid: string): Promise<{ membershipActive: boolean; everMember: boolean } | null> {
    // GROUP BY makes a missing/deleted world return zero rows (the caller's
    // 404) instead of an all-zero aggregate row.
    const row = await this.first<Row>(
      `SELECT MAX(CASE WHEN wm.player_uuid IS NOT NULL AND wm.deleted_at IS NULL THEN 1 ELSE 0 END) AS active,
              COUNT(wm.player_uuid) AS ever
       FROM worlds w
       LEFT JOIN world_memberships wm ON wm.world_id = w.id AND wm.player_uuid = ?
       WHERE w.id = ? AND w.deleted_at IS NULL
       GROUP BY w.id`,
      playerUuid,
      worldId
    );
    if (!row) {
      return null;
    }
    return {
      membershipActive: Number(row.active ?? 0) === 1,
      everMember: Number(row.ever ?? 0) > 0
    };
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
    // One transactional batch: the world row and the owner membership land
    // together or not at all.
    await this.batch([
      this.prepared(
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
      ),
      this.prepared(
        `INSERT INTO world_memberships (world_id, player_uuid, player_name, role, joined_at, deleted_at)
         VALUES (?, ?, ?, 'owner', ?, NULL)`,
        id,
        ctx.playerUuid,
        ctx.playerName,
        now
      )
    ]);
    const details = await this.getWorldDetails(id, ctx.playerUuid);
    if (!details) {
      throw new Error("World creation failed.");
    }
    return details;
  }

  async getWorldDetails(worldId: string, playerUuid: string): Promise<WorldDetails | null> {
    // The membership list doubles as the access gate and the member count —
    // no separate member-join or COUNT query.
    const memberships = await this.listMemberships(worldId);
    const membership = memberships.find((entry) => entry.playerUuid === playerUuid);
    if (!membership) {
      return null;
    }
    const summaries = await this.buildWorldSummaries([worldId], {
      memberCounts: new Map([[worldId, memberships.length]])
    });
    const summary = summaries.get(worldId);
    if (!summary) {
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
    // Legacy transition leg (pre-0026 pack rows) plus the JSON directories.
    const packReference = await this.first<Row>(
      "SELECT 1 AS found FROM snapshot_packs WHERE storage_key = ? LIMIT 1",
      storageKey
    );
    if (packReference) {
      return true;
    }
    const directoryReference = await this.first<Row>(
      `SELECT 1 AS found
       FROM snapshots, json_each(COALESCE(snapshots.packs_json, '[]')) AS pack
       WHERE json_extract(pack.value, '$.storageKey') = ?
       LIMIT 1`,
      storageKey
    );
    if (directoryReference) {
      return true;
    }
    // S1 chain recipes: a surviving snapshot's steps may reference blobs
    // whose original snapshot rows are long gone.
    const chainStepReference = await this.first<Row>(
      `SELECT 1 AS found
       FROM snapshots, json_each(COALESCE(snapshots.packs_json, '[]')) AS pack,
            json_each(COALESCE(json_extract(pack.value, '$.chainSteps'), '[]')) AS step
       WHERE json_extract(step.value, '$.storageKey') = ?
       LIMIT 1`,
      storageKey
    );
    if (chainStepReference) {
      return true;
    }
    // 0027 manifest documents (partial index idx_snapshots_manifest_storage_key).
    const manifestDocReference = await this.first<Row>(
      "SELECT 1 AS found FROM snapshots WHERE manifest_storage_key = ? LIMIT 1",
      storageKey
    );
    if (manifestDocReference) {
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
         SELECT json_extract(pack.value, '$.storageKey') AS storage_key
         FROM snapshots s, json_each(COALESCE(s.packs_json, '[]')) AS pack
         WHERE s.world_id = ?
         UNION
         SELECT json_extract(step.value, '$.storageKey') AS storage_key
         FROM snapshots s, json_each(COALESCE(s.packs_json, '[]')) AS pack,
              json_each(COALESCE(json_extract(pack.value, '$.chainSteps'), '[]')) AS step
         WHERE s.world_id = ?
         UNION
         SELECT s.manifest_storage_key AS storage_key
         FROM snapshots s
         WHERE s.world_id = ? AND s.manifest_storage_key IS NOT NULL
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

  async createUploadSession(record: StorageUploadSessionRecord): Promise<void> {
    await this.run(
      `INSERT INTO storage_upload_sessions (
         upload_id, provider, storage_account_id, world_id, storage_key, session_url, content_type, expected_size, created_at, confirmed_at
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      record.uploadId,
      record.provider,
      record.storageAccountId,
      record.worldId,
      record.storageKey,
      record.sessionUrl,
      record.contentType,
      record.expectedSize,
      record.createdAt,
      record.confirmedAt
    );
  }

  async getUploadSession(uploadId: string): Promise<StorageUploadSessionRecord | null> {
    const row = await this.first<Row>(
      "SELECT * FROM storage_upload_sessions WHERE upload_id = ?",
      uploadId
    );
    return row ? mapUploadSession(row) : null;
  }

  async markUploadSessionConfirmed(uploadId: string, confirmedAt: string): Promise<void> {
    await this.run(
      "UPDATE storage_upload_sessions SET confirmed_at = ? WHERE upload_id = ?",
      confirmedAt,
      uploadId
    );
  }

  async deleteUploadSession(uploadId: string): Promise<void> {
    await this.run("DELETE FROM storage_upload_sessions WHERE upload_id = ?", uploadId);
  }

  async listUnconfirmedUploadSessionsBefore(
    provider: StorageProviderType,
    storageAccountId: string,
    createdBefore: string,
    limit: number
  ): Promise<StorageUploadSessionRecord[]> {
    const rows = await this.all<Row>(
      `SELECT * FROM storage_upload_sessions
       WHERE provider = ? AND storage_account_id = ? AND confirmed_at IS NULL AND created_at < ?
       ORDER BY created_at ASC
       LIMIT ?`,
      provider,
      storageAccountId,
      createdBefore,
      limit
    );
    return rows.map(mapUploadSession);
  }

  async deleteConfirmedUploadSessionsBefore(
    provider: StorageProviderType,
    storageAccountId: string,
    confirmedBefore: string,
    limit: number
  ): Promise<void> {
    await this.run(
      `DELETE FROM storage_upload_sessions WHERE upload_id IN (
         SELECT upload_id FROM storage_upload_sessions
         WHERE provider = ? AND storage_account_id = ? AND confirmed_at IS NOT NULL AND confirmed_at < ?
         LIMIT ?
       )`,
      provider,
      storageAccountId,
      confirmedBefore,
      limit
    );
  }

  async enqueuePendingBlobDelete(provider: StorageProviderType, storageAccountId: string, storageKey: string, enqueuedAt: string): Promise<void> {
    await this.run(
      `INSERT INTO pending_blob_deletes (provider, storage_account_id, storage_key, enqueued_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT (provider, storage_account_id, storage_key) DO NOTHING`,
      provider,
      storageAccountId,
      storageKey,
      enqueuedAt
    );
  }

  async enqueuePendingBlobDeletes(provider: StorageProviderType, storageAccountId: string, storageKeys: readonly string[], enqueuedAt: string): Promise<void> {
    if (storageKeys.length === 0) {
      return;
    }
    await this.batch(storageKeys.map((storageKey) => this.db.prepare(
      `INSERT INTO pending_blob_deletes (provider, storage_account_id, storage_key, enqueued_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT (provider, storage_account_id, storage_key) DO NOTHING`
    ).bind(provider, storageAccountId, storageKey, enqueuedAt)));
  }

  async listPendingBlobDeletes(provider: StorageProviderType, storageAccountId: string, limit: number): Promise<Array<{ storageKey: string; attempts: number }>> {
    const rows = await this.all<Row>(
      `SELECT storage_key, attempts
       FROM pending_blob_deletes
       WHERE provider = ? AND storage_account_id = ?
       ORDER BY enqueued_at ASC
       LIMIT ?`,
      provider,
      storageAccountId,
      limit
    );
    return rows.map((row) => ({ storageKey: String(row.storage_key), attempts: Number(row.attempts) }));
  }

  async deletePendingBlobDelete(provider: StorageProviderType, storageAccountId: string, storageKey: string): Promise<void> {
    await this.run(
      "DELETE FROM pending_blob_deletes WHERE provider = ? AND storage_account_id = ? AND storage_key = ?",
      provider,
      storageAccountId,
      storageKey
    );
  }

  async bumpPendingBlobDeleteAttempt(provider: StorageProviderType, storageAccountId: string, storageKey: string, attemptedAt: string): Promise<void> {
    await this.run(
      `UPDATE pending_blob_deletes
       SET attempts = attempts + 1, last_attempt_at = ?
       WHERE provider = ? AND storage_account_id = ? AND storage_key = ?`,
      attemptedAt,
      provider,
      storageAccountId,
      storageKey
    );
  }

  async createInvite(worldId: string, _ctx: RequestContext, invite: InviteCode): Promise<InviteCode> {
    // Physical expiry of stale rows happens here, on the write path —
    // getActiveInvite filters them out in its WHERE clause instead of
    // issuing an UPDATE on every owner world-details read.
    await this.run(
      "UPDATE invite_codes SET status = 'expired' WHERE world_id = ? AND status = 'active' AND expires_at < ?",
      worldId,
      invite.createdAt
    );
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
    // Pure read: expiry is enforced in the WHERE clause. Rows past their
    // expires_at keep status='active' until the next invite write physically
    // expires them (createInvite) — no reader can observe them as active.
    const row = await this.first<Row>(
      `SELECT id, world_id, code, created_by_uuid, created_at, expires_at, redeemed_by_uuid, redeemed_at, status
       FROM invite_codes
       WHERE world_id = ? AND status = 'active' AND expires_at >= ?
       ORDER BY created_at DESC, id DESC
       LIMIT 1`,
      worldId,
      now.toISOString()
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

  /**
   * Latest-snapshot facts that live on the snapshots row itself (one row
   * read) — enough for world summaries and for cache keying, without paying
   * for the full manifest's file rows.
   */
  private async latestSnapshotRow(worldId: string): Promise<Row | null> {
    return this.first<Row>(
      `SELECT id, world_id, created_at, created_by_uuid, data_version, minecraft_version
       FROM snapshots
       WHERE world_id = ?
       ORDER BY created_at DESC, id DESC
       LIMIT 1`,
      worldId
    );
  }

  /**
   * Compare-and-set claim of the world's hourly retention slot: true means
   * this caller runs retention now; false means another finalize ran it
   * within the interval. Retention only ever deletes >24h-old snapshots, so
   * an hourly cadence loses nothing.
   */
  async claimRetentionSlot(worldId: string, now: Date, intervalMs: number): Promise<boolean> {
    const changes = await this.runWithChanges(
      `UPDATE worlds SET last_retention_at = ?
       WHERE id = ? AND deleted_at IS NULL
         AND (last_retention_at IS NULL OR last_retention_at < ?)`,
      now.toISOString(),
      worldId,
      new Date(now.getTime() - intervalMs).toISOString()
    );
    return changes > 0;
  }

  async getLatestSnapshotStamp(worldId: string): Promise<{ id: string } | null> {
    const row = await this.latestSnapshotRow(worldId);
    return row == null ? null : { id: String(row.id) };
  }

  async getLatestSnapshot(worldId: string): Promise<SnapshotManifest | null> {
    const snapshot = await this.latestSnapshotRow(worldId);
    if (!snapshot) {
      return null;
    }
    return this.loadSnapshotCached(String(snapshot.id), worldId, String(snapshot.created_at), String(snapshot.created_by_uuid));
  }

  async getSnapshot(worldId: string, snapshotId: string): Promise<SnapshotManifest | null> {
    // The DB existence check always runs first: a retention-deleted snapshot
    // must return null even while its manifest still sits in the cache.
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
    return this.loadSnapshotCached(String(row.id), String(row.world_id), String(row.created_at), String(row.created_by_uuid));
  }

  async getLatestSnapshotHeaders(worldId: string): Promise<SnapshotManifest | null> {
    // Own query (not latestSnapshotRow) so the directory rides along with
    // the row: the id-only callers of latestSnapshotRow must not pay for
    // packs_json bytes on every heartbeat.
    const snapshot = await this.first<Row>(
      `SELECT id, world_id, created_at, created_by_uuid, packs_json
       FROM snapshots
       WHERE world_id = ?
       ORDER BY created_at DESC, id DESC
       LIMIT 1`,
      worldId
    );
    if (!snapshot) {
      return null;
    }
    return this.loadSnapshotHeaders(String(snapshot.id), worldId, String(snapshot.created_at), String(snapshot.created_by_uuid), snapshot.packs_json);
  }

  async getSnapshotHeaders(worldId: string, snapshotId: string): Promise<SnapshotManifest | null> {
    const row = await this.first<Row>(
      `SELECT id, world_id, created_at, created_by_uuid, packs_json
       FROM snapshots
       WHERE world_id = ? AND id = ?`,
      worldId,
      snapshotId
    );
    if (!row) {
      return null;
    }
    return this.loadSnapshotHeaders(String(row.id), String(row.world_id), String(row.created_at), String(row.created_by_uuid), row.packs_json);
  }

  /**
   * Headers for many snapshots of one world in a fixed number of queries
   * (finalize validates/accounts/stamps every delta pack against its base
   * snapshot — hundreds of packs over dozens of distinct bases, which as
   * one-at-a-time loads cost 3 sequential D1 round-trips per base per pass;
   * measured at ~18s of finalize wall time in production). Unknown ids are
   * simply absent from the result. Ids ride in as one JSON array (D1 caps
   * bound parameters, and a delta-heavy world can reference many bases).
   */
  async existingSnapshotIds(worldId: string, snapshotIds: readonly string[]): Promise<Set<string>> {
    const ids = [...new Set(snapshotIds)];
    if (ids.length === 0) {
      return new Set();
    }
    const rows = await this.all<Row>(
      `SELECT id FROM snapshots WHERE world_id = ? AND id IN (SELECT value FROM json_each(?))`,
      worldId,
      JSON.stringify(ids)
    );
    return new Set(rows.map((row) => String(row.id)));
  }

  async getSnapshotHeadersBatch(worldId: string, snapshotIds: readonly string[]): Promise<Map<string, SnapshotManifest>> {
    const result = new Map<string, SnapshotManifest>();
    const ids = [...new Set(snapshotIds)];
    if (ids.length === 0) {
      return result;
    }
    const idsJson = JSON.stringify(ids);
    const rows = await this.all<Row>(
      `SELECT id, world_id, created_at, created_by_uuid, packs_json
       FROM snapshots
       WHERE world_id = ? AND id IN (SELECT value FROM json_each(?))`,
      worldId,
      idsJson
    );
    if (rows.length === 0) {
      return result;
    }
    const looseRows = await this.all<Row>(
      `SELECT snapshot_id, path, hash, size, compressed_size, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
       FROM snapshot_files
       WHERE snapshot_id IN (SELECT value FROM json_each(?)) AND pack_id IS NULL
       ORDER BY snapshot_id ASC, path ASC`,
      idsJson
    );
    const looseBySnapshot = new Map<string, Row[]>();
    for (const row of looseRows) {
      const key = String(row.snapshot_id);
      const list = looseBySnapshot.get(key) ?? [];
      list.push(row);
      looseBySnapshot.set(key, list);
    }
    for (const row of rows) {
      const snapshotId = String(row.id);
      result.set(snapshotId, {
        worldId: String(row.world_id),
        snapshotId,
        createdAt: String(row.created_at),
        createdByUuid: String(row.created_by_uuid),
        files: (looseBySnapshot.get(snapshotId) ?? []).map((file) => looseFileOfRow(file)),
        // Legacy pre-0026 rows (packs_json NULL) fall back to a per-snapshot
        // snapshot_packs read inside packDirectory — none are written anymore.
        packs: assembleSnapshotPacks(await this.packDirectory(snapshotId, asNullableString(row.packs_json)), () => [], { includeChainSteps: true })
      });
    }
    return result;
  }

  /**
   * Headers-only manifest: loose files + pack headers with EMPTY member
   * lists — no member rows, no manifest document, no cache (an
   * empty-membered manifest must never pollute the real manifest cache).
   * For callers that consume only headers (upload planning, finalize delta
   * validation, chainDeltaBytes): keeping them doc-free means a missing
   * manifest document can never block the next finalize — the world always
   * heals by snapshotting again.
   */
  private async loadSnapshotHeaders(snapshotId: string, worldId: string, createdAt: string, createdByUuid: string, rawPacksJson?: unknown): Promise<SnapshotManifest> {
    const rows = await this.all<Row>(
      `SELECT path, hash, size, compressed_size, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
       FROM snapshot_files
       WHERE snapshot_id = ? AND pack_id IS NULL
       ORDER BY path ASC`,
      snapshotId
    );
    // Callers that already hold the snapshot row pass its packs_json so the
    // directory does not cost a second read of the same row.
    const directory = rawPacksJson === undefined
      ? await this.packDirectoryOf(snapshotId)
      : await this.packDirectory(snapshotId, asNullableString(rawPacksJson));
    return {
      worldId,
      snapshotId,
      createdAt,
      createdByUuid,
      files: rows.map((row) => looseFileOfRow(row)),
      packs: assembleSnapshotPacks(directory, () => [], { includeChainSteps: true })
    };
  }

  /**
   * Manifest content is immutable per snapshot id, so a cache hit skips the
   * file/pack row loads entirely — the difference between ~2 and several
   * thousand D1 rows read for the polling paths.
   */
  private async loadSnapshotCached(snapshotId: string, worldId: string, createdAt: string, createdByUuid: string): Promise<SnapshotManifest> {
    const cached = await this.manifestCache?.match(worldId, snapshotId);
    if (cached != null) {
      return cached;
    }
    const manifest = await this.loadSnapshot(snapshotId, worldId, createdAt, createdByUuid);
    await this.manifestCache?.put(worldId, snapshotId, manifest);
    return manifest;
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
      `SELECT s.id, s.created_at, s.created_by_uuid, s.data_version, s.minecraft_version,
              s.packs_json, s.loose_file_count, s.loose_total_size
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

    // 0026: file/size aggregates come straight off the snapshots rows (loose
    // columns + directory memberCount/memberTotalSize). The quadratic
    // member-row join survives only as a fallback for rows written by a
    // pre-0026 worker mid-deploy.
    const legacyIds = rows
      .filter((row) => asNullableString(row.packs_json) == null || row.loose_file_count == null)
      .map((row) => String(row.id));
    const legacyLoose = new Map<string, Row>();
    const legacyPacks = new Map<string, Row>();
    if (legacyIds.length > 0) {
      const legacyPlaceholders = sqlPlaceholders(legacyIds.length);
      const looseAggregates = await this.all<Row>(
        `SELECT sf.snapshot_id AS sid, COUNT(*) AS n, COALESCE(SUM(sf.size), 0) AS total
         FROM snapshot_files sf
         WHERE sf.pack_id IS NULL AND sf.snapshot_id IN (${legacyPlaceholders})
         GROUP BY sf.snapshot_id`,
        ...legacyIds
      );
      const packAggregates = await this.all<Row>(
        `SELECT sp.snapshot_id AS sid, COUNT(sf.path) AS n, COALESCE(SUM(sf.size), 0) AS total
         FROM snapshot_packs sp
         LEFT JOIN snapshot_files sf
           ON sf.snapshot_id = COALESCE(sp.members_snapshot_id, sp.snapshot_id)
          AND sf.pack_id = sp.pack_id
         WHERE sp.snapshot_id IN (${legacyPlaceholders})
         GROUP BY sp.snapshot_id`,
        ...legacyIds
      );
      for (const row of looseAggregates) {
        legacyLoose.set(String(row.sid), row);
      }
      for (const row of packAggregates) {
        legacyPacks.set(String(row.sid), row);
      }
    }

    // Stored bytes stay query-time (screen-open frequency only): dedupe by
    // storage key against provider-reported object sizes, with the file's
    // compressed size as fallback. Pack keys come from both representations.
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
         UNION
         SELECT s.id AS sid, json_extract(pack.value, '$.storageKey') AS storage_key, NULL AS fallback_size
         FROM snapshots s, json_each(COALESCE(s.packs_json, '[]')) AS pack
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
      worldId,
      String(world.storage_provider ?? "google-drive"),
      String(world.storage_account_id ?? "")
    );

    const storedBySnapshot = new Map(storedAggregates.map((row) => [String(row.sid), row]));
    return rows.map((row) => {
      const snapshotId = String(row.id);
      const stored = storedBySnapshot.get(snapshotId);
      let fileCount: number;
      let totalSize: number;
      if (asNullableString(row.packs_json) != null && row.loose_file_count != null) {
        const directory = JSON.parse(String(row.packs_json)) as PackDirectoryEntry[];
        fileCount = Number(row.loose_file_count) + directory.reduce((total, entry) => total + (entry.memberCount ?? 0), 0);
        totalSize = Number(row.loose_total_size ?? 0) + directory.reduce((total, entry) => total + (entry.memberTotalSize ?? 0), 0);
      } else {
        const loose = legacyLoose.get(snapshotId);
        const packs = legacyPacks.get(snapshotId);
        fileCount = Number(loose?.n ?? 0) + Number(packs?.n ?? 0);
        totalSize = Number(loose?.total ?? 0) + Number(packs?.total ?? 0);
      }
      return {
        snapshotId,
        createdAt: String(row.created_at),
        createdByUuid: String(row.created_by_uuid),
        dataVersion: row.data_version == null ? null : Number(row.data_version),
        minecraftVersion: asNullableString(row.minecraft_version),
        fileCount,
        totalSize,
        totalCompressedSize: Number(stored?.used ?? 0),
        isLatest: snapshotId === latestSnapshotId
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
    // Legacy transition leg (pre-0026 pack rows) plus the JSON directories.
    const packRows = await this.all<Row>(
      `SELECT DISTINCT sp.snapshot_id, sp.base_snapshot_id
       FROM snapshot_packs sp
       JOIN snapshots s ON s.id = sp.snapshot_id
       WHERE s.world_id = ? AND sp.base_snapshot_id IS NOT NULL`,
      worldId
    );
    const directoryRows = await this.all<Row>(
      `SELECT DISTINCT s.id AS snapshot_id, json_extract(pack.value, '$.baseSnapshotId') AS base_snapshot_id
       FROM snapshots s, json_each(COALESCE(s.packs_json, '[]')) AS pack
       WHERE s.world_id = ? AND json_extract(pack.value, '$.baseSnapshotId') IS NOT NULL`,
      worldId
    );
    // S1 self-containment: a snapshot whose every delta pack carries a
    // chainSteps recipe (and which has no loose delta rows) needs NO base
    // snapshot rows — its download plan builds from the recipe and its blob
    // references are covered by the chainSteps GC legs. Such referrers
    // contribute no edges, which is what lets retention and manual delete
    // actually drop old snapshots. Partially-stamped snapshots stay
    // conservative: all their edges remain.
    const selfContainedRows = await this.all<Row>(
      `SELECT s.id
       FROM snapshots s
       WHERE s.world_id = ?
         AND s.packs_json IS NOT NULL
         AND NOT EXISTS (
           SELECT 1 FROM json_each(COALESCE(s.packs_json, '[]')) AS pack
           WHERE json_extract(pack.value, '$.baseSnapshotId') IS NOT NULL
             AND json_extract(pack.value, '$.chainSteps') IS NULL
         )
         AND NOT EXISTS (
           SELECT 1 FROM snapshot_files sf
           WHERE sf.snapshot_id = s.id AND sf.pack_id IS NULL AND sf.base_snapshot_id IS NOT NULL
         )`,
      worldId
    );
    const selfContained = new Set(selfContainedRows.map((row) => String(row.id)));
    // Member-donor pointers (membersSnapshotId) are deliberately NOT edges
    // here: deleteSnapshots promotes inherited member rows to a surviving
    // heir, so donors never need to be kept alive for retention or deletion.
    const edges = new Map<string, { snapshotId: string; baseSnapshotId: string }>();
    for (const row of [...fileRows, ...packRows, ...directoryRows]) {
      const edge = { snapshotId: String(row.snapshot_id), baseSnapshotId: String(row.base_snapshot_id) };
      if (selfContained.has(edge.snapshotId)) {
        continue;
      }
      edges.set(`${edge.snapshotId}->${edge.baseSnapshotId}`, edge);
    }
    return [...edges.values()];
  }

  /**
   * S1 lazy upgrade: merge chainSteps recipes into an existing snapshot's
   * pack directory. Directory-only — cached/served manifests never include
   * chainSteps, so rewriting packs_json here cannot violate the manifest
   * cache's bytes-per-snapshot-id immutability.
   */
  async stampSnapshotChainSteps(
    snapshotId: string,
    stepsByPackId: ReadonlyMap<string, import("../../shared/src/index.ts").PackChainStep[]>
  ): Promise<void> {
    if (stepsByPackId.size === 0) {
      return;
    }
    const row = await this.first<Row>("SELECT packs_json FROM snapshots WHERE id = ?", snapshotId);
    const raw = asNullableString(row?.packs_json);
    if (raw == null) {
      return;
    }
    const directory = JSON.parse(raw) as PackDirectoryEntry[];
    let changed = false;
    for (const entry of directory) {
      const steps = stepsByPackId.get(entry.packId);
      if (steps != null && entry.chainSteps == null) {
        entry.chainSteps = steps;
        changed = true;
      }
    }
    if (changed) {
      await this.run("UPDATE snapshots SET packs_json = ? WHERE id = ?", JSON.stringify(directory), snapshotId);
    }
  }

  /**
   * Pack rows of a base snapshot, keyed by pack id, for member-row
   * inheritance during finalize. `membersSnapshotId` is the snapshot that
   * physically holds the pack's member rows (NULL = the base itself).
   */
  private async basePackRowsForInheritance(worldId: string, baseSnapshotId: string): Promise<Map<string, PackDirectoryEntry>> {
    // The world scoping the legacy query enforced via a join is preserved:
    // a base snapshot from another world simply yields no directory here.
    const owned = await this.first<Row>(
      "SELECT manifest_storage_key FROM snapshots WHERE id = ? AND world_id = ?",
      baseSnapshotId,
      worldId
    );
    if (!owned) {
      return new Map();
    }
    if (asNullableString(owned.manifest_storage_key) != null) {
      // 0027 guard: a doc-format base has NO member rows, so a legacy-mode
      // finalize (doc write unavailable) must materialize every member from
      // the request instead of "inheriting" from a row-less snapshot —
      // which would yield permanently empty manifests.
      return new Map();
    }
    const directory = await this.packDirectoryOf(baseSnapshotId);
    return new Map(directory.map((entry) => [entry.packId, entry]));
  }

  async finalizeSnapshot(
    worldId: string,
    ctx: RequestContext,
    request: FinalizeSnapshotRequest,
    now: Date,
    options?: { manifestStorageKey?: string | null }
  ): Promise<SnapshotManifest> {
    const snapshotId = `snapshot_${crypto.randomUUID().replace(/-/g, "")}`;
    const manifestStorageKey = options?.manifestStorageKey ?? null;
    // Doc mode needs no inheritance lookup: member lists live in the
    // document, so no member rows are written and nothing is inherited.
    const basePacks = manifestStorageKey == null && request.baseSnapshotId != null
      ? await this.basePackRowsForInheritance(worldId, request.baseSnapshotId)
      : null;
    // Pack HEADERS live in the snapshots row's JSON directory (0026): an
    // unchanged 300-pack world used to rewrite 300 header rows per autosave.
    // Member file rows keep the row-level inheritance machinery unchanged.
    const directory: PackDirectoryEntry[] = [];
    const statements: ReturnType<D1Database["prepare"]>[] = [];
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
      // judged on the same fields the header stores — the same trust model
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
        && (pack.deltaFormatVersion ?? null) === base.deltaFormatVersion
        && (pack.deltaBlobSize ?? null) === base.deltaBlobSize
        && (pack.chainDeltaBytes ?? null) === base.chainDeltaBytes
        ? (base.membersSnapshotId ?? request.baseSnapshotId ?? null)
        : null;
      directory.push({
        packId: pack.packId,
        hash: pack.hash,
        size: pack.size,
        storageKey: pack.storageKey,
        transferMode: pack.transferMode,
        baseSnapshotId: pack.baseSnapshotId ?? null,
        baseHash: pack.baseHash ?? null,
        chainDepth: pack.chainDepth ?? null,
        membersSnapshotId: inheritFrom,
        deltaFormatVersion: pack.deltaFormatVersion ?? null,
        deltaBlobSize: pack.deltaBlobSize ?? null,
        chainDeltaBytes: pack.chainDeltaBytes ?? null,
        // The client always sends full member lists, even for inherited
        // packs — the aggregates cost nothing here and make snapshot
        // listing O(snapshots).
        memberCount: pack.files.length,
        memberTotalSize: pack.files.reduce((total, file) => total + file.size, 0),
        // Server-stamped upstream (never client-supplied); null when the
        // stamping pass could not synthesize a legacy base's chain.
        chainSteps: pack.chainSteps ?? null
      });
      if (inheritFrom != null || manifestStorageKey != null) {
        // Doc mode: member lists live in the manifest document — no member
        // rows at all (inheritFrom is always null here since basePacks is
        // skipped, keeping membersSnapshotId null: the 0027 invariant that
        // makes doc snapshots invisible to the promotion machinery).
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
    directory.sort((a, b) => a.packId.localeCompare(b.packId));
    const directoryJson = JSON.stringify(directory);
    if (directoryJson.length > 1_000_000) {
      // chainSteps are bounded by the delta depth ceilings; a directory this
      // large means the budget/depth levers need tightening, not a failure.
      console.warn("SharedWorld pack directory unusually large", { worldId, bytes: directoryJson.length, packs: directory.length });
    }
    // One transactional batch: a failure mid-write must not leave a partial
    // snapshot behind, because a partial row would become the world's
    // "latest" manifest.
    statements.unshift(this.prepared(
      `INSERT INTO snapshots (id, world_id, created_at, created_by_uuid, base_snapshot_id, data_version, minecraft_version, packs_json, loose_file_count, loose_total_size, manifest_storage_key)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      snapshotId,
      worldId,
      now.toISOString(),
      ctx.playerUuid,
      request.baseSnapshotId ?? null,
      request.dataVersion ?? null,
      request.minecraftVersion ?? null,
      directoryJson,
      request.files.length,
      request.files.reduce((total, file) => total + file.size, 0),
      manifestStorageKey
    ));
    await this.batch(statements);
    // Cached loader on purpose: a freshly finalized snapshot id cannot be in
    // the cache yet, so this populates it while every reader is about to ask.
    return this.loadSnapshotCached(snapshotId, worldId, now.toISOString(), ctx.playerUuid);
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
      `SELECT id, manifest_storage_key
       FROM snapshots
       WHERE world_id = ? AND id IN (${requestedPlaceholders})`,
      worldId,
      ...snapshotIds
    );
    const deletedSnapshotIds = deletedRows.map((row) => String(row.id));
    const doomedManifestDocKeys = deletedRows
      .map((row) => asNullableString(row.manifest_storage_key))
      .filter((key): key is string => key != null);
    if (deletedSnapshotIds.length === 0) {
      return {
        deletedSnapshotIds: [],
        unreferencedStorageKeys: []
      };
    }

    const deletePlaceholders = sqlPlaceholders(deletedSnapshotIds.length);
    const doomed = new Set(deletedSnapshotIds);
    const candidateRows = await this.all<Row>(
      `SELECT DISTINCT storage_key
       FROM snapshot_files
       WHERE snapshot_id IN (${deletePlaceholders})`,
      ...deletedSnapshotIds
    );
    // Legacy transition leg: pack rows written by a pre-0026 worker.
    const packCandidateRows = await this.all<Row>(
      `SELECT DISTINCT storage_key
       FROM snapshot_packs
       WHERE snapshot_id IN (${deletePlaceholders})`,
      ...deletedSnapshotIds
    );

    // All of the world's snapshots with their pack directories, oldest
    // first: feeds doomed-pack candidates, referrer detection, and the
    // oldest-heir promotion choice. Retention bounds this to ~35 rows.
    const worldSnapshotRows = await this.all<Row>(
      `SELECT id, packs_json FROM snapshots WHERE world_id = ? ORDER BY created_at ASC, id ASC`,
      worldId
    );
    const directories = new Map<string, PackDirectoryEntry[] | null>();
    for (const row of worldSnapshotRows) {
      const raw = asNullableString(row.packs_json);
      directories.set(String(row.id), raw == null ? null : JSON.parse(raw) as PackDirectoryEntry[]);
    }

    const candidateStorageKeys = [...new Set([
      ...candidateRows.map((row) => String(row.storage_key)),
      ...packCandidateRows.map((row) => String(row.storage_key)),
      ...deletedSnapshotIds.flatMap((id) => (directories.get(id) ?? []).map((entry) => entry.storageKey)),
      // S1 chain recipes: doomed snapshots' steps reference the chain blobs
      // behind their packs — candidates unless a survivor's recipe shares them.
      ...deletedSnapshotIds.flatMap((id) =>
        (directories.get(id) ?? []).flatMap((entry) => (entry.chainSteps ?? []).map((step) => step.storageKey))
      ),
      // 0027 manifest documents are content-addressed and shared across
      // snapshots (restore); reclaimed only when the last referencer dies.
      ...doomedManifestDocKeys
    ])];

    // Member-row promotion: surviving packs that inherit their member rows
    // from a doomed snapshot get those rows copied to the OLDEST surviving
    // heir before the donor is deleted; every other heir is repointed at the
    // new physical holder. This keeps every surviving manifest loadable
    // without retention ever having to keep donor snapshots alive. Referrers
    // come from both representations: survivors' JSON directories and (for
    // pre-0026 rows) the legacy snapshot_packs table.
    const legacyReferrerRows = await this.all<Row>(
      `SELECT sp.snapshot_id, sp.pack_id, sp.members_snapshot_id
       FROM snapshot_packs sp
       JOIN snapshots s ON s.id = sp.snapshot_id
       WHERE s.world_id = ?
         AND sp.members_snapshot_id IN (${deletePlaceholders})
         AND sp.snapshot_id NOT IN (${deletePlaceholders})`,
      worldId,
      ...deletedSnapshotIds,
      ...deletedSnapshotIds
    );
    type Referrer = { snapshotId: string; packId: string; donorId: string; representation: "json" | "legacy" };
    const referrers: Referrer[] = [];
    // worldSnapshotRows is oldest-first, so referrers accumulate in age
    // order regardless of representation.
    for (const row of worldSnapshotRows) {
      const snapshotId = String(row.id);
      if (doomed.has(snapshotId)) {
        continue;
      }
      for (const entry of directories.get(snapshotId) ?? []) {
        if (entry.membersSnapshotId != null && doomed.has(entry.membersSnapshotId)) {
          referrers.push({ snapshotId, packId: entry.packId, donorId: entry.membersSnapshotId, representation: "json" });
        }
      }
      for (const legacyRow of legacyReferrerRows) {
        if (String(legacyRow.snapshot_id) === snapshotId) {
          referrers.push({
            snapshotId,
            packId: String(legacyRow.pack_id),
            donorId: String(legacyRow.members_snapshot_id),
            representation: "legacy"
          });
        }
      }
    }

    const statements: ReturnType<D1Database["prepare"]>[] = [];
    const promotionTargetByDonorPack = new Map<string, string>();
    const rewrittenDirectories = new Set<string>();
    for (const referrer of referrers) {
      const key = `${referrer.donorId}\u0000${referrer.packId}`;
      let targetId = promotionTargetByDonorPack.get(key);
      if (targetId == null) {
        // First (oldest) referrer becomes the new physical holder.
        targetId = referrer.snapshotId;
        promotionTargetByDonorPack.set(key, targetId);
        statements.push(this.prepared(
          `INSERT INTO snapshot_files (
             snapshot_id, path, hash, size, compressed_size, pack_id, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
           )
           SELECT ?, path, hash, size, compressed_size, pack_id, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
           FROM snapshot_files
           WHERE snapshot_id = ? AND pack_id = ?`,
          targetId,
          referrer.donorId,
          referrer.packId
        ));
      }
      if (referrer.representation === "legacy") {
        statements.push(this.prepared(
          "UPDATE snapshot_packs SET members_snapshot_id = ? WHERE snapshot_id = ? AND pack_id = ? AND members_snapshot_id = ?",
          referrer.snapshotId === targetId ? null : targetId,
          referrer.snapshotId,
          referrer.packId,
          referrer.donorId
        ));
      } else {
        const directory = directories.get(referrer.snapshotId);
        const entry = directory?.find((candidate) => candidate.packId === referrer.packId);
        if (entry != null && entry.membersSnapshotId === referrer.donorId) {
          entry.membersSnapshotId = referrer.snapshotId === targetId ? null : targetId;
          rewrittenDirectories.add(referrer.snapshotId);
        }
      }
    }
    for (const snapshotId of rewrittenDirectories) {
      statements.push(this.prepared(
        "UPDATE snapshots SET packs_json = ? WHERE id = ?",
        JSON.stringify(directories.get(snapshotId) ?? []),
        snapshotId
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
      // Content-addressed dedupe is per storage account, cross-world — the
      // directory leg must scan every world's snapshots, like the row legs.
      const referencedDirectoryRows = await this.all<Row>(
        `SELECT DISTINCT json_extract(pack.value, '$.storageKey') AS storage_key
         FROM snapshots, json_each(COALESCE(snapshots.packs_json, '[]')) AS pack
         WHERE json_extract(pack.value, '$.storageKey') IN (${keyPlaceholders})`,
        ...candidateStorageKeys
      );
      // 0027 leg: surviving snapshots' manifest documents (partial index).
      const referencedManifestDocRows = await this.all<Row>(
        `SELECT DISTINCT manifest_storage_key AS storage_key
         FROM snapshots
         WHERE manifest_storage_key IN (${keyPlaceholders})`,
        ...candidateStorageKeys
      );
      // S1 leg: surviving snapshots' chain recipes (account-wide, like the
      // other legs — content-addressed keys are shared cross-world).
      const referencedChainStepRows = await this.all<Row>(
        `SELECT DISTINCT json_extract(step.value, '$.storageKey') AS storage_key
         FROM snapshots, json_each(COALESCE(snapshots.packs_json, '[]')) AS pack,
              json_each(COALESCE(json_extract(pack.value, '$.chainSteps'), '[]')) AS step
         WHERE json_extract(step.value, '$.storageKey') IN (${keyPlaceholders})`,
        ...candidateStorageKeys
      );
      const stillReferenced = new Set(
        [...referencedRows, ...referencedPackRows, ...referencedDirectoryRows, ...referencedManifestDocRows, ...referencedChainStepRows]
          .map((row) => String(row.storage_key))
      );
      unreferencedStorageKeys = candidateStorageKeys.filter((key) => !stillReferenced.has(key)).sort();
    }

    return {
      deletedSnapshotIds,
      unreferencedStorageKeys
    };
  }

  /**
   * Set-based summary builder: five fixed queries for any number of worlds
   * (worlds, member counts, runtime mirrors, latest snapshots, account
   * emails), each mirror parsed exactly once. Deleted/unknown worlds are
   * simply absent from the result map. Summaries only need latest-snapshot
   * facts that live on the snapshots row itself — loading the full manifest
   * here made every world list and world-details read cost thousands of
   * snapshot_files rows.
   */
  private async buildWorldSummaries(
    worldIds: readonly string[],
    precomputed: { memberCounts?: Map<string, number> } = {}
  ): Promise<Map<string, WorldSummary>> {
    const result = new Map<string, WorldSummary>();
    if (worldIds.length === 0) {
      return result;
    }
    const placeholders = worldIds.map(() => "?").join(", ");
    const worlds = await this.all<Row>(
      `SELECT id, slug, name, motd, custom_icon_storage_key, owner_uuid, storage_provider, storage_account_id, settings, settings_revision
       FROM worlds
       WHERE deleted_at IS NULL AND id IN (${placeholders})`,
      ...worldIds
    );
    if (worlds.length === 0) {
      return result;
    }

    const memberCounts = precomputed.memberCounts ?? new Map<string, number>();
    if (precomputed.memberCounts == null) {
      const countRows = await this.all<Row>(
        `SELECT world_id, COUNT(*) AS count
         FROM world_memberships
         WHERE deleted_at IS NULL AND world_id IN (${placeholders})
         GROUP BY world_id`,
        ...worldIds
      );
      for (const row of countRows) {
        memberCounts.set(String(row.world_id), Number(row.count ?? 0));
      }
    }

    const mirrors = new Map<string, ParsedRuntimeMirror>();
    const mirrorRows = await this.all<Row>(
      `SELECT world_id, status_json, room_players_json
       FROM world_runtime_mirror
       WHERE world_id IN (${placeholders})`,
      ...worldIds
    );
    for (const row of mirrorRows) {
      mirrors.set(String(row.world_id), parseRuntimeMirror(row.status_json, row.room_players_json));
    }

    const latestByWorld = new Map<string, Row>();
    // Latest snapshot per world: one 1-row index walk per world (migration
    // 0029) resolved to ids, then a primary-key fetch — instead of a window
    // over every snapshot of every listed world.
    const latestRows = await this.all<Row>(
      `SELECT id, world_id, created_at, data_version, minecraft_version
       FROM snapshots
       WHERE id IN (
         SELECT ${LATEST_SNAPSHOT_ID_SUBQUERY("j.value")} FROM json_each(?) j
       )`,
      JSON.stringify(worldIds)
    );
    for (const row of latestRows) {
      latestByWorld.set(String(row.world_id), row);
    }

    const accountIds = [...new Set(
      worlds.map((row) => asNullableString(row.storage_account_id)).filter((id): id is string => id != null)
    )];
    const accountEmails = new Map<string, string | null>();
    if (accountIds.length > 0) {
      const accountRows = await this.all<Row>(
        `SELECT id, email FROM storage_accounts WHERE id IN (${accountIds.map(() => "?").join(", ")})`,
        ...accountIds
      );
      for (const row of accountRows) {
        accountEmails.set(String(row.id), asNullableString(row.email));
      }
    }

    for (const world of worlds) {
      const worldId = String(world.id);
      const mirror = mirrors.get(worldId) ?? EMPTY_RUNTIME_MIRROR;
      const lifecycle = lifecycleOfMirror(mirror);
      const onlinePlayers = onlinePlayersOfMirror(mirror);
      const latest = latestByWorld.get(worldId) ?? null;
      const storageAccountId = asNullableString(world.storage_account_id);
      result.set(worldId, {
        id: worldId,
        slug: String(world.slug),
        name: String(world.name),
        ownerUuid: String(world.owner_uuid),
        motd: asNullableString(world.motd),
        customIconStorageKey: asNullableString(world.custom_icon_storage_key),
        customIconDownload: null,
        memberCount: memberCounts.get(worldId) ?? 0,
        status: lifecycle.status,
        lastSnapshotId: latest == null ? null : String(latest.id),
        lastSnapshotAt: latest == null ? null : String(latest.created_at),
        lastSnapshotDataVersion: latest == null || latest.data_version == null ? null : Number(latest.data_version),
        lastSnapshotMinecraftVersion: latest == null ? null : asNullableString(latest.minecraft_version),
        activeHostUuid: lifecycle.activeHostUuid,
        activeHostPlayerName: lifecycle.activeHostPlayerName,
        activeJoinTarget: lifecycle.activeJoinTarget,
        onlinePlayerCount: onlinePlayers.length,
        onlinePlayerNames: onlinePlayers.map((entry) => entry.playerName),
        storageProvider: String(world.storage_provider ?? "google-drive") as StorageProviderType,
        storageLinked: storageAccountId != null,
        storageAccountEmail: storageAccountId == null ? null : accountEmails.get(storageAccountId) ?? null,
        settings: parseWorldSettings(world.settings),
        settingsRevision: Number(world.settings_revision ?? 0)
      });
    }
    return result;
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
      packs: await this.loadPacksForManifest(snapshotId, worldId)
    };
  }

  private async loadPacksForManifest(snapshotId: string, worldId: string): Promise<SnapshotManifest["packs"]> {
    const row = await this.first<Row>(
      "SELECT packs_json, manifest_storage_key FROM snapshots WHERE id = ?",
      snapshotId
    );
    const directory = await this.packDirectory(snapshotId, asNullableString(row?.packs_json));
    const manifestStorageKey = asNullableString(row?.manifest_storage_key);
    if (manifestStorageKey != null) {
      return this.loadSnapshotPacksFromDocument(worldId, snapshotId, manifestStorageKey, directory);
    }
    return this.loadSnapshotPacks(snapshotId, directory);
  }

  /**
   * The snapshot's pack headers: from the 0026 packs_json directory, or the
   * legacy snapshot_packs rows where the directory is absent (rows written
   * by a pre-0026 worker mid-deploy). Always sorted by packId so resolved
   * manifests stay byte-identical to their pre-0026 shape — the manifest
   * cache depends on content per snapshot id never changing.
   */
  private async packDirectoryOf(snapshotId: string): Promise<PackDirectoryEntry[]> {
    const row = await this.first<Row>("SELECT packs_json FROM snapshots WHERE id = ?", snapshotId);
    return this.packDirectory(snapshotId, asNullableString(row?.packs_json));
  }

  private async packDirectory(snapshotId: string, rawPacksJson: string | null): Promise<PackDirectoryEntry[]> {
    if (rawPacksJson != null) {
      const parsed = JSON.parse(rawPacksJson) as PackDirectoryEntry[];
      return parsed.sort((a, b) => a.packId.localeCompare(b.packId));
    }
    const packRows = await this.all<Row>(
      `SELECT pack_id, hash, size, storage_key, transfer_mode, base_snapshot_id, base_hash, chain_depth, members_snapshot_id,
              delta_format_version, delta_blob_size, chain_delta_bytes
       FROM snapshot_packs
       WHERE snapshot_id = ?
       ORDER BY pack_id ASC`,
      snapshotId
    );
    return packRows.map((packRow) => legacyPackRowToDirectoryEntry(packRow));
  }

  /**
   * 0027 read path: member lists come from the snapshot's manifest document.
   * Failures are LOUD (502 snapshot_manifest_unavailable) — assembling a
   * pack with an empty member list would silently corrupt download plans
   * (packChanged compares member hashes), which is strictly worse than an
   * error the client retries. The Workers manifest cache fronts this, so
   * steady-state readers rarely reach the provider fetch.
   */
  private async loadSnapshotPacksFromDocument(
    worldId: string,
    snapshotId: string,
    storageKey: string,
    directory: PackDirectoryEntry[]
  ): Promise<SnapshotManifest["packs"]> {
    if (directory.length === 0) {
      return [];
    }
    const reader = this.manifestDocumentReader;
    if (reader == null) {
      throw manifestUnavailable("Snapshot manifest document reader is not configured.");
    }
    const binding = await this.getWorldStorageBinding(worldId);
    if (binding == null) {
      throw manifestUnavailable("Snapshot manifest document storage is unavailable for this world.");
    }
    const document = await reader.load(binding, storageKey);
    if (document == null) {
      console.warn("SharedWorld snapshot manifest document missing from storage", { worldId, snapshotId, storageKey });
      throw manifestUnavailable("Snapshot manifest document is missing from storage.");
    }
    const membersByPack = new Map(document.packs.map((pack) => [pack.packId, pack.files]));
    return assembleSnapshotPacks(directory, (entry) => {
      const members = membersByPack.get(entry.packId);
      if (members == null) {
        console.warn("SharedWorld snapshot manifest document lacks a directory pack", { worldId, snapshotId, storageKey, packId: entry.packId });
        throw manifestUnavailable("Snapshot manifest document does not match the snapshot's pack directory.");
      }
      // Defensive re-sort: assembled manifests must stay byte-identical to
      // the row-built shape regardless of document byte order.
      return [...members].sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
    });
  }

  /**
   * Resolves every pack's member rows in one query instead of one per pack —
   * large worlds carry 100+ capped bundle/shard packs, and a per-pack query
   * here put whole-manifest loads (session enter, upload/download plans,
   * backup lists) over the Worker CPU budget. Inherited packs resolve their
   * members from the donor snapshot that physically holds them
   * (members_snapshot_id, always one hop).
   */
  private async loadSnapshotPacks(snapshotId: string, directory: PackDirectoryEntry[]): Promise<SnapshotManifest["packs"]> {
    if (directory.length === 0) {
      return [];
    }
    const memberSnapshotIds = [...new Set(directory.map((entry) => entry.membersSnapshotId ?? snapshotId))];
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
    return assembleSnapshotPacks(directory, (entry) => {
      const membersSnapshotId = entry.membersSnapshotId ?? snapshotId;
      const members = membersByPack.get(`${membersSnapshotId}\u0000${entry.packId}`) ?? [];
      if (members.length === 0 && membersSnapshotId !== snapshotId) {
        console.warn("SharedWorld snapshot pack inherited zero member rows — donor missing?", {
          snapshotId,
          packId: entry.packId,
          membersSnapshotId
        });
      }
      return members;
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

}

/**
 * One pack header inside a snapshot's packs_json directory (0026). Field
 * names are the manifest's own camelCase; memberCount/memberTotalSize are
 * finalize-time aggregates over the pack's member file rows (null on entries
 * derived from legacy snapshot_packs rows, which carry no aggregates).
 */
type PackDirectoryEntry = {
  packId: string;
  hash: string;
  size: number;
  storageKey: string;
  transferMode: string;
  baseSnapshotId: string | null;
  baseHash: string | null;
  chainDepth: number | null;
  membersSnapshotId: string | null;
  deltaFormatVersion: number | null;
  deltaBlobSize: number | null;
  chainDeltaBytes: number | null;
  memberCount: number | null;
  memberTotalSize: number | null;
  /** Absent on legacy entries — omitted (not null) so their manifests stay byte-identical. */
  chainSteps?: import("../../shared/src/index.ts").PackChainStep[] | null;
};

/**
 * The single directory→SnapshotPack mapper shared by the row-based and
 * document-based member sources: whatever produced the members, the
 * assembled pack must be shape- and order-identical (the Workers manifest
 * cache assumes content per snapshot id never changes).
 */
function assembleSnapshotPacks(
  directory: PackDirectoryEntry[],
  membersFor: (entry: PackDirectoryEntry) => Array<{ path: string; hash: string; size: number; contentType: string }>,
  options?: { includeChainSteps?: boolean }
): SnapshotManifest["packs"] {
  return directory.map((entry) => ({
    packId: entry.packId,
    hash: entry.hash,
    size: entry.size,
    storageKey: entry.storageKey,
    transferMode: entry.transferMode as FileTransferMode,
    baseSnapshotId: entry.baseSnapshotId,
    baseHash: entry.baseHash,
    chainDepth: entry.chainDepth,
    deltaFormatVersion: entry.deltaFormatVersion,
    deltaBlobSize: entry.deltaBlobSize,
    chainDeltaBytes: entry.chainDeltaBytes,
    // chainSteps are backend-internal (headers path only, never cached or
    // served): retention's lazy upgrade rewrites directories in place, and
    // cached manifest BYTES per snapshot id must never change.
    ...(options?.includeChainSteps && entry.chainSteps != null ? { chainSteps: entry.chainSteps } : {}),
    files: membersFor(entry)
  }));
}

function legacyPackRowToDirectoryEntry(packRow: Row): PackDirectoryEntry {
  return {
    packId: String(packRow.pack_id),
    hash: String(packRow.hash),
    size: Number(packRow.size),
    storageKey: String(packRow.storage_key),
    transferMode: String(packRow.transfer_mode),
    baseSnapshotId: asNullableString(packRow.base_snapshot_id),
    baseHash: asNullableString(packRow.base_hash),
    chainDepth: packRow.chain_depth == null ? null : Number(packRow.chain_depth),
    membersSnapshotId: asNullableString(packRow.members_snapshot_id),
    deltaFormatVersion: packRow.delta_format_version == null ? null : Number(packRow.delta_format_version),
    deltaBlobSize: packRow.delta_blob_size == null ? null : Number(packRow.delta_blob_size),
    chainDeltaBytes: packRow.chain_delta_bytes == null ? null : Number(packRow.chain_delta_bytes),
    memberCount: null,
    memberTotalSize: null
  };
}

type ParsedRuntimeMirror = {
  status: import("../../shared/src/index.ts").WorldRuntimeStatus | null;
  roomPlayers: Array<{ playerUuid: string; playerName: string }>;
};

const EMPTY_RUNTIME_MIRROR: ParsedRuntimeMirror = { status: null, roomPlayers: [] };

function parseRuntimeMirror(statusJson: unknown, roomPlayersJson: unknown): ParsedRuntimeMirror {
  return {
    status: statusJson == null
      ? null
      : JSON.parse(String(statusJson)) as import("../../shared/src/index.ts").WorldRuntimeStatus,
    roomPlayers: roomPlayersJson == null
      ? []
      : JSON.parse(String(roomPlayersJson)) as Array<{ playerUuid: string; playerName: string }>
  };
}

function lifecycleOfMirror(mirror: ParsedRuntimeMirror): {
  status: WorldSummary["status"];
  activeHostUuid: string | null;
  activeHostPlayerName: string | null;
  activeJoinTarget: string | null;
} {
  const status = mirror.status;
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
function onlinePlayersOfMirror(mirror: ParsedRuntimeMirror): Array<{ playerUuid: string; playerName: string }> {
  const { status, roomPlayers } = mirror;
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
