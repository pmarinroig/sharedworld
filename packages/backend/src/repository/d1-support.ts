import type { StartupProgressMode, WorldRuntimePhase } from "../../../shared/src/index.ts";

export type Row = Record<string, unknown>;

export type LegacyClaimHostRequest = {
  joinTarget?: string | null;
};

export type LegacyHostLease = {
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

export type LegacyHostStatus = {
  worldId: string;
  activeLease: LegacyHostLease | null;
  nextHostUuid: string | null;
  nextHostPlayerName: string | null;
};

export function normalizeBoundValues(values: unknown[]): unknown[] {
  return values.map((value) => value === undefined ? null : value);
}

export function sqlPlaceholders(count: number): string {
  return Array.from({ length: count }, () => "?").join(", ");
}

export function joinMotdLines(line1: string | null, line2: string | null): string | null {
  const lines = [line1 ?? "", line2 ?? ""]
    .flatMap((line) => line.split("\n"))
    .map((line) => line.trimEnd())
    .filter((line) => line.length > 0);
  return lines.length > 0 ? lines.join("\n") : null;
}

export function asNullableString(value: unknown): string | null {
  return value == null ? null : String(value);
}

export function clampFraction(value: number | null): number | null {
  if (value == null || Number.isNaN(value)) {
    return null;
  }
  return Math.max(0, Math.min(1, value));
}
