import type { UncleanShutdownWarning } from "../../shared/src/index.ts";

import {
  choosePreferredCandidate,
  resolveRuntimeTimeout,
  timedOutUncleanShutdownWarning,
  type RuntimeMembership,
  type RuntimeWaiter,
  type WorldRuntimeRecord
} from "./runtime-protocol.ts";
import type { ResolvedRuntimeState } from "./runtime-service-support.ts";

/**
 * The data-access surface runtime reconciliation needs. Both the service layer
 * (through the repository interface) and the D1 repository itself (for world
 * summaries) satisfy this structurally, so there is exactly ONE implementation
 * of timeout expiry + candidate election — the two views can never disagree.
 */
export interface RuntimeReconciliationStore {
  listMemberships(worldId: string): Promise<RuntimeMembership[]>;
  listActiveWaiters(worldId: string, now: Date): Promise<RuntimeWaiter[]>;
  getRuntimeRecord(worldId: string, now: Date): Promise<WorldRuntimeRecord | null>;
  setUncleanShutdownWarning(worldId: string, warning: UncleanShutdownWarning): Promise<void>;
  deleteRuntimeRecord(worldId: string, expected: { runtimeEpoch: number; runtimeToken: string | null }): Promise<boolean>;
  clearWaiters(worldId: string): Promise<void>;
  clearWorldPresence(worldId: string): Promise<void>;
  getUncleanShutdownWarning(worldId: string): Promise<UncleanShutdownWarning | null>;
  getLastRuntimeEpoch(worldId: string): Promise<number>;
}

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
export async function reconcileRuntimeState(store: RuntimeReconciliationStore, worldId: string, now: Date): Promise<ResolvedRuntimeState> {
  const memberships = await store.listMemberships(worldId);
  const waiters = await store.listActiveWaiters(worldId, now);
  const candidate = choosePreferredCandidate(waiters.filter((waiter) => waiter.waiting), memberships);
  const before = await store.getRuntimeRecord(worldId, now);
  const timeoutWarning = timedOutUncleanShutdownWarning(before, now);
  if (timeoutWarning != null && before != null) {
    await store.setUncleanShutdownWarning(worldId, timeoutWarning);
    await store.deleteRuntimeRecord(worldId, { runtimeEpoch: before.runtimeEpoch, runtimeToken: before.runtimeToken });
    await store.clearWaiters(worldId);
    await store.clearWorldPresence(worldId);
    return {
      runtime: null,
      candidate: null,
      warning: timeoutWarning,
      retiredRuntimeEpoch: before.runtimeEpoch
    };
  }
  const afterTimeout = resolveRuntimeTimeout(before, now);
  if (before != null && afterTimeout == null) {
    await store.deleteRuntimeRecord(worldId, { runtimeEpoch: before.runtimeEpoch, runtimeToken: before.runtimeToken });
  }
  const warning = await store.getUncleanShutdownWarning(worldId);
  const retiredRuntimeEpoch = afterTimeout == null
    ? before?.runtimeEpoch ?? warning?.runtimeEpoch ?? await store.getLastRuntimeEpoch(worldId)
    : null;
  return {
    runtime: afterTimeout,
    candidate,
    warning,
    retiredRuntimeEpoch
  };
}
