import {
  HOST_LEASE_TIMEOUT_MS,
  HANDOFF_WAITER_TIMEOUT_MS,
  type HostAssignment,
  type HostStartupProgress,
  type UncleanShutdownWarning,
  type WorldRuntimePhase,
  type WorldRuntimeStatus
} from "../../shared/src/index.ts";

export interface RuntimeCandidate {
  playerUuid: string;
  playerName: string;
}

export interface RuntimeWaiter extends RuntimeCandidate {
  waiterSessionId: string;
  waiting: boolean;
  updatedAt: string;
}

export interface WorldRuntimeRecord {
  worldId: string;
  phase: WorldRuntimePhase;
  runtimeEpoch: number;
  runtimeToken: string | null;
  hostUuid: string | null;
  hostPlayerName: string | null;
  candidateUuid: string | null;
  joinTarget: string | null;
  claimedAt: string | null;
  expiresAt: string | null;
  startupDeadlineAt: string | null;
  runtimeTokenIssuedAt: string | null;
  lastProgressAt: string | null;
  updatedAt: string;
  revokedAt: string | null;
  startupProgress: HostStartupProgress | null;
}

export function pruneWaiters<T extends RuntimeWaiter>(waiters: T[], now: Date): T[] {
  const cutoff = now.getTime() - HANDOFF_WAITER_TIMEOUT_MS;
  return waiters.filter((waiter) => waiter.waiting && new Date(waiter.updatedAt).getTime() >= cutoff);
}

export function choosePreferredCandidate(waiters: RuntimeCandidate[], memberships: RuntimeMembership[]): RuntimeCandidate | null {
  const membershipByPlayer = new Map(memberships.map((membership) => [membership.playerUuid, membership]));
  const candidates = waiters
    .map((waiter) => {
      const membership = membershipByPlayer.get(waiter.playerUuid);
      if (!membership || membership.deletedAt != null) {
        return null;
      }
      return {
        playerUuid: waiter.playerUuid,
        playerName: membership.playerName,
        role: membership.role,
        joinedAt: membership.joinedAt
      };
    })
    .filter((entry): entry is NonNullable<typeof entry> => entry != null);

  if (candidates.length === 0) {
    return null;
  }

  candidates.sort((left, right) => {
    const ownerScore = Number(left.role === "owner") - Number(right.role === "owner");
    if (ownerScore !== 0) {
      return -ownerScore;
    }
    if (left.joinedAt !== right.joinedAt) {
      return left.joinedAt.localeCompare(right.joinedAt);
    }
    return left.playerUuid.localeCompare(right.playerUuid);
  });

  return {
    playerUuid: candidates[0].playerUuid,
    playerName: candidates[0].playerName
  };
}

export interface RuntimeMembership {
  playerUuid: string;
  playerName: string;
  role: "owner" | "member";
  joinedAt: string;
  deletedAt: string | null;
}

/**
 * Responsibility:
 * Apply deadline expiry to the currently resolved runtime before any caller acts on it.
 *
 * Preconditions:
 * The caller already chose the next preferred waiter candidate for this world.
 *
 * Postconditions:
 * Expired host-starting/host-live runtimes become handoff-waiting or idle; host-finalizing stays put.
 *
 * Stale-work rule:
 * Timeout resolution always happens before session-entry or authority checks use the runtime.
 *
 * Authority source:
 * The backend runtime record and its phase-specific deadline.
 */
export function resolveRuntimeTimeout(
  runtime: WorldRuntimeRecord | null,
  nextCandidate: RuntimeCandidate | null,
  now: Date
): WorldRuntimeRecord | null {
  if (!runtime) {
    return null;
  }

  const deadline = phaseDeadline(runtime);
  if (deadline == null || deadline.getTime() > now.getTime()) {
    return runtime;
  }

  return null;
}

export function timedOutUncleanShutdownWarning(runtime: WorldRuntimeRecord | null, now: Date): UncleanShutdownWarning | null {
  if (runtime == null) {
    return null;
  }
  if (runtime.phase !== "host-live" && runtime.phase !== "host-finalizing") {
    return null;
  }
  const deadline = phaseDeadline(runtime);
  if (deadline == null || deadline.getTime() > now.getTime()) {
    return null;
  }
  if (runtime.hostUuid == null || runtime.hostPlayerName == null) {
    return null;
  }
  return {
    hostUuid: runtime.hostUuid,
    hostPlayerName: runtime.hostPlayerName,
    phase: runtime.phase,
    recordedAt: now.toISOString()
  };
}

export function ensureCandidate(
  runtime: WorldRuntimeRecord | null,
  nextCandidate: RuntimeCandidate | null,
  now: Date
): WorldRuntimeRecord | null {
  if (!runtime || runtime.phase !== "handoff-waiting") {
    return runtime;
  }

  if (!nextCandidate) {
    return null;
  }

  if (runtime.candidateUuid === nextCandidate.playerUuid) {
    return runtime;
  }

  return {
    ...runtime,
    candidateUuid: nextCandidate.playerUuid,
    updatedAt: now.toISOString()
  };
}

