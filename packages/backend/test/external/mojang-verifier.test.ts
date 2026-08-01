import { afterAll, beforeEach, describe, expect, test } from "bun:test";

import { HttpError } from "../../src/http.ts";
import { MinecraftSessionServerAuthVerifier } from "../../src/service.ts";

/**
 * Tests for the real Mojang hasJoined HTTP client against a local fake
 * session server. Every upstream cause maps to the single retryable code
 * identity_verification_unavailable (shipped clients key on the code), but
 * the user-facing message and retry metadata differ per cause.
 */

type FakeResponse = {
  status: number;
  body?: string;
  contentType?: string;
  retryAfter?: string;
};

let nextResponse: FakeResponse = { status: 204 };
let hits = 0;
let lastRequestUrl: URL | null = null;

const server = Bun.serve({
  port: 0,
  fetch(request) {
    hits += 1;
    lastRequestUrl = new URL(request.url);
    const headers: Record<string, string> = {};
    if (nextResponse.contentType) {
      headers["content-type"] = nextResponse.contentType;
    }
    if (nextResponse.retryAfter) {
      headers["retry-after"] = nextResponse.retryAfter;
    }
    return new Response(nextResponse.body ?? null, {
      status: nextResponse.status,
      headers
    });
  }
});

const verifier = new MinecraftSessionServerAuthVerifier(`http://127.0.0.1:${server.port}/session/minecraft/hasJoined`);

afterAll(() => {
  server.stop(true);
});

beforeEach(() => {
  nextResponse = { status: 204 };
  hits = 0;
  lastRequestUrl = null;
});

async function expectUnavailable(promise: Promise<unknown>, message: string): Promise<HttpError> {
  let caught: unknown = null;
  try {
    await promise;
  } catch (error) {
    caught = error;
  }
  expect(caught).toBeInstanceOf(HttpError);
  const httpError = caught as HttpError;
  expect(httpError.status).toBe(503);
  expect(httpError.code).toBe("identity_verification_unavailable");
  expect(httpError.message).toBe(message);
  return httpError;
}

