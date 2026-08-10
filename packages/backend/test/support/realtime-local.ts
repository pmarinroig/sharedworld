import * as fs from "node:fs";

import type { RealtimeEvent, RoomPlayer, WorldRuntimeStatus } from "../../../shared/src/index.ts";

import { WorldCoordinator, type CoordinatorEffects } from "../../src/realtime/coordinator.ts";
import type { CoordinatorHandle, RealtimeService } from "../../src/realtime/service.ts";
import type { SharedWorldRepository } from "../../src/repository.ts";
import type { RuntimeMembership, RuntimeWaiter, WorldRuntimeRecord } from "../../src/runtime-protocol.ts";
import { InMemoryCoordinatorStore } from "./realtime.ts";

interface LocalWorldRealtime {
  coordinator: WorldCoordinator;
  store: InMemoryCoordinatorStore;
  alarmAt: Date | null;
  alarmTimer: ReturnType<typeof setTimeout> | null;
  persistingHandle: CoordinatorHandle | null;
}

/** JSON snapshot of one world's coordinator state (chaos-drill persistence). */
interface PersistedWorldState {
  runtime: ReturnType<InMemoryCoordinatorStore["getRuntime"]>;
  warning: ReturnType<InMemoryCoordinatorStore["getWarning"]>;
  lastEpoch: number;
  waiters: RuntimeWaiter[];
  roomPlayers: ReturnType<InMemoryCoordinatorStore["getRoomPlayers"]>;
  legacyPresence: ReturnType<InMemoryCoordinatorStore["listLegacyPresence"]>;
  hostLink: ReturnType<InMemoryCoordinatorStore["getHostLink"]>;
  alarmAt: string | null;
  hostWatch: string | null;
}

/** What the harness's Bun WebSocket server reports about live sockets. */
export interface SocketBridge {
  isConnected(playerUuid: string): boolean;
  lastSeenAt(playerUuid: string): Date | null;
}

/**
 * In-process RealtimeService for bun tests and the integration harness: the
 * REAL WorldCoordinator logic per world, backed by the same repository the
 * service under test uses (mirrors land in D1 exactly like production).
 * Sockets do not exist here — publishes are recorded (and forwarded to an
 * optional sink so the integration server can bridge them to WebSockets),
 * host watches report "not connected", and alarms are recorded so tests can
 * fire them deterministically via fireAlarm().
 */
export class LocalRealtimeService implements RealtimeService {
  private readonly worlds = new Map<string, LocalWorldRealtime>();
  /** worldId → hostUuid whose socket state the coordinator wants reported. */
  private readonly hostWatches = new Map<string, string>();
  readonly published: Array<{ event: RealtimeEvent; recipients: string[] | undefined }> = [];
  /** Optional bridge for the integration harness (WS delivery). */
  onPublish: ((event: RealtimeEvent, recipients: string[] | undefined) => void) | null = null;
  /** Live-socket facts, provided by the harness server when one exists. */
  socketBridge: SocketBridge | null = null;
  /** Real-time alarm timers (integration harness only; unit tests fire manually). */
  private alarmTimersEnabled = false;

  /**
   * @param persistDir when set, every world's coordinator state is written
   * as JSON after each call and restored on first touch after a restart —
   * the harness stand-in for Durable Object storage surviving a deploy.
   */
  constructor(
    private readonly repository: SharedWorldRepository,
    private readonly persistDir: string | null = null
  ) {}

  /**
   * Run alarms on real timers, like the DO runtime would. Only the harness
   * server enables this — unit tests keep frozen clocks and fireAlarm().
   */
  enableAlarmTimers(): void {
    this.alarmTimersEnabled = true;
  }

  /**
   * Rehydrate every persisted world at boot. Faithful to production: DO
   * alarms survive deploys and re-fire without any incoming traffic, so the
   * harness re-arms them eagerly instead of waiting for a request.
   */
  restorePersistedWorlds(): void {
    if (this.persistDir == null || !fs.existsSync(this.persistDir)) {
      return;
    }
    for (const file of fs.readdirSync(this.persistDir)) {
      if (file.endsWith(".json")) {
        this.world(file.slice(0, -".json".length));
      }
    }
  }

  /** The harness server reports socket open/close for a player. */
  async socketStateChanged(playerUuid: string, connected: boolean, now: Date): Promise<void> {
    for (const [worldId, hostUuid] of this.hostWatches) {
      if (hostUuid !== playerUuid) {
        continue;
      }
      const coordinator = this.world(worldId).coordinator;
      if (connected) {
        await coordinator.hostSocketConnected(playerUuid, now);
      } else {
        await coordinator.hostSocketClosed(playerUuid, now);
      }
    }
  }

