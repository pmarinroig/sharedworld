import type { Env } from "../env.ts";

/**
 * Signed blob-authority stamps: an HMAC-SHA256 claim minted at plan time —
 * AFTER full coordinator authority validation — that lets the high-frequency
 * blob routes (relay PUT, blob-session create, blob-commit) verify host
 * authority cryptographically plus one runtime-mirror row read, instead of a
 * coordinator DO round-trip per artifact. Finalize keeps full DO authority:
 * a wrongly-accepted blob is at worst an orphaned content-addressed object,
 * never a wrong manifest.
 *
 * Shape: `v1.<b64url(claims-json)>.<b64url(hmac)>` with claims
 * `{ w: worldId, e: runtimeEpoch, k: storageKey, exp: epochMs }`. The
 * per-storage-key scope means a leaked stamp cannot write other keys; the
 * TTL covers long multi-artifact uploads and bounds rotation exposure.
 *
 * Keys: SIGNING_SECRET signs; verification also accepts
 * SIGNING_SECRET_PREVIOUS (rotation = shift current to previous). With no
 * secret configured, minting returns null and verification always fails —
 * every caller then falls back to the coordinator path, so a missing secret
 * can never brick uploads.
 */
export interface BlobStampClaims {
  w: string;
  e: number;
  k: string;
  exp: number;
}

export const BLOB_STAMP_TTL_MS = 60 * 60_000;

const STAMP_VERSION = "v1";
const encoder = new TextEncoder();
const keyCache = new Map<string, Promise<CryptoKey>>();

function hmacKey(secret: string): Promise<CryptoKey> {
  let key = keyCache.get(secret);
  if (key == null) {
    key = crypto.subtle.importKey(
      "raw",
      encoder.encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign", "verify"]
    );
    keyCache.set(secret, key);
  }
  return key;
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlDecode(text: string): Uint8Array | null {
  try {
    const padded = text.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (text.length % 4)) % 4);
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) {
      bytes[index] = binary.charCodeAt(index);
    }
    return bytes;
  } catch {
    return null;
  }
}

export async function mintBlobStamp(
  env: Pick<Env, "SIGNING_SECRET">,
  claims: { worldId: string; runtimeEpoch: number; storageKey: string },
  now: Date
): Promise<string | null> {
  const secret = env.SIGNING_SECRET;
  if (!secret) {
    return null;
  }
  const payload: BlobStampClaims = {
    w: claims.worldId,
    e: claims.runtimeEpoch,
    k: claims.storageKey,
    exp: now.getTime() + BLOB_STAMP_TTL_MS
  };
  const body = base64UrlEncode(encoder.encode(JSON.stringify(payload)));
  const signature = new Uint8Array(await crypto.subtle.sign("HMAC", await hmacKey(secret), encoder.encode(body)));
  return `${STAMP_VERSION}.${body}.${base64UrlEncode(signature)}`;
}

/**
 * Returns the stamped runtime epoch when the stamp is authentic, unexpired,
 * and scoped to exactly this world + storage key; null otherwise. Callers
 * must still check the epoch against the current runtime (mirror) — the
 * stamp proves the plan was authorized, not that the epoch is still live.
 */
export async function verifyBlobStamp(
  env: Pick<Env, "SIGNING_SECRET" | "SIGNING_SECRET_PREVIOUS">,
  stamp: string,
  expected: { worldId: string; storageKey: string },
  now: Date
): Promise<{ runtimeEpoch: number } | null> {
  const parts = stamp.split(".");
  if (parts.length !== 3 || parts[0] !== STAMP_VERSION) {
    return null;
  }
  const [, body, signatureText] = parts;
  const signature = base64UrlDecode(signatureText ?? "");
  if (body == null || signature == null) {
    return null;
  }
  const secrets = [env.SIGNING_SECRET, env.SIGNING_SECRET_PREVIOUS].filter((secret): secret is string => Boolean(secret));
  let authentic = false;
  for (const secret of secrets) {
    if (await crypto.subtle.verify("HMAC", await hmacKey(secret), toArrayBuffer(signature), encoder.encode(body))) {
      authentic = true;
      break;
    }
  }
  if (!authentic) {
    return null;
  }
  const payloadBytes = base64UrlDecode(body);
  if (payloadBytes == null) {
    return null;
  }
  let claims: BlobStampClaims;
  try {
    claims = JSON.parse(new TextDecoder().decode(payloadBytes)) as BlobStampClaims;
  } catch {
    return null;
  }
  if (typeof claims.exp !== "number" || claims.exp <= now.getTime()) {
    return null;
  }
  if (claims.w !== expected.worldId || claims.k !== expected.storageKey) {
    return null;
  }
  if (!Number.isSafeInteger(claims.e) || claims.e < 0) {
    return null;
  }
  return { runtimeEpoch: claims.e };
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}
