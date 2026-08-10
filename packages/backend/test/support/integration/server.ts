import {
  REALTIME_KEEPALIVE_REQUEST,
  REALTIME_KEEPALIVE_RESPONSE,
  REALTIME_PROTOCOL_VERSION,
  type RealtimeClientFrame,
  type RealtimeEvent,
  type RealtimeServerFrame
} from "../../../../shared/src/index.ts";

import { createIntegrationTestApp } from "./app.ts";

const port = Number.parseInt(process.env.SHAREDWORLD_INTEGRATION_PORT ?? "18787", 10);
const baseUrl = `http://127.0.0.1:${port}`;
const app = createIntegrationTestApp(baseUrl, {
  dbPath: process.env.SHAREDWORLD_INTEGRATION_DB_FILE || undefined,
  realtimeStateDir: process.env.SHAREDWORLD_INTEGRATION_STATE_DIR || undefined,
  blobDir: process.env.SHAREDWORLD_INTEGRATION_BLOB_DIR || undefined
});

/**
 * The harness's stand-in for the UserGatewayDO layer: Bun-native WebSockets
 * plus a socket registry, bridged into the in-process LocalRealtimeService
 * (which runs the real WorldCoordinator logic). Keepalives are answered
 * here — like Cloudflare's auto-response, they never reach the coordinator.
 */
interface SocketData {
  playerUuid: string;
}

const socketsByPlayer = new Map<string, Set<import("bun").ServerWebSocket<SocketData>>>();
const lastSeenByPlayer = new Map<string, number>();
/** Worlds each player announced guest presence in (world-presence frames). */
const presenceWorldsByPlayer = new Map<string, Set<string>>();

/**
 * e2e knobs.
 * ws-mode: "normal" serves sockets faithfully; "blackhole" keeps accepted
 * sockets open but answers no keepalives and delivers no pushes (a half-open
 * socket, for the client ack-deadline drill); "reject" refuses /ws upgrades
 * with 503 (the HTTP-fallback lane).
 * request-log: ring buffer of every non-__test HTTP request, attributed to
 * the bearer session's player when resolvable — lets the e2e assert the
 * zero-polling-while-connected budget.
 */
let wsMode: "normal" | "blackhole" | "reject" = "normal";
const REQUEST_LOG_LIMIT = 5_000;
const requestLog: Array<{ at: string; playerUuid: string | null; method: string; path: string; status: number }> = [];

function logRequest(playerUuid: string | null, method: string, path: string, status: number): void {
  requestLog.push({ at: new Date().toISOString(), playerUuid, method, path, status });
  if (requestLog.length > REQUEST_LOG_LIMIT) {
    requestLog.splice(0, requestLog.length - REQUEST_LOG_LIMIT);
  }
}

async function playerOf(request: Request): Promise<string | null> {
  const header = request.headers.get("authorization");
  if (!header?.startsWith("Bearer ")) {
    return null;
  }
  const session = await app.getSession(header.slice("Bearer ".length)).catch(() => null);
  return session?.playerUuid ?? null;
}

function wireRealtime(): void {
  const realtime = app.realtime();
  realtime.enableAlarmTimers();
  realtime.restorePersistedWorlds();
  realtime.socketBridge = {
    isConnected: (playerUuid) => (socketsByPlayer.get(playerUuid)?.size ?? 0) > 0,
    lastSeenAt: (playerUuid) => {
      const at = lastSeenByPlayer.get(playerUuid);
      return at == null ? null : new Date(at);
    }
  };
  realtime.onPublish = (event: RealtimeEvent, recipients: string[] | undefined) => {
    if (wsMode === "blackhole") {
      return; // half-open drill: the server "delivers" into the void
    }
    const frame: RealtimeServerFrame = { v: REALTIME_PROTOCOL_VERSION, type: "event", event };
    const encoded = JSON.stringify(frame);
    for (const playerUuid of recipients ?? []) {
      for (const ws of socketsByPlayer.get(playerUuid) ?? []) {
        try {
          ws.send(encoded);
        } catch {
          // A dying socket surfaces via close(); drop the frame.
        }
      }
    }
  };
}
wireRealtime();

