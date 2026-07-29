import {
  PLAYER_PRESENCE_TIMEOUT_MS,
  type AbandonFinalizationRequest,
  type BeginFinalizationRequest,
  type CancelWaitingRequest,
  type CompleteFinalizationRequest,
  type EnterSessionRequest,
  type EnterSessionResponse,
  type FinalizationActionResult,
  type HeartbeatRequest,
  type HostHeartbeatMembership,
  type HostHeartbeatResponse,
  type HostStartupProgressRequest,
  type ObserveWaitingRequest,
  type ObserveWaitingResponse,
  type PresenceHeartbeatRequest,
  type RefreshWaitingRequest,
  type ReleaseHostRequest,
  type WorldRuntimeStatus
} from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import { randomId } from "../ids.ts";
import type { RequestContext, UncleanShutdownWarning } from "../repository.ts";
import {
  assignHostStarting,
  matchesHostAuthorization,
  moveToFinalizing,
  refreshLiveRuntime,
  runtimePhaseToWorldStatus,
  setHostProgress,
  toRuntimeStatus,
  type RuntimeCandidate,
  type WorldRuntimeRecord
} from "../runtime-protocol.ts";
import {
  hostAssignmentForCurrentRuntime,
  runtimeAllowsDirectConnect,
  runtimeRequiresWaiting,
  type AuthorizedRuntime,
  type ResolvedRuntimeState
} from "../runtime-service-support.ts";
import type { ServiceContext } from "./context.ts";
import {
  hostNotActiveError,
  requireAuthorizedRuntime,
  requireOwner,
  requireSessionAccess,
  requireWorldDetails,
  resolveRuntimeState,
  runtimeEpochBaseline
} from "./runtime-access.ts";
import { getWorld } from "./worlds.ts";

/**
 * Responsibility:
 * Resolve a player's authoritative session entry outcome: connect, wait, or host assignment.
 *
 * Postconditions:
 * Exactly one entry action is returned, based on the resolved runtime and waiter candidate.
 *
 * Stale-work rule:
 * The backend never trusts client-side host eligibility; it derives the answer from the
 * current runtime record and waiter set each time.
 */
export async function enterSession(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: EnterSessionRequest,
  now: Date
): Promise<EnterSessionResponse> {
  await requireSessionAccess(svc, ctx, worldId);
  const world = await getWorld(svc, ctx, worldId, now);
  const latestManifest = await svc.repository.getLatestSnapshot(worldId);
  const requestedWaiterSessionId = sanitizeWaiterSessionId(request.waiterSessionId);
  const resolved = await resolveRuntimeState(svc, worldId, now);
  if (runtimeAllowsDirectConnect(resolved)) {
    if (requestedWaiterSessionId != null) {
      await svc.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId: requestedWaiterSessionId });
    }
    return {
      action: "connect",
      world,
      latestManifest,
      runtime: toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning),
      assignment: null,
      waiterSessionId: null
    };
  }
  const currentAssignment = hostAssignmentForCurrentRuntime(resolved, ctx.playerUuid);
  if (currentAssignment != null) {
    if (requestedWaiterSessionId != null) {
      await svc.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId: requestedWaiterSessionId });
    }
    return {
      action: "host",
      world,
      latestManifest,
      runtime: toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning),
      assignment: currentAssignment,
      waiterSessionId: null
    };
  }
  if (resolved.runtime == null && resolved.candidate == null) {
    if (resolved.warning != null && !request.acknowledgeUncleanShutdown) {
      return {
        action: "warn-host",
        world,
        latestManifest,
        runtime: toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning),
        assignment: null,
        waiterSessionId: null
      };
    }
    const assigned = assignHostStarting(
      worldId,
      { playerUuid: ctx.playerUuid, playerName: ctx.playerName },
      runtimeEpochBaseline(resolved),
      now,
      () => randomId("rt")
    );
    if (await svc.repository.claimRuntimeAssignment(assigned.runtime)) {
      if (requestedWaiterSessionId != null) {
        await svc.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId: requestedWaiterSessionId });
      }
      return {
        action: "host",
        world,
        latestManifest,
        runtime: toRuntimeStatus(worldId, assigned.runtime, resolved.candidate, resolved.warning),
        assignment: assigned.assignment,
        waiterSessionId: null
      };
    }
    // Lost the acquire race to a concurrent claimant; fall through to the
    // waiting flow, which re-resolves against the winner's runtime.
  }
  const waiterSessionId = requestedWaiterSessionId ?? randomId("wait");
  const waiterSessionActive = requestedWaiterSessionId != null
    ? await svc.repository.refreshWaiterSession(worldId, ctx, { waiterSessionId }, now)
    : (await svc.repository.upsertWaiterSession(worldId, ctx, waiterSessionId, now), true);
  const waitingResolved = await resolveRuntimeState(svc, worldId, now);
  if (runtimeRequiresWaiting(waitingResolved)) {
    return {
      action: "wait",
      world,
      latestManifest,
      runtime: toRuntimeStatus(worldId, waitingResolved.runtime, waitingResolved.candidate, waitingResolved.warning),
      assignment: null,
      waiterSessionId: waiterSessionActive ? waiterSessionId : null
    };
  }
  if (waitingResolved.runtime == null && waiterSessionActive && waitingResolved.candidate?.playerUuid === ctx.playerUuid) {
    const assigned = assignHostStarting(
      worldId,
      waitingResolved.candidate,
      runtimeEpochBaseline(waitingResolved),
      now,
      () => randomId("rt")
    );
    if (await svc.repository.claimRuntimeAssignment(assigned.runtime)) {
      await svc.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId });
      return {
        action: "host",
        world,
        latestManifest,
        runtime: toRuntimeStatus(worldId, assigned.runtime, waitingResolved.candidate, waitingResolved.warning),
        assignment: assigned.assignment,
        waiterSessionId: null
      };
    }
    // Lost the promotion race; keep waiting against the winner's runtime.
  }
  return {
    action: "wait",
    world,
    latestManifest,
    runtime: toRuntimeStatus(worldId, waitingResolved.runtime, waitingResolved.candidate, waitingResolved.warning),
    assignment: null,
    waiterSessionId: waiterSessionActive ? waiterSessionId : null
  };
}

