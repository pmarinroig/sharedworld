import {
  REALTIME_KEEPALIVE_REQUEST,
  REALTIME_KEEPALIVE_RESPONSE,
  REALTIME_PROTOCOL_VERSION,
  type RealtimeClientFrame,
  type RealtimeEvent,
  type RealtimeServerFrame,
  type RoomPlayer,
  type UncleanShutdownWarning,
  type WorldRuntimeStatus
} from "../../../shared/src/index.ts";

import { D1SharedWorldRepository } from "../d1-repository.ts";
import type { Env } from "../env.ts";
import {
  WorldCoordinator,
  type CoordinatorEffects,
  type CoordinatorStore,
  type LegacyPresenceEntry
} from "./coordinator.ts";
import { decodeCallBody, toErrorEnvelope } from "./service.ts";
import type { RuntimeMembership, RuntimeWaiter, WorldRuntimeRecord } from "../runtime-protocol.ts";

/**
 * The two Durable Object shells. All protocol logic lives in the injected
 * classes (WorldCoordinator); the shells own exactly three things: storage
 * plumbing, the call envelope, and WebSocket lifecycle.
 *
 * Every logic call is serialized through a promise-chain mutex: DO input
 * gates open while awaiting external I/O (D1 reads, gateway calls), so
 * without the mutex two requests could interleave inside one coordinator
 * method and break the single-threaded-per-world reasoning.
 */
class CallSerializer {
  private tail: Promise<unknown> = Promise.resolve();

  run<T>(fn: () => Promise<T>): Promise<T> {
    const next = this.tail.then(fn, fn);
    this.tail = next.catch(() => undefined);
    return next;
  }
}

// ------------------------------------------------------------- coordinator

/** CoordinatorStore over the DO's synchronous SQLite, as JSON kv rows. */
class SqlCoordinatorStore implements CoordinatorStore {
  constructor(private readonly sql: SqlStorage) {
    this.sql.exec("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
  }

  private read<T>(key: string): T | null {
    const row = this.sql.exec("SELECT value FROM kv WHERE key = ?", key).toArray()[0];
    return row == null ? null : (JSON.parse(String(row.value)) as T);
  }

  private write(key: string, value: unknown): void {
    this.sql.exec(
      "INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
      key,
      JSON.stringify(value)
    );
  }

  private remove(key: string): void {
    this.sql.exec("DELETE FROM kv WHERE key = ?", key);
  }

  getRuntime() { return this.read<WorldRuntimeRecord>("runtime"); }
  putRuntime(runtime: WorldRuntimeRecord) { this.write("runtime", runtime); }
  deleteRuntime() { this.remove("runtime"); }
  getWarning() { return this.read<UncleanShutdownWarning>("warning"); }
  setWarning(warning: UncleanShutdownWarning) { this.write("warning", warning); }
  clearWarning() { this.remove("warning"); }
  getLastEpoch() { return this.read<number>("lastEpoch") ?? 0; }
  setLastEpoch(epoch: number) { this.write("lastEpoch", epoch); }
  listWaiters() { return this.read<RuntimeWaiter[]>("waiters") ?? []; }
  upsertWaiter(waiter: RuntimeWaiter) {
    const waiters = this.listWaiters().filter((entry) => entry.playerUuid !== waiter.playerUuid);
    waiters.push(waiter);
    this.write("waiters", waiters);
  }
  deleteWaiter(playerUuid: string) {
    this.write("waiters", this.listWaiters().filter((entry) => entry.playerUuid !== playerUuid));
  }
  clearWaiters() { this.remove("waiters"); }
  getRoomPlayers() { return this.read<RoomPlayer[]>("roomPlayers"); }
  setRoomPlayers(players: RoomPlayer[] | null) {
    if (players == null) {
      this.remove("roomPlayers");
    } else {
      this.write("roomPlayers", players);
    }
  }
  listLegacyPresence() { return this.read<LegacyPresenceEntry[]>("legacyPresence") ?? []; }
  upsertLegacyPresence(entry: LegacyPresenceEntry) {
    const entries = this.listLegacyPresence().filter((existing) => existing.playerUuid !== entry.playerUuid);
    entries.push(entry);
    this.write("legacyPresence", entries);
  }
  deleteLegacyPresence(playerUuid: string) {
    this.write("legacyPresence", this.listLegacyPresence().filter((entry) => entry.playerUuid !== playerUuid));
  }
  clearLegacyPresence() { this.remove("legacyPresence"); }
  getHostLink() {
    return this.read<{ connected: boolean; graceDeadlineAt: string | null }>("hostLink")
      ?? { connected: false, graceDeadlineAt: null };
  }
  setHostLink(link: { connected: boolean; graceDeadlineAt: string | null }) { this.write("hostLink", link); }
  clearAll() { this.sql.exec("DELETE FROM kv"); }
}

class DoCoordinatorEffects implements CoordinatorEffects {
  constructor(
    private readonly env: Env,
    private readonly storage: DurableObjectStorage,
    private readonly worldId: string
  ) {}

