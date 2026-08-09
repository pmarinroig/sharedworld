import {
  HANDOFF_WAITER_TIMEOUT_MS,
  HOST_LEASE_TIMEOUT_MS,
  PLAYER_PRESENCE_TIMEOUT_MS,
  type EnterSessionRequest,
  type FinalizationActionResult,
  type HostStartupProgressRequest,
  type RealtimeEvent,
  type RoomPlayer,
  type UncleanShutdownWarning,
  type WorldRuntimeStatus
} from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import { randomId } from "../ids.ts";
import {
  assignHostStarting,
  choosePreferredCandidate,
  matchesHostAuthorization,
  moveToFinalizing,
  refreshLiveRuntime,
  resolveRuntimeTimeout,
  runtimePhaseToWorldStatus,
  setHostProgress,
  timedOutUncleanShutdownWarning,
  toRuntimeStatus,
  type RuntimeCandidate,
  type RuntimeMembership,
  type RuntimeWaiter,
  type WorldRuntimeRecord
} from "../runtime-protocol.ts";
import {
  hostAssignmentForCurrentRuntime,
  runtimeAllowsDirectConnect,
  runtimeRequiresWaiting,
  type ResolvedRuntimeState
} from "../runtime-service-support.ts";

/** Grace window between the host's socket dropping and lease forfeiture. */
export const HOST_DISCONNECT_GRACE_MS = 30_000;

/**
 * The caller's identity plus the membership facts the Worker already checked
 * against D1. The coordinator never re-reads membership for access control;
 * it only adds the runtime-derived exception (a revoked host finishing its
 * own shutdown).
 */
export interface SessionActor {
  playerUuid: string;
  playerName: string;
  /** Active (non-deleted) member of the world right now. */
  membershipActive: boolean;
  /** Has a membership row at all, including a revoked one. */
  everMember: boolean;
}

/**
 * Legacy 0.2.x self-reported presence entry (dies with the legacy adapters).
 * present=false entries are tombstones: they persist so a stale heartbeat
 * from an older guest session cannot resurrect a disconnected player.
 */
export interface LegacyPresenceEntry {
  playerUuid: string;
  playerName: string;
  present: boolean;
  guestSessionEpoch: number;
  presenceSequence: number;
  expiresAt: string;
}

/**
 * Synchronous per-world state. The coordinator is single-threaded, so the
 * store needs no fencing: reads and writes in one method invocation are
 * atomic by construction.
 */
export interface CoordinatorStore {
  getRuntime(): WorldRuntimeRecord | null;
  putRuntime(runtime: WorldRuntimeRecord): void;
  deleteRuntime(): void;
  getWarning(): UncleanShutdownWarning | null;
  setWarning(warning: UncleanShutdownWarning): void;
  clearWarning(): void;
  getLastEpoch(): number;
  setLastEpoch(epoch: number): void;
  listWaiters(): RuntimeWaiter[];
  upsertWaiter(waiter: RuntimeWaiter): void;
  deleteWaiter(playerUuid: string): void;
  clearWaiters(): void;
  /** Host-reported roster for the CURRENT hosting session, null when none reported. */
  getRoomPlayers(): RoomPlayer[] | null;
  setRoomPlayers(players: RoomPlayer[] | null): void;
  listLegacyPresence(): LegacyPresenceEntry[];
  upsertLegacyPresence(entry: LegacyPresenceEntry): void;
  deleteLegacyPresence(playerUuid: string): void;
  clearLegacyPresence(): void;
  getHostLink(): { connected: boolean; graceDeadlineAt: string | null };
  setHostLink(link: { connected: boolean; graceDeadlineAt: string | null }): void;
  clearAll(): void;
}

/**
 * Everything with a side effect outside this world's own state. Injected so
 * the logic class is deterministic under test and the DO shell stays thin.
 */
export interface CoordinatorEffects {
  listMemberships(worldId: string): Promise<RuntimeMembership[]>;
  /** Single-writer display mirror: the full public status for D1 summary reads. */
  mirrorRuntime(worldId: string, status: WorldRuntimeStatus): Promise<void>;
  /** Single-writer display mirror: world_presence rows for summaries. */
  mirrorPresence(worldId: string, players: RoomPlayer[]): Promise<void>;
  /** Fan out one event to member gateways (or an explicit recipient list). */
  publish(event: RealtimeEvent, recipients?: string[]): Promise<void>;
  /** Replace the single pending alarm; null cancels it. */
  scheduleAlarm(at: Date | null): Promise<void>;
  /**
   * Ask the host's gateway to report socket open/close for this world.
   * Returns whether the host's socket is connected right now (false when
   * unwatching) — returned rather than called back to avoid re-entrant
   * DO-to-DO deadlock.
   */
  setHostWatch(hostUuid: string, watching: boolean): Promise<boolean>;
  /** Last keepalive seen on the host's socket, null when unknown/absent. */
  probeHostReachability(hostUuid: string): Promise<Date | null>;
}

