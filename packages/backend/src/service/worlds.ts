import type {
  CreateWorldRequest,
  CreateWorldResult,
  HostGameRulesReportRequest,
  HostGameRulesReportResponse,
  StorageUsageSummary,
  UpdateWorldRequest,
  UpdateWorldSettingsRequest,
  WorldDefaultGameMode,
  WorldDetails,
  WorldDifficulty,
  WorldGameRule,
  WorldSettings,
  WorldSummary
} from "../../../shared/src/index.ts";

import { clientVersionAtLeast, HttpError } from "../http.ts";
import { slugify } from "../ids.ts";
import type { RequestContext, WorldUpdateRecord } from "../repository.ts";
import type { StorageBinding } from "../storage.ts";
import { signDownloadForWorld, type ServiceContext } from "./context.ts";
import {
  publishWorldEvent,
  requireHostAuthority,
  requireMembership,
  requireOwner,
  requireWorldDetails,
  requireWorldStorageBinding,
  sessionActorOf
} from "./runtime-access.ts";
import { maybeDeleteUnreferencedBlob, purgeWorldSnapshots } from "./snapshots.ts";
import { parsePositiveInt } from "./sync-plan.ts";

export async function listWorlds(svc: ServiceContext, ctx: RequestContext): Promise<WorldSummary[]> {
  const worlds = await svc.repository.listWorldsForPlayer(ctx.playerUuid);
  return Promise.all(worlds.map((world) => hydrateWorldSummary(svc, world, ctx.requestOrigin)));
}

/**
 * Weak ETags over the change facts that feed the two world GET responses.
 * The body itself can never be hashed: it is per-user and carries an
 * advisory customIconDownload.expiresAt recomputed per call (the signer
 * enforces nothing at expiry, so serving a cached body with a stale
 * advisory timestamp is harmless). playerUuid/origin/clientVersion join the
 * hash because they change what the body contains.
 */
export async function worldsEtag(svc: ServiceContext, ctx: RequestContext): Promise<string> {
  const facts = await svc.repository.worldsChangeFacts(ctx.playerUuid);
  return weakEtagOf({ facts, playerUuid: ctx.playerUuid, origin: ctx.requestOrigin ?? null, clientVersion: ctx.clientVersion ?? null });
}

export async function worldEtag(svc: ServiceContext, ctx: RequestContext, worldId: string, now = new Date()): Promise<string | null> {
  const facts = await svc.repository.worldChangeFacts(worldId, ctx.playerUuid, now);
  if (facts == null) {
    return null;
  }
  return weakEtagOf({ facts, playerUuid: ctx.playerUuid, origin: ctx.requestOrigin ?? null, clientVersion: ctx.clientVersion ?? null });
}

async function weakEtagOf(material: unknown): Promise<string> {
  const bytes = new TextEncoder().encode(JSON.stringify(material));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  const hex = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `W/"${hex}"`;
}

export async function createWorld(
  svc: ServiceContext,
  ctx: RequestContext,
  request: CreateWorldRequest,
  now: Date
): Promise<CreateWorldResult> {
  // Growth valve: a hard capacity ceiling turns unexpected virality into a
  // polite queue instead of an unbounded bill. Checked before any validation
  // or storage-link consumption so a refused create leaves nothing behind.
  const maxActiveWorlds = parsePositiveInt(svc.env.MAX_ACTIVE_WORLDS, 0);
  if (maxActiveWorlds > 0 && await svc.repository.countActiveWorlds() >= maxActiveWorlds) {
    throw new HttpError(
      503,
      "world_capacity_reached",
      "SharedWorld is at capacity right now, so new worlds can't be created. Please try again later."
    );
  }
  const name = requireValidWorldName(request.name);
  if ((request.storageLinkSessionId || request.useLinkedStorageAccount) && (request.importSource?.type !== "local-save" || !request.importSource.id.trim())) {
    throw new HttpError(400, "invalid_import_source", "A local save import source is required.");
  }
  const binding: StorageBinding = request.storageLinkSessionId
    ? await (async () => {
      const link = await svc.storageLinks.requireCompletedLinkSession(ctx, request.storageLinkSessionId!);
      return { provider: link.provider, storageAccountId: link.storageAccountId };
    })()
    : request.useLinkedStorageAccount
      ? await resolveLinkedStorageBinding(svc, ctx)
      : { provider: svc.storageProvider.provider, storageAccountId: null };
  const motd = normalizeMotd(request.motdLine1 ?? null, request.motdLine2 ?? null);
  const world = await svc.repository.createWorld(ctx, name, slugify(name), binding, motd, null);
  if (request.customIconPngBase64) {
    const customIconStorageKey = await storeCustomIcon(
      svc,
      { provider: world.storageProvider, storageAccountId: binding.storageAccountId },
      request.customIconPngBase64
    );
    const updated = await svc.repository.updateWorld(ctx, world.id, {
      name: world.name,
      motdLine1: splitMotd(world.motd)[0],
      motdLine2: splitMotd(world.motd)[1],
      customIconStorageKey,
      customIconPngBase64: null,
      clearCustomIcon: false
    });
    return createSeededWorldResult(svc, ctx, updated, now);
  }
  return createSeededWorldResult(svc, ctx, world, now);
}

