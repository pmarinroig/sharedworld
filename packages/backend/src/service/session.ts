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
  type PresenceHeartbeatResponse,
  type ReleaseHostRequest,
  type WorldRuntimeStatus
} from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import type { RequestContext, UncleanShutdownWarning } from "../repository.ts";
import {
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
  runtimeRequiresWaiting,
  type AuthorizedRuntime,
  type ResolvedRuntimeState
} from "../runtime-service-support.ts";
import type { ServiceContext } from "./context.ts";
import { parsePositiveInt } from "./sync-plan.ts";
import {
  hostNotActiveError,
  requireAuthorizedRuntime,
  requireOwner,
  requireSessionAccess,
  requireWorldDetails,
  resolveRuntimeState
} from "./runtime-access.ts";
import {
  immediateEntryKind,
  registerWaiterAndResolve,
  tryClaimFreshHost,
  tryPromotePreferredCandidate
} from "./session-entry.ts";
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
  const respond = (
    action: EnterSessionResponse["action"],
    resolvedOrRuntime: ResolvedRuntimeState | { runtime: WorldRuntimeRecord; resolved: ResolvedRuntimeState },
    assignment: EnterSessionResponse["assignment"] = null,
    waiterSessionId: string | null = null
  ): EnterSessionResponse => {
    const resolved = "resolved" in resolvedOrRuntime ? resolvedOrRuntime.resolved : resolvedOrRuntime;
    const runtime = "runtime" in resolvedOrRuntime ? resolvedOrRuntime.runtime : resolved.runtime;
    return {
      action,
      world,
      latestManifest,
      runtime: toRuntimeStatus(worldId, runtime, resolved.candidate, resolved.warning),
      assignment,
      waiterSessionId
    };
  };
  const cancelRequestedWaiter = async () => {
    if (requestedWaiterSessionId != null) {
      await svc.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId: requestedWaiterSessionId });
    }
  };

  const resolved = await resolveRuntimeState(svc, worldId, now);
  const immediate = immediateEntryKind(resolved, ctx.playerUuid);
  if (immediate != null) {
    await cancelRequestedWaiter();
    return immediate.kind === "connect"
      ? respond("connect", resolved)
      : respond("host", resolved, immediate.assignment);
  }
  if (resolved.runtime == null && resolved.candidate == null) {
    if (resolved.warning != null && !request.acknowledgeUncleanShutdown) {
      return respond("warn-host", resolved);
    }
    const claimed = await tryClaimFreshHost(svc, ctx, worldId, resolved, now);
    if (claimed != null) {
      await cancelRequestedWaiter();
      return respond("host", { runtime: claimed.runtime, resolved }, claimed.assignment);
    }
    // Lost the acquire race to a concurrent claimant; fall through to the
    // waiting flow, which re-resolves against the winner's runtime.
  }
  const waiting = await registerWaiterAndResolve(svc, ctx, worldId, requestedWaiterSessionId, now);
  const reportedWaiterSessionId = waiting.waiterSessionActive ? waiting.waiterSessionId : null;
  if (runtimeRequiresWaiting(waiting.resolved)) {
    return respond("wait", waiting.resolved, null, reportedWaiterSessionId);
  }
  if (waiting.waiterSessionActive) {
    const promoted = await tryPromotePreferredCandidate(svc, ctx, worldId, waiting.resolved, now);
    if (promoted != null) {
      await svc.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId: waiting.waiterSessionId });
      return respond("host", { runtime: promoted.runtime, resolved: waiting.resolved }, promoted.assignment);
    }
    // Lost the promotion race; keep waiting against the winner's runtime.
  }
  return respond("wait", waiting.resolved, null, reportedWaiterSessionId);
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
  const respond = (
    action: ObserveWaitingResponse["action"],
    runtime: WorldRuntimeRecord | null = resolved.runtime,
    reportedWaiterSessionId: string | null = null
  ): ObserveWaitingResponse => ({
    action,
    runtime: toRuntimeStatus(worldId, runtime, resolved.candidate, resolved.warning),
    assignment: null,
    waiterSessionId: reportedWaiterSessionId
  });
  const cancelWaiter = async () => {
    if (waiterSessionActive) {
      await svc.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId });
    }
  };

  const immediate = immediateEntryKind(resolved, ctx.playerUuid);
  if (immediate != null) {
    await cancelWaiter();
    return respond(immediate.kind === "connect" ? "connect" : "restart");
  }
  if (!waiterSessionActive) {
    return respond("restart");
  }
  const promoted = await tryPromotePreferredCandidate(svc, ctx, worldId, resolved, now);
  if (promoted != null) {
    await svc.repository.cancelWaiterSession(worldId, ctx, { waiterSessionId });
    return respond("restart", promoted.runtime);
  }
  if (resolved.runtime == null) {
    // Either someone else is the preferred candidate (keep waiting) or the
    // promotion race was lost / nobody is waiting (restart so the client
    // re-enters against the authoritative state).
    return resolved.candidate != null && resolved.candidate.playerUuid !== ctx.playerUuid
      ? respond("wait", null, waiterSessionId)
      : respond("restart");
  }
  return respond("wait", resolved.runtime, waiterSessionId);
}

export async function runtimeStatus(svc: ServiceContext, ctx: RequestContext, worldId: string, now: Date): Promise<WorldRuntimeStatus> {
  await requireSessionAccess(svc, ctx, worldId, { allowRevokedHost: true });
  const resolved = await resolveRuntimeState(svc, worldId, now);
  const status = toRuntimeStatus(worldId, resolved.runtime, resolved.candidate, resolved.warning);
  // Remote throttle lever: only the top-level GET /runtime response carries
  // the suggestion — that is the poll the guest watcher drives.
  const suggestedPollIntervalMs = parsePositiveInt(svc.env.SUGGESTED_RUNTIME_POLL_INTERVAL_MS, 0);
  return suggestedPollIntervalMs > 0 ? { ...status, suggestedPollIntervalMs } : status;
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
 * the membership list to keep in-game command permissions current and the world
 * settings to keep its running server configured, while older clients bind the
 * same body to WorldRuntimeStatus and ignore the extra fields.
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
  const worldSettings = await svc.repository.getWorldSettings(worldId);
  const suggestedHeartbeatIntervalMs = parsePositiveInt(svc.env.SUGGESTED_HOST_HEARTBEAT_INTERVAL_MS, 0);
  const suggestedAutosaveIntervalMs = parsePositiveInt(svc.env.SUGGESTED_AUTOSAVE_INTERVAL_MS, 0);
  return {
    ...status,
    memberships,
    settings: worldSettings?.settings ?? null,
    settingsRevision: worldSettings?.settingsRevision ?? 0,
    ...(suggestedHeartbeatIntervalMs > 0 ? { suggestedHeartbeatIntervalMs } : {}),
    ...(suggestedAutosaveIntervalMs > 0 ? { suggestedAutosaveIntervalMs } : {})
  };
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
): Promise<PresenceHeartbeatResponse> {
  await requireSessionAccess(svc, ctx, worldId);
  await svc.repository.setPlayerPresence(worldId, ctx, request, now);
  const suggestedIntervalMs = parsePositiveInt(svc.env.SUGGESTED_PRESENCE_INTERVAL_MS, 0);
  return {
    worldId,
    present: request.present,
    updatedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + PLAYER_PRESENCE_TIMEOUT_MS).toISOString(),
    ...(suggestedIntervalMs > 0 ? { suggestedIntervalMs } : {})
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