export interface SessionEntryDecision {
  action: "connect" | "host" | "warn-host" | "wait";
  runtime: WorldRuntimeStatus;
  assignment: ReturnType<typeof hostAssignmentForCurrentRuntime>;
  waiterSessionId: string | null;
}

export interface WaitingObservation {
  action: "connect" | "wait" | "restart";
  runtime: WorldRuntimeStatus;
  waiterSessionId: string | null;
}

function hostNotActiveError(reason?: "lease_expired" | "replaced"): HttpError {
  const error = new HttpError(409, "host_not_active", "Someone else is hosting this world now, so this upload was stopped.");
  error.reason = reason;
  return error;
}

/**
 * host_not_active covers two very different situations, and 0.3.2 clients
 * render both as "someone else took over hosting" — a lie for a solo host
 * whose own lease lapsed. The reason lets newer clients tell them apart:
 * "lease_expired" (no runtime survives — the caller's own lease lapsed) vs
 * "replaced" (a different player holds the runtime now). Same-player
 * mismatches (own newer session, wrong phase) stay reasonless.
 */
function hostNotActiveReason(
  runtime: WorldRuntimeRecord | null,
  playerUuid: string
): "lease_expired" | "replaced" | undefined {
  if (runtime == null) {
    return "lease_expired";
  }
  if (runtime.hostUuid !== playerUuid) {
    return "replaced";
  }
  return undefined;
}

/**
 * The per-world runtime authority. One instance per world, single-threaded
 * (Durable Object semantics); every session/election/lease decision funnels
 * through here. Ports the protocol semantics that previously lived across
 * service/session.ts, session-entry.ts, and runtime-reconciliation.ts — the
 * pure reducers in runtime-protocol.ts are reused as-is.
 */
export class WorldCoordinator {
  constructor(
    private readonly worldId: string,
    private readonly store: CoordinatorStore,
    private readonly effects: CoordinatorEffects
  ) {}

  // ---------------------------------------------------------------- entry

  async enterSession(
    actor: SessionActor,
    request: Pick<EnterSessionRequest, "waiterSessionId" | "acknowledgeUncleanShutdown">,
    now: Date
  ): Promise<SessionEntryDecision> {
    await this.requireSessionAccess(actor);
    const requestedWaiterSessionId = sanitizeWaiterSessionId(request.waiterSessionId);
    const resolved = await this.resolve(now);

    const respond = async (
      action: SessionEntryDecision["action"],
      state: ResolvedRuntimeState,
      runtime: WorldRuntimeRecord | null = state.runtime,
      assignment: SessionEntryDecision["assignment"] = null,
      waiterSessionId: string | null = null
    ): Promise<SessionEntryDecision> => {
      await this.afterStateChange(now);
      return {
        action,
        runtime: toRuntimeStatus(this.worldId, runtime, state.candidate, state.warning),
        assignment,
        waiterSessionId
      };
    };
    const cancelRequestedWaiter = () => {
      if (requestedWaiterSessionId != null) {
        this.cancelWaiterSessionInternal(actor.playerUuid, requestedWaiterSessionId);
      }
    };

    const immediate = immediateEntryKind(resolved, actor.playerUuid);
    if (immediate != null) {
      cancelRequestedWaiter();
      return immediate.kind === "connect"
        ? respond("connect", resolved)
        : respond("host", resolved, resolved.runtime, immediate.assignment);
    }
    if (resolved.runtime == null && resolved.candidate == null) {
      if (resolved.warning != null && !request.acknowledgeUncleanShutdown) {
        return respond("warn-host", resolved);
      }
      const claimed = await this.claimHost(
        { playerUuid: actor.playerUuid, playerName: actor.playerName },
        resolved,
        now
      );
      cancelRequestedWaiter();
      return respond("host", resolved, claimed.runtime, claimed.assignment);
    }
    const waiting = this.registerWaiter(actor, requestedWaiterSessionId, now);
    const reresolved = await this.resolve(now);
    const reportedWaiterSessionId = waiting.active ? waiting.waiterSessionId : null;
    if (runtimeRequiresWaiting(reresolved)) {
      return respond("wait", reresolved, reresolved.runtime, null, reportedWaiterSessionId);
    }
    if (waiting.active && reresolved.runtime == null && reresolved.candidate?.playerUuid === actor.playerUuid) {
      const promoted = await this.claimHost(reresolved.candidate, reresolved, now);
      this.cancelWaiterSessionInternal(actor.playerUuid, waiting.waiterSessionId);
      return respond("host", reresolved, promoted.runtime, promoted.assignment);
    }
    return respond("wait", reresolved, reresolved.runtime, null, reportedWaiterSessionId);
  }