/**
 * Responsibility:
 * Create the next authoritative host-starting runtime and host assignment token.
 *
 * Preconditions:
 * The backend already decided who the next host candidate is.
 *
 * Postconditions:
 * A new runtime epoch/token pair exists and all older host work becomes stale.
 *
 * Stale-work rule:
 * Every ownership change increments runtimeEpoch and issues a fresh runtimeToken.
 *
 * Authority source:
 * The backend host election decision.
 */
export function assignHostStarting(
  worldId: string,
  assignee: RuntimeCandidate,
  previousRuntime: Pick<WorldRuntimeRecord, "runtimeEpoch"> | null,
  now: Date,
  tokenFactory: () => string
): { runtime: WorldRuntimeRecord; assignment: HostAssignment } {
  const runtimeEpoch = (previousRuntime?.runtimeEpoch ?? 0) + 1;
  const issuedAt = now.toISOString();
  const startupDeadlineAt = new Date(now.getTime() + HOST_LEASE_TIMEOUT_MS).toISOString();
  const runtimeToken = tokenFactory();
  const runtime: WorldRuntimeRecord = {
    worldId,
    phase: "host-starting",
    runtimeEpoch,
    runtimeToken,
    hostUuid: assignee.playerUuid,
    hostPlayerName: assignee.playerName,
    candidateUuid: assignee.playerUuid,
    joinTarget: null,
    claimedAt: issuedAt,
    expiresAt: startupDeadlineAt,
    startupDeadlineAt,
    runtimeTokenIssuedAt: issuedAt,
    lastProgressAt: null,
    updatedAt: issuedAt,
    revokedAt: null,
    startupProgress: null
  };
  return {
    runtime,
    assignment: {
      worldId,
      playerUuid: assignee.playerUuid,
      playerName: assignee.playerName,
      runtimeEpoch,
      hostToken: runtimeToken,
      startupDeadlineAt
    }
  };
}

export function candidateToWaitingRuntime(runtime: WorldRuntimeRecord, nextCandidate: RuntimeCandidate | null, now: Date): WorldRuntimeRecord | null {
  if (!nextCandidate) {
    return null;
  }
  return {
    ...runtime,
    phase: "handoff-waiting",
    candidateUuid: nextCandidate.playerUuid,
    runtimeToken: null,
    joinTarget: null,
    expiresAt: null,
    startupDeadlineAt: null,
    runtimeTokenIssuedAt: null,
    lastProgressAt: null,
    updatedAt: now.toISOString(),
    startupProgress: null
  };
}

export function moveToLive(runtime: WorldRuntimeRecord, joinTarget: string | null, now: Date): WorldRuntimeRecord {
  return {
    ...runtime,
    phase: "host-live",
    joinTarget: joinTarget ?? runtime.joinTarget,
    expiresAt: new Date(now.getTime() + HOST_LEASE_TIMEOUT_MS).toISOString(),
    startupDeadlineAt: null,
    updatedAt: now.toISOString()
  };
}

/**
 * Responsibility:
 * Refresh host liveness for the currently authoritative runtime.
 *
 * Preconditions:
 * The caller already proved authority for the current host-starting or host-live runtime.
 *
 * Postconditions:
 * host-starting extends startupDeadlineAt, and a non-blank join target promotes the runtime to host-live.
 *
 * Stale-work rule:
 * This helper assumes the caller already rejected stale epochs/tokens.
 *
 * Authority source:
 * The currently authorized backend runtime record.
 */
export function refreshLiveRuntime(runtime: WorldRuntimeRecord, joinTarget: string | null, now: Date): WorldRuntimeRecord {
  if (joinTarget != null && joinTarget.trim().length > 0 && runtime.phase === "host-starting") {
    return moveToLive(runtime, joinTarget, now);
  }
  if (runtime.phase === "host-starting") {
    const extendedDeadline = new Date(now.getTime() + HOST_LEASE_TIMEOUT_MS).toISOString();
    return {
      ...runtime,
      joinTarget: joinTarget ?? runtime.joinTarget,
      expiresAt: extendedDeadline,
      startupDeadlineAt: extendedDeadline,
      updatedAt: now.toISOString()
    };
  }
  return {
    ...runtime,
    joinTarget: joinTarget ?? runtime.joinTarget,
    expiresAt: new Date(now.getTime() + HOST_LEASE_TIMEOUT_MS).toISOString(),
    updatedAt: now.toISOString()
  };
}

/**
 * Responsibility:
 * Freeze an authoritative host runtime so guests stop connecting while the host finalizes state.
 *
 * Preconditions:
 * The caller still owns the active host-starting or host-live runtime.
 *
 * Postconditions:
 * The runtime becomes host-finalizing with no join target and no ordinary host deadline.
 *
 * Stale-work rule:
 * The caller must reject stale epochs/tokens before invoking this transition.
 *
 * Authority source:
 * The currently authorized backend runtime record.
 */
