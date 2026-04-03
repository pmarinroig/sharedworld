import type {
  HostAssignment,
  WorldDetails,
  WorldRuntimeStatus
} from "../../shared/src/index.ts";

import type { WorldUncleanShutdownWarningRecord } from "./repository.ts";
import type { RuntimeCandidate, WorldRuntimeRecord } from "./runtime-protocol.ts";
import { candidateToWaitingRuntime } from "./runtime-protocol.ts";

export interface ResolvedRuntimeState {
  runtime: WorldRuntimeRecord | null;
  candidate: RuntimeCandidate | null;
  warning: WorldUncleanShutdownWarningRecord | null;
  retiredRuntimeEpoch: number | null;
}

export interface AuthorizedRuntime {
  runtime: WorldRuntimeRecord;
}

export interface EnterSessionResult {
  action: "connect" | "wait" | "host";
  world: WorldDetails;
  latestManifest: unknown;
  runtime: WorldRuntimeStatus;
  assignment: HostAssignment | null;
}

/**
 * Service-side session resolution helpers.
 *
 * Responsibility:
 * Centralize the protocol decisions that turn a resolved runtime state into
 * connect / wait / host behavior.
 *
 * Preconditions:
 * The caller already resolved the current runtime and preferred waiter candidate.
 *
 * Postconditions:
 * Decisions are based only on authoritative runtime state plus the current candidate.
 *
 * Stale-work rule:
 * Helpers never infer authority from local client state. They only interpret the
 * backend runtime record that was already resolved for this request.
 *
 * Authority source:
 * The resolved runtime record is authoritative.
 */
export function runtimeAllowsDirectConnect(resolved: ResolvedRuntimeState): boolean {
  return resolved.runtime?.phase === "host-live"
    && resolved.runtime.revokedAt == null
    && !!resolved.runtime.joinTarget;
}

export function runtimeRequiresWaiting(resolved: ResolvedRuntimeState): boolean {
  return resolved.runtime?.phase === "host-finalizing";
}

export function hostAssignmentForCurrentRuntime(
  resolved: ResolvedRuntimeState,
  playerUuid: string
): HostAssignment | null {
  if (resolved.runtime?.phase !== "host-starting" || resolved.runtime.hostUuid !== playerUuid) {
    return null;
  }
  return {
    worldId: resolved.runtime.worldId,
    playerUuid,
    playerName: resolved.runtime.hostPlayerName ?? "",
    runtimeEpoch: resolved.runtime.runtimeEpoch,
    hostToken: resolved.runtime.runtimeToken ?? "",
    startupDeadlineAt: resolved.runtime.startupDeadlineAt
  };
}

export function shouldAssignCurrentCandidate(resolved: ResolvedRuntimeState, playerUuid: string): boolean {
  return resolved.runtime?.phase === "handoff-waiting" && resolved.candidate?.playerUuid === playerUuid;
}

export function initialWaitingRuntime(
  worldId: string,
  candidate: RuntimeCandidate | null,
  now: Date
): WorldRuntimeRecord | null {
  if (!candidate) {
    return null;
  }
  return candidateToWaitingRuntime({
    worldId,
    phase: "handoff-waiting",
    runtimeEpoch: 0,
    runtimeToken: null,
    hostUuid: null,
    hostPlayerName: null,
    candidateUuid: candidate.playerUuid,
    joinTarget: null,
    claimedAt: null,
    expiresAt: null,
    startupDeadlineAt: null,
    runtimeTokenIssuedAt: null,
    lastProgressAt: null,
    updatedAt: now.toISOString(),
    revokedAt: null,
    startupProgress: null
  }, candidate, now);
}