  async observeWaiting(actor: SessionActor, waiterSessionIdRaw: string | null | undefined, now: Date): Promise<WaitingObservation> {
    await this.requireSessionAccess(actor);
    const waiterSessionId = sanitizeWaiterSessionId(waiterSessionIdRaw);
    if (!waiterSessionId) {
      throw new HttpError(400, "invalid_waiter_session", "SharedWorld waiting session id is required.");
    }
    const waiterSessionActive = this.refreshWaiterSession(actor, waiterSessionId, now);
    const resolved = await this.resolve(now);
    const respond = async (
      action: WaitingObservation["action"],
      runtime: WorldRuntimeRecord | null = resolved.runtime,
      reportedWaiterSessionId: string | null = null
    ): Promise<WaitingObservation> => {
      await this.afterStateChange(now);
      return {
        action,
        runtime: toRuntimeStatus(this.worldId, runtime, resolved.candidate, resolved.warning),
        waiterSessionId: reportedWaiterSessionId
      };
    };

    const immediate = immediateEntryKind(resolved, actor.playerUuid);
    if (immediate != null) {
      if (waiterSessionActive) {
        this.cancelWaiterSessionInternal(actor.playerUuid, waiterSessionId);
      }
      return respond(immediate.kind === "connect" ? "connect" : "restart");
    }
    if (!waiterSessionActive) {
      return respond("restart");
    }
    if (resolved.runtime == null && resolved.candidate?.playerUuid === actor.playerUuid) {
      const promoted = await this.claimHost(resolved.candidate, resolved, now);
      this.cancelWaiterSessionInternal(actor.playerUuid, waiterSessionId);
      return respond("restart", promoted.runtime);
    }
    if (resolved.runtime == null) {
      return resolved.candidate != null && resolved.candidate.playerUuid !== actor.playerUuid
        ? respond("wait", null, waiterSessionId)
        : respond("restart");
    }
    return respond("wait", resolved.runtime, waiterSessionId);
  }

  async runtimeStatus(actor: SessionActor, now: Date): Promise<WorldRuntimeStatus> {
    await this.requireSessionAccess(actor, { allowRevokedHost: true });
    const resolved = await this.resolve(now);
    await this.afterStateChange(now);
    return toRuntimeStatus(this.worldId, resolved.runtime, resolved.candidate, resolved.warning);
  }

  async cancelWaiting(actor: SessionActor, waiterSessionId: string, now: Date): Promise<WorldRuntimeStatus> {
    await this.requireSessionAccess(actor);
    this.cancelWaiterSessionInternal(actor.playerUuid, waiterSessionId);
    const resolved = await this.resolve(now);
    await this.afterStateChange(now);
    return toRuntimeStatus(this.worldId, resolved.runtime, resolved.candidate, resolved.warning);
  }

  // ------------------------------------------------------------- host ops

  async heartbeat(
    actor: SessionActor,
    request: { runtimeEpoch: number | null; hostToken: string | null; joinTarget?: string | null; minecraftVersion?: string | null },
    now: Date
  ): Promise<WorldRuntimeStatus> {
    await this.requireSessionAccess(actor);
    if (request.runtimeEpoch == null || request.runtimeEpoch < 0 || request.hostToken == null) {
      throw hostNotActiveError();
    }
    const resolved = await this.resolve(now);
    const runtime = resolved.runtime;
    if (runtime == null || !matchesHostAuthorization(runtime, actor.playerUuid, request.runtimeEpoch, request.hostToken)) {
      throw hostNotActiveError(hostNotActiveReason(runtime, actor.playerUuid));
    }
    // The host just proved itself reachable over HTTPS; a socket-grace
    // deadline armed by a dropped push channel must not forfeit its lease
    // while heartbeats keep landing. The socket may still be down, so only
    // the deadline clears — connected state belongs to the gateway.
    const link = this.store.getHostLink();
    if (link.graceDeadlineAt != null) {
      this.store.setHostLink({ connected: link.connected, graceDeadlineAt: null });
    }
    if (runtime.phase === "host-finalizing") {
      await this.afterStateChange(now);
      return toRuntimeStatus(this.worldId, runtime, resolved.candidate);
    }
    if (runtime.phase !== "host-starting" && runtime.phase !== "host-live") {
      throw hostNotActiveError();
    }
    const refreshed = refreshLiveRuntime(runtime, request.joinTarget ?? null, now);
    const updated = request.minecraftVersion != null && request.minecraftVersion.trim().length > 0
      ? { ...refreshed, hostMinecraftVersion: request.minecraftVersion.trim() }
      : refreshed;
    this.store.putRuntime(updated);
    await this.afterStateChange(now);
    return toRuntimeStatus(this.worldId, updated, candidateFromRuntime(updated));
  }

  async setStartupProgress(
    actor: SessionActor,
    request: HostStartupProgressRequest,
    now: Date
  ): Promise<WorldRuntimeStatus> {
    await this.requireSessionAccess(actor, { allowRevokedHost: true });
    if (request.runtimeEpoch == null || request.runtimeEpoch < 0 || request.hostToken == null) {
      throw hostNotActiveError();
    }
    const runtime = await this.requireAuthorizedRuntime(actor, request.runtimeEpoch, request.hostToken, ["host-starting", "host-finalizing"], now);
    const progress = request.label != null && request.mode != null
      ? {
          label: request.label,
          mode: request.mode,
          fraction: clampFraction(request.fraction ?? null),
          updatedAt: now.toISOString()
        }
      : null;
    const updated = setHostProgress(runtime, progress, now);
    this.store.putRuntime(updated);
    await this.afterStateChange(now);
    return toRuntimeStatus(this.worldId, updated, candidateFromRuntime(updated));
  }

