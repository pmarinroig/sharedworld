import { describe, expect, test } from "bun:test";

import type { SessionToken } from "../../../shared/src/index.ts";

import { createRouter } from "../../src/router.ts";
import { createRouterService } from "../support/router.ts";

/**
 * The in-isolate session cache saves one D1 read per authenticated request.
 * Its safety argument: sessions are immutable (no revocation path exists),
 * and expiry is still enforced from the record's own expiresAt on every
 * request — so the cache can shortcut lookups but never access decisions.
 */
describe("router session cache", () => {
  function session(token: string, expiresAt: string): SessionToken {
    return {
      token,
      playerUuid: "player-owner",
      playerName: "Owner",
      expiresAt
    };
  }

  function authed(path: string, token: string) {
    return new Request(`http://127.0.0.1:8787${path}`, {
      headers: { authorization: `Bearer ${token}` }
    });
  }

  test("a repeat request with the same token skips the session lookup", async () => {
    let lookups = 0;
    const router = createRouter(createRouterService({
      async getSession(token) {
        lookups += 1;
        return token === "cached-token" ? session("cached-token", "2099-01-01T00:00:00.000Z") : null;
      },
      async listWorlds() {
        return [];
      }
    }));

    const first = await router(authed("/worlds", "cached-token"));
    const second = await router(authed("/worlds", "cached-token"));

    expect(first.status).toBe(200);
    expect(second.status).toBe(200);
    expect(lookups).toBe(1);
  });

  test("a cached session still expires on its own expiresAt", async () => {
    let lookups = 0;
    const soon = new Date(Date.now() + 250).toISOString();
    const router = createRouter(createRouterService({
      async getSession(token) {
        lookups += 1;
        return token === "short-token" ? session("short-token", soon) : null;
      },
      async listWorlds() {
        return [];
      }
    }));

    expect((await router(authed("/worlds", "short-token"))).status).toBe(200);
    await new Promise((resolve) => setTimeout(resolve, 300));
    const expired = await router(authed("/worlds", "short-token"));
    expect(expired.status).toBe(401);
    await expect(expired.json()).resolves.toMatchObject({ error: "expired_session" });
    // The rejection came from the cached record's own expiry, not a re-read.
    expect(lookups).toBe(1);
  });

  test("unknown tokens are not cached and keep hitting the lookup", async () => {
    let lookups = 0;
    const router = createRouter(createRouterService({
      async getSession() {
        lookups += 1;
        return null;
      }
    }));

    expect((await router(authed("/worlds", "nope"))).status).toBe(401);
    expect((await router(authed("/worlds", "nope"))).status).toBe(401);
    expect(lookups).toBe(2);
  });
});