export async function getWorld(svc: ServiceContext, ctx: RequestContext, worldId: string, now: Date): Promise<WorldDetails> {
  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  const hydrated = await hydrateWorldDetails(svc, world, ctx.requestOrigin);
  // 0.4.1+ clients fetch usage on demand (GET /worlds/:id/storage-usage from
  // the edit screen); the inline value here priced every world-details read
  // at a full file-table scan plus a Drive quota call — fatal on the paths
  // old cache warmers poll every 30s. Pre-0.4.1 clients keep the inline
  // value, served from the Workers Cache. Best-effort either way: the
  // storage display must never block world details, and clients tolerate
  // null here.
  if (clientVersionAtLeast(ctx.clientVersion, 0, 4, 1)) {
    hydrated.storageUsage = null;
  } else {
    try {
      hydrated.storageUsage = await legacyCachedStorageUsage(svc, hydrated);
    } catch (error) {
      console.warn("SharedWorld storage usage unavailable for world details", {
        worldId,
        error: error instanceof Error ? error.message : String(error)
      });
      hydrated.storageUsage = null;
    }
  }
  hydrated.activeInviteCode = world.ownerUuid === ctx.playerUuid
    ? await svc.repository.getActiveInvite(worldId, now)
    : null;
  return hydrated;
}

export async function updateWorld(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: UpdateWorldRequest
): Promise<WorldDetails> {
  const name = requireValidWorldName(request.name);

  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  requireOwner(world, ctx, "edit this world");

  const motd = normalizeMotd(request.motdLine1 ?? null, request.motdLine2 ?? null);
  const binding = await requireWorldStorageBinding(svc, worldId);
  let customIconStorageKey = world.customIconStorageKey;
  if (request.clearCustomIcon) {
    customIconStorageKey = null;
  } else if (request.customIconPngBase64) {
    customIconStorageKey = await storeCustomIcon(svc, binding, request.customIconPngBase64);
  }
  const updated = await svc.repository.updateWorld(ctx, worldId, {
    name,
    motdLine1: splitMotd(motd)[0],
    motdLine2: splitMotd(motd)[1],
    customIconStorageKey,
    customIconPngBase64: null,
    clearCustomIcon: Boolean(request.clearCustomIcon)
  } satisfies WorldUpdateRecord);
  await maybeDeleteUnreferencedBlob(
    svc,
    binding,
    world.customIconStorageKey !== updated.customIconStorageKey ? world.customIconStorageKey : null
  );
  await publishWorldEvent(svc, worldId, "world-changed");
  return hydrateWorldDetails(svc, updated, ctx.requestOrigin);
}

export async function updateWorldSettings(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: UpdateWorldSettingsRequest
): Promise<WorldDetails> {
  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  requireOwner(world, ctx, "change world settings");
  const settings = validateWorldSettings(request.settings);
  if (!await svc.repository.updateWorldSettings(worldId, JSON.stringify(settings))) {
    throw new HttpError(404, "world_not_found", "This Shared World no longer exists.");
  }
  await publishWorldEvent(svc, worldId, "settings-changed");
  const updated = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  return hydrateWorldDetails(svc, updated, ctx.requestOrigin);
}

/**
 * Host-reported settings persistence: the active host pushes the managed
 * server's current gamerule/difficulty/game-mode values so in-game
 * /gamerule, /difficulty, and /defaultgamemode changes survive the session.
 * Runtime-authorized (the host may not be the owner). Gamerules merge per
 * key; difficulty and game mode are last-write-wins with the owner's
 * settings screen.
 */
