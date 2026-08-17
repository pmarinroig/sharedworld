import type { Env } from "../env.ts";

/**
 * Signed blob-authority stamps: HMAC-SHA256 claims minted at plan time —
 * AFTER full authority validation — that let the high-frequency blob routes
 * verify authority cryptographically instead of paying a coordinator DO
 * round-trip per artifact.
 *
 * Two claim kinds share one envelope (`v1.<b64url(claims-json)>.<b64url(hmac)>`)
 * and one header; the route decides which verifier applies:
 *
 * - Upload stamps `{ w, e, k, exp }` (world, runtime epoch, storage key):
 *   relay PUT, blob-session create, blob-commit verify host authority with
 *   the stamp plus one runtime-mirror row read. Finalize keeps full DO
 *   authority: a wrongly-accepted blob is at worst an orphaned
 *   content-addressed object, never a wrong manifest.
 * - Download stamps `{ t: "dl", w, k, p, exp }` (world, storage key, player):
 *   the relay GET verifies read access with the stamp alone. Minted only for
 *   the authenticated member the plan/summary was built for, and bound to
 *   that player, so a leaked header is useless without their session. The
 *   only semantic change versus the coordinator path: a member revoked
 *   mid-download may finish fetching blobs they already had a plan for,
 *   until the stamp expires — content-addressed bytes they were already
 *   authorized to read.
 *
 * The per-storage-key scope means a leaked stamp cannot touch other keys;
 * the TTLs cover long multi-artifact transfers and bound rotation exposure.
 * A verifier never accepts the other kind's claims.
 *
 * Keys: SIGNING_SECRET signs; verification also accepts
 * SIGNING_SECRET_PREVIOUS (rotation = shift current to previous). With no
 * secret configured, minting returns null and verification always fails —
 * every caller then falls back to the coordinator path, so a missing secret
 * can never brick transfers.
 */
export interface BlobStampClaims {
  w: string;
  e: number;
  k: string;
  exp: number;
}

export interface DownloadStampClaims {
  t: "dl";
  w: string;
  k: string;
  p: string;
  exp: number;
}

export const BLOB_STAMP_TTL_MS = 60 * 60_000;
/**
 * Downloads get a longer horizon: a slow link syncing a multi-GB world can
 * outlive an hour, and an expired stamp merely drops that GET back onto the
 * coordinator path (correct, just slower).
 */
export const DOWNLOAD_STAMP_TTL_MS = 3 * 60 * 60_000;

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

async function signClaims(secret: string, payload: unknown): Promise<string> {
  const body = base64UrlEncode(encoder.encode(JSON.stringify(payload)));
  const signature = new Uint8Array(await crypto.subtle.sign("HMAC", await hmacKey(secret), encoder.encode(body)));
  return `${STAMP_VERSION}.${body}.${base64UrlEncode(signature)}`;
}

/** Envelope check only: authentic under a configured secret and well-formed JSON. */
async function openClaims(
  env: Pick<Env, "SIGNING_SECRET" | "SIGNING_SECRET_PREVIOUS">,
  stamp: string
): Promise<Record<string, unknown> | null> {
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
  try {
    const parsed = JSON.parse(new TextDecoder().decode(payloadBytes)) as unknown;
    return parsed != null && typeof parsed === "object" ? parsed as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

function unexpired(claims: Record<string, unknown>, now: Date): boolean {
  return typeof claims.exp === "number" && claims.exp > now.getTime();
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
  return signClaims(secret, payload);
}

/**
 * Returns the stamped runtime epoch when the stamp is authentic, unexpired,
 * an upload stamp, and scoped to exactly this world + storage key; null
 * otherwise. Callers must still check the epoch against the current runtime
 * (mirror) — the stamp proves the plan was authorized, not that the epoch is
 * still live.
 */
export async function verifyBlobStamp(
  env: Pick<Env, "SIGNING_SECRET" | "SIGNING_SECRET_PREVIOUS">,
  stamp: string,
  expected: { worldId: string; storageKey: string },
  now: Date
): Promise<{ runtimeEpoch: number } | null> {
  const claims = await openClaims(env, stamp);
  if (claims == null || !unexpired(claims, now) || claims.t != null) {
    return null;
  }
  if (claims.w !== expected.worldId || claims.k !== expected.storageKey) {
    return null;
  }
  const epoch = claims.e;
  if (typeof epoch !== "number" || !Number.isSafeInteger(epoch) || epoch < 0) {
    return null;
  }
  return { runtimeEpoch: epoch };
}

export async function mintDownloadStamp(
  env: Pick<Env, "SIGNING_SECRET">,
  claims: { worldId: string; storageKey: string; playerUuid: string },
  now: Date
): Promise<string | null> {
  const secret = env.SIGNING_SECRET;
  if (!secret) {
    return null;
  }
  const payload: DownloadStampClaims = {
    t: "dl",
    w: claims.worldId,
    k: claims.storageKey,
    p: claims.playerUuid,
    exp: now.getTime() + DOWNLOAD_STAMP_TTL_MS
  };
  return signClaims(secret, payload);
}

/**
 * True when the stamp is authentic, unexpired, a download stamp, and scoped
 * to exactly this world + storage key + player. The relay GET then serves the
 * blob without re-deriving membership from the coordinator.
 */
export async function verifyDownloadStamp(
  env: Pick<Env, "SIGNING_SECRET" | "SIGNING_SECRET_PREVIOUS">,
  stamp: string,
  expected: { worldId: string; storageKey: string; playerUuid: string },
  now: Date
): Promise<boolean> {
  const claims = await openClaims(env, stamp);
  if (claims == null || !unexpired(claims, now) || claims.t !== "dl") {
    return false;
  }
  return claims.w === expected.worldId && claims.k === expected.storageKey && claims.p === expected.playerUuid;
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}