describe("MinecraftSessionServerAuthVerifier", () => {
  test("returns the joined identity and sends username/serverId query parameters", async () => {
    nextResponse = {
      status: 200,
      body: JSON.stringify({ id: "11111111111111111111111111111111", name: "HostA" }),
      contentType: "application/json"
    };
    const identity = await verifier.verifyJoin("HostA", "server-id-1");
    expect(identity).toEqual({ playerUuid: "11111111111111111111111111111111", playerName: "HostA" });
    expect(lastRequestUrl?.searchParams.get("username")).toBe("HostA");
    expect(lastRequestUrl?.searchParams.get("serverId")).toBe("server-id-1");
  });

  test("treats 204 as not-joined-yet", async () => {
    nextResponse = { status: 204 };
    expect(await verifier.verifyJoin("HostA", "server-id-1")).toBeNull();
  });

  test("treats 404 as not-joined-yet", async () => {
    nextResponse = { status: 404 };
    expect(await verifier.verifyJoin("HostA", "server-id-1")).toBeNull();
  });

  test("treats an empty 200 body as not-joined-yet", async () => {
    nextResponse = { status: 200, body: "  " };
    expect(await verifier.verifyJoin("HostA", "server-id-1")).toBeNull();
  });

  for (const status of [400, 500, 502, 503]) {
    test(`maps HTTP ${status} to identity_verification_unavailable after exactly one attempt`, async () => {
      nextResponse = { status };
      const error = await expectUnavailable(
        verifier.verifyJoin("HostA", "server-id-1"),
        "Minecraft identity verification is unavailable."
      );
      expect(error.upstreamStatus).toBe(status);
      // The verifier itself is single-attempt by design; the retry ladder
      // lives in AuthDomainService.verifyJoinedIdentity, which treats this
      // 503 as a retriable attempt (see test/service/auth.test.ts).
      expect(hits).toBe(1);
    });
  }

  test("maps HTTP 429 to the rate-limit message and defaults Retry-After to 10s", async () => {
    nextResponse = { status: 429 };
    const error = await expectUnavailable(
      verifier.verifyJoin("HostA", "server-id-1"),
      "Minecraft's identity service is rate-limiting the SharedWorld server. Please wait a minute and try again."
    );
    expect(error.upstreamStatus).toBe(429);
    expect(error.retryAfterSeconds).toBe(10);
  });

  test("clamps Mojang's Retry-After on 429 into [10, 120] seconds", async () => {
    nextResponse = { status: 429, retryAfter: "60" };
    expect((await expectUnavailable(
      verifier.verifyJoin("HostA", "server-id-1"),
      "Minecraft's identity service is rate-limiting the SharedWorld server. Please wait a minute and try again."
    )).retryAfterSeconds).toBe(60);

    nextResponse = { status: 429, retryAfter: "3" };
    expect((await expectUnavailable(
      verifier.verifyJoin("HostA", "server-id-1"),
      "Minecraft's identity service is rate-limiting the SharedWorld server. Please wait a minute and try again."
    )).retryAfterSeconds).toBe(10);

    nextResponse = { status: 429, retryAfter: "86400" };
    expect((await expectUnavailable(
      verifier.verifyJoin("HostA", "server-id-1"),
      "Minecraft's identity service is rate-limiting the SharedWorld server. Please wait a minute and try again."
    )).retryAfterSeconds).toBe(120);

    // HTTP-date form (not delta-seconds) falls back to the default.
    nextResponse = { status: 429, retryAfter: "Fri, 01 Aug 2026 00:00:00 GMT" };
    expect((await expectUnavailable(
      verifier.verifyJoin("HostA", "server-id-1"),
      "Minecraft's identity service is rate-limiting the SharedWorld server. Please wait a minute and try again."
    )).retryAfterSeconds).toBe(10);
  });

  test("maps HTTP 403 to the refused-verification message", async () => {
    nextResponse = { status: 403 };
    const error = await expectUnavailable(
      verifier.verifyJoin("HostA", "server-id-1"),
      "Minecraft's identity service refused the verification request. Please try again in a few minutes; if it keeps failing, please report it along with your Minecraft name."
    );
    expect(error.upstreamStatus).toBe(403);
    expect(error.retryAfterSeconds).toBeUndefined();
  });

  test("maps a connection failure to the unreachable message", async () => {
    const closedPortServer = Bun.serve({ port: 0, fetch: () => new Response(null) });
    const closedPort = closedPortServer.port;
    closedPortServer.stop(true);
    const unreachable = new MinecraftSessionServerAuthVerifier(`http://127.0.0.1:${closedPort}/hasJoined`);
    const error = await expectUnavailable(
      unreachable.verifyJoin("HostA", "server-id-1"),
      "Minecraft's identity service is unreachable right now. Please try again in a minute."
    );
    expect(error.upstreamStatus).toBeUndefined();
  });

  test("maps a non-JSON body to the invalid-response variant", async () => {
    nextResponse = { status: 200, body: "<html>rate limited</html>", contentType: "text/html" };
    await expectUnavailable(
      verifier.verifyJoin("HostA", "server-id-1"),
      "Minecraft identity verification returned an invalid response."
    );
  });

  test("maps a JSON body missing id or name to the invalid-response variant", async () => {
    nextResponse = { status: 200, body: JSON.stringify({ id: "1111" }), contentType: "application/json" };
    await expectUnavailable(
      verifier.verifyJoin("HostA", "server-id-1"),
      "Minecraft identity verification returned an invalid response."
    );
  });

  test("aborts a hasJoined request that exceeds the per-attempt deadline", async () => {
    const hangingServer = Bun.serve({
      port: 0,
      async fetch() {
        await Bun.sleep(60_000);
        return new Response(null, { status: 204 });
      }
    });
    try {
      const impatient = new MinecraftSessionServerAuthVerifier(`http://127.0.0.1:${hangingServer.port}/hasJoined`, 250);
      const startedAt = Date.now();
      await expectUnavailable(
        impatient.verifyJoin("HostA", "server-id-1"),
        "Minecraft's identity service is unreachable right now. Please try again in a minute."
      );
      expect(Date.now() - startedAt).toBeLessThan(5_000);
    } finally {
      hangingServer.stop(true);
    }
  }, 10_000);
});
