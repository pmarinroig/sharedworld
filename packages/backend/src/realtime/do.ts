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
import { workersSnapshotManifestCache } from "../manifest-cache.ts";
import {
  WorldCoordinator,
  type CoordinatorEffects,
  type CoordinatorStore,
  type LegacyPresenceEntry,
  type SocketPresenceEntry
} from "./coordinator.ts";
import { decodeCallBody, encodeCallBody, toErrorEnvelope } from "./service.ts";
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

/**
 * CoordinatorStore over the DO's synchronous SQLite, as JSON kv rows, with a
 * write-through in-memory mirror of the whole table.
 *
 * Every coordinator call re-reads the same dozen keys (runtime, waiters,
 * presence lists, fingerprints…) across resolve/publish/afterStateChange/
 * nextDeadline — ~20 SELECTs per call against DO SQLite, each a billed row
 * read, for state that only this single-threaded object ever writes. The
 * mirror loads the table once per DO wake and serves every read from
 * memory; writes and deletes go to SQLite first, then the mirror, so a
 * storage failure (which resets the object anyway) can never leave memory
 * ahead of disk. Values are mirrored as their JSON text and re-parsed per
 * read, so callers never share (and can never mutate) a cached object.
 */
export class SqlCoordinatorStore implements CoordinatorStore {
  private readonly mirror = new Map<string, string>();

  constructor(private readonly sql: SqlStorage) {
    this.sql.exec("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    for (const row of this.sql.exec("SELECT key, value FROM kv").toArray()) {
      this.mirror.set(String(row.key), String(row.value));
    }
  }

  private read<T>(key: string): T | null {
    const value = this.mirror.get(key);
    return value == null ? null : (JSON.parse(value) as T);
  }

  private write(key: string, value: unknown): void {
    const text = JSON.stringify(value);
    this.sql.exec(
      "INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
      key,
      text
    );
    this.mirror.set(key, text);
  }

  private remove(key: string): void {
    this.sql.exec("DELETE FROM kv WHERE key = ?", key);
    this.mirror.delete(key);
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
  listSocketPresence() { return this.read<SocketPresenceEntry[]>("socketPresence") ?? []; }
  upsertSocketPresence(entry: SocketPresenceEntry) {
    const entries = this.listSocketPresence().filter((existing) => existing.playerUuid !== entry.playerUuid);
    entries.push(entry);
    this.write("socketPresence", entries);
  }
  deleteSocketPresence(playerUuid: string) {
    this.write("socketPresence", this.listSocketPresence().filter((entry) => entry.playerUuid !== playerUuid));
  }
  clearSocketPresence() { this.remove("socketPresence"); }
  getHostLink() {
    return this.read<{ connected: boolean; graceDeadlineAt: string | null }>("hostLink")
      ?? { connected: false, graceDeadlineAt: null };
  }
  setHostLink(link: { connected: boolean; graceDeadlineAt: string | null }) { this.write("hostLink", link); }
  getMembershipCache() { return this.read<{ members: RuntimeMembership[]; fetchedAt: string }>("membershipCache"); }
  setMembershipCache(cache: { members: RuntimeMembership[]; fetchedAt: string }) { this.write("membershipCache", cache); }
  clearMembershipCache() { this.remove("membershipCache"); }
  getStatusFingerprint() { return this.read<string>("statusFingerprint"); }
  setStatusFingerprint(fingerprint: string) { this.write("statusFingerprint", fingerprint); }
  getPresenceFingerprint() { return this.read<string>("presenceFingerprint"); }
  setPresenceFingerprint(fingerprint: string) { this.write("presenceFingerprint", fingerprint); }
  clearAll() {
    this.sql.exec("DELETE FROM kv");
    this.mirror.clear();
  }
}

export class DoCoordinatorEffects implements CoordinatorEffects {
  constructor(
    private readonly env: Env,
    private readonly storage: DurableObjectStorage,
    private readonly worldId: string
  ) {}

