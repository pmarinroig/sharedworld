import { afterAll, beforeEach, describe, expect, test } from "bun:test";

import { HttpError } from "../../src/http.ts";
import { MinecraftSessionServerAuthVerifier } from "../../src/service.ts";

/**
 * Characterization tests for the real Mojang hasJoined HTTP client against a
 * local fake session server. Several assertions document current behavior that
 * is a known product defect (no retry, no timeout); those are marked with
 * KNOWN-DEFECT comments and must be flipped when the fix lands, not deleted.
 */

type FakeResponse = {
  status: number;
  body?: string;
  contentType?: string;
};

let nextResponse: FakeResponse = { status: 204 };
let hits = 0;
let lastRequestUrl: URL | null = null;

const server = Bun.serve({
  port: 0,
  fetch(request) {
    hits += 1;
    lastRequestUrl = new URL(request.url);
    return new Response(nextResponse.body ?? null, {
      status: nextResponse.status,
      headers: nextResponse.contentType ? { "content-type": nextResponse.contentType } : {}
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

  for (const status of [429, 500, 502, 503]) {
    test(`maps HTTP ${status} to identity_verification_unavailable after exactly one attempt`, async () => {
      nextResponse = { status };
      await expectUnavailable(
        verifier.verifyJoin("HostA", "server-id-1"),
        "Minecraft identity verification is unavailable."
      );
      // KNOWN-DEFECT(mojang-no-retry): a single transient Mojang failure —
      // including 429 rate limiting — aborts verification immediately. When
      // retry/backoff lands this assertion should expect multiple attempts.
      expect(hits).toBe(1);
    });
  }

  test("maps a connection failure to identity_verification_unavailable", async () => {
    const closedPortServer = Bun.serve({ port: 0, fetch: () => new Response(null) });
    const closedPort = closedPortServer.port;
    closedPortServer.stop(true);
    const unreachable = new MinecraftSessionServerAuthVerifier(`http://127.0.0.1:${closedPort}/hasJoined`);
    await expectUnavailable(
      unreachable.verifyJoin("HostA", "server-id-1"),
      "Minecraft identity verification is unavailable."
    );
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

  // KNOWN-DEFECT(mojang-no-timeout): the verifier's fetch has no AbortSignal,
  // so a hanging Mojang response blocks the worker for the runtime default.
  // A test would hang forever today; write it when the timeout lands.
  test.todo("aborts a hasJoined request that exceeds a deadline", () => {});
});
