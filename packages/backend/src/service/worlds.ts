import type {
  CreateWorldRequest,
  CreateWorldResult,
  StorageUsageSummary,
  UpdateWorldRequest,
  WorldDetails,
  WorldSummary
} from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import { randomId, slugify } from "../ids.ts";
import type { RequestContext, WorldUpdateRecord } from "../repository.ts";
import { assignHostStarting } from "../runtime-protocol.ts";
import type { StorageBinding } from "../storage.ts";
import { signDownloadForWorld, type ServiceContext } from "./context.ts";
import {
  requireMembership,
  requireOwner,
  requireWorldDetails,
  requireWorldStorageBinding
} from "./runtime-access.ts";
import { maybeDeleteUnreferencedBlob, purgeWorldSnapshots } from "./snapshots.ts";

export async function listWorlds(svc: ServiceContext, ctx: RequestContext): Promise<WorldSummary[]> {
  const worlds = await svc.repository.listWorldsForPlayer(ctx.playerUuid);
  return Promise.all(worlds.map((world) => hydrateWorldSummary(svc, world, ctx.requestOrigin)));
}

export async function createWorld(
  svc: ServiceContext,
  ctx: RequestContext,
  request: CreateWorldRequest,
  now: Date
): Promise<CreateWorldResult> {
  const name = request.name.trim();
  if (name.length < 3) {
    throw new HttpError(400, "invalid_world_name", "World name must be at least 3 characters.");
  }
  if (request.storageLinkSessionId && (request.importSource?.type !== "local-save" || !request.importSource.id.trim())) {
    throw new HttpError(400, "invalid_import_source", "A local save import source is required.");
  }
  const binding: StorageBinding = request.storageLinkSessionId
    ? await (async () => {
      const link = await svc.storageLinks.requireCompletedLinkSession(ctx, request.storageLinkSessionId!);
      return { provider: link.provider, storageAccountId: link.storageAccountId };
    })()
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
  hydrated.storageUsage = await getStorageUsage(svc, ctx, worldId);
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
  const name = request.name.trim();
  if (name.length < 3) {
    throw new HttpError(400, "invalid_world_name", "World name must be at least 3 characters.");
  }

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
  return hydrateWorldDetails(svc, updated, ctx.requestOrigin);
}

export async function deleteWorld(svc: ServiceContext, ctx: RequestContext, worldId: string, now: Date): Promise<void> {
  await requireWorldDetails(svc, worldId, ctx.playerUuid);
  const binding = await requireWorldStorageBinding(svc, worldId);
  const result = await svc.repository.deleteWorldForPlayer(ctx, worldId, now);
  if (result.worldDeleted) {
    await purgeWorldSnapshots(svc, binding, worldId);
    await maybeDeleteUnreferencedBlob(svc, binding, result.deletedCustomIconStorageKey);
  }
}

export async function getStorageUsage(svc: ServiceContext, ctx: RequestContext, worldId: string): Promise<StorageUsageSummary> {
  await requireMembership(svc, ctx, worldId);
  const usage = await svc.repository.getStorageUsage(worldId);
  const binding = await requireWorldStorageBinding(svc, worldId);
  const quota = await svc.storageProvider.quota(binding);
  return {
    ...usage,
    quotaUsedBytes: quota.usedBytes,
    quotaTotalBytes: quota.totalBytes
  };
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
  const initialUpload = assignHostStarting(
    world.id,
    {
      playerUuid: ctx.playerUuid,
      playerName: ctx.playerName
    },
    null,
    now,
    () => randomId("rt")
  );
  await svc.repository.clearWaitersForPlayer(world.id, ctx.playerUuid);
  await svc.repository.upsertRuntimeRecord(initialUpload.runtime);
  return {
    world: await hydrateWorldDetails(svc, world, ctx.requestOrigin),
    initialUploadAssignment: initialUpload.assignment
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