/**
 * Responsibility:
 * Advance a single waiting attempt atomically so the client can react to one authoritative
 * action instead of inferring session transitions from raw runtime state.
 *
 * Postconditions:
 * Exactly one action is returned: connect, wait, or restart.
 *
 * Stale-work rule:
 * A missing or stale waiter session never reanimates waiting; it yields restart instead.
 */
export async function observeWaiting(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: ObserveWaitingRequest,
  now: Date
): Promise<ObserveWaitingResponse> {
  await requireSessionAccess(svc, ctx, worldId);
  const waiterSessionId = sanitizeWaiterSessionId(request.waiterSessionId);
  if (!waiterSessionId) {
    throw new HttpError(400, "invalid_waiter_session", "SharedWorld waiting session id is required.");
  }
  const waiterSessionActive = await svc.repository.refreshWaiterSession(worldId, ctx, { waiterSessionId }, now);
  const resolved = await resolveRuntimeState(svc, worldId, now);
  const runtime = toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning);
  if (runtimeAllowsDirectConnect(resolved)) {
    if (waiterSessionActive) {
      await svc.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId });
    }
    return {
      action: "connect",
      runtime,
      assignment: null,
      waiterSessionId: null
    };
  }
  const currentAssignment = hostAssignmentForCurrentRuntime(resolved, ctx.playerUuid);
  if (currentAssignment != null) {
    if (waiterSessionActive) {
      await svc.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId });
    }
    return {
      action: "restart",
      runtime,
      assignment: null,
      waiterSessionId: null
    };
  }
  if (!waiterSessionActive) {
    return {
      action: "restart",
      runtime,
      assignment: null,
      waiterSessionId: null
    };
  }
  if (resolved.runtime == null && resolved.candidate?.playerUuid === ctx.playerUuid) {
    const assigned = assignHostStarting(
      worldId,
      resolved.candidate,
      runtimeEpochBaseline(resolved),
      now,
      () => randomId("rt")
    );
    if (await svc.repository.claimRuntimeAssignment(assigned.runtime)) {
      await svc.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId });
      return {
        action: "restart",
        runtime: toRuntimeStatus(worldId, assigned.runtime, resolved.candidate, resolved.warning),
        assignment: null,
        waiterSessionId: null
      };
    }
    // Lost the promotion race; restart so the client re-enters against the
    // winner's runtime instead of acting on a stale assignment.
    return {
      action: "restart",
      runtime,
      assignment: null,
      waiterSessionId: null
    };
  }
  if (resolved.runtime == null && resolved.candidate == null) {
    return {
      action: "restart",
      runtime,
      assignment: null,
      waiterSessionId: null
    };
  }
  return {
    action: "wait",
    runtime,
    assignment: null,
    waiterSessionId
  };
}