  private repository(): D1SharedWorldRepository {
    if (!this.env.DB) {
      throw new Error("SharedWorld coordinator requires the D1 binding (DB).");
    }
    return new D1SharedWorldRepository(this.env.DB);
  }

  private gateway(playerUuid: string) {
    const namespace = this.env.USER_GATEWAY;
    if (!namespace) {
      throw new Error("SharedWorld coordinator requires the USER_GATEWAY binding.");
    }
    return namespace.get(namespace.idFromName(playerUuid));
  }

  async listMemberships(worldId: string): Promise<RuntimeMembership[]> {
    return this.repository().listMemberships(worldId);
  }

  async mirrorRuntime(worldId: string, status: WorldRuntimeStatus): Promise<void> {
    await this.repository().upsertRuntimeMirror(worldId, JSON.stringify(status), null);
  }

  async mirrorPresence(worldId: string, players: RoomPlayer[]): Promise<void> {
    await this.repository().upsertRuntimeMirror(worldId, null, JSON.stringify(players));
  }

  async publish(event: RealtimeEvent, recipients?: string[]): Promise<void> {
    const targets = recipients
      ?? (await this.listMemberships(event.worldId))
        .filter((member) => member.deletedAt == null)
        .map((member) => member.playerUuid);
    const frame: RealtimeServerFrame = { v: REALTIME_PROTOCOL_VERSION, type: "event", event };
    await Promise.allSettled(targets.map(async (playerUuid) => {
      await this.gateway(playerUuid).fetch("https://do/notify", {
        method: "POST",
        body: JSON.stringify({ frame })
      });
    }));
  }

  async scheduleAlarm(at: Date | null): Promise<void> {
    if (at == null) {
      await this.storage.deleteAlarm();
    } else {
      await this.storage.setAlarm(at);
    }
  }

  async setHostWatch(hostUuid: string, watching: boolean): Promise<boolean> {
    const response = await this.gateway(hostUuid).fetch("https://do/watch", {
      method: "POST",
      body: JSON.stringify({ worldId: this.worldId, watching })
    });
    const body = await response.json() as { connected: boolean };
    return body.connected;
  }

  async probeHostReachability(hostUuid: string): Promise<Date | null> {
    const response = await this.gateway(hostUuid).fetch("https://do/probe");
    const body = await response.json() as { lastSeenAt: string | null };
    return body.lastSeenAt == null ? null : new Date(body.lastSeenAt);
  }
}

export class WorldCoordinatorDO {
  private readonly serializer = new CallSerializer();
  private coordinatorInstance: WorldCoordinator | null = null;

  constructor(
    private readonly ctx: DurableObjectState,
    private readonly env: Env
  ) {}

  private coordinator(): WorldCoordinator {
    if (this.coordinatorInstance == null) {
      const worldId = this.ctx.id.name;
      if (worldId == null) {
        throw new Error("WorldCoordinatorDO must be addressed by world id (idFromName).");
      }
      this.coordinatorInstance = new WorldCoordinator(
        worldId,
        new SqlCoordinatorStore(this.ctx.storage.sql),
        new DoCoordinatorEffects(this.env, this.ctx.storage, worldId)
      );
    }
    return this.coordinatorInstance;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname !== "/call" || request.method !== "POST") {
      return new Response("not found", { status: 404 });
    }
    const { method, args } = decodeCallBody(await request.text());
    return this.serializer.run(async () => {
      try {
        const coordinator = this.coordinator() as unknown as Record<string, (...callArgs: unknown[]) => Promise<unknown>>;
        const fn = coordinator[method];
        if (typeof fn !== "function") {
          return Response.json({ error: { status: 500, code: "internal_error", message: `Unknown coordinator method ${method}.` } }, { status: 500 });
        }
        const result = await fn.apply(coordinator, args);
        return Response.json({ ok: result ?? null });
      } catch (error) {
        return Response.json(toErrorEnvelope(error), { status: 200 });
      }
    });
  }

  async alarm(): Promise<void> {
    await this.serializer.run(() => this.coordinator().onAlarm(new Date()));
  }
}

// ----------------------------------------------------------------- gateway

interface GatewayAttachment {
  playerUuid: string;
  connectedAt: string;
}

/**
 * One hibernated socket per player. Watches (world ids whose coordinator
 * wants host-socket signals) persist in storage; everything else derives
 * from the live sockets. The gateway never awaits a coordinator response
 * inside a request the coordinator is itself awaiting (watch/probe answer
 * from local state only), which keeps DO-to-DO calls cycle-free.
 */
export class UserGatewayDO {
  private readonly serializer = new CallSerializer();

