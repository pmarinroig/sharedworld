import type { WorldDetails } from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import type { RequestContext, WorldStorageBinding } from "../repository.ts";
import { matchesHostAuthorization, type WorldRuntimeRecord } from "../runtime-protocol.ts";
import { reconcileRuntimeState } from "../runtime-reconciliation.ts";
import type { AuthorizedRuntime, ResolvedRuntimeState } from "../runtime-service-support.ts";
import type { ServiceContext } from "./context.ts";

/**
 * Responsibility:
 * Resolve the single authoritative runtime record for a world after applying timeout and
 * current-candidate reconciliation.
 *
 * Postconditions:
 * The returned runtime reflects timeout expiry and current preferred candidate selection.
 *
 * Stale-work rule:
 * Timeout and candidate reconciliation happen before any caller reasons about the runtime.
 */
export async function resolveRuntimeState(svc: ServiceContext, worldId: string, now: Date): Promise<ResolvedRuntimeState> {
  return reconcileRuntimeState(svc.repository, worldId, now);
}

/**
 * New host assignments must never reuse an epoch that an earlier runtime already used,
 * even when that runtime expired or ended in an unclean-shutdown warning.
 */
export function runtimeEpochBaseline(resolved: ResolvedRuntimeState): Pick<WorldRuntimeRecord, "runtimeEpoch"> | null {
  if (resolved.runtime != null) {
    return resolved.runtime;
  }
  if (resolved.warning != null) {
    return { runtimeEpoch: resolved.warning.runtimeEpoch };
  }
  if (resolved.retiredRuntimeEpoch != null) {
    return { runtimeEpoch: resolved.retiredRuntimeEpoch };
  }
  return null;
}

/**
 * Responsibility:
 * Enforce epoch/token authority for host-owned runtime mutations.
 *
 * Stale-work rule:
 * Any mismatched epoch/token is rejected, even if the same player used to be host.
 */
export async function requireAuthorizedRuntime(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  now: Date,
  runtimeEpoch: number | null | undefined,
  hostToken: string | null | undefined,
  allowedPhases: WorldRuntimeRecord["phase"][]
): Promise<AuthorizedRuntime> {
  const resolved = await resolveRuntimeState(svc, worldId, now);
  if (!resolved.runtime
    || !allowedPhases.includes(resolved.runtime.phase)
    || !matchesHostAuthorization(resolved.runtime, ctx.playerUuid, runtimeEpoch, hostToken)) {
    throw hostNotActiveError();
  }
  return {
    runtime: resolved.runtime
  };
}

export function hostNotActiveError(): HttpError {
  return new HttpError(409, "host_not_active", "Someone else is hosting this world now, so this upload was stopped.");
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

/**
 * Session access is membership access, except that a kicked host may still finish
 * shutting down the runtime it already owns (I7 in the protocol doc).
 */
export async function requireSessionAccess(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  options: { allowRevokedHost?: boolean } = {}
): Promise<void> {
  if (!await svc.repository.hasActiveWorld(worldId)) {
    throw worldNotFoundError();
  }
  if (await svc.repository.isWorldMember(worldId, ctx.playerUuid)) {
    return;
  }
  if (options.allowRevokedHost) {
    const resolved = await resolveRuntimeState(svc, worldId, new Date());
    if (resolved.runtime?.hostUuid === ctx.playerUuid && resolved.runtime.revokedAt != null) {
      return;
    }
  }
  if (!await svc.repository.hasWorldMembership(worldId, ctx.playerUuid)) {
    throw new HttpError(403, "forbidden", "You do not have access to this SharedWorld server.");
  }
  throw new HttpError(403, "membership_revoked", "You were removed from this SharedWorld.");
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
