import type { RealtimeEvent, RoomPlayer, UncleanShutdownWarning, WorldRuntimeStatus } from "../../../shared/src/index.ts";

import {
  WorldCoordinator,
  type CoordinatorEffects,
  type CoordinatorStore,
  type LegacyPresenceEntry,
  type SessionActor
} from "../../src/realtime/coordinator.ts";
import type { RuntimeMembership, RuntimeWaiter, WorldRuntimeRecord } from "../../src/runtime-protocol.ts";

/** Plain-object store; the coordinator is single-threaded so this is exact. */
export class InMemoryCoordinatorStore implements CoordinatorStore {
  runtime: WorldRuntimeRecord | null = null;
  warning: UncleanShutdownWarning | null = null;
  lastEpoch = 0;
  waiters = new Map<string, RuntimeWaiter>();
  roomPlayers: RoomPlayer[] | null = null;
  legacyPresence = new Map<string, LegacyPresenceEntry>();
  hostLink: { connected: boolean; graceDeadlineAt: string | null } = { connected: false, graceDeadlineAt: null };

  getRuntime() { return this.runtime; }
  putRuntime(runtime: WorldRuntimeRecord) { this.runtime = runtime; }
  deleteRuntime() { this.runtime = null; }
  getWarning() { return this.warning; }
  setWarning(warning: UncleanShutdownWarning) { this.warning = warning; }
  clearWarning() { this.warning = null; }
  getLastEpoch() { return this.lastEpoch; }
  setLastEpoch(epoch: number) { this.lastEpoch = epoch; }
  listWaiters() { return [...this.waiters.values()]; }
  upsertWaiter(waiter: RuntimeWaiter) { this.waiters.set(waiter.playerUuid, waiter); }
  deleteWaiter(playerUuid: string) { this.waiters.delete(playerUuid); }
  clearWaiters() { this.waiters.clear(); }
  getRoomPlayers() { return this.roomPlayers; }
  setRoomPlayers(players: RoomPlayer[] | null) { this.roomPlayers = players; }
  listLegacyPresence() { return [...this.legacyPresence.values()]; }
  upsertLegacyPresence(entry: LegacyPresenceEntry) { this.legacyPresence.set(entry.playerUuid, entry); }
  deleteLegacyPresence(playerUuid: string) { this.legacyPresence.delete(playerUuid); }
  clearLegacyPresence() { this.legacyPresence.clear(); }
  getHostLink() { return this.hostLink; }
  setHostLink(link: { connected: boolean; graceDeadlineAt: string | null }) { this.hostLink = link; }
  clearAll() {
    this.runtime = null;
    this.warning = null;
    this.lastEpoch = 0;
    this.waiters.clear();
    this.roomPlayers = null;
    this.legacyPresence.clear();
    this.hostLink = { connected: false, graceDeadlineAt: null };
  }
}

export class RecordingEffects implements CoordinatorEffects {
  memberships: RuntimeMembership[] = [];
  published: Array<{ event: RealtimeEvent; recipients: string[] | undefined }> = [];
  mirroredRuntimes: WorldRuntimeStatus[] = [];
  mirroredPresence: RoomPlayer[][] = [];
  alarmAt: Date | null = null;
  hostWatches: Array<{ hostUuid: string; watching: boolean }> = [];
  /** What setHostWatch reports back as the host's current socket state. */
  hostSocketConnected = false;
  /** What probeHostReachability answers; null = unknown/never seen. */
  lastKeepaliveAt: Date | null = null;

  async listMemberships(): Promise<RuntimeMembership[]> {
    return this.memberships;
  }
  async mirrorRuntime(_worldId: string, status: WorldRuntimeStatus): Promise<void> {
    this.mirroredRuntimes.push(status);
  }
  async mirrorPresence(_worldId: string, players: RoomPlayer[]): Promise<void> {
    this.mirroredPresence.push(players);
  }
  async publish(event: RealtimeEvent, recipients?: string[]): Promise<void> {
    this.published.push({ event, recipients });
  }
  async scheduleAlarm(at: Date | null): Promise<void> {
    this.alarmAt = at;
  }
  async setHostWatch(hostUuid: string, watching: boolean): Promise<boolean> {
    this.hostWatches.push({ hostUuid, watching });
    return watching ? this.hostSocketConnected : false;
  }
  async probeHostReachability(): Promise<Date | null> {
    return this.lastKeepaliveAt;
  }

  eventsOfKind(kind: RealtimeEvent["kind"]): RealtimeEvent[] {
    return this.published.filter((entry) => entry.event.kind === kind).map((entry) => entry.event);
  }
}

export function member(playerUuid: string, playerName: string, role: "owner" | "member", joinedAt: string): RuntimeMembership {
  return { playerUuid, playerName, role, joinedAt, deletedAt: null };
}

export function actor(playerUuid: string, playerName: string, overrides: Partial<SessionActor> = {}): SessionActor {
  return { playerUuid, playerName, membershipActive: true, everMember: true, ...overrides };
}

export interface CoordinatorHarness {
  coordinator: WorldCoordinator;
  store: InMemoryCoordinatorStore;
  effects: RecordingEffects;
}

export function makeCoordinator(worldId = "world-1"): CoordinatorHarness {
  const store = new InMemoryCoordinatorStore();
  const effects = new RecordingEffects();
  const coordinator = new WorldCoordinator(worldId, store, effects);
  return { coordinator, store, effects };
}