  /**
   * Access-only check for HTTP read paths that must honor the revoked-host
   * exception (a kicked host finishing its own shutdown still downloads).
   */
  async assertSessionAccess(actor: SessionActor, options: { allowRevokedHost?: boolean } = {}): Promise<void> {
    await this.requireSessionAccess(actor, options);
  }

  /**
   * Host-owned write authorization for HTTP paths outside the coordinator
   * (uploads, finalize-snapshot, gamerule reports). Throws host_not_active
   * unless the caller holds the exact current epoch/token in an allowed phase.
   */
  async validateHostAuthority(
    actor: SessionActor,
    runtimeEpoch: number | null | undefined,
    hostToken: string | null | undefined,
    allowedPhases: WorldRuntimeRecord["phase"][],
    now: Date
  ): Promise<void> {
    await this.requireSessionAccess(actor, { allowRevokedHost: true });
    if (runtimeEpoch == null || runtimeEpoch < 0 || hostToken == null) {
      throw hostNotActiveError();
    }
    await this.requireAuthorizedRuntime(actor, runtimeEpoch, hostToken, allowedPhases, now);
  }

  async beginFinalization(
    actor: SessionActor,
    request: { runtimeEpoch: number | null; hostToken: string | null },
    now: Date
  ): Promise<FinalizationActionResult> {
    await this.requireSessionAccess(actor, { allowRevokedHost: true });
    const runtime = await this.requireAuthorizedRuntime(
      actor,
      request.runtimeEpoch,
      request.hostToken,
      ["host-starting", "host-live", "host-finalizing"],
      now
    );
    if (runtime.phase === "host-finalizing") {
      await this.afterStateChange(now);
      return finalizationResult(this.worldId, runtime, null);
    }
    const updated = moveToFinalizing(runtime, now);
    this.store.putRuntime(updated);
    await this.afterStateChange(now);
    return finalizationResult(this.worldId, updated, null);
  }

  async completeFinalization(
    actor: SessionActor,
    request: { runtimeEpoch: number | null; hostToken: string | null },
    now: Date
  ): Promise<FinalizationActionResult> {
    await this.requireSessionAccess(actor, { allowRevokedHost: true });
    const resolved = await this.resolve(now);
    const runtime = resolved.runtime;
    if (runtime == null || runtime.phase !== "host-finalizing") {
      if (this.isReleasedEpochReplay(request.runtimeEpoch, resolved.warning)) {
        await this.afterStateChange(now);
        return finalizationResult(this.worldId, null, resolved.candidate);
      }
      throw new HttpError(409, "not_finalizing", "SharedWorld is not currently finalizing.");
    }
    if (!matchesHostAuthorization(runtime, actor.playerUuid, request.runtimeEpoch, request.hostToken)) {
      throw hostNotActiveError(hostNotActiveReason(runtime, actor.playerUuid));
    }
    await this.retireRuntime(runtime, now);
    this.store.clearWarning();
    const after = await this.resolve(now);
    await this.afterStateChange(now);
    return finalizationResult(this.worldId, null, after.candidate);
  }

  /** Owner check happens in the Worker (D1 owns world ownership). */
  async abandonFinalization(now: Date): Promise<FinalizationActionResult> {
    const resolved = await this.resolve(now);
    const current = resolved.runtime;
    if (current == null || current.phase !== "host-finalizing") {
      await this.afterStateChange(now);
      return finalizationResult(this.worldId, current, resolved.candidate);
    }
    await this.retireRuntime(current, now);
    const after = await this.resolve(now);
    await this.afterStateChange(now);
    return finalizationResult(this.worldId, null, after.candidate);
  }

  async releaseHost(
    actor: SessionActor,
    request: { runtimeEpoch: number | null; hostToken: string | null; graceful: boolean },
    now: Date
  ): Promise<{ worldId: string; releasedAt: string; graceful: boolean; nextHostUuid: string | null; nextHostPlayerName: string | null }> {
    await this.requireSessionAccess(actor, { allowRevokedHost: true });
    const resolved = await this.resolve(now);
    const runtime = resolved.runtime;
    const authorized = runtime != null
      && (runtime.phase === "host-starting" || runtime.phase === "host-live" || runtime.phase === "host-finalizing")
      && matchesHostAuthorization(runtime, actor.playerUuid, request.runtimeEpoch, request.hostToken);
    if (!authorized) {
      if (this.isReleasedEpochReplay(request.runtimeEpoch, resolved.warning)) {
        return this.releaseResult(request.graceful, resolved, now);
      }
      throw hostNotActiveError(hostNotActiveReason(runtime, actor.playerUuid));
    }
    this.store.deleteWaiter(actor.playerUuid);
    await this.retireRuntime(runtime, now);
    if (request.graceful) {
      this.store.clearWarning();
    }
    const after = await this.resolve(now);
    return this.releaseResult(request.graceful, after, now);
  }

