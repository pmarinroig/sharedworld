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
  readonly published: Array<{ event: RealtimeEvent; recipients: string[] | undefined }> = [];
  /** Optional bridge for the integration harness (WS delivery). */
  onPublish: ((event: RealtimeEvent, recipients: string[] | undefined) => void) | null = null;

  constructor(private readonly repository: SharedWorldRepository) {}

  coordinator(worldId: string): CoordinatorHandle {
    return this.world(worldId).coordinator;
  }

  async notifyUsers(event: RealtimeEvent, recipients: string[]): Promise<void> {
    this.record(event, recipients);
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

  private record(event: RealtimeEvent, recipients: string[] | undefined): void {
    this.published.push({ event, recipients });
    this.onPublish?.(event, recipients);
  }

  private world(worldId: string): LocalWorldRealtime {
    const existing = this.worlds.get(worldId);
    if (existing != null) {
      return existing;
    }
    const store = new InMemoryCoordinatorStore();
    const entry: LocalWorldRealtime = { coordinator: null as unknown as WorldCoordinator, store, alarmAt: null };
    const effects: CoordinatorEffects = {
      listMemberships: async (id: string): Promise<RuntimeMembership[]> => this.repository.listMemberships(id),
      mirrorRuntime: async (id: string, status: WorldRuntimeStatus): Promise<void> => {
        await this.repository.upsertRuntimeMirror(id, JSON.stringify(status), null);
      },
      mirrorPresence: async (id: string, players: RoomPlayer[]): Promise<void> => {
        await this.repository.upsertRuntimeMirror(id, null, JSON.stringify(players));
      },
      publish: async (event: RealtimeEvent, recipients?: string[]): Promise<void> => {
        this.record(event, recipients);
      },
      scheduleAlarm: async (at: Date | null): Promise<void> => {
        entry.alarmAt = at;
      },
      setHostWatch: async (): Promise<boolean> => false,
      probeHostReachability: async (): Promise<Date | null> => null
    };
    entry.coordinator = new WorldCoordinator(worldId, store, effects);
    this.worlds.set(worldId, entry);
    return entry;
  }
}