export async function runtimeStatus(svc: ServiceContext, ctx: RequestContext, worldId: string, now: Date): Promise<WorldRuntimeStatus> {
  await requireSessionAccess(svc, ctx, worldId, { allowRevokedHost: true });
  const resolved = await resolveRuntimeState(svc, worldId, now);
  return toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning);
}

export async function refreshWaiting(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: RefreshWaitingRequest,
  now: Date
): Promise<WorldRuntimeStatus> {
  await requireSessionAccess(svc, ctx, worldId);
  await svc.repository.refreshWaiterSession(worldId, ctx, request, now);
  const resolved = await resolveRuntimeState(svc, worldId, now);
  return toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning);
}

export async function cancelWaiting(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: CancelWaitingRequest,
  now: Date
): Promise<WorldRuntimeStatus> {
  await requireSessionAccess(svc, ctx, worldId);
  await svc.repository.cancelWaiterSession(worldId, ctx, request);
  const resolved = await resolveRuntimeState(svc, worldId, now);
  return toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning);
}

/**
 * Responsibility:
 * Refresh the currently authorized host runtime while preserving epoch/token authority.
 *
 * Postconditions:
 * The authoritative runtime deadline is extended, and host-starting may become host-live.
 * During host-finalizing the heartbeat answers with current state without refreshing the lease.
 *
 * Stale-work rule:
 * Old epochs/tokens are rejected.
 */
export async function heartbeatHost(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: HeartbeatRequest,
  now: Date
): Promise<HostHeartbeatResponse> {
  await requireSessionAccess(svc, ctx, worldId);
  if (request.runtimeEpoch == null || request.runtimeEpoch < 0 || request.hostToken == null) {
    throw hostNotActiveError();
  }
  const resolved = await resolveRuntimeState(svc, worldId, now);
  const runtime = resolved.runtime;
  if (runtime == null || !matchesHostAuthorization(runtime, ctx.playerUuid, request.runtimeEpoch, request.hostToken)) {
    throw hostNotActiveError();
  }
  if (runtime.phase === "host-finalizing") {
    return withHeartbeatMemberships(svc, worldId, toRuntimeStatus(worldId, runtime, resolved.candidate));
  }
  if (runtime.phase !== "host-starting" && runtime.phase !== "host-live") {
    throw hostNotActiveError();
  }
  const refreshed = refreshLiveRuntime(runtime, request.joinTarget ?? null, now);
  const updated = request.minecraftVersion != null && request.minecraftVersion.trim().length > 0
    ? { ...refreshed, hostMinecraftVersion: request.minecraftVersion.trim() }
    : refreshed;
  if (!await svc.repository.updateAuthorizedRuntime(updated)) {
    throw hostNotActiveError();
  }
  return withHeartbeatMemberships(svc, worldId, toRuntimeStatus(worldId, updated, runtimeCandidateFromRuntime(updated)));
}

/**
 * The heartbeat response is a FLAT superset of WorldRuntimeStatus: the host uses
 * the membership list to keep in-game command permissions current, while older
 * clients bind the same body to WorldRuntimeStatus and ignore the extra field.
 */
async function withHeartbeatMemberships(
  svc: ServiceContext,
  worldId: string,
  status: WorldRuntimeStatus
): Promise<HostHeartbeatResponse> {
  const memberships: HostHeartbeatMembership[] = (await svc.repository.listMemberships(worldId)).map((member) => ({
    playerUuid: member.playerUuid,
    playerName: member.playerName,
    canUseCommands: member.canUseCommands
  }));
  return { ...status, memberships };
}

/**
 * Responsibility:
 * Publish host-controlled startup/finalization progress for the current authoritative runtime.
 */
export async function setHostStartupProgress(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: HostStartupProgressRequest,
  now: Date
): Promise<WorldRuntimeStatus | null> {
  await requireSessionAccess(svc, ctx, worldId, { allowRevokedHost: true });
  if (request.runtimeEpoch == null || request.runtimeEpoch < 0 || request.hostToken == null) {
    throw hostNotActiveError();
  }
  const authorized: AuthorizedRuntime = await requireAuthorizedRuntime(
    svc,
    ctx,
    worldId,
    now,
    request.runtimeEpoch,
    request.hostToken,
    ["host-starting", "host-finalizing"]
  );
  const progress = request.label != null && request.mode != null
    ? {
        label: request.label,
        mode: request.mode,
        fraction: clampFraction(request.fraction ?? null),
        updatedAt: now.toISOString()
      }
    : null;
  const updated = setHostProgress(authorized.runtime, progress, now);
  if (!await svc.repository.updateAuthorizedRuntime(updated)) {
    throw hostNotActiveError();
  }
  return toRuntimeStatus(worldId, updated, runtimeCandidateFromRuntime(updated));
}