export async function reportHostGameRules(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: HostGameRulesReportRequest,
  now: Date
): Promise<HostGameRulesReportResponse> {
  // host-finalizing is allowed so a shutdown flush can still land.
  await requireHostAuthority(svc, ctx, worldId, request.runtimeEpoch, request.hostToken, [
    "host-live",
    "host-finalizing"
  ], now);
  const gamerules = validateGameRules(request.gamerules);
  const difficulty = validateOptionalDifficulty(request.difficulty);
  const defaultGameMode = validateOptionalGameMode(request.defaultGameMode);
  // Merge-and-CAS: the owner's PUT bumps the revision blindly, so a report
  // racing an owner save must re-read and merge against the fresh base
  // instead of resurrecting stale difficulty/gameMode values.
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const stored = await svc.repository.getWorldSettings(worldId);
    if (!stored) {
      throw new HttpError(404, "world_not_found", "This Shared World no longer exists.");
    }
    const merged: WorldSettings = {
      ...(stored.settings ?? {}),
      gamerules: { ...(stored.settings?.gamerules ?? {}), ...gamerules }
    };
    if (difficulty != null) {
      merged.difficulty = difficulty;
    }
    if (defaultGameMode != null) {
      merged.defaultGameMode = defaultGameMode;
    }
    if (await svc.repository.updateWorldSettingsIfRevision(worldId, JSON.stringify(merged), stored.settingsRevision)) {
      await publishWorldEvent(svc, worldId, "settings-changed");
      return { settings: merged, settingsRevision: stored.settingsRevision + 1 };
    }
  }
  throw new HttpError(409, "settings_conflict", "World settings changed while saving the game rule update. Please try again.");
}

export async function deleteWorld(svc: ServiceContext, ctx: RequestContext, worldId: string, now: Date): Promise<void> {
  await requireWorldDetails(svc, worldId, ctx.playerUuid);
  const binding = await requireWorldStorageBinding(svc, worldId);
  // Capture the recipients before the membership rows go away: they are who
  // must hear that the world vanished.
  const recipients = (await svc.repository.listMemberships(worldId))
    .filter((member) => member.deletedAt == null)
    .map((member) => member.playerUuid);
  const result = await svc.repository.deleteWorldForPlayer(ctx, worldId, now);
  if (result.worldDeleted) {
    await purgeWorldSnapshots(svc, binding, worldId);
    await maybeDeleteUnreferencedBlob(svc, binding, result.deletedCustomIconStorageKey);
    // P5: the coordinator drops every runtime trace and pushes world-deleted.
    await svc.realtime.coordinator(worldId).destroyWorld(recipients);
  }
}

export async function getStorageUsage(svc: ServiceContext, ctx: RequestContext, worldId: string): Promise<StorageUsageSummary> {
  await requireMembership(svc, ctx, worldId);
  const usage = await svc.repository.getStorageUsage(worldId);
  const binding = await requireWorldStorageBinding(svc, worldId);
  const quota = await cachedQuota(svc, binding);
  return {
    ...usage,
    quotaUsedBytes: quota.usedBytes,
    quotaTotalBytes: quota.totalBytes
  };
}

/**
 * The pre-0.4.1 inline storageUsage, priced for a polling path: usedBytes is
 * cached keyed (worldId, lastSnapshotId) so the referenced-keys CTE runs
 * once per snapshot change instead of per poll, and the provider/linked/email
 * facts ride the summary the caller already loaded. Advisory display data —
 * retention/icon drift self-corrects within the cache TTL.
 */
async function legacyCachedStorageUsage(svc: ServiceContext, world: WorldDetails): Promise<StorageUsageSummary> {
  const cache = svc.storageUsageCache;
  let usedBytes = await cache?.getUsedBytes(world.id, world.lastSnapshotId) ?? null;
  if (usedBytes == null) {
    usedBytes = (await svc.repository.getStorageUsage(world.id)).usedBytes;
    await cache?.putUsedBytes(world.id, world.lastSnapshotId, usedBytes);
  }
  const binding = await requireWorldStorageBinding(svc, world.id);
  const quota = await cachedQuota(svc, binding);
  return {
    provider: world.storageProvider,
    linked: world.storageLinked,
    usedBytes,
    quotaUsedBytes: quota.usedBytes,
    quotaTotalBytes: quota.totalBytes,
    accountEmail: world.storageAccountEmail
  };
}

/** Account quota with the Workers-Cache front: one Drive `/about` per TTL, not per poll. */
async function cachedQuota(
  svc: ServiceContext,
  binding: Awaited<ReturnType<typeof requireWorldStorageBinding>>
): Promise<{ usedBytes: number | null; totalBytes: number | null }> {
  const accountId = binding.storageAccountId;
  const cache = svc.storageUsageCache;
  if (accountId != null) {
    const cached = await cache?.getQuota(accountId);
    if (cached != null) {
      return cached;
    }
  }
  const fresh = await svc.storageProvider.quota(binding);
  const quota = { usedBytes: fresh.usedBytes, totalBytes: fresh.totalBytes };
  if (accountId != null) {
    await cache?.putQuota(accountId, quota);
  }
  return quota;
}