  /** Bridge for world-presence frames — mirrors the gateway's coordinator poke. */
  async reportSocketPresence(worldId: string, playerUuid: string, present: boolean, now: Date): Promise<void> {
    const entry = this.world(worldId);
    await entry.coordinator.reportSocketPresence(playerUuid, present, now);
    this.persistWorld(worldId, entry);
  }

  /** Bridge for a presence player's last socket closing. */
  async presenceSocketClosed(worldId: string, playerUuid: string, now: Date): Promise<void> {
    const entry = this.world(worldId);
    await entry.coordinator.presenceSocketClosed(playerUuid, now);
    this.persistWorld(worldId, entry);
  }

  coordinator(worldId: string): CoordinatorHandle {
    const entry = this.world(worldId);
    if (this.persistDir == null) {
      return entry.coordinator;
    }
    if (entry.persistingHandle == null) {
      // Persist after every coordinator call — the calls are the only
      // mutation entry points, so this snapshots exactly like DO storage
      // committing at the end of each event.
      const handle: Record<string, (...args: unknown[]) => Promise<unknown>> = {};
      const target = entry.coordinator as unknown as Record<string, (...args: unknown[]) => Promise<unknown>>;
      for (const method of Object.getOwnPropertyNames(Object.getPrototypeOf(entry.coordinator))) {
        if (typeof target[method] !== "function" || method === "constructor") {
          continue;
        }
        handle[method] = async (...args: unknown[]) => {
          try {
            return await target[method].apply(entry.coordinator, args);
          } finally {
            this.persistWorld(worldId, entry);
          }
        };
      }
      entry.persistingHandle = handle as unknown as CoordinatorHandle;
    }
    return entry.persistingHandle;
  }

  async notifyUsers(event: RealtimeEvent, recipients: string[]): Promise<void> {
    await this.record(event, recipients);
  }

  /**
   * WebSocket upgrades are served by the integration server's Bun bridge,
   * which owns the sockets; the in-process service only records intent.
   */
  async connect(): Promise<Response> {
    return new Response("realtime connect is handled by the harness server", { status: 501 });
  }

  /** Fire the world's pending alarm (if any), like the DO runtime would. */
  async fireAlarm(worldId: string, now: Date): Promise<void> {
    const world = this.world(worldId);
    if (world.alarmAt == null) {
      return;
    }
    world.alarmAt = null;
    await world.coordinator.onAlarm(now);
  }

  alarmAt(worldId: string): Date | null {
    return this.world(worldId).alarmAt;
  }

  store(worldId: string): InMemoryCoordinatorStore {
    return this.world(worldId).store;
  }

  eventsOfKind(kind: RealtimeEvent["kind"]): RealtimeEvent[] {
    return this.published.filter((entry) => entry.event.kind === kind).map((entry) => entry.event);
  }

  // ---- typed seams for tests that used to seed/read D1 runtime rows ----

  /** Seed the world's coordinator store with a runtime record. */
  seedRuntime(runtime: WorldRuntimeRecord): void {
    this.world(runtime.worldId).store.putRuntime(runtime);
  }

  runtimeRecord(worldId: string): WorldRuntimeRecord | null {
    return this.world(worldId).store.getRuntime();
  }

  lastRuntimeEpoch(worldId: string): number {
    return this.world(worldId).store.getLastEpoch();
  }

  seedWaiter(worldId: string, waiter: RuntimeWaiter): void {
    this.world(worldId).store.upsertWaiter(waiter);
  }

  waiters(worldId: string): RuntimeWaiter[] {
    return this.world(worldId).store.listWaiters();
  }

  seedWarning(worldId: string, warning: NonNullable<ReturnType<InMemoryCoordinatorStore["getWarning"]>>): void {
    this.world(worldId).store.setWarning(warning);
  }

  warning(worldId: string): ReturnType<InMemoryCoordinatorStore["getWarning"]> {
    return this.world(worldId).store.getWarning();
  }

  /** Fenced delete + epoch high-water bump, like a release would do. */
  deleteRuntime(worldId: string, expected: { runtimeEpoch: number; runtimeToken: string | null }): boolean {
    const store = this.world(worldId).store;
    const current = store.getRuntime();
    if (current == null || current.runtimeEpoch !== expected.runtimeEpoch || current.runtimeToken !== expected.runtimeToken) {
      return false;
    }
    store.deleteRuntime();
    store.setLastEpoch(Math.max(store.getLastEpoch(), current.runtimeEpoch));
    return true;
  }

  private async record(event: RealtimeEvent, recipients: string[] | undefined): Promise<void> {
    // The harness bridge needs concrete recipients to deliver frames;
    // undefined means "current active members", so resolve it here.
    const resolved = recipients
      ?? (await this.repository.listMemberships(event.worldId))
        .filter((member) => member.deletedAt == null)
        .map((member) => member.playerUuid);
    this.published.push({ event, recipients });
    this.onPublish?.(event, resolved);
  }