export async function setPlayerPresence(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: PresenceHeartbeatRequest,
  now: Date
) {
  await requireSessionAccess(svc, ctx, worldId);
  await svc.repository.setPlayerPresence(worldId, ctx, request, now);
  return {
    worldId,
    present: request.present,
    updatedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + PLAYER_PRESENCE_TIMEOUT_MS).toISOString()
  };
}

/**
 * Responsibility:
 * Freeze the authoritative host runtime into host-finalizing before the final snapshot upload.
 *
 * A retried begin from the runtime that already moved to host-finalizing is a
 * success replay, not a lost lease: shutdown-time requests are exactly the ones
 * clients retry after network flaps.
 */
export async function beginFinalization(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: BeginFinalizationRequest,
  now: Date
): Promise<FinalizationActionResult> {
  await requireSessionAccess(svc, ctx, worldId, { allowRevokedHost: true });
  const authorized = await requireAuthorizedRuntime(
    svc,
    ctx,
    worldId,
    now,
    request.runtimeEpoch,
    request.hostToken,
    ["host-starting", "host-live", "host-finalizing"]
  );
  if (authorized.runtime.phase === "host-finalizing") {
    return runtimeToFinalizationResult(worldId, authorized.runtime, null);
  }
  const updated = moveToFinalizing(authorized.runtime, now);
  if (!await svc.repository.updateAuthorizedRuntime(updated)) {
    throw hostNotActiveError();
  }
  return runtimeToFinalizationResult(worldId, updated, null);
}

/**
 * Responsibility:
 * Complete a host-finalizing runtime by handing off to the next candidate or returning to idle.
 *
 * Stale-work rule:
 * A completion request from an older epoch/token is rejected even if the caller was host before.
 */
export async function completeFinalization(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: CompleteFinalizationRequest,
  now: Date
): Promise<FinalizationActionResult> {
  await requireSessionAccess(svc, ctx, worldId, { allowRevokedHost: true });
  const resolved = await resolveRuntimeState(svc, worldId, now);
  const runtime = resolved.runtime;
  if (runtime == null || runtime.phase !== "host-finalizing") {
    if (await isReleasedEpochReplay(svc, worldId, request.runtimeEpoch, resolved.warning)) {
      return runtimeToFinalizationResult(worldId, null, resolved.candidate);
    }
    throw new HttpError(409, "not_finalizing", "SharedWorld is not currently finalizing.");
  }
  if (!matchesHostAuthorization(runtime, ctx.playerUuid, request.runtimeEpoch, request.hostToken)) {
    throw hostNotActiveError();
  }
  const deleted = await svc.repository.deleteRuntimeRecord(worldId, {
    runtimeEpoch: runtime.runtimeEpoch,
    runtimeToken: runtime.runtimeToken
  });
  if (!deleted) {
    if (await isReleasedEpochReplay(svc, worldId, request.runtimeEpoch, resolved.warning)) {
      return runtimeToFinalizationResult(worldId, null, resolved.candidate);
    }
    throw hostNotActiveError();
  }
  await svc.repository.clearWorldPresence(worldId);
  await svc.repository.clearUncleanShutdownWarning(worldId);
  return runtimeToFinalizationResult(worldId, null, resolved.candidate);
}

/**
 * Owner-only escape hatch for a stranded previous-host finalization: drops the
 * frozen runtime so the world can be hosted again, invalidating stale uploads.
 */
export async function abandonFinalization(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  _request: AbandonFinalizationRequest,
  now: Date
): Promise<FinalizationActionResult> {
  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  requireOwner(world, ctx, "discard stranded finalization state");
  const resolved = await resolveRuntimeState(svc, worldId, now);
  const current = resolved.runtime;
  if (current == null || current.phase !== "host-finalizing") {
    return runtimeToFinalizationResult(worldId, current, resolved.candidate);
  }
  const deleted = await svc.repository.deleteRuntimeRecord(worldId, {
    runtimeEpoch: current.runtimeEpoch,
    runtimeToken: current.runtimeToken
  });
  if (deleted) {
    await svc.repository.clearWorldPresence(worldId);
  }
  return runtimeToFinalizationResult(worldId, null, resolved.candidate);
}

