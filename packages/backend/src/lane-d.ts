import type { Env } from "./env.ts";

/**
 * Lane D (MODE = "lane-d"): the worker keeps only what is free on
 * Cloudflare; egress. It is a thin front for the Rust server ("the box"):
 *
 * - `GET /worlds/:id/storage/blob/*` with a valid relay token → stream the
 *   blob straight from Google Drive (the box minted the token at plan time;
 *   it carries the Drive file id and the account's access token sealed
 *   under a key only the box and this worker hold). Anything else on that
 *   route falls through to forwarding.
 * - everything else, including the `/ws` upgrade → forwarded to the box
 *   verbatim (headers pass through; `x-sw-entry-origin` tells the box which
 *   origin the client addressed so signed URLs keep pointing at it).
 *
 * No D1, no Durable Objects, no cron: this file imports nothing stateful.
 */
export interface LaneDEnv extends Env {
  MODE?: string;
  BOX_URL?: string;
  INTERNAL_API_SECRET?: string;
  /** Ed25519 public key (base64 standard) that verifies relay tokens. */
  RELAY_PUBLIC_KEY?: string;
  /** AES-256-GCM key (base64 standard) that opens the sealed Drive token. */
  RELAY_TOKEN_KEY?: string;
}

export const RELAY_TOKEN_HEADER = "x-sharedworld-relay-token";
const ENTRY_ORIGIN_HEADER = "x-sw-entry-origin";
const INTERNAL_SECRET_HEADER = "x-sw-internal-secret";
const BLOB_ROUTE = /^\/worlds\/[^/]+\/storage\/blob\/.+/;

export interface RelayClaims {
  t: "rl";
  w: string;
  k: string;
  a: string;
  f: string;
  p: string;
  exp: number;
  dt: string;
}

function b64urlDecode(text: string): Uint8Array | null {
  try {
    const padded = text.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (text.length % 4)) % 4);
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  } catch {
    return null;
  }
}

function b64Decode(text: string): Uint8Array {
  const binary = atob(text.trim());
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

const keyCache = new Map<string, Promise<CryptoKey>>();

function verifyKey(publicKeyB64: string): Promise<CryptoKey> {
  let key = keyCache.get(`ed:${publicKeyB64}`);
  if (key == null) {
    key = crypto.subtle.importKey("raw", b64Decode(publicKeyB64) as BufferSource, { name: "Ed25519" }, false, ["verify"]);
    keyCache.set(`ed:${publicKeyB64}`, key);
  }
  return key;
}

function tokenKey(keyB64: string): Promise<CryptoKey> {
  let key = keyCache.get(`aes:${keyB64}`);
  if (key == null) {
    key = crypto.subtle.importKey("raw", b64Decode(keyB64) as BufferSource, { name: "AES-GCM" }, false, ["decrypt"]);
    keyCache.set(`aes:${keyB64}`, key);
  }
  return key;
}

/** Ed25519 envelope check + expiry; null when not a valid relay token. */
export async function verifyRelayToken(publicKeyB64: string, token: string, now: Date): Promise<RelayClaims | null> {
  const parts = token.split(".");
  if (parts.length !== 3 || parts[0] !== "v2") {
    return null;
  }
  const signature = b64urlDecode(parts[2]);
  const body = parts[1];
  if (signature == null) {
    return null;
  }
  try {
    const ok = await crypto.subtle.verify({ name: "Ed25519" }, await verifyKey(publicKeyB64), signature as BufferSource, new TextEncoder().encode(body) as BufferSource);
    if (!ok) {
      return null;
    }
  } catch {
    return null;
  }
  const payload = b64urlDecode(body);
  if (payload == null) {
    return null;
  }
  let claims: RelayClaims;
  try {
    claims = JSON.parse(new TextDecoder().decode(payload)) as RelayClaims;
  } catch {
    return null;
  }
  if (claims?.t !== "rl" || typeof claims.exp !== "number" || claims.exp <= now.getTime()) {
    return null;
  }
  if (typeof claims.f !== "string" || typeof claims.a !== "string" || typeof claims.dt !== "string") {
    return null;
  }
  return claims;
}

/** Opens the sealed Drive access token (nonce‖ciphertext, AAD = file id). */
export async function openSealedToken(tokenKeyB64: string, sealed: string, fileId: string): Promise<string | null> {
  const bytes = b64urlDecode(sealed);
  if (bytes == null || bytes.length < 12) {
    return null;
  }
  try {
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: bytes.slice(0, 12) as BufferSource, additionalData: new TextEncoder().encode(fileId) as BufferSource },
      await tokenKey(tokenKeyB64),
      bytes.slice(12) as BufferSource
    );
    return new TextDecoder().decode(plain);
  } catch {
    return null;
  }
}