  // ------------------------------------------------------------- presence

  /** Host-reported full roster of the integrated server (0.3.0 clients). */
  async reportHostPlayers(playerUuid: string, runtimeEpoch: number, players: RoomPlayer[], now: Date): Promise<void> {
    const resolved = await this.resolve(now);
    const runtime = resolved.runtime;
    if (runtime == null || runtime.hostUuid !== playerUuid || runtime.runtimeEpoch !== runtimeEpoch) {
      return; // stale or unauthorized report: drop silently
    }
    this.store.setRoomPlayers(players);
    await this.publishPresence(now);
    await this.afterStateChange(now);
  }

  /**
   * Legacy 0.2.x presence self-report adapter (retires with legacy clients).
   * Epoch/sequence fencing ported from the old world_presence SQL: an update
   * only lands from a newer guest session, or the same session moving forward.
   */
  async reportLegacyPresence(
    actor: SessionActor,
    request: { present: boolean; guestSessionEpoch: number; presenceSequence: number },
    now: Date
  ): Promise<void> {
    await this.requireSessionAccess(actor);
    const existing = this.store.listLegacyPresence().find((entry) => entry.playerUuid === actor.playerUuid);
    const accepted = existing == null
      || request.guestSessionEpoch > existing.guestSessionEpoch
      || (request.guestSessionEpoch === existing.guestSessionEpoch && request.presenceSequence >= existing.presenceSequence);
    if (accepted) {
      this.store.upsertLegacyPresence({
        playerUuid: actor.playerUuid,
        playerName: actor.playerName,
        present: request.present,
        guestSessionEpoch: request.guestSessionEpoch,
        presenceSequence: request.presenceSequence,
        expiresAt: new Date(now.getTime() + PLAYER_PRESENCE_TIMEOUT_MS).toISOString()
      });
    }
    await this.publishPresence(now);
    await this.afterStateChange(now);
  }

  /**
   * The effective room roster: host-reported when this hosting session has
   * reported one, otherwise unexpired legacy self-reports.
   */
  roomPlayers(now: Date): RoomPlayer[] {
    const reported = this.store.getRoomPlayers();
    if (reported != null) {
      return reported;
    }
    return this.store.listLegacyPresence()
      .filter((entry) => entry.present && new Date(entry.expiresAt).getTime() > now.getTime())
      .map((entry) => ({ playerUuid: entry.playerUuid, playerName: entry.playerName }));
  }

  // ------------------------------------------------------------- liveness

  async hostSocketConnected(playerUuid: string, now: Date): Promise<void> {
    const runtime = this.store.getRuntime();
    if (runtime == null || runtime.hostUuid !== playerUuid) {
      return;
    }
    this.store.setHostLink({ connected: true, graceDeadlineAt: null });
    await this.afterStateChange(now);
  }

  async hostSocketClosed(playerUuid: string, now: Date): Promise<void> {
    const runtime = this.store.getRuntime();
    if (runtime == null || runtime.hostUuid !== playerUuid) {
      return;
    }
    if (runtime.phase !== "host-starting" && runtime.phase !== "host-live") {
      return;
    }
    const graceDeadlineAt = new Date(now.getTime() + HOST_DISCONNECT_GRACE_MS).toISOString();
    this.store.setHostLink({ connected: false, graceDeadlineAt });
    await this.afterStateChange(now);
  }

  /**
   * The single alarm handler: applies lease expiry, disconnect-grace
   * forfeiture, waiter expiry, and legacy-presence expiry, then republishes
   * and re-arms. Connection state only ever *shortens* patience; a reachable
   * host (fresh keepalive on its socket) extends its own lease without any
   * heartbeat request.
   */
  async onAlarm(now: Date): Promise<void> {
    const runtime = this.store.getRuntime();
    if (runtime != null && (runtime.phase === "host-starting" || runtime.phase === "host-live")) {
      const link = this.store.getHostLink();
      const graceDeadline = link.graceDeadlineAt != null ? new Date(link.graceDeadlineAt) : null;
      const graceDue = graceDeadline != null && graceDeadline.getTime() <= now.getTime();
      if (graceDue || leaseDeadlinePassed(runtime, now)) {
        const rescued = await this.rescueReachableHost(runtime, now);
        if (rescued == null && graceDue) {
          await this.expireRuntime(runtime, now);
        }
      }
    }
    await this.resolve(now);
    await this.publishPresence(now);
    await this.afterStateChange(now);
  }

  // ------------------------------------------------------------ lifecycle

