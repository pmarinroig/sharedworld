import type { HostAssignment } from "../../../shared/src/index.ts";

import { randomId } from "../ids.ts";
import type { RequestContext } from "../repository.ts";
import { assignHostStarting, type WorldRuntimeRecord } from "../runtime-protocol.ts";
import {
  hostAssignmentForCurrentRuntime,
  runtimeAllowsDirectConnect,
  type ResolvedRuntimeState
} from "../runtime-service-support.ts";
import type { ServiceContext } from "./context.ts";
import { resolveRuntimeState, runtimeEpochBaseline } from "./runtime-access.ts";

/**
 * The session-entry decision helpers shared by enterSession and observeWaiting.
 * Both flows reduce a resolved runtime state to one authoritative outcome;
 * these helpers own the decision logic while the two orchestrators keep their
 * own waiter-session registration/cancellation sequences (which genuinely
 * differ between first entry and an ongoing waiting poll).
 */

/** A successful host claim: the freshly installed runtime plus its assignment. */
export interface ClaimedHost {
  runtime: WorldRuntimeRecord;
  assignment: HostAssignment;
}

/**
 * The two outcomes that pre-empt any waiting flow: the runtime is directly
 * joinable, or the caller already owns the current host-starting assignment.
 */
export function immediateEntryKind(resolved: ResolvedRuntimeState, playerUuid: string): { kind: "connect" } | { kind: "current-host"; assignment: HostAssignment } | null {
  if (runtimeAllowsDirectConnect(resolved)) {
    return { kind: "connect" };
  }
  const assignment = hostAssignmentForCurrentRuntime(resolved, playerUuid);
  if (assignment != null) {
    return { kind: "current-host", assignment };
  }
  return null;
}

/**
 * Claim a brand-new host-starting runtime for the caller against an idle,
 * candidate-less world. Null means the acquire race was lost and the caller
 * must fall through to the waiting flow.
 */
export async function tryClaimFreshHost(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  resolved: ResolvedRuntimeState,
  now: Date
): Promise<ClaimedHost | null> {
  const assigned = assignHostStarting(
    worldId,
    { playerUuid: ctx.playerUuid, playerName: ctx.playerName },
    runtimeEpochBaseline(resolved),
    now,
    () => randomId("rt")
  );
  if (await svc.repository.claimRuntimeAssignment(assigned.runtime)) {
    return assigned;
  }
  return null;
}

/**
 * Promote the caller when it is the preferred waiting candidate for a world
 * with no runtime. Null means someone else is preferred, a runtime exists, or
 * the promotion race was lost — the caller keeps waiting (or restarts).
 */
export async function tryPromotePreferredCandidate(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  resolved: ResolvedRuntimeState,
  now: Date
): Promise<ClaimedHost | null> {
  if (resolved.runtime != null || resolved.candidate?.playerUuid !== ctx.playerUuid) {
    return null;
  }
  const assigned = assignHostStarting(
    worldId,
    resolved.candidate,
    runtimeEpochBaseline(resolved),
    now,
    () => randomId("rt")
  );
  if (await svc.repository.claimRuntimeAssignment(assigned.runtime)) {
    return assigned;
  }
  return null;
}

/**
 * Register (or refresh) the caller's waiter session and re-resolve against the
 * post-registration state. A requested id refreshes and reports whether it is
 * still active; a fresh entry upserts a new id, which is always active.
 */
export async function registerWaiterAndResolve(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  requestedWaiterSessionId: string | null,
  now: Date
): Promise<{ waiterSessionId: string; waiterSessionActive: boolean; resolved: ResolvedRuntimeState }> {
  const waiterSessionId = requestedWaiterSessionId ?? randomId("wait");
  let waiterSessionActive: boolean;
  if (requestedWaiterSessionId != null) {
    waiterSessionActive = await svc.repository.refreshWaiterSession(worldId, ctx, { waiterSessionId }, now);
  } else {
    await svc.repository.upsertWaiterSession(worldId, ctx, waiterSessionId, now);
    waiterSessionActive = true;
  }
  return {
    waiterSessionId,
    waiterSessionActive,
    resolved: await resolveRuntimeState(svc, worldId, now)
  };
}