export async function releaseHost(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: ReleaseHostRequest,
  now: Date
) {
  await requireSessionAccess(svc, ctx, worldId, { allowRevokedHost: true });
  const resolved = await resolveRuntimeState(svc, worldId, now);
  const runtime = resolved.runtime;
  const authorized = runtime != null
    && (runtime.phase === "host-starting" || runtime.phase === "host-live" || runtime.phase === "host-finalizing")
    && matchesHostAuthorization(runtime, ctx.playerUuid, request.runtimeEpoch, request.hostToken);
  if (!authorized) {
    // A retried release of an already-released epoch is a success replay, not a
    // lost lease; a genuinely expired lease left an unclean warning and still 409s.
    if (await isReleasedEpochReplay(svc, worldId, request.runtimeEpoch, resolved.warning)) {
      return releaseHostResult(worldId, request, resolved, now);
    }
    throw hostNotActiveError();
  }
  await svc.repository.clearWaitersForPlayer(worldId, ctx.playerUuid);
  const deleted = await svc.repository.deleteRuntimeRecord(worldId, {
    runtimeEpoch: runtime.runtimeEpoch,
    runtimeToken: runtime.runtimeToken
  });
  if (deleted) {
    await svc.repository.clearWorldPresence(worldId);
    if (request.graceful) {
      await svc.repository.clearUncleanShutdownWarning(worldId);
    }
  }
  const resolvedStatus = await resolveRuntimeState(svc, worldId, now);
  return releaseHostResult(worldId, request, resolvedStatus, now);
}

function releaseHostResult(
  worldId: string,
  request: ReleaseHostRequest,
  resolved: ResolvedRuntimeState,
  now: Date
) {
  const status = toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning);
  return {
    worldId,
    releasedAt: now.toISOString(),
    graceful: request.graceful,
    // Ungraceful release relinquishes authority immediately, but it does not erase other
    // waiters. They may still be elected by the normal backend-owned waiting flow.
    nextHostUuid: request.graceful ? status.candidateUuid : null,
    nextHostPlayerName: request.graceful ? status.candidatePlayerName : null
  };
}

/**
 * A mutation retried after its original succeeded finds the runtime gone but the
 * world's epoch high-water mark equal to its own epoch. An unclean-shutdown
 * warning for that same epoch means the lease actually expired, which is a real
 * authority loss, never a replay.
 */
async function isReleasedEpochReplay(
  svc: ServiceContext,
  worldId: string,
  runtimeEpoch: number | null | undefined,
  warning: UncleanShutdownWarning | null
): Promise<boolean> {
  if (runtimeEpoch == null || runtimeEpoch < 1) {
    return false;
  }
  if (warning != null && warning.runtimeEpoch === runtimeEpoch) {
    return false;
  }
  return (await svc.repository.getLastRuntimeEpoch(worldId)) === runtimeEpoch;
}

function runtimeToFinalizationResult(
  worldId: string,
  runtime: WorldRuntimeRecord | null,
  candidate: RuntimeCandidate | null
): FinalizationActionResult {
  const status = toRuntimeStatus(worldId, runtime, candidate);
  return {
    worldId,
    nextHostUuid: status.candidateUuid,
    nextHostPlayerName: status.candidatePlayerName,
    status: runtime != null
      ? runtimePhaseToWorldStatus(runtime.phase)
      : candidate != null
      ? "handoff"
      : "idle"
  };
}

function runtimeCandidateFromRuntime(runtime: WorldRuntimeRecord): RuntimeCandidate | null {
  if (runtime.candidateUuid == null || runtime.hostUuid == null || runtime.hostPlayerName == null) {
    return null;
  }
  if (runtime.candidateUuid !== runtime.hostUuid) {
    return null;
  }
  return {
    playerUuid: runtime.candidateUuid,
    playerName: runtime.hostPlayerName
  };
}

function sanitizeWaiterSessionId(waiterSessionId: string | null | undefined): string | null {
  if (waiterSessionId == null) {
    return null;
  }
  const trimmed = waiterSessionId.trim();
  return trimmed.length === 0 ? null : trimmed;
}

function clampFraction(value: number | null): number | null {
  if (value == null || !Number.isFinite(value)) {
    return null;
  }
  return Math.max(0, Math.min(1, value));
}

export type { ResolvedRuntimeState };
