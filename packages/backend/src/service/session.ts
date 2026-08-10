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
  type GuestHeartbeatResponse,
  type ReleaseHostRequest,
  type WorldRuntimeStatus
} from "../../../shared/src/index.ts";

import { clientVersionAtLeast } from "../http.ts";
import type { RequestContext } from "../repository.ts";
import type { ServiceContext } from "./context.ts";
import { parsePositiveInt } from "./sync-plan.ts";
import { requireOwner, requireWorldDetails, sessionActorOf } from "./runtime-access.ts";
import { getWorld } from "./worlds.ts";

/**
 * 0.3.0: every runtime decision lives in the world's coordinator (single
 * threaded, Durable Object). This module keeps only what belongs to the
 * Worker: membership facts from D1, response composition, and the legacy
 * pacing levers. The polling routes remain as the legacy/fallback read path;
 * connected 0.3.0 clients hear about changes as pushed events instead.
 */

export async function enterSession(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: EnterSessionRequest,
  now: Date
): Promise<EnterSessionResponse> {
  const actor = await sessionActorOf(svc, ctx, worldId);
  // Access verdicts come first (a kicked member gets 403 membership_revoked,
  // not the world lookup's 404) — but WITHOUT touching the coordinator yet:
  // every read must succeed before the coordinator mutates any state, or a
  // failing world read would strand freshly registered waiters and claims.
  if (!actor.membershipActive) {
    await svc.realtime.coordinator(worldId).assertSessionAccess(actor);
  }
  const world = await getWorld(svc, ctx, worldId, now);
  // 0.3.2+ clients decide "does this world have a snapshot" from
  // world.lastSnapshotId and never read the manifest body, so loading and
  // serializing it (thousands of file entries on large worlds) is pure CPU
  // burn for them. Older clients null-check the field and keep the full
  // manifest.
  const latestManifest = clientVersionAtLeast(ctx.clientVersion, 0, 3, 2)
    ? null
    : await svc.repository.getLatestSnapshot(worldId);
  const decision = await svc.realtime.coordinator(worldId).enterSession(actor, request, now);
  return {
    action: decision.action,
    world,
    latestManifest,
    runtime: decision.runtime,
    assignment: decision.assignment,
    waiterSessionId: decision.waiterSessionId
  };
}

export async function observeWaiting(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: ObserveWaitingRequest,
  now: Date
): Promise<ObserveWaitingResponse> {
  const actor = await sessionActorOf(svc, ctx, worldId);
  const observation = await svc.realtime.coordinator(worldId).observeWaiting(actor, request.waiterSessionId, now);
  return {
    action: observation.action,
    runtime: observation.runtime,
    assignment: null,
    waiterSessionId: observation.waiterSessionId
  };
}

export async function runtimeStatus(svc: ServiceContext, ctx: RequestContext, worldId: string, now: Date): Promise<WorldRuntimeStatus> {
  const actor = await sessionActorOf(svc, ctx, worldId);
  const status = await svc.realtime.coordinator(worldId).runtimeStatus(actor, now);
  // Remote throttle lever for legacy/fallback polling: only the top-level
  // GET /runtime response carries the suggestion.
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
  const actor = await sessionActorOf(svc, ctx, worldId);
  return svc.realtime.coordinator(worldId).cancelWaiting(actor, request.waiterSessionId, now);
}

export async function heartbeatHost(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: HeartbeatRequest,
  now: Date
): Promise<HostHeartbeatResponse> {
  const actor = await sessionActorOf(svc, ctx, worldId);
  const status = await svc.realtime.coordinator(worldId).heartbeat(
    actor,
    {
      runtimeEpoch: request.runtimeEpoch ?? null,
      hostToken: request.hostToken ?? null,
      joinTarget: request.joinTarget ?? null,
      minecraftVersion: request.minecraftVersion ?? null
    },
    now
  );
  return withHeartbeatMemberships(svc, worldId, status);
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

export async function setHostStartupProgress(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: HostStartupProgressRequest,
  now: Date
): Promise<WorldRuntimeStatus | null> {
  const actor = await sessionActorOf(svc, ctx, worldId);
  return svc.realtime.coordinator(worldId).setStartupProgress(actor, request, now);
}

/**
 * The guest beat. Historically a bare presence self-report; since the
 * efficiency release the response is a FLAT superset (GuestHeartbeatResponse)
 * carrying the resolved runtime status and the latest snapshot id, so a
 * 0.4.1+ guest replaces its runtime poll and snapshot-id poll with this one
 * call. Older clients bind the same body to PresenceHeartbeatResponse and
 * ignore the extras.
 */
export async function setPlayerPresence(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: PresenceHeartbeatRequest,
  now: Date
): Promise<GuestHeartbeatResponse> {
  const actor = await sessionActorOf(svc, ctx, worldId);
  const status = await svc.realtime.coordinator(worldId).guestHeartbeat(
    actor,
    { present: request.present, guestSessionEpoch: request.guestSessionEpoch, presenceSequence: request.presenceSequence },
    now
  );
  const latest = await svc.repository.getLatestSnapshotStamp(worldId);
  const suggestedIntervalMs = parsePositiveInt(svc.env.SUGGESTED_PRESENCE_INTERVAL_MS, 0);
  // Runtime updatedAt is dropped: the presence ack owns that field name in
  // this body, and it churns per request anyway.
  const { updatedAt: _runtimeUpdatedAt, suggestedPollIntervalMs: _unused, ...runtimeFields } = status;
  void _runtimeUpdatedAt;
  void _unused;
  return {
    ...runtimeFields,
    worldId,
    present: request.present,
    updatedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + PLAYER_PRESENCE_TIMEOUT_MS).toISOString(),
    lastSnapshotId: latest?.id ?? null,
    ...(suggestedIntervalMs > 0 ? { suggestedIntervalMs } : {})
  };
}

export async function beginFinalization(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: BeginFinalizationRequest,
  now: Date
): Promise<FinalizationActionResult> {
  const actor = await sessionActorOf(svc, ctx, worldId);
  return svc.realtime.coordinator(worldId).beginFinalization(
    actor,
    { runtimeEpoch: request.runtimeEpoch ?? null, hostToken: request.hostToken ?? null },
    now
  );
}

export async function completeFinalization(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: CompleteFinalizationRequest,
  now: Date
): Promise<FinalizationActionResult> {
  const actor = await sessionActorOf(svc, ctx, worldId);
  return svc.realtime.coordinator(worldId).completeFinalization(
    actor,
    { runtimeEpoch: request.runtimeEpoch ?? null, hostToken: request.hostToken ?? null },
    now
  );
}

/**
 * Owner-only escape hatch for a stranded previous-host finalization. The
 * ownership check stays in the Worker: D1 owns world ownership.
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
  return svc.realtime.coordinator(worldId).abandonFinalization(now);
}

export async function releaseHost(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  request: ReleaseHostRequest,
  now: Date
) {
  const actor = await sessionActorOf(svc, ctx, worldId);
  return svc.realtime.coordinator(worldId).releaseHost(
    actor,
    {
      runtimeEpoch: request.runtimeEpoch ?? null,
      hostToken: request.hostToken ?? null,
      graceful: request.graceful
    },
    now
  );
}