const server = Bun.serve<SocketData>({
  port,
  async fetch(request, bunServer) {
    const url = new URL(request.url);
    if (url.pathname === "/ws") {
      if (wsMode === "reject") {
        return new Response(JSON.stringify({ error: "realtime_unavailable", message: "WS disabled by test knob." }), { status: 503 });
      }
      const header = request.headers.get("authorization");
      if (!header?.startsWith("Bearer ")) {
        return new Response(JSON.stringify({ error: "unauthorized", message: "Missing bearer token." }), { status: 401 });
      }
      const session = await app.getSession(header.slice("Bearer ".length));
      if (!session) {
        return new Response(JSON.stringify({ error: "unauthorized", message: "Invalid session." }), { status: 401 });
      }
      if (bunServer.upgrade(request, { data: { playerUuid: session.playerUuid } })) {
        return undefined as unknown as Response;
      }
      return new Response("expected websocket", { status: 426 });
    }
    if (url.pathname === "/__test/ws-mode" && request.method === "POST") {
      const body = await request.json() as { mode?: string };
      if (body.mode !== "normal" && body.mode !== "blackhole" && body.mode !== "reject") {
        return new Response(JSON.stringify({ error: "invalid_mode" }), { status: 400 });
      }
      wsMode = body.mode;
      return Response.json({ mode: wsMode });
    }
    if (url.pathname === "/__test/request-log" && request.method === "GET") {
      return Response.json({ requests: requestLog });
    }
    if (url.pathname === "/__test/request-log/reset" && request.method === "POST") {
      requestLog.length = 0;
      return Response.json({ ok: true });
    }
    if (url.pathname === "/__test/reset" && request.method === "POST") {
      const response = await app.fetch(request);
      // A reset builds a fresh service + realtime; re-attach the bridge.
      wireRealtime();
      wsMode = "normal";
      requestLog.length = 0;
      presenceWorldsByPlayer.clear();
      return response;
    }
    if (url.pathname.startsWith("/__test/")) {
      return app.fetch(request);
    }
    const playerUuid = await playerOf(request);
    const response = await app.fetch(request);
    logRequest(playerUuid, request.method, url.pathname, response.status);
    return response;
  },
  websocket: {
    open(ws) {
      const playerUuid = ws.data.playerUuid;
      let set = socketsByPlayer.get(playerUuid);
      if (!set) {
        set = new Set();
        socketsByPlayer.set(playerUuid, set);
      }
      set.add(ws);
      lastSeenByPlayer.set(playerUuid, Date.now());
      ws.send(JSON.stringify({ v: REALTIME_PROTOCOL_VERSION, type: "welcome" } satisfies RealtimeServerFrame));
      void app.realtime().socketStateChanged(playerUuid, true, new Date());
    },
    message(ws, message) {
      const playerUuid = ws.data.playerUuid;
      if (typeof message !== "string") {
        return;
      }
      if (wsMode === "blackhole") {
        return; // half-open drill: inbound frames vanish, no ack, no pokes
      }
      lastSeenByPlayer.set(playerUuid, Date.now());
      if (message === REALTIME_KEEPALIVE_REQUEST) {
        ws.send(REALTIME_KEEPALIVE_RESPONSE);
        return;
      }
      let frame: RealtimeClientFrame;
      try {
        frame = JSON.parse(message) as RealtimeClientFrame;
      } catch {
        return;
      }
      if (frame.type === "host-players" && typeof frame.worldId === "string") {
        void app.realtime().coordinator(frame.worldId)
          .reportHostPlayers(playerUuid, frame.runtimeEpoch, frame.players ?? [], new Date());
      }
      if (frame.type === "world-presence" && typeof frame.worldId === "string" && typeof frame.present === "boolean") {
        let worlds = presenceWorldsByPlayer.get(playerUuid);
        if (!worlds) {
          worlds = new Set();
          presenceWorldsByPlayer.set(playerUuid, worlds);
        }
        if (frame.present) {
          worlds.add(frame.worldId);
        } else {
          worlds.delete(frame.worldId);
        }
        void app.realtime().reportSocketPresence(frame.worldId, playerUuid, frame.present, new Date());
      }
    },
    close(ws) {
      const playerUuid = ws.data.playerUuid;
      const set = socketsByPlayer.get(playerUuid);
      set?.delete(ws);
      if (set != null && set.size === 0) {
        socketsByPlayer.delete(playerUuid);
        void app.realtime().socketStateChanged(playerUuid, false, new Date());
        const worlds = presenceWorldsByPlayer.get(playerUuid);
        presenceWorldsByPlayer.delete(playerUuid);
        for (const worldId of worlds ?? []) {
          void app.realtime().presenceSocketClosed(worldId, playerUuid, new Date());
        }
      }
    }
  }
});

console.log(`SharedWorld integration backend listening on ${baseUrl}`);

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    server.stop(true);
    process.exit(0);
  });
}