export async function hydrateWorldSummary(svc: ServiceContext, world: WorldSummary, requestOrigin?: string): Promise<WorldSummary> {
  if (!world.customIconStorageKey) {
    return world;
  }
  return {
    ...world,
    customIconDownload: await signDownloadForWorld(svc, world.id, world.customIconStorageKey, requestOrigin)
  };
}

export function hydrateWorldDetails(svc: ServiceContext, world: WorldDetails, requestOrigin?: string): Promise<WorldDetails> {
  return hydrateWorldSummary(svc, world, requestOrigin) as Promise<WorldDetails>;
}

/**
 * A brand-new world starts with a host-starting runtime owned by its creator so the
 * initial world upload runs under a normal epoch/token authorization.
 */
async function createSeededWorldResult(
  svc: ServiceContext,
  ctx: RequestContext,
  world: WorldDetails,
  now: Date
): Promise<CreateWorldResult> {
  const actor = await sessionActorOf(svc, ctx, world.id);
  let decision;
  try {
    decision = await svc.realtime.coordinator(world.id).enterSession(actor, {}, now);
  } catch (error) {
    // P8: a failed create must leave nothing behind — even a coordinator
    // outage during the seed claim compensates instead of stranding rows.
    await svc.repository.deleteWorldForPlayer(ctx, world.id, now);
    throw error;
  }
  if (decision.action !== "host" || decision.assignment == null) {
    // The world row and owner membership were already inserted, so
    // compensate before rejecting. (Any hash-keyed icon blob stays: it may
    // be shared with other worlds.)
    await svc.repository.deleteWorldForPlayer(ctx, world.id, now);
    throw new HttpError(409, "world_busy", "SharedWorld is already being set up.");
  }
  return {
    world: await hydrateWorldDetails(svc, world, ctx.requestOrigin),
    initialUploadAssignment: decision.assignment
  };
}

async function storeCustomIcon(svc: ServiceContext, binding: StorageBinding, iconBase64: string): Promise<string> {
  const bytes = Uint8Array.from(atob(iconBase64), (value) => value.charCodeAt(0));
  if (!isPng(bytes) || pngWidth(bytes) !== 64 || pngHeight(bytes) !== 64) {
    throw new HttpError(400, "invalid_custom_icon", "Custom icon must be a 64x64 PNG.");
  }
  const hash = await sha256Hex(bytes);
  const storageKey = iconStorageKey(hash);
  if (!(await svc.storageProvider.exists(binding, storageKey))) {
    await svc.storageProvider.put(binding, storageKey, bytes, "image/png");
  }
  return storageKey;
}

/**
 * Bind a new world to the caller's already-linked storage account. Only an
 * account that can still refresh its authorization qualifies; anything else
 * must go through a fresh link session.
 */
async function resolveLinkedStorageBinding(svc: ServiceContext, ctx: RequestContext): Promise<StorageBinding> {
  const accounts = await svc.repository.findStorageAccountsByOwner(svc.storageProvider.provider, ctx.playerUuid);
  const account = accounts.find((candidate) => candidate.refreshToken != null);
  if (!account) {
    throw new HttpError(409, "storage_not_linked", "Google Drive isn't connected yet. Connect it and try again.");
  }
  return { provider: account.provider, storageAccountId: account.id };
}

const WORLD_DIFFICULTIES: readonly WorldDifficulty[] = ["peaceful", "easy", "normal", "hard"];
const WORLD_DEFAULT_GAME_MODES: readonly WorldDefaultGameMode[] = ["survival", "creative", "adventure"];
const WORLD_GAME_RULES: readonly WorldGameRule[] = ["keepInventory", "mobGriefing", "daylightCycle", "weatherCycle", "pvp"];

/** Whitelist validation: reject unknown fields/values instead of storing them. */
function validateWorldSettings(raw: WorldSettings | undefined): WorldSettings {
  if (raw == null || typeof raw !== "object" || Array.isArray(raw)) {
    throw new HttpError(400, "invalid_world_settings", "World settings are missing or malformed.");
  }
  const settings: WorldSettings = {};
  for (const key of Object.keys(raw)) {
    if (key !== "difficulty" && key !== "defaultGameMode" && key !== "gamerules") {
      throw new HttpError(400, "invalid_world_settings", `Unknown world setting "${key}".`);
    }
  }
  if (raw.difficulty !== undefined) {
    if (!WORLD_DIFFICULTIES.includes(raw.difficulty)) {
      throw new HttpError(400, "invalid_world_settings", "That difficulty isn't one of the supported values.");
    }
    settings.difficulty = raw.difficulty;
  }
  if (raw.defaultGameMode !== undefined) {
    if (!WORLD_DEFAULT_GAME_MODES.includes(raw.defaultGameMode)) {
      throw new HttpError(400, "invalid_world_settings", "That game mode isn't one of the supported values.");
    }
    settings.defaultGameMode = raw.defaultGameMode;
  }
  if (raw.gamerules !== undefined) {
    settings.gamerules = validateGameRules(raw.gamerules);
  }
  return settings;
}