  /** P5: world deleted — drop every trace and tell the (former) members. */
  async destroyWorld(recipients: string[]): Promise<void> {
    const runtime = this.store.getRuntime();
    if (runtime?.hostUuid != null) {
      await this.effects.setHostWatch(runtime.hostUuid, false);
    }
    this.store.clearAll();
    await this.effects.scheduleAlarm(null);
    await this.effects.publish({ worldId: this.worldId, kind: "world-deleted" }, recipients);
  }

  /** A member was kicked. If they are the current host, mark the runtime revoked (P6). */
  async memberRevoked(playerUuid: string, now: Date): Promise<void> {
    const runtime = this.store.getRuntime();
    if (runtime != null && runtime.hostUuid === playerUuid && runtime.revokedAt == null) {
      this.store.putRuntime({ ...runtime, revokedAt: now.toISOString(), updatedAt: now.toISOString() });
    }
    this.store.deleteWaiter(playerUuid);
    this.store.deleteLegacyPresence(playerUuid);
    await this.publishPresence(now);
    await this.afterStateChange(now);
  }

  // ------------------------------------------------------------ internals

  private async requireSessionAccess(actor: SessionActor, options: { allowRevokedHost?: boolean } = {}): Promise<void> {
    if (actor.membershipActive) {
      return;
    }
    if (options.allowRevokedHost) {
      const runtime = this.store.getRuntime();
      if (runtime?.hostUuid === actor.playerUuid && runtime.revokedAt != null) {
        return;
      }
    }
    if (!actor.everMember) {
      throw new HttpError(403, "forbidden", "You do not have access to this SharedWorld server.");
    }
    throw new HttpError(403, "membership_revoked", "You were removed from this SharedWorld.");
  }

  private async requireAuthorizedRuntime(
    actor: SessionActor,
    runtimeEpoch: number | null | undefined,
    hostToken: string | null | undefined,
    allowedPhases: WorldRuntimeRecord["phase"][],
    now: Date
  ): Promise<WorldRuntimeRecord> {
    const resolved = await this.resolve(now);
    if (!resolved.runtime
      || !allowedPhases.includes(resolved.runtime.phase)
      || !matchesHostAuthorization(resolved.runtime, actor.playerUuid, runtimeEpoch, hostToken)) {
      throw hostNotActiveError(hostNotActiveReason(resolved.runtime, actor.playerUuid));
    }
    return resolved.runtime;
  }

  /**
   * Port of reconcileRuntimeState: apply timeout expiry and elect the
   * preferred candidate before anything reasons about the runtime. Single
   * implementation, single thread — the summaries mirror can never disagree.
   */
  private async resolve(now: Date): Promise<ResolvedRuntimeState> {
    this.expireWaiters(now);
    const memberships = await this.effects.listMemberships(this.worldId);
    const waiters = this.store.listWaiters();
    const candidate = choosePreferredCandidate(waiters.filter((waiter) => waiter.waiting), memberships);
    let before = this.store.getRuntime();
    if (before != null
      && (before.phase === "host-starting" || before.phase === "host-live")
      && leaseDeadlinePassed(before, now)) {
      // Same mercy as onAlarm: an over-deadline lease is not trusted on its
      // own — the renewal alarm can lose a race against inbound traffic (an
      // autosave upload's blob PUTs each run this path), so a host whose
      // socket keepalive is fresh gets its lease renewed here instead of
      // being expired with a false unclean-shutdown warning.
      before = (await this.rescueReachableHost(before, now)) ?? before;
    }
    const timeoutWarning = timedOutUncleanShutdownWarning(before, now);
    if (timeoutWarning != null && before != null) {
      await this.expireRuntime(before, now, { clearWaiters: true });
      return { runtime: null, candidate: null, warning: timeoutWarning, retiredRuntimeEpoch: before.runtimeEpoch };
    }
    const afterTimeout = resolveRuntimeTimeout(before, now);
    if (before != null && afterTimeout == null) {
      await this.retireRuntime(before, now);
    }
    const warning = this.store.getWarning();
    const retiredRuntimeEpoch = afterTimeout == null
      ? before?.runtimeEpoch ?? warning?.runtimeEpoch ?? this.store.getLastEpoch()
      : null;
    return { runtime: afterTimeout, candidate, warning, retiredRuntimeEpoch };
  }

  /**
   * Connection signals are lossy: the gateway's connected/closed pokes can
   * die with a coordinator mid-reset ("Internal error in Durable Object
   * storage caused object to be reset" seen in production), so neither
   * link.connected nor an armed grace deadline is trusted on its own. The
   * gateway's keepalive timestamp is the ground truth — verify with a probe
   * before declaring the host gone, and repair the link state when the host
   * turns out to be reachable. Returns the renewed runtime, or null when the
   * host is genuinely unreachable.
   */
  private async rescueReachableHost(runtime: WorldRuntimeRecord, now: Date): Promise<WorldRuntimeRecord | null> {
    const lastSeen = await this.effects.probeHostReachability(runtime.hostUuid ?? "");
    const reachable = lastSeen != null && now.getTime() - lastSeen.getTime() < HOST_LEASE_TIMEOUT_MS;
    if (!reachable) {
      return null;
    }
    const refreshed = refreshLiveRuntime(runtime, null, now);
    this.store.putRuntime(refreshed);
    this.store.setHostLink({ connected: true, graceDeadlineAt: null });
    return refreshed;
  }

