import type { StartupProgressMode, WorldRuntimePhase } from "../../shared/src/index.ts";

import {
  runtimePhaseToWorldStatus,
  type WorldRuntimeRecord
} from "./runtime-protocol.ts";

type LegacyHostLease = {
  worldId: string;
  hostUuid: string;
  hostPlayerName: string;
  status: "idle" | "hosting" | "finalizing" | "handoff";
  runtimePhase?: WorldRuntimePhase | null;
  runtimeEpoch?: number | null;
  runtimeToken?: string | null;
  claimedAt: string;
  expiresAt: string;
  updatedAt: string;
  joinTarget: string | null;
  handoffCandidateUuid: string | null;
  revokedAt?: string | null;
  startupDeadlineAt?: string | null;
  runtimeTokenIssuedAt?: string | null;
  lastProgressAt?: string | null;
  startupProgress?: {
    label: string;
    mode: StartupProgressMode;
    fraction: number | null;
    updatedAt: string;
  } | null;
};

/**
 * Repository invariant boundary for runtime persistence.
 *
 * Responsibility:
 * Preserve the exact runtime protocol shape when converting to and from storage.
 *
 * Preconditions:
 * The caller is reading or writing a runtime-backed host lease record.
 *
 * Postconditions:
 * - Runtime epoch/token round-trip losslessly.
 * - host-starting validity uses startupDeadlineAt before falling back to expiresAt.
 * - host-finalizing is never expired by ordinary live-host deadline rules.
 *
 * Stale-work rule:
 * Persistence helpers never infer authority from partial data. They only map fields and
 * compute phase-appropriate deadlines.
 *
 * Authority source:
 * The backend runtime protocol is authoritative; storage adapters must preserve it exactly.
 */
export function leaseRuntimePhase(lease: LegacyHostLease): WorldRuntimePhase {
  if (lease.runtimePhase != null) {
    return lease.runtimePhase;
  }
  switch (lease.status) {
    case "hosting":
      return lease.joinTarget ? "host-live" : "host-starting";
    case "finalizing":
      return "host-finalizing";
    case "handoff":
      return "handoff-waiting";
    default:
      return "idle";
  }
}

export function runtimeLeaseDeadline(lease: LegacyHostLease): string | null {
  const phase = leaseRuntimePhase(lease);
  return phase === "host-starting"
    ? lease.startupDeadlineAt ?? lease.expiresAt
    : phase === "host-live"
    ? lease.expiresAt
    : null;
}

export function leaseExpired(lease: LegacyHostLease, now: Date): boolean {
  const deadline = runtimeLeaseDeadline(lease);
  return deadline != null && new Date(deadline).getTime() < now.getTime();
}

export function leaseToRuntimeRecord(lease: LegacyHostLease | null): WorldRuntimeRecord | null {
  if (!lease) {
    return null;
  }
  return {
    worldId: lease.worldId,
    phase: leaseRuntimePhase(lease),
    runtimeEpoch: lease.runtimeEpoch ?? 0,
    runtimeToken: lease.runtimeToken ?? null,
    hostUuid: lease.hostUuid,
    hostPlayerName: lease.hostPlayerName,
    candidateUuid: lease.handoffCandidateUuid,
    joinTarget: lease.joinTarget,
    claimedAt: lease.claimedAt,
    expiresAt: lease.expiresAt,
    startupDeadlineAt: lease.startupDeadlineAt ?? null,
    runtimeTokenIssuedAt: lease.runtimeTokenIssuedAt ?? null,
    lastProgressAt: lease.lastProgressAt ?? null,
    updatedAt: lease.updatedAt,
    revokedAt: lease.revokedAt ?? null,
    startupProgress: lease.startupProgress ?? null
  };
}

export function runtimeToLease(runtime: WorldRuntimeRecord): LegacyHostLease {
  return {
    worldId: runtime.worldId,
    hostUuid: runtime.hostUuid ?? "",
    hostPlayerName: runtime.hostPlayerName ?? "",
    status: runtimePhaseToWorldStatus(runtime.phase),
    runtimePhase: runtime.phase,
    runtimeEpoch: runtime.runtimeEpoch,
    runtimeToken: runtime.runtimeToken,
    claimedAt: runtime.claimedAt ?? runtime.updatedAt,
    expiresAt: runtime.expiresAt ?? runtime.updatedAt,
    updatedAt: runtime.updatedAt,
    joinTarget: runtime.joinTarget,
    handoffCandidateUuid: runtime.candidateUuid,
    revokedAt: runtime.revokedAt,
    startupDeadlineAt: runtime.startupDeadlineAt,
    runtimeTokenIssuedAt: runtime.runtimeTokenIssuedAt,
    lastProgressAt: runtime.lastProgressAt,
    startupProgress: runtime.startupProgress
  };
}
