import { existsSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, test } from "bun:test";

import { createApp } from "../src/index.ts";
import { createLaneDApp, openSealedToken, relayBlob, verifyRelayToken, type LaneDEnv } from "../src/lane-d.ts";
import type { Env } from "../src/env.ts";

/**
 * Lane D: the worker as a thin front for the Rust server. Token verification
 * (Ed25519 + AES-GCM) is checked against a token minted by the Rust side
 * (`swctl relay-token-demo`) when the binary is built, and against a
 * WebCrypto-signed token otherwise; forwarding uses a fake fetch.
 */

function b64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

async function mintWithWebCrypto(accessToken: string, fileId: string, exp: number) {
  const pair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const publicRaw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
  const aesRaw = crypto.getRandomValues(new Uint8Array(32));
  const aesKey = await crypto.subtle.importKey("raw", aesRaw, "AES-GCM", false, ["encrypt"]);
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const sealed = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce, additionalData: new TextEncoder().encode(fileId) },
    aesKey,
    new TextEncoder().encode(accessToken)
  ));
  const dt = b64url(new Uint8Array([...nonce, ...sealed]));
  const claims = { t: "rl", w: "w1", k: "packs/full/ab/abc.pack", a: "acct", f: fileId, p: "player", exp, dt };
  const body = b64url(new TextEncoder().encode(JSON.stringify(claims)));
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, pair.privateKey, new TextEncoder().encode(body)));
  return { token: `v2.${body}.${b64url(sig)}`, publicKey: b64(publicRaw), tokenKey: b64(aesRaw) };
}

describe("lane-d relay tokens", () => {
  test("WebCrypto-minted token verifies, opens, expires and rejects tampering", async () => {
    const now = new Date("2026-08-19T12:00:00.000Z");
    const minted = await mintWithWebCrypto("ya29.secret", "file-1", now.getTime() + 60_000);
    const claims = await verifyRelayToken(minted.publicKey, minted.token, now);
    expect(claims?.f).toBe("file-1");
    expect(await openSealedToken(minted.tokenKey, claims!.dt, "file-1")).toBe("ya29.secret");
    expect(await openSealedToken(minted.tokenKey, claims!.dt, "other")).toBeNull();
    expect(await verifyRelayToken(minted.publicKey, minted.token, new Date(now.getTime() + 120_000))).toBeNull();
    const tampered = minted.token.slice(0, 4) + (minted.token[4] === "A" ? "B" : "A") + minted.token.slice(5);
    expect(await verifyRelayToken(minted.publicKey, tampered, now)).toBeNull();
    const other = await mintWithWebCrypto("x", "file-1", now.getTime() + 60_000);
    expect(await verifyRelayToken(other.publicKey, minted.token, now)).toBeNull();
  });

  test("Rust-minted token (swctl relay-token-demo) verifies in the worker", async () => {
    const swctl = join(import.meta.dir, "../../server/target/debug/swctl");
    if (!existsSync(swctl)) {
      console.warn("swctl not built; skipping cross-language relay token check");
      return;
    }
    const proc = Bun.spawnSync([swctl, "relay-token-demo"]);
    const demo = JSON.parse(proc.stdout.toString()) as { publicKey: string; tokenKey: string; token: string; fileId: string; accessToken: string; exp: number };
    const claims = await verifyRelayToken(demo.publicKey, demo.token, new Date(demo.exp - 1000));
    expect(claims?.f).toBe(demo.fileId);
    expect(await openSealedToken(demo.tokenKey, claims!.dt, demo.fileId)).toBe(demo.accessToken);
  });
});