export function moveToFinalizing(runtime: WorldRuntimeRecord, now: Date): WorldRuntimeRecord {
  return {
    ...runtime,
    phase: "host-finalizing",
    joinTarget: null,
    expiresAt: null,
    startupDeadlineAt: null,
    updatedAt: now.toISOString(),
    startupProgress: null
  };
}

export function clearHostProgress(runtime: WorldRuntimeRecord, now: Date): WorldRuntimeRecord {
  return {
    ...runtime,
    startupProgress: null,
    lastProgressAt: null,
    updatedAt: now.toISOString()
  };
}

export function setHostProgress(
  runtime: WorldRuntimeRecord,
  progress: HostStartupProgress | null,
  now: Date
): WorldRuntimeRecord {
  return {
    ...runtime,
    startupProgress: progress,
    lastProgressAt: progress?.updatedAt ?? null,
    updatedAt: now.toISOString()
  };
}

/**
 * Responsibility:
 * Check whether a caller still owns the current host runtime.
 *
 * Preconditions:
 * The caller supplied a player UUID plus the epoch/token pair it believes is current.
 *
 * Postconditions:
 * Returns true only for the exact current host epoch/token.
 *
 * Stale-work rule:
 * Old epochs or tokens fail closed, even for the same player.
 *
 * Authority source:
 * The current backend runtime record.
 */
export function matchesHostAuthorization(
  runtime: WorldRuntimeRecord | null,
  playerUuid: string,
  runtimeEpoch: number | null | undefined,
  hostToken: string | null | undefined
): boolean {
  return runtime != null
    && runtime.hostUuid === playerUuid
    && runtime.runtimeEpoch === runtimeEpoch
    && runtime.runtimeToken != null
    && runtime.runtimeToken === hostToken;
}

export function toRuntimeStatus(
  worldId: string,
  runtime: WorldRuntimeRecord | null,
  candidate: RuntimeCandidate | null,
  warning: UncleanShutdownWarning | null = null
): WorldRuntimeStatus {
  const publicWarning = warning == null
    ? null
    : {
        hostUuid: warning.hostUuid,
        hostPlayerName: warning.hostPlayerName,
        phase: warning.phase,
        runtimeEpoch: warning.runtimeEpoch,
        recordedAt: warning.recordedAt
      };
  if (!runtime) {
    return {
      worldId,
      phase: candidate != null ? "handoff-waiting" : "idle",
      runtimeEpoch: 0,
      hostUuid: null,
      hostPlayerName: null,
      candidateUuid: candidate?.playerUuid ?? null,
      candidatePlayerName: candidate?.playerName ?? null,
      joinTarget: null,
      startupDeadlineAt: null,
      runtimeTokenIssuedAt: null,
      lastProgressAt: null,
      updatedAt: null,
      revokedAt: null,
      startupProgress: null,
      uncleanShutdownWarning: publicWarning
    };
  }
  return {
    worldId: runtime.worldId,
    phase: runtime.phase,
    runtimeEpoch: runtime.runtimeEpoch,
    hostUuid: runtime.hostUuid,
    hostPlayerName: runtime.hostPlayerName,
    candidateUuid: runtime.candidateUuid,
    candidatePlayerName: runtime.candidateUuid != null && candidate?.playerUuid === runtime.candidateUuid ? candidate.playerName : null,
    joinTarget: runtime.joinTarget,
    startupDeadlineAt: runtime.startupDeadlineAt,
    runtimeTokenIssuedAt: runtime.runtimeTokenIssuedAt,
    lastProgressAt: runtime.lastProgressAt,
    updatedAt: runtime.updatedAt,
    revokedAt: runtime.revokedAt,
    startupProgress: runtime.startupProgress,
    uncleanShutdownWarning: publicWarning
  };
}

export function runtimePhaseToWorldStatus(phase: WorldRuntimePhase): "idle" | "hosting" | "finalizing" | "handoff" {
  return switchPhase(phase);
}

function switchPhase(phase: WorldRuntimePhase): "idle" | "hosting" | "finalizing" | "handoff" {
  switch (phase) {
    case "idle":
      return "idle";
    case "host-starting":
    case "host-live":
      return "hosting";
    case "host-finalizing":
      return "finalizing";
    case "handoff-waiting":
      return "handoff";
  }
}

function phaseDeadline(runtime: WorldRuntimeRecord): Date | null {
  if (runtime.phase === "host-finalizing") {
    const lastActivityAt = runtime.lastProgressAt ?? runtime.updatedAt;
    if (!lastActivityAt) {
      return null;
    }
    return new Date(new Date(lastActivityAt).getTime() + HOST_LEASE_TIMEOUT_MS);
  }
  const rawDeadline = runtime.phase === "host-starting"
    ? runtime.startupDeadlineAt
    : runtime.phase === "host-live"
    ? runtime.expiresAt
    : null;
  if (!rawDeadline) {
    return null;
  }
  return new Date(rawDeadline);
}
