import type { WorldRuntimeStatus } from "./contracts.ts";

/**
 * SharedWorld realtime wire schema (0.3.0+).
 *
 * One hibernated WebSocket per player carries awareness only: pushed events,
 * room presence, and liveness signals. Every authoritative state change stays
 * an HTTP request. Frames are JSON with an explicit protocol version so the
 * schema can evolve without guessing.
 */
export const REALTIME_PROTOCOL_VERSION = 1;

/** Client keepalive text answered at the edge without waking the gateway. */
export const REALTIME_KEEPALIVE_REQUEST = "sw-keepalive";
export const REALTIME_KEEPALIVE_RESPONSE = "sw-keepalive-ack";

export type RealtimeEventKind =
  | "runtime-changed"
  | "presence-changed"
  | "membership-changed"
  | "settings-changed"
  | "world-changed"
  | "world-deleted"
  | "snapshot-changed";

/** A player currently on the hosted Minecraft server, as reported by the host. */
export interface RoomPlayer {
  playerUuid: string;
  playerName: string;
}

/**
 * One pushed change notification. `runtime` rides along on runtime-changed so
 * the hot path (waiting screens, guest watchers) needs no follow-up fetch;
 * every other kind is an invalidation the client answers with its existing
 * HTTP read.
 */
export interface RealtimeEvent {
  worldId: string;
  kind: RealtimeEventKind;
  runtime?: WorldRuntimeStatus;
  roomPlayers?: RoomPlayer[];
}

export type RealtimeServerFrame =
  | { v: number; type: "welcome" }
  | { v: number; type: "event"; event: RealtimeEvent };

/**
 * host-players: the hosting client reports the full current roster of its
 * integrated server whenever it changes (full list, not deltas — self-healing
 * against a missed frame). The gateway's authenticated identity plus the
 * coordinator's current host record authorize it; a stale epoch is dropped.
 *
 * world-presence: a 0.4.1+ guest announces (or withdraws) presence in a
 * world over its socket — the socket-native replacement for periodic
 * presence POSTs. Carries no playerName: the gateway's authenticated
 * identity is the identity, and the coordinator resolves display names from
 * its membership cache. Re-announced by the client after every reconnect;
 * a lost frame is healed by the server-side socket grace.
 */
export type RealtimeClientFrame =
  | { v: number; type: "host-players"; worldId: string; runtimeEpoch: number; players: RoomPlayer[] }
  | { v: number; type: "world-presence"; worldId: string; present: boolean };
