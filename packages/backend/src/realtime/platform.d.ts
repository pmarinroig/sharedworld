/**
 * Minimal ambient declarations for the Durable Object platform surface the
 * realtime shells use — hand-declared like D1Database in env.ts, so the
 * backend stays free of @cloudflare/workers-types and every module keeps
 * loading under plain bun for tests.
 */

interface DurableObjectId {
  readonly name?: string;
}

interface DurableObjectStub {
  fetch(input: string | Request, init?: RequestInit): Promise<Response>;
}

interface DurableObjectNamespace {
  idFromName(name: string): DurableObjectId;
  get(id: DurableObjectId): DurableObjectStub;
}

interface SqlStorageCursor {
  toArray(): Record<string, unknown>[];
}

interface SqlStorage {
  exec(query: string, ...bindings: unknown[]): SqlStorageCursor;
}

interface DurableObjectStorage {
  get<T>(key: string): Promise<T | undefined>;
  put(key: string, value: unknown): Promise<void>;
  delete(key: string): Promise<boolean>;
  setAlarm(scheduledTime: number | Date): Promise<void>;
  getAlarm(): Promise<number | null>;
  deleteAlarm(): Promise<void>;
  readonly sql: SqlStorage;
}

interface DurableObjectState {
  readonly id: DurableObjectId;
  readonly storage: DurableObjectStorage;
  acceptWebSocket(ws: WebSocket, tags?: string[]): void;
  getWebSockets(tag?: string): WebSocket[];
  setWebSocketAutoResponse(pair: WebSocketRequestResponsePair): void;
  getWebSocketAutoResponseTimestamp(ws: WebSocket): Date | null;
}

declare class WebSocketRequestResponsePair {
  constructor(request: string, response: string);
}

declare class WebSocketPair {
  readonly 0: WebSocket;
  readonly 1: WebSocket;
}

/** Workers-runtime extensions on the standard WebSocket. */
interface WebSocket {
  serializeAttachment(value: unknown): void;
  deserializeAttachment(): unknown;
}

/** Workers accepts a socket on the 101 response. */
interface ResponseInit {
  webSocket?: WebSocket;
}