/** Host-reported difficulty: absent means "no change", anything else must be valid. */
function validateOptionalDifficulty(raw: WorldDifficulty | null | undefined): WorldDifficulty | null {
  if (raw == null) {
    return null;
  }
  if (!WORLD_DIFFICULTIES.includes(raw)) {
    throw new HttpError(400, "invalid_world_settings", "That difficulty isn't one of the supported values.");
  }
  return raw;
}

/** Host-reported game mode: absent means "no change", anything else must be valid. */
function validateOptionalGameMode(raw: WorldDefaultGameMode | null | undefined): WorldDefaultGameMode | null {
  if (raw == null) {
    return null;
  }
  if (!WORLD_DEFAULT_GAME_MODES.includes(raw)) {
    throw new HttpError(400, "invalid_world_settings", "That game mode isn't one of the supported values.");
  }
  return raw;
}

/** Whitelist validation shared by the owner settings PUT and the host gamerule report. */
function validateGameRules(raw: unknown): Partial<Record<WorldGameRule, boolean>> {
  if (raw == null || typeof raw !== "object" || Array.isArray(raw)) {
    throw new HttpError(400, "invalid_world_settings", "World settings are missing or malformed.");
  }
  const gamerules: Partial<Record<WorldGameRule, boolean>> = {};
  for (const [rule, value] of Object.entries(raw)) {
    if (!WORLD_GAME_RULES.includes(rule as WorldGameRule) || typeof value !== "boolean") {
      throw new HttpError(400, "invalid_world_settings", `Unknown game rule "${rule}".`);
    }
    gamerules[rule as WorldGameRule] = value;
  }
  return gamerules;
}

const MAX_WORLD_NAME_LENGTH = 128;

/**
 * The client caps the name field at 128 characters, but the backend must not trust that: validate
 * both ends here so a hand-crafted request cannot store an unbounded name.
 */
function requireValidWorldName(rawName: string): string {
  const name = rawName.trim();
  if (name.length < 3) {
    throw new HttpError(400, "invalid_world_name", "World name must be at least 3 characters.");
  }
  if (name.length > MAX_WORLD_NAME_LENGTH) {
    throw new HttpError(400, "invalid_world_name", `World name must be at most ${MAX_WORLD_NAME_LENGTH} characters.`);
  }
  return name;
}

function normalizeMotd(line1: string | null, line2: string | null): string | null {
  const lines = [line1 ?? "", line2 ?? ""]
    .flatMap((line) => line.replace(/\r/g, "").split("\n"))
    .map((line) => line.trimEnd())
    .filter((line) => line.length > 0);
  if (lines.length > 2) {
    throw new HttpError(400, "invalid_motd", "Shared World MOTD can use at most 2 lines.");
  }
  return lines.length > 0 ? lines.join("\n") : null;
}

function splitMotd(motd: string | null): [string | null, string | null] {
  if (!motd) {
    return [null, null];
  }
  const lines = motd.split("\n");
  return [lines[0] ?? null, lines[1] ?? null];
}

function iconStorageKey(hash: string): string {
  return `icons/${hash.slice(0, 2)}/${hash}.png`;
}

function isPng(bytes: Uint8Array): boolean {
  const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  return signature.every((value, index) => bytes[index] === value);
}

function pngWidth(bytes: Uint8Array): number {
  return bytes.length >= 24 ? readPngInt(bytes, 16) : 0;
}

function pngHeight(bytes: Uint8Array): number {
  return bytes.length >= 24 ? readPngInt(bytes, 20) : 0;
}

function readPngInt(bytes: Uint8Array, offset: number): number {
  return ((bytes[offset] ?? 0) << 24)
    | ((bytes[offset + 1] ?? 0) << 16)
    | ((bytes[offset + 2] ?? 0) << 8)
    | (bytes[offset + 3] ?? 0);
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  const digest = await crypto.subtle.digest("SHA-256", copy.buffer);
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, "0")).join("");
}