  private repository(): D1SharedWorldRepository {
    if (!this.env.DB) {
      throw new Error("SharedWorld coordinator requires the D1 binding (DB).");
    }
    return new D1SharedWorldRepository(this.env.DB, workersSnapshotManifestCache());
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

  /**
   * Per-recipient send queues for the detached fan-out below. Each player's
   * frames stay ordered relative to each other (a stale runtime status must
   * never arrive after a newer one), while recipients never wait on one
   * another. Entries self-prune once their queue drains; instance state only,
   * and a hard eviction dropping queued frames is within the lossy contract.
   */
  private readonly notifyTails = new Map<string, Promise<void>>();

  async publish(event: RealtimeEvent, recipients?: string[]): Promise<void> {
    const targets = recipients
      ?? (await this.listMemberships(event.worldId))
        .filter((member) => member.deletedAt == null)
        .map((member) => member.playerUuid);
    const frame: RealtimeServerFrame = { v: REALTIME_PROTOCOL_VERSION, type: "event", event };
    const body = JSON.stringify({ frame });
    // Detached on purpose: each recipient's gateway DO lives in that
    // player's home colo, so awaiting the fan-out held every coordinator
    // request — and, through the call serializer, every queued request and
    // alarm — hostage to the slowest round trip on the planet (observed as
    // multi-second request wall times). Delivery is best-effort and the
    // polling fallback covers a lost frame; only per-recipient ORDER matters.
    for (const playerUuid of targets) {
      const tail = this.notifyTails.get(playerUuid) ?? Promise.resolve();
      const next = tail.then(async () => {
        try {
          await this.gateway(playerUuid).fetch("https://do/notify", { method: "POST", body });
        } catch {
          // Lossy by contract; the recipient's fallback lane re-derives state.
        }
      });
      this.notifyTails.set(playerUuid, next);
      void next.finally(() => {
        if (this.notifyTails.get(playerUuid) === next) {
          this.notifyTails.delete(playerUuid);
        }
      });
    }
  }

  /**
   * Dedupe cursor for scheduleAlarm: nearly every coordinator call re-arms
   * the same deadline, and each setAlarm is a DO-storage write. Instance
   * state only — a cold start re-arms once, which is harmless.
   */
  private lastScheduledAlarmMs: number | null | undefined = undefined;

  async scheduleAlarm(at: Date | null): Promise<void> {
    const target = at == null ? null : at.getTime();
    if (this.lastScheduledAlarmMs !== undefined && this.lastScheduledAlarmMs === target) {
      return;
    }
    if (at == null) {
      await this.storage.deleteAlarm();
    } else {
      await this.storage.setAlarm(at);
    }
    this.lastScheduledAlarmMs = target;
  }

  async setHostWatch(hostUuid: string, watching: boolean): Promise<boolean> {
    try {
      const body = await this.gatewayCall(hostUuid, "https://do/watch", {
        method: "POST",
        body: JSON.stringify({ worldId: this.worldId, watching })
      }) as { connected: boolean };
      return body.connected;
    } catch (error) {
      // The watch only tunes connection-signal fidelity (and signals are
      // lossy by design); claiming or retiring a host must not fail on it.
      console.warn("SharedWorld host watch poke failed", { watching, error: String(error) });
      return false;
    }
  }

  async probeHostReachability(hostUuid: string): Promise<Date | null> {
    // No catch: callers treat a throw as "renewal aborted, expiry skipped" —
    // the safe failure for a lease decision. Returning null here instead
    // would read as "unreachable" and forfeit a healthy host's lease.
    const body = await this.gatewayCall(hostUuid, "https://do/probe") as { lastSeenAt: string | null };
    return body.lastSeenAt == null ? null : new Date(body.lastSeenAt);
  }

  /**
   * Gateway pokes get the same one-immediate-retry treatment as coordinator
   * stub calls (see callStub in service.ts): a gateway DO mid-reset rejects
   * the fetch or answers a non-JSON error page, and both probe/watch are
   * read-mostly calls that are safe to repeat against the restarted object.
   */
  private async gatewayCall(hostUuid: string, url: string, init?: { method?: string; body?: string }): Promise<unknown> {
    let lastError: unknown;
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        const response = await this.gateway(hostUuid).fetch(url, init);
        if (!response.ok) {
          throw new Error(`gateway poke returned HTTP ${response.status}`);
        }
        return await response.json();
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError;
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
    if (url.pathname === "/dump" && request.method === "GET") {
      // Lane-D cutover: the whole kv table (runtime, lastEpoch, waiters,
      // presence, fingerprints…) — the state that lives nowhere in D1.
      const rows: Record<string, string> = {};
      for (const row of this.ctx.storage.sql.exec("SELECT key, value FROM kv").toArray()) {
        rows[String(row.key)] = String(row.value);
      }
      const alarm = await this.ctx.storage.getAlarm();
      return Response.json({ worldId: this.ctx.id.name ?? null, kv: rows, alarmAt: alarm == null ? null : new Date(alarm).toISOString() });
    }
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

  /**
   * Worlds this player's 0.4.1 client announced guest presence in via
   * world-presence frames. Deliberately separate from `watches`: that set is
   * coordinator-owned host plumbing (claim/retire write it, close pokes feed
   * host grace), while this one is client-announced and cleared on last
   * socket close — a reconnecting client re-announces.
   */
  private async presenceWorlds(): Promise<string[]> {
    return (await this.ctx.storage.get<string[]>("presenceWorlds")) ?? [];
  }

  private async setPresenceWorld(worldId: string, present: boolean): Promise<void> {
    const worlds = await this.presenceWorlds();
    const next = present
      ? [...new Set([...worlds, worldId])].filter((id) => id.length > 0)
      : worlds.filter((id) => id !== worldId);
    await this.ctx.storage.put("presenceWorlds", next);
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
    // The call envelope, not plain JSON: Date arguments must survive
    // (JSON.stringify turns them into strings before any replacer sees them).
    await this.coordinatorStub(worldId).fetch("https://do/call", {
      method: "POST",
      body: encodeCallBody(method, args)
    });
  }

  /**
   * Awareness pokes must never break the socket lifecycle they ride on: a
   * coordinator mid-reset gets one retry, then the signal is dropped — the
   * coordinator's alarm probe re-derives host liveness from this gateway's
   * keepalive timestamp on its own, so a lost poke costs latency, not truth.
   */
  private async pokeCoordinator(worldId: string, method: string, args: unknown[]): Promise<void> {
    for (let attempt = 0; ; attempt += 1) {
      try {
        await this.callCoordinator(worldId, method, args);
        return;
      } catch (error) {
        if (attempt > 0) {
          console.warn("SharedWorld gateway dropped a coordinator poke", { worldId, method, error: String(error) });
          return;
        }
      }
    }
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
        await this.serializer.run(() => this.pokeCoordinator(worldId, "hostSocketConnected", [this.playerUuid(), now]));
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
      await this.serializer.run(() => this.pokeCoordinator(frame.worldId, "reportHostPlayers", [
        attachment.playerUuid,
        frame.runtimeEpoch,
        frame.players ?? [],
        new Date()
      ]));
    }
    if (frame.type === "world-presence" && typeof frame.worldId === "string" && typeof frame.present === "boolean") {
      const attachment = ws.deserializeAttachment() as GatewayAttachment;
      await this.serializer.run(async () => {
        await this.setPresenceWorld(frame.worldId, frame.present);
        await this.pokeCoordinator(frame.worldId, "reportSocketPresence", [
          attachment.playerUuid,
          frame.present,
          new Date()
        ]);
      });
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
      await this.serializer.run(() => this.pokeCoordinator(worldId, "hostSocketClosed", [this.playerUuid(), now]));
    }
    // Guest presence: start each announced world's grace, then forget the
    // set — a reconnecting client re-announces, and the coordinator's grace
    // prune covers a client that never comes back.
    const presenceWorlds = await this.presenceWorlds();
    if (presenceWorlds.length > 0) {
      await this.ctx.storage.put("presenceWorlds", []);
      for (const worldId of presenceWorlds) {
        await this.serializer.run(() => this.pokeCoordinator(worldId, "presenceSocketClosed", [this.playerUuid(), now]));
      }
    }
  }
}