  private async claimHost(
    candidate: RuntimeCandidate,
    resolved: ResolvedRuntimeState,
    now: Date
  ): Promise<{ runtime: WorldRuntimeRecord; assignment: NonNullable<SessionEntryDecision["assignment"]> }> {
    const assigned = assignHostStarting(this.worldId, candidate, epochBaseline(resolved), now, () => randomId("rt"));
    this.store.putRuntime(assigned.runtime);
    // The epoch high-water mark ([P1]/[I3] replay detection) moves only when a
    // runtime is retired, never on claim — a live epoch must not look released.
    this.store.setRoomPlayers(null);
    const connected = await this.effects.setHostWatch(candidate.playerUuid, true);
    this.store.setHostLink({ connected, graceDeadlineAt: null });
    return assigned;
  }

  /** Delete the runtime record and advance the replay high-water mark. */
  private async retireRuntime(runtime: WorldRuntimeRecord, now: Date): Promise<void> {
    this.store.deleteRuntime();
    this.store.setLastEpoch(Math.max(this.store.getLastEpoch(), runtime.runtimeEpoch));
    this.store.setHostLink({ connected: false, graceDeadlineAt: null });
    this.store.setRoomPlayers(null);
    this.store.clearLegacyPresence();
    if (runtime.hostUuid != null) {
      await this.effects.setHostWatch(runtime.hostUuid, false);
    }
    await this.publishPresence(now);
  }

  /**
   * Lease/grace forfeiture: like retire, but records the unclean-shutdown
   * warning. A blown host-starting deadline stays warning-free (startup never
   * went live, so nothing was left unclean) — same rule as the reducer.
   */
  private async expireRuntime(runtime: WorldRuntimeRecord, now: Date, options: { clearWaiters?: boolean } = {}): Promise<void> {
    if ((runtime.phase === "host-live" || runtime.phase === "host-finalizing")
      && runtime.hostUuid != null && runtime.hostPlayerName != null) {
      this.store.setWarning({
        hostUuid: runtime.hostUuid,
        hostPlayerName: runtime.hostPlayerName,
        phase: runtime.phase,
        runtimeEpoch: runtime.runtimeEpoch,
        recordedAt: now.toISOString()
      });
    }
    await this.retireRuntime(runtime, now);
    if (options.clearWaiters) {
      this.store.clearWaiters();
    }
  }

  private registerWaiter(actor: SessionActor, requestedWaiterSessionId: string | null, now: Date): { waiterSessionId: string; active: boolean } {
    const waiterSessionId = requestedWaiterSessionId ?? randomId("wait");
    if (requestedWaiterSessionId != null) {
      return { waiterSessionId, active: this.refreshWaiterSession(actor, waiterSessionId, now) };
    }
    this.store.upsertWaiter({
      playerUuid: actor.playerUuid,
      playerName: actor.playerName,
      waiterSessionId,
      waiting: true,
      updatedAt: now.toISOString()
    });
    return { waiterSessionId, active: true };
  }

  private refreshWaiterSession(actor: SessionActor, waiterSessionId: string, now: Date): boolean {
    const existing = this.store.listWaiters().find(
      (waiter) => waiter.playerUuid === actor.playerUuid && waiter.waiterSessionId === waiterSessionId
    );
    if (existing == null) {
      return false;
    }
    this.store.upsertWaiter({ ...existing, playerName: actor.playerName, waiting: true, updatedAt: now.toISOString() });
    return true;
  }

  private cancelWaiterSessionInternal(playerUuid: string, waiterSessionId: string): void {
    const existing = this.store.listWaiters().find(
      (waiter) => waiter.playerUuid === playerUuid && waiter.waiterSessionId === waiterSessionId
    );
    if (existing != null) {
      this.store.deleteWaiter(playerUuid);
    }
  }

  private expireWaiters(now: Date): void {
    const cutoff = now.getTime() - HANDOFF_WAITER_TIMEOUT_MS;
    for (const waiter of this.store.listWaiters()) {
      if (new Date(waiter.updatedAt).getTime() < cutoff) {
        this.store.deleteWaiter(waiter.playerUuid);
      }
    }
  }

  private isReleasedEpochReplay(runtimeEpoch: number | null | undefined, warning: UncleanShutdownWarning | null): boolean {
    if (runtimeEpoch == null || runtimeEpoch < 1) {
      return false;
    }
    if (warning != null && warning.runtimeEpoch === runtimeEpoch) {
      return false;
    }
    return this.store.getLastEpoch() === runtimeEpoch;
  }

