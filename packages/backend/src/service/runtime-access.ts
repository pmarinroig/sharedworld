import type { WorldDetails } from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import type { RequestContext, WorldStorageBinding } from "../repository.ts";
import type { SessionActor } from "../realtime/coordinator.ts";
import type { ServiceContext } from "./context.ts";

/**
 * Membership facts for a session call, resolved from D1 (which owns worlds
 * and memberships). The coordinator applies the access decision — including
 * the revoked-host exception (I7) — against these facts plus its own runtime.
 */
export async function sessionActorOf(svc: ServiceContext, ctx: RequestContext, worldId: string): Promise<SessionActor> {
  if (!await svc.repository.hasActiveWorld(worldId)) {
    throw worldNotFoundError();
  }
  const membershipActive = await svc.repository.isWorldMember(worldId, ctx.playerUuid);
  const everMember = membershipActive || await svc.repository.hasWorldMembership(worldId, ctx.playerUuid);
  return {
    playerUuid: ctx.playerUuid,
    playerName: ctx.playerName,
    membershipActive,
    everMember
  };
}

/** Old requireSessionAccess without options: active member or a labeled 403. */
export async function requireActiveMembership(svc: ServiceContext, ctx: RequestContext, worldId: string): Promise<void> {
  const facts = await sessionActorOf(svc, ctx, worldId);
  if (facts.membershipActive) {
    return;
  }
  if (facts.everMember) {
    throw new HttpError(403, "membership_revoked", "You were removed from this SharedWorld.");
  }
  throw new HttpError(403, "forbidden", "You do not have access to this SharedWorld server.");
}

/**
 * Session access honoring the revoked-host exception (I7): resolved by the
 * coordinator, which owns the runtime the exception depends on.
 */
export async function requireSessionAccessAllowingRevokedHost(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string
): Promise<void> {
  const facts = await sessionActorOf(svc, ctx, worldId);
  await svc.realtime.coordinator(worldId).assertSessionAccess(facts, { allowRevokedHost: true });
}

/**
 * Host authority for HTTP write paths (uploads, finalize-snapshot, gamerule
 * reports): delegates to the coordinator, the only runtime truth. Includes
 * session access with the revoked-host exception.
 */
export async function requireHostAuthority(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  runtimeEpoch: number | null | undefined,
  hostToken: string | null | undefined,
  allowedPhases: Array<"host-starting" | "host-live" | "host-finalizing">,
  now: Date
): Promise<void> {
  const facts = await sessionActorOf(svc, ctx, worldId);
  await svc.realtime.coordinator(worldId).validateHostAuthority(facts, runtimeEpoch, hostToken, allowedPhases, now);
}

/** Fan one change event out to the world's active members' gateways. */
export async function publishWorldEvent(
  svc: ServiceContext,
  worldId: string,
  kind: "membership-changed" | "settings-changed" | "world-changed" | "snapshot-changed"
): Promise<void> {
  const members = await svc.repository.listMemberships(worldId);
  const recipients = members.filter((member) => member.deletedAt == null).map((member) => member.playerUuid);
  await svc.realtime.notifyUsers({ worldId, kind }, recipients);
}

export async function requireMembership(svc: ServiceContext, ctx: RequestContext, worldId: string): Promise<void> {
  if (!await svc.repository.hasActiveWorld(worldId)) {
    throw worldNotFoundError();
  }
  const isMember = await svc.repository.isWorldMember(worldId, ctx.playerUuid);
  if (!isMember) {
    throw new HttpError(403, "forbidden", "You do not have access to this SharedWorld server.");
  }
}

export async function requireWorldDetails(svc: ServiceContext, worldId: string, playerUuid: string): Promise<WorldDetails> {
  const world = await svc.repository.getWorldDetails(worldId, playerUuid);
  if (!world) {
    throw worldNotFoundError();
  }
  return world;
}

export function requireOwner(world: WorldDetails, ctx: RequestContext, action: string): void {
  if (world.ownerUuid !== ctx.playerUuid) {
    throw new HttpError(403, "forbidden", `Only the SharedWorld owner can ${action}.`);
  }
}

export async function requireWorldStorageBinding(svc: ServiceContext, worldId: string): Promise<WorldStorageBinding> {
  const binding = await svc.repository.getWorldStorageBinding(worldId);
  if (!binding) {
    throw worldNotFoundError();
  }
  return binding;
}

export function worldNotFoundError(): HttpError {
  return new HttpError(404, "world_not_found", "SharedWorld server not found.");
}