  constructor(
    private readonly ctx: DurableObjectState,
    private readonly env: Env
  ) {}

  private playerUuid(): string {
    const name = this.ctx.id.name;
    if (name == null) {
      throw new Error("UserGatewayDO must be addressed by player uuid (idFromName).");
    }
    return name;
  }

  private async watches(): Promise<string[]> {
    return (await this.ctx.storage.get<string[]>("watches")) ?? [];
  }

  private hasSocket(): boolean {
    // readyState 1 = OPEN; a socket mid-close can still appear in the list.
    return this.ctx.getWebSockets().some((ws) => ws.readyState === 1);
  }

  private lastSeenAt(): string | null {
    let latest: number | null = null;
    for (const ws of this.ctx.getWebSockets()) {
      const attachment = ws.deserializeAttachment() as GatewayAttachment | null;
      const candidates = [
        this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime() ?? null,
        attachment != null ? new Date(attachment.connectedAt).getTime() : null
      ];
      for (const value of candidates) {
        if (value != null && (latest == null || value > latest)) {
          latest = value;
        }
      }
    }
    return latest == null ? null : new Date(latest).toISOString();
  }

  private coordinatorStub(worldId: string) {
    const namespace = this.env.WORLD_COORDINATOR;
    if (!namespace) {
      throw new Error("SharedWorld gateway requires the WORLD_COORDINATOR binding.");
    }
    return namespace.get(namespace.idFromName(worldId));
  }

  private async callCoordinator(worldId: string, method: string, args: unknown[]): Promise<void> {
    await this.coordinatorStub(worldId).fetch("https://do/call", {
      method: "POST",
      body: JSON.stringify({ method, args })
    });
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/connect") {
      if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
        return new Response("expected websocket", { status: 426 });
      }
      const pair = new WebSocketPair();
      const client = pair[0];
      const server = pair[1];
      this.ctx.acceptWebSocket(server);
      server.serializeAttachment({
        playerUuid: this.playerUuid(),
        connectedAt: new Date().toISOString()
      } satisfies GatewayAttachment);
      this.ctx.setWebSocketAutoResponse(
        new WebSocketRequestResponsePair(REALTIME_KEEPALIVE_REQUEST, REALTIME_KEEPALIVE_RESPONSE)
      );
      server.send(JSON.stringify({ v: REALTIME_PROTOCOL_VERSION, type: "welcome" } satisfies RealtimeServerFrame));
      const watches = await this.watches();
      const now = new Date();
      for (const worldId of watches) {
        await this.serializer.run(() => this.callCoordinator(worldId, "hostSocketConnected", [this.playerUuid(), now]));
      }
      return new Response(null, { status: 101, webSocket: client });
    }
    if (url.pathname === "/notify" && request.method === "POST") {
      const { frame } = await request.json() as { frame: RealtimeServerFrame };
      const encoded = JSON.stringify(frame);
      for (const ws of this.ctx.getWebSockets()) {
        try {
          ws.send(encoded);
        } catch {
          // A dying socket surfaces via webSocketClose/Error; drop the frame.
        }
      }
      return Response.json({ delivered: this.hasSocket() });
    }
    if (url.pathname === "/watch" && request.method === "POST") {
      const { worldId, watching } = await request.json() as { worldId: string; watching: boolean };
      const watches = await this.watches();
      const next = watching
        ? [...new Set([...watches, worldId])].filter((id) => id.length > 0)
        : watches.filter((id) => id !== worldId);
      await this.ctx.storage.put("watches", next);
      return Response.json({ connected: this.hasSocket() });
    }
    if (url.pathname === "/probe") {
      return Response.json({ lastSeenAt: this.lastSeenAt() });
    }
    return new Response("not found", { status: 404 });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string") {
      return;
    }
    let frame: RealtimeClientFrame;
    try {
      frame = JSON.parse(message) as RealtimeClientFrame;
    } catch {
      return;
    }
    if (frame.type === "host-players" && typeof frame.worldId === "string") {
      const attachment = ws.deserializeAttachment() as GatewayAttachment;
      await this.serializer.run(() => this.callCoordinator(frame.worldId, "reportHostPlayers", [
        attachment.playerUuid,
        frame.runtimeEpoch,
        frame.players ?? [],
        new Date()
      ]));
    }
  }

  async webSocketClose(): Promise<void> {
    await this.reportClosedIfLastSocket();
  }

  async webSocketError(): Promise<void> {
    await this.reportClosedIfLastSocket();
  }

  private async reportClosedIfLastSocket(): Promise<void> {
    if (this.hasSocket()) {
      return;
    }
    const watches = await this.watches();
    const now = new Date();
    for (const worldId of watches) {
      await this.serializer.run(() => this.callCoordinator(worldId, "hostSocketClosed", [this.playerUuid(), now]));
    }
  }
}