/** Forward the request to the box untouched (incl. WebSocket upgrades). */
export async function forwardToBox(env: LaneDEnv, request: Request): Promise<Response> {
  const box = env.BOX_URL;
  if (!box) {
    return Response.json({ error: "misconfigured", message: "BOX_URL is not set.", status: 503 }, { status: 503 });
  }
  const incoming = new URL(request.url);
  const target = new URL(box);
  target.pathname = incoming.pathname;
  target.search = incoming.search;
  const forwarded = new Request(target.toString(), request);
  forwarded.headers.set(ENTRY_ORIGIN_HEADER, incoming.origin);
  if (env.INTERNAL_API_SECRET) {
    forwarded.headers.set(INTERNAL_SECRET_HEADER, env.INTERNAL_API_SECRET);
  }
  // WebSocket pass-through: returning the 101 response hands the socket to
  // the runtime, which pipes frames between client and box from then on.
  return fetch(forwarded, { redirect: "manual" });
}

async function freshDriveToken(env: LaneDEnv, token: string): Promise<{ accessToken: string; fileId: string } | null> {
  if (!env.BOX_URL || !env.INTERNAL_API_SECRET) {
    return null;
  }
  try {
    const response = await fetch(`${env.BOX_URL}/internal/relay/authorize`, {
      method: "POST",
      headers: { "content-type": "application/json", [INTERNAL_SECRET_HEADER]: env.INTERNAL_API_SECRET },
      body: JSON.stringify({ token })
    });
    if (!response.ok) {
      return null;
    }
    const body = await response.json() as { accessToken?: string; fileId?: string };
    return body.accessToken && body.fileId ? { accessToken: body.accessToken, fileId: body.fileId } : null;
  } catch {
    return null;
  }
}

/**
 * Relay a blob GET straight from Drive. Returns null when the request should
 * be forwarded to the box instead (no/invalid token, unsealable, or Drive
 * refusing even after a token refresh).
 */
export async function relayBlob(env: LaneDEnv, request: Request, now = new Date()): Promise<Response | null> {
  const token = request.headers.get(RELAY_TOKEN_HEADER);
  if (!token) {
    // Legacy fleet (≤0.4.5 plans carry no token) or a direct box client whose
    // plan predates relay keys: the box streams it.
    return null;
  }
  if (!env.RELAY_PUBLIC_KEY || !env.RELAY_TOKEN_KEY) {
    console.warn("SharedWorld relay fallback", { reason: "relay keys not configured" });
    return null;
  }
  const claims = await verifyRelayToken(env.RELAY_PUBLIC_KEY, token, now);
  if (claims == null) {
    console.warn("SharedWorld relay fallback", { reason: "token rejected (signature/shape/expiry)" });
    return null;
  }
  let accessToken = await openSealedToken(env.RELAY_TOKEN_KEY, claims.dt, claims.f);
  let fileId = claims.f;
  if (accessToken == null) {
    const fresh = await freshDriveToken(env, token);
    if (fresh == null) {
      console.warn("SharedWorld relay fallback", { reason: "sealed token unreadable and box refresh failed" });
      return null;
    }
    accessToken = fresh.accessToken;
    fileId = fresh.fileId;
  }
  const base = env.GOOGLE_DRIVE_API_BASE ?? "https://www.googleapis.com";
  const driveFetch = (bearer: string) => {
    const headers = new Headers({ authorization: `Bearer ${bearer}` });
    const range = request.headers.get("range");
    if (range) {
      headers.set("range", range);
    }
    return fetch(`${base}/drive/v3/files/${encodeURIComponent(fileId)}?alt=media`, { headers });
  };
  let upstream = await driveFetch(accessToken);
  if (upstream.status === 401) {
    // The sealed token aged out (plan older than the Drive token): one refresh via the box.
    const fresh = await freshDriveToken(env, token);
    if (fresh == null) {
      console.warn("SharedWorld relay fallback", { reason: "Drive 401 and box refresh failed" });
      return null;
    }
    fileId = fresh.fileId;
    upstream = await driveFetch(fresh.accessToken);
  }
  if (upstream.status === 416) {
    return Response.json({ error: "range_not_satisfiable", message: "Requested range is beyond the end of the stored blob.", status: 416 }, { status: 416 });
  }
  if (upstream.status === 404) {
    return Response.json({ error: "blob_not_found", message: "Blob not found.", status: 404 }, { status: 404 });
  }
  if (!upstream.ok) {
    // Let the box answer (it owns the error vocabulary and the retry ladder).
    console.warn("SharedWorld relay fallback", { reason: "Drive refused", status: upstream.status });
    return null;
  }
  const headers = new Headers();
  headers.set("content-type", upstream.headers.get("content-type") ?? "application/octet-stream");
  headers.set("accept-ranges", "bytes");
  for (const name of ["content-length", "content-range"]) {
    const value = upstream.headers.get(name);
    if (value) {
      headers.set(name, value);
    }
  }
  return new Response(upstream.body, { status: upstream.status === 206 ? 206 : 200, headers });
}

export function createLaneDApp(env: LaneDEnv) {
  return {
    async fetch(request: Request): Promise<Response> {
      const url = new URL(request.url);
      if (request.method === "GET" && BLOB_ROUTE.test(url.pathname)) {
        const relayed = await relayBlob(env, request);
        if (relayed != null) {
          return relayed;
        }
      }
      return forwardToBox(env, request);
    },
    /** No cron work in lane D (the box runs its own jobs). */
    async scheduled(): Promise<number> {
      return 0;
    }
  };
}

export function isLaneD(env: LaneDEnv): boolean {
  return (env.MODE ?? "").toLowerCase() === "lane-d";
}
