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
    if (url.pathname === "/__test/reset" && request.method === "POST") {
      const response = await app.fetch(request);
      // A reset builds a fresh service + realtime; re-attach the bridge.
      wireRealtime();
      return response;
    }
    return app.fetch(request);
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
      lastSeenByPlayer.set(playerUuid, Date.now());
      if (typeof message !== "string") {
        return;
      }
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
    },
    close(ws) {
      const playerUuid = ws.data.playerUuid;
      const set = socketsByPlayer.get(playerUuid);
      set?.delete(ws);
      if (set != null && set.size === 0) {
        socketsByPlayer.delete(playerUuid);
        void app.realtime().socketStateChanged(playerUuid, false, new Date());
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
