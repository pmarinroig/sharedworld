import { REALTIME_PROTOCOL_VERSION, type RealtimeEvent, type RealtimeServerFrame } from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import type { WorldCoordinator } from "./coordinator.ts";

/**
 * The per-world coordinator surface as seen from the Worker service layer:
 * exactly the logic class's public methods. Production implements it with a
 * Durable Object stub speaking the JSON call envelope below; tests and the
 * Bun integration harness implement it with in-process WorldCoordinator
 * instances, so the same service-level tests exercise the real protocol.
 */
export type CoordinatorHandle = Pick<
  WorldCoordinator,
  | "enterSession"
  | "observeWaiting"
  | "runtimeStatus"
  | "cancelWaiting"
  | "assertSessionAccess"
  | "heartbeat"
  | "setStartupProgress"
  | "validateHostAuthority"
  | "beginFinalization"
  | "completeFinalization"
  | "abandonFinalization"
  | "releaseHost"
  | "reportLegacyPresence"
  | "reportHostPlayers"
  | "destroyWorld"
  | "memberRevoked"
>;

export interface RealtimeService {
  coordinator(worldId: string): CoordinatorHandle;
  /**
   * Fan one event out to the named players' gateways (durable-data write
   * paths: settings, membership, world meta, snapshots). Best-effort — the
   * fallback polling path covers a lost event.
   */
  notifyUsers(event: RealtimeEvent, recipients: string[]): Promise<void>;
  /** Upgrade the authenticated caller's WebSocket onto their gateway. */
  connect(playerUuid: string, request: Request): Promise<Response>;
}

export const COORDINATOR_METHODS: ReadonlyArray<keyof CoordinatorHandle> = [
  "enterSession",
  "observeWaiting",
  "runtimeStatus",
  "cancelWaiting",
  "assertSessionAccess",
  "heartbeat",
  "setStartupProgress",
  "validateHostAuthority",
  "beginFinalization",
  "completeFinalization",
  "abandonFinalization",
  "releaseHost",
  "reportLegacyPresence",
  "reportHostPlayers",
  "destroyWorld",
  "memberRevoked"
];

// ---------------------------------------------------------------- envelope
//
// Coordinator calls cross the Worker→DO boundary as JSON: Dates are encoded
// with a marker so `now` parameters survive, and HttpError crosses back as a
// typed error payload instead of losing its status/code in RPC serialization.

const DATE_MARKER = "__sw_date";

export function encodeCallBody(method: string, args: unknown[]): string {
  return JSON.stringify({ method, args }, (_key, value: unknown) => {
    if (value instanceof Date) {
      return { [DATE_MARKER]: value.toISOString() };
    }
    return value;
  });
}

export function decodeCallBody(body: string): { method: string; args: unknown[] } {
  return JSON.parse(body, (_key, value: unknown) => {
    if (value != null && typeof value === "object" && DATE_MARKER in (value as Record<string, unknown>)) {
      return new Date(String((value as Record<string, unknown>)[DATE_MARKER]));
    }
    return value;
  }) as { method: string; args: unknown[] };
}

export interface ErrorEnvelope {
  error: { status: number; code: string; message: string };
}

export function isErrorEnvelope(value: unknown): value is ErrorEnvelope {
  return value != null && typeof value === "object" && "error" in (value as Record<string, unknown>);
}

export function toErrorEnvelope(error: unknown): ErrorEnvelope {
  if (error instanceof HttpError) {
    return { error: { status: error.status, code: error.code, message: error.message } };
  }
  throw error;
}

export function rethrowEnvelope(envelope: ErrorEnvelope): never {
  throw new HttpError(envelope.error.status, envelope.error.code, envelope.error.message);
}

interface StubLike {
  fetch(input: string, init?: { method?: string; body?: string }): Promise<{ ok: boolean; status: number; text(): Promise<string> }>;
}

interface NamespaceLike {
  idFromName(name: string): unknown;
  get(id: unknown): StubLike;
}

async function callStub(stub: StubLike, path: string, method: string, args: unknown[]): Promise<unknown> {
  const response = await stub.fetch(`https://do${path}`, { method: "POST", body: encodeCallBody(method, args) });
  const text = await response.text();
  const parsed: unknown = text.length > 0 ? JSON.parse(text) : null;
  if (isErrorEnvelope(parsed)) {
    rethrowEnvelope(parsed);
  }
  if (!response.ok) {
    throw new HttpError(502, "internal_error", "SharedWorld realtime coordination failed. Please try again.");
  }
  return (parsed as { ok: unknown } | null)?.ok ?? null;
}

/** Production RealtimeService over the two Durable Object namespaces. */
export class DoRealtimeService implements RealtimeService {
  constructor(
    private readonly coordinators: NamespaceLike,
    private readonly gateways: NamespaceLike
  ) {}

  coordinator(worldId: string): CoordinatorHandle {
    const stub = this.coordinators.get(this.coordinators.idFromName(worldId));
    const handle: Record<string, (...args: unknown[]) => Promise<unknown>> = {};
    for (const method of COORDINATOR_METHODS) {
      handle[method] = (...args: unknown[]) => callStub(stub, "/call", method, args);
    }
    // The envelope forwards each method 1:1; the cast restores the real types.
    return handle as unknown as CoordinatorHandle;
  }

  async notifyUsers(event: RealtimeEvent, recipients: string[]): Promise<void> {
    const frame: RealtimeServerFrame = { v: REALTIME_PROTOCOL_VERSION, type: "event", event };
    await Promise.allSettled(recipients.map(async (playerUuid) => {
      const stub = this.gateways.get(this.gateways.idFromName(playerUuid));
      await stub.fetch("https://do/notify", { method: "POST", body: JSON.stringify({ frame }) });
    }));
  }

  async connect(playerUuid: string, request: Request): Promise<Response> {
    const stub = this.gateways.get(this.gateways.idFromName(playerUuid));
    // Forward the original upgrade request with the DO-internal path; the
    // stub type in platform.d.ts only names string inputs, so widen here.
    const forwarded = new Request("https://do/connect", request as unknown as RequestInit & Request);
    return (stub as unknown as { fetch(input: Request): Promise<Response> }).fetch(forwarded);
  }
}
