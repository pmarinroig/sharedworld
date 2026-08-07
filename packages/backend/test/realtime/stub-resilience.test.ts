import { describe, expect, test } from "bun:test";

import { HttpError } from "../../src/http.ts";
import { DoRealtimeService } from "../../src/realtime/service.ts";

/**
 * A Durable Object mid-reset ("Internal error in Durable Object storage
 * caused object to be reset" — seen in production) rejects the stub fetch
 * outright. The service must absorb one such blip transparently and turn a
 * persistent failure into a clean retryable 503, never an unhandled 500.
 */
function serviceWithStub(fetchImpl: (input: string, init?: { body?: unknown }) => Promise<Response>): DoRealtimeService {
  const namespace = {
    idFromName: (name: string) => name,
    get: () => ({ fetch: fetchImpl })
  };
  return new DoRealtimeService(namespace as never, namespace as never);
}

describe("coordinator stub resilience", () => {
  test("one stub failure is retried transparently", async () => {
    let calls = 0;
    const service = serviceWithStub(async () => {
      calls += 1;
      if (calls === 1) {
        throw new Error("Internal error in Durable Object storage caused object to be reset.");
      }
      return new Response(JSON.stringify({ ok: { phase: "host-live" } }), { status: 200 });
    });
    const result = await service.coordinator("world-1").runtimeStatus(
      { playerUuid: "p", playerName: "P", membershipActive: true, everMember: true } as never,
      new Date()
    );
    expect(calls).toBe(2);
    expect((result as { phase: string }).phase).toBe("host-live");
  });

  test("a persistent stub failure becomes a retryable 503, not an unhandled error", async () => {
    let calls = 0;
    const service = serviceWithStub(async () => {
      calls += 1;
      throw new Error("Network connection lost.");
    });
    try {
      await service.coordinator("world-1").runtimeStatus(
        { playerUuid: "p", playerName: "P", membershipActive: true, everMember: true } as never,
        new Date()
      );
      throw new Error("expected the call to fail");
    } catch (error) {
      expect(error).toBeInstanceOf(HttpError);
      const httpError = error as HttpError;
      expect(httpError.status).toBe(503);
      expect(httpError.code).toBe("realtime_unavailable");
      expect(httpError.retryAfterSeconds).toBe(2);
    }
    expect(calls).toBe(2);
  });

  test("an application error from the coordinator is never retried", async () => {
    let calls = 0;
    const service = serviceWithStub(async () => {
      calls += 1;
      return new Response(
        JSON.stringify({ error: { status: 403, code: "membership_revoked", message: "You were removed from this SharedWorld." } }),
        { status: 200 }
      );
    });
    try {
      await service.coordinator("world-1").runtimeStatus(
        { playerUuid: "p", playerName: "P", membershipActive: false, everMember: true } as never,
        new Date()
      );
      throw new Error("expected the call to fail");
    } catch (error) {
      expect((error as HttpError).code).toBe("membership_revoked");
    }
    expect(calls).toBe(1);
  });
});
