import { HttpError } from "../http.ts";

/**
 * Offline verification of Mojang player certificates (the 1.19+ chat-signing
 * keypair). Mojang signs (profile UUID, expiry, RSA public key); the client
 * proves possession of the private key by signing the challenge nonce. No
 * Mojang endpoint is contacted here — only the cached services key set is
 * needed (see services-keys.ts).
 */

const UUID_HEX = /^[0-9a-f]{32}$/;

/**
 * The exact byte layout Mojang's signature covers, mirroring vanilla
 * ProfilePublicKey.Data#signedPayload: big-endian UUID msb (8) | UUID lsb (8)
 * | expiry epoch millis (8) | X.509 SPKI DER of the public key.
 */
export function buildCertificateSignedPayload(
  playerUuidHex: string,
  expiresAtEpochMs: number,
  publicKeyDer: Uint8Array
): Uint8Array {
  if (!UUID_HEX.test(playerUuidHex)) {
    throw new HttpError(403, "certificate_invalid", "Minecraft profile certificate is invalid.");
  }
  const payload = new Uint8Array(24 + publicKeyDer.length);
  const view = new DataView(payload.buffer);
  view.setBigUint64(0, BigInt(`0x${playerUuidHex.slice(0, 16)}`));
  view.setBigUint64(8, BigInt(`0x${playerUuidHex.slice(16, 32)}`));
  view.setBigInt64(16, BigInt(expiresAtEpochMs));
  payload.set(publicKeyDer, 24);
  return payload;
}

/** True when any of the services keys validly signed the payload (SHA1withRSA). */
export async function verifyCertificateSignature(
  payload: Uint8Array,
  keySignature: Uint8Array,
  servicesKeysDer: Uint8Array[]
): Promise<boolean> {
  for (const keyDer of servicesKeysDer) {
    try {
      const key = await crypto.subtle.importKey(
        "spki",
        keyDer as BufferSource,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" },
        false,
        ["verify"]
      );
      if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, keySignature as BufferSource, payload as BufferSource)) {
        return true;
      }
    } catch {
      // A malformed services key must not mask a valid one later in the set.
    }
  }
  return false;
}

/** True when `signature` is the certified key's SHA256withRSA signature over the nonce bytes. */
export async function verifyNonceSignature(
  publicKeyDer: Uint8Array,
  nonce: string,
  signature: Uint8Array
): Promise<boolean> {
  let key: CryptoKey;
  try {
    key = await crypto.subtle.importKey(
      "spki",
      publicKeyDer as BufferSource,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"]
    );
  } catch {
    return false;
  }
  return crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    signature as BufferSource,
    new TextEncoder().encode(nonce) as BufferSource
  );
}

export function decodeBase64Field(value: string, errorCode: string, message: string, status = 403): Uint8Array {
  try {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) {
      bytes[index] = binary.charCodeAt(index);
    }
    return bytes;
  } catch {
    throw new HttpError(status, errorCode, message);
  }
}