describe("lane-d app", () => {
  const env: LaneDEnv = {
    MODE: "lane-d",
    BOX_URL: "https://box.example",
    INTERNAL_API_SECRET: "s3cret",
    PUBLIC_BASE_URL: "https://relay.example",
  };

  test("everything else forwards to the box with entry origin + internal secret", async () => {
    const seen: Request[] = [];
    const original = globalThis.fetch;
    globalThis.fetch = (async (input: RequestInfo | URL) => {
      const request = input instanceof Request ? input : new Request(String(input));
      seen.push(request);
      return new Response("ok", { status: 200 });
    }) as typeof fetch;
    try {
      const app = createLaneDApp(env);
      const response = await app.fetch(new Request("https://relay.example/worlds?x=1", {
        method: "POST",
        headers: { authorization: "Bearer t", "x-sharedworld-version": "0.4.5", "content-type": "application/json" },
        body: "{}"
      }));
      expect(response.status).toBe(200);
      expect(seen).toHaveLength(1);
      expect(seen[0].url).toBe("https://box.example/worlds?x=1");
      expect(seen[0].method).toBe("POST");
      expect(seen[0].headers.get("authorization")).toBe("Bearer t");
      expect(seen[0].headers.get("x-sharedworld-version")).toBe("0.4.5");
      expect(seen[0].headers.get("x-sw-entry-origin")).toBe("https://relay.example");
      expect(seen[0].headers.get("x-sw-internal-secret")).toBe("s3cret");
    } finally {
      globalThis.fetch = original;
    }
  });

  test("a blob GET with a valid relay token streams from Drive; without one it forwards", async () => {
    const now = new Date("2026-08-19T12:00:00.000Z");
    const minted = await mintWithWebCrypto("ya29.secret", "file-9", now.getTime() + 60_000);
    const relayEnv: LaneDEnv = { ...env, RELAY_PUBLIC_KEY: minted.publicKey, RELAY_TOKEN_KEY: minted.tokenKey, GOOGLE_DRIVE_API_BASE: "https://drive.example" };
    const seen: Request[] = [];
    const original = globalThis.fetch;
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(String(input), init);
      seen.push(request);
      if (request.url.startsWith("https://drive.example/")) {
        const range = request.headers.get("range");
        return new Response("hello", {
          status: range ? 206 : 200,
          headers: range ? { "content-range": "bytes 0-4/5", "content-length": "5" } : { "content-length": "5" }
        });
      }
      return new Response("forwarded", { status: 200 });
    }) as typeof fetch;
    try {
      const withToken = new Request("https://relay.example/worlds/w1/storage/blob/packs%2Ffull%2Fab%2Fabc.pack", {
        headers: { [ "x-sharedworld-relay-token" ]: minted.token, range: "bytes=0-" }
      });
      const relayed = await relayBlob(relayEnv, withToken, now);
      expect(relayed?.status).toBe(206);
      expect(relayed?.headers.get("content-range")).toBe("bytes 0-4/5");
      expect(relayed?.headers.get("accept-ranges")).toBe("bytes");
      expect(await relayed?.text()).toBe("hello");
      expect(seen[0].url).toBe("https://drive.example/drive/v3/files/file-9?alt=media");
      expect(seen[0].headers.get("authorization")).toBe("Bearer ya29.secret");
      expect(seen[0].headers.get("range")).toBe("bytes=0-");

      const without = new Request("https://relay.example/worlds/w1/storage/blob/packs%2Ffull%2Fab%2Fabc.pack");
      expect(await relayBlob(relayEnv, without, now)).toBeNull();
      const app = createLaneDApp(relayEnv);
      const forwarded = await app.fetch(without);
      expect(await forwarded.text()).toBe("forwarded");
    } finally {
      globalThis.fetch = original;
    }
  });
});

describe("maintenance mode (cutover freeze)", () => {
  test("every request, including a socket upgrade, answers 503 with Retry-After and nothing touches D1", async () => {
    // No DB / DO bindings at all: maintenance must not need them.
    const app = createApp({ MODE: "maintenance" } as unknown as Env);
    for (const request of [
      new Request("https://w.example/worlds", { headers: { authorization: "Bearer x" } }),
      new Request("https://w.example/worlds/w1/heartbeat", { method: "POST", body: "{}" }),
      new Request("https://w.example/ws", { headers: { upgrade: "websocket", authorization: "Bearer x" } })
    ]) {
      const response = await app.fetch(request);
      expect(response.status).toBe(503);
      expect(response.headers.get("retry-after")).toBe("60");
      expect(await response.json()).toMatchObject({ error: "maintenance", status: 503 });
    }
    expect(await app.scheduled()).toBe(0);
  });
});