  private async releaseResult(
    graceful: boolean,
    resolved: ResolvedRuntimeState,
    now: Date
  ): Promise<{ worldId: string; releasedAt: string; graceful: boolean; nextHostUuid: string | null; nextHostPlayerName: string | null }> {
    await this.afterStateChange(now);
    const status = toRuntimeStatus(this.worldId, resolved.runtime, resolved.candidate, resolved.warning);
    return {
      worldId: this.worldId,
      releasedAt: now.toISOString(),
      graceful,
      nextHostUuid: graceful ? status.candidateUuid : null,
      nextHostPlayerName: graceful ? status.candidatePlayerName : null
    };
  }

  private async publishPresence(now: Date): Promise<void> {
    const players = this.roomPlayers(now);
    const fingerprint = JSON.stringify(players);
    if (fingerprint === this.lastPresenceFingerprint) {
      return;
    }
    this.lastPresenceFingerprint = fingerprint;
    await this.effects.mirrorPresence(this.worldId, players);
    await this.effects.publish({ worldId: this.worldId, kind: "presence-changed", roomPlayers: players });
  }

  /**
   * Runs after every externally visible operation: mirrors runtime state to
   * D1 for summaries/legacy reads, pushes runtime-changed when the public
   * status materially changed, and re-arms the single alarm at the earliest
   * relevant deadline.
   */
  private async afterStateChange(now: Date): Promise<void> {
    const runtime = this.store.getRuntime();
    const memberships = await this.effects.listMemberships(this.worldId);
    const candidate = choosePreferredCandidate(
      this.store.listWaiters().filter((waiter) => waiter.waiting),
      memberships
    );
    const status = toRuntimeStatus(this.worldId, runtime, candidate, this.store.getWarning());
    const fingerprint = statusFingerprint(status);
    if (fingerprint !== this.lastPublishedFingerprint) {
      this.lastPublishedFingerprint = fingerprint;
      await this.effects.mirrorRuntime(this.worldId, status);
      await this.effects.publish({ worldId: this.worldId, kind: "runtime-changed", runtime: status });
    }
    await this.effects.scheduleAlarm(this.nextDeadline(now));
  }

  private nextDeadline(now: Date): Date | null {
    const candidates: number[] = [];
    const runtime = this.store.getRuntime();
    if (runtime != null) {
      const deadline = phaseDeadlineOf(runtime);
      if (deadline != null) {
        candidates.push(deadline.getTime());
      }
      const link = this.store.getHostLink();
      if (link.graceDeadlineAt != null) {
        candidates.push(new Date(link.graceDeadlineAt).getTime());
      }
    }
    for (const waiter of this.store.listWaiters()) {
      candidates.push(new Date(waiter.updatedAt).getTime() + HANDOFF_WAITER_TIMEOUT_MS);
    }
    for (const entry of this.store.listLegacyPresence()) {
      candidates.push(new Date(entry.expiresAt).getTime());
    }
    if (candidates.length === 0) {
      return null;
    }
    return new Date(Math.max(Math.min(...candidates), now.getTime() + 1_000));
  }

  private lastPublishedFingerprint: string | null = null;
  private lastPresenceFingerprint: string | null = null;
}

// ---------------------------------------------------------------- helpers

function immediateEntryKind(resolved: ResolvedRuntimeState, playerUuid: string) {
  if (runtimeAllowsDirectConnect(resolved)) {
    return { kind: "connect" as const };
  }
  const assignment = hostAssignmentForCurrentRuntime(resolved, playerUuid);
  if (assignment != null) {
    return { kind: "current-host" as const, assignment };
  }
  return null;
}

function epochBaseline(resolved: ResolvedRuntimeState): Pick<WorldRuntimeRecord, "runtimeEpoch"> | null {
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

function candidateFromRuntime(runtime: WorldRuntimeRecord): RuntimeCandidate | null {
  if (runtime.candidateUuid == null || runtime.hostUuid == null || runtime.hostPlayerName == null) {
    return null;
  }
  if (runtime.candidateUuid !== runtime.hostUuid) {
    return null;
  }
  return { playerUuid: runtime.candidateUuid, playerName: runtime.hostPlayerName };
}

function finalizationResult(
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

function leaseDeadlinePassed(runtime: WorldRuntimeRecord, now: Date): boolean {
  const deadline = phaseDeadlineOf(runtime);
  return deadline != null && deadline.getTime() <= now.getTime();
}

function phaseDeadlineOf(runtime: WorldRuntimeRecord): Date | null {
  if (runtime.phase === "host-finalizing") {
    const lastActivityAt = runtime.lastProgressAt ?? runtime.updatedAt;
    return lastActivityAt ? new Date(new Date(lastActivityAt).getTime() + HOST_LEASE_TIMEOUT_MS) : null;
  }
  const raw = runtime.phase === "host-starting"
    ? runtime.startupDeadlineAt
    : runtime.phase === "host-live"
    ? runtime.expiresAt
    : null;
  return raw ? new Date(raw) : null;
}

/** Drop per-request churn (updatedAt) so heartbeats do not spam events. */
function statusFingerprint(status: WorldRuntimeStatus): string {
  const { updatedAt, ...rest } = status;
  void updatedAt;
  return JSON.stringify(rest);
}