  /** Snapshot one world's coordinator state to disk (chaos persistence). */
  private persistWorld(worldId: string, entry: LocalWorldRealtime): void {
    if (this.persistDir == null) {
      return;
    }
    const state: PersistedWorldState = {
      runtime: entry.store.getRuntime(),
      warning: entry.store.getWarning(),
      lastEpoch: entry.store.getLastEpoch(),
      waiters: entry.store.listWaiters(),
      roomPlayers: entry.store.getRoomPlayers(),
      legacyPresence: entry.store.listLegacyPresence(),
      hostLink: entry.store.getHostLink(),
      alarmAt: entry.alarmAt == null ? null : entry.alarmAt.toISOString(),
      hostWatch: this.hostWatches.get(worldId) ?? null
    };
    fs.mkdirSync(this.persistDir, { recursive: true });
    fs.writeFileSync(`${this.persistDir}/${worldId}.json`, JSON.stringify(state));
  }

  private restoreWorld(worldId: string, entry: LocalWorldRealtime): void {
    if (this.persistDir == null) {
      return;
    }
    const path = `${this.persistDir}/${worldId}.json`;
    if (!fs.existsSync(path)) {
      return;
    }
    const state = JSON.parse(fs.readFileSync(path, "utf8")) as PersistedWorldState;
    if (state.runtime != null) {
      entry.store.putRuntime(state.runtime);
    }
    if (state.warning != null) {
      entry.store.setWarning(state.warning);
    }
    entry.store.setLastEpoch(state.lastEpoch);
    for (const waiter of state.waiters) {
      entry.store.upsertWaiter(waiter);
    }
    entry.store.setRoomPlayers(state.roomPlayers);
    for (const legacy of state.legacyPresence) {
      entry.store.upsertLegacyPresence(legacy);
    }
    entry.store.setHostLink(state.hostLink);
    if (state.hostWatch != null) {
      this.hostWatches.set(worldId, state.hostWatch);
    }
    if (state.alarmAt != null) {
      // Like a Durable Object waking after a deploy: a missed alarm runs
      // now, a future one is re-armed.
      const at = new Date(state.alarmAt);
      entry.alarmAt = at;
      if (this.alarmTimersEnabled) {
        entry.alarmTimer = setTimeout(() => {
          entry.alarmTimer = null;
          void this.fireAlarm(worldId, new Date());
        }, Math.max(0, at.getTime() - Date.now()));
      }
    }
  }

  private world(worldId: string): LocalWorldRealtime {
    const existing = this.worlds.get(worldId);
    if (existing != null) {
      return existing;
    }
    const store = new InMemoryCoordinatorStore();
    const entry: LocalWorldRealtime = {
      coordinator: null as unknown as WorldCoordinator,
      store,
      alarmAt: null,
      alarmTimer: null,
      persistingHandle: null
    };
    const effects: CoordinatorEffects = {
      listMemberships: async (id: string): Promise<RuntimeMembership[]> => this.repository.listMemberships(id),
      mirrorRuntime: async (id: string, status: WorldRuntimeStatus): Promise<void> => {
        await this.repository.upsertRuntimeMirror(id, JSON.stringify(status), null);
      },
      mirrorPresence: async (id: string, players: RoomPlayer[]): Promise<void> => {
        await this.repository.upsertRuntimeMirror(id, null, JSON.stringify(players));
      },
      publish: async (event: RealtimeEvent, recipients?: string[]): Promise<void> => {
        await this.record(event, recipients);
      },
      scheduleAlarm: async (at: Date | null): Promise<void> => {
        entry.alarmAt = at;
        if (!this.alarmTimersEnabled) {
          return;
        }
        if (entry.alarmTimer != null) {
          clearTimeout(entry.alarmTimer);
          entry.alarmTimer = null;
        }
        if (at != null) {
          entry.alarmTimer = setTimeout(() => {
            entry.alarmTimer = null;
            void this.fireAlarm(worldId, new Date());
          }, Math.max(0, at.getTime() - Date.now()));
        }
      },
      setHostWatch: async (hostUuid: string, watching: boolean): Promise<boolean> => {
        if (watching) {
          this.hostWatches.set(worldId, hostUuid);
          return this.socketBridge?.isConnected(hostUuid) ?? false;
        }
        if (this.hostWatches.get(worldId) === hostUuid) {
          this.hostWatches.delete(worldId);
        }
        return false;
      },
      probeHostReachability: async (hostUuid: string): Promise<Date | null> =>
        this.socketBridge?.lastSeenAt(hostUuid) ?? null
    };
    entry.coordinator = new WorldCoordinator(worldId, store, effects);
    this.restoreWorld(worldId, entry);
    this.worlds.set(worldId, entry);
    return entry;
  }
}
