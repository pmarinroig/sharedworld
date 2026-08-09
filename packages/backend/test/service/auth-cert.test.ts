import { describe, expect, spyOn, test } from "bun:test";

import type { AuthCompleteCertRequest } from "../../../shared/src/index.ts";

import { HttpError } from "../../src/http.ts";
import { buildCertificateSignedPayload } from "../../src/auth/certificate.ts";
import { MojangServicesKeyProvider } from "../../src/auth/services-keys.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { createTestService } from "../support/service-fixtures.ts";

/**
 * Certificate-based auth (/auth/complete-cert): every signature here is real
 * RSA via WebCrypto — a test-generated "Mojang services" keypair signs the
 * profile certificate exactly like Mojang does (SHA1withRSA over the vanilla
 * ProfilePublicKey payload), and the profile key signs the challenge nonce
 * (SHA256withRSA) like the mod does.
 */

const PLAYER_UUID = "3f9a2b1c4d5e6f708192a3b4c5d6e7f8";
const OTHER_UUID = "00000000000000000000000000000001";

type TestKeys = {
  servicesPublicB64: string;
  profilePublicDer: Uint8Array;
  signCertificate(uuidHex: string, expiresAtMs: number, publicKeyDer: Uint8Array): Promise<Uint8Array>;
  signNonce(nonce: string): Promise<Uint8Array>;
};

async function generateTestKeys(): Promise<TestKeys> {
  const rsa = (hash: string) => ({
    name: "RSASSA-PKCS1-v1_5",
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash
  });
  const services = await crypto.subtle.generateKey(rsa("SHA-1"), true, ["sign", "verify"]);
  const profile = await crypto.subtle.generateKey(rsa("SHA-256"), true, ["sign", "verify"]);
  const servicesPublicDer = new Uint8Array(await crypto.subtle.exportKey("spki", services.publicKey));
  const profilePublicDer = new Uint8Array(await crypto.subtle.exportKey("spki", profile.publicKey));
  return {
    servicesPublicB64: Buffer.from(servicesPublicDer).toString("base64"),
    profilePublicDer,
    async signCertificate(uuidHex, expiresAtMs, publicKeyDer) {
      const payload = buildCertificateSignedPayload(uuidHex, expiresAtMs, publicKeyDer);
      return new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", services.privateKey, payload as BufferSource));
    },
    async signNonce(nonce) {
      return new Uint8Array(
        await crypto.subtle.sign("RSASSA-PKCS1-v1_5", profile.privateKey, new TextEncoder().encode(nonce) as BufferSource)
      );
    }
  };
}

const keysPromise = generateTestKeys();

function certService(keys: TestKeys, repository = createSqliteRepository()) {
  return createTestService(
    repository,
    {
      async verifyJoin() {
        throw new Error("certificate auth must never call the Mojang sessionserver verifier");
      }
    },
    undefined,
    {
      SESSION_TTL_HOURS: "24",
      MOJANG_PLAYER_CERTIFICATE_KEYS: keys.servicesPublicB64
    }
  );
}

async function validRequest(keys: TestKeys, serverId: string, overrides: Partial<AuthCompleteCertRequest> = {}): Promise<AuthCompleteCertRequest> {
  const expiresAtMs = Date.now() + 48 * 60 * 60_000;
  return {
    serverId,
    playerUuid: PLAYER_UUID,
    playerName: "HostA",
    publicKey: Buffer.from(keys.profilePublicDer).toString("base64"),
    publicKeyExpiresAtMs: expiresAtMs,
    keySignature: Buffer.from(await keys.signCertificate(PLAYER_UUID, expiresAtMs, keys.profilePublicDer)).toString("base64"),
    nonceSignature: Buffer.from(await keys.signNonce(serverId)).toString("base64"),
    ...overrides
  };
}

async function expectHttpError(promise: Promise<unknown>, status: number, code: string): Promise<HttpError> {
  let caught: unknown = null;
  try {
    await promise;
  } catch (error) {
    caught = error;
  }
  expect(caught).toBeInstanceOf(HttpError);
  expect((caught as HttpError).status).toBe(status);
  expect((caught as HttpError).code).toBe(code);
  return caught as HttpError;
}

describe("completeCertAuth", () => {
  test("a validly signed certificate + nonce signature mints a session and consumes the challenge", async () => {
    const keys = await keysPromise;
    const instance = certService(keys);
    const challenge = await instance.createChallenge();
    const request = await validRequest(keys, challenge.serverId);

    const session = await instance.completeCertAuth(request);

    expect(session.playerUuid).toBe(PLAYER_UUID);
    expect(session.playerName).toBe("HostA");
    expect(session.token).toMatch(/^session_/);
    // Challenge is single-use even across auth flavors.
    await expectHttpError(instance.completeCertAuth(request), 409, "challenge_used");
  });

  test("an unknown challenge is rejected before any crypto runs", async () => {
    const keys = await keysPromise;
    const instance = certService(keys);
    const request = await validRequest(keys, "0000000000000000000000000000dead");
    await expectHttpError(instance.completeCertAuth(request), 404, "challenge_not_found");
  });

  test("an expired certificate is rejected as certificate_expired", async () => {
    const keys = await keysPromise;
    const instance = certService(keys);
    const challenge = await instance.createChallenge();
    const expiredMs = Date.now() - 60_000;
    const request = await validRequest(keys, challenge.serverId, {
      publicKeyExpiresAtMs: expiredMs,
      keySignature: Buffer.from(await keys.signCertificate(PLAYER_UUID, expiredMs, keys.profilePublicDer)).toString("base64")
    });
    await expectHttpError(instance.completeCertAuth(request), 403, "certificate_expired");
  });

  test("a certificate signed for a different UUID is rejected as certificate_invalid", async () => {
    const keys = await keysPromise;
    const instance = certService(keys);
    const challenge = await instance.createChallenge();
    const expiresAtMs = Date.now() + 60 * 60_000;
    // Mojang signed OTHER_UUID's certificate; the caller claims PLAYER_UUID.
    const request = await validRequest(keys, challenge.serverId, {
      publicKeyExpiresAtMs: expiresAtMs,
      keySignature: Buffer.from(await keys.signCertificate(OTHER_UUID, expiresAtMs, keys.profilePublicDer)).toString("base64")
    });
    await expectHttpError(instance.completeCertAuth(request), 403, "certificate_invalid");
  });

  test("a tampered Mojang signature is rejected as certificate_invalid", async () => {
    const keys = await keysPromise;
    const instance = certService(keys);
    const challenge = await instance.createChallenge();
    const request = await validRequest(keys, challenge.serverId);
    const tampered = Buffer.from(request.keySignature, "base64");
    tampered[10] ^= 0xff;
    request.keySignature = tampered.toString("base64");
    await expectHttpError(instance.completeCertAuth(request), 403, "certificate_invalid");
  });

  test("a nonce signed by a different key is rejected as signature_invalid", async () => {
    const keys = await keysPromise;
    const otherKeys = await generateTestKeys();
    const instance = certService(keys);
    const challenge = await instance.createChallenge();
    const request = await validRequest(keys, challenge.serverId, {
      nonceSignature: Buffer.from(await otherKeys.signNonce(challenge.serverId)).toString("base64")
    });
    await expectHttpError(instance.completeCertAuth(request), 403, "signature_invalid");
  });

  test("a nonce signature over a different challenge is rejected as signature_invalid", async () => {
    const keys = await keysPromise;
    const instance = certService(keys);
    const challenge = await instance.createChallenge();
    const request = await validRequest(keys, challenge.serverId, {
      nonceSignature: Buffer.from(await keys.signNonce("a-different-nonce")).toString("base64")
    });
    await expectHttpError(instance.completeCertAuth(request), 403, "signature_invalid");
  });

  test("a failed verification leaves the challenge reusable for the fallback flow", async () => {
    const keys = await keysPromise;
    const instance = certService(keys);
    const challenge = await instance.createChallenge();
    const bad = await validRequest(keys, challenge.serverId, { nonceSignature: Buffer.from("nope").toString("base64") });
    await expectHttpError(instance.completeCertAuth(bad), 403, "signature_invalid");

    const good = await validRequest(keys, challenge.serverId);
    const session = await instance.completeCertAuth(good);
    expect(session.playerUuid).toBe(PLAYER_UUID);
  });

  test("a certificate rejection leaves a Workers Logs line naming the code and player", async () => {
    // The client falls back to the join flow silently on these, so this warn
    // is the only production record that a real certificate was rejected.
    const keys = await keysPromise;
    const instance = certService(keys);
    const challenge = await instance.createChallenge();
    const request = await validRequest(keys, challenge.serverId, {
      nonceSignature: Buffer.from("nope").toString("base64")
    });

    const warn = spyOn(console, "warn").mockImplementation(() => {});
    try {
      await expectHttpError(instance.completeCertAuth(request), 403, "signature_invalid");
      const logged = warn.mock.calls.find((call) => call[0] === "SharedWorld certificate auth rejected");
      expect(logged).toBeDefined();
      expect(logged?.[1]).toMatchObject({
        code: "signature_invalid",
        status: 403,
        playerName: "HostA",
        playerUuid: PLAYER_UUID
      });
    } finally {
      warn.mockRestore();
    }
  });

  test("a malformed player name is rejected before any crypto runs", async () => {
    const keys = await keysPromise;
    const instance = certService(keys);
    const challenge = await instance.createChallenge();
    const request = await validRequest(keys, challenge.serverId, { playerName: "not a name!" });
    await expectHttpError(instance.completeCertAuth(request), 400, "invalid_player_name");
  });
});

describe("MojangServicesKeyProvider", () => {
  test("fetches once, caches in the repository, and serves the cache within the TTL", async () => {
    const keys = await keysPromise;
    let hits = 0;
    const server = Bun.serve({
      port: 0,
      fetch() {
        hits += 1;
        return Response.json({
          profilePropertyKeys: [{ publicKey: "aWdub3JlZA==" }],
          playerCertificateKeys: [{ publicKey: keys.servicesPublicB64 }]
        });
      }
    });
    try {
      const repository = createSqliteRepository();
      const provider = new MojangServicesKeyProvider(repository, {
        MOJANG_SERVICES_PUBLICKEYS_ENDPOINT: `http://127.0.0.1:${server.port}/publickeys`
      });
      const first = await provider.playerCertificateKeys(new Date());
      const second = await provider.playerCertificateKeys(new Date());
      expect(first).toHaveLength(1);
      expect(second).toHaveLength(1);
      expect(hits).toBe(1);
      expect(await repository.getMojangServicesKeys()).not.toBeNull();
    } finally {
      server.stop(true);
    }
  });

  test("serves the stale cache when the refresh fails, and fails 503 with no cache at all", async () => {
    const keys = await keysPromise;
    const closedPortServer = Bun.serve({ port: 0, fetch: () => new Response(null) });
    const closedPort = closedPortServer.port;
    closedPortServer.stop(true);
    const env = { MOJANG_SERVICES_PUBLICKEYS_ENDPOINT: `http://127.0.0.1:${closedPort}/publickeys` };

    const repository = createSqliteRepository();
    // A cache far older than the TTL still beats a failed login.
    await repository.putMojangServicesKeys("2020-01-01T00:00:00.000Z", JSON.stringify([keys.servicesPublicB64]));
    const stale = new MojangServicesKeyProvider(repository, env);
    expect(await stale.playerCertificateKeys(new Date())).toHaveLength(1);

    const empty = new MojangServicesKeyProvider(createSqliteRepository(), env);
    await expectHttpError(empty.playerCertificateKeys(new Date()), 503, "identity_verification_unavailable");
  });

  test("a failed refresh throttles further fetch attempts instead of retrying per request", async () => {
    const keys = await keysPromise;
    let hits = 0;
    const server = Bun.serve({
      port: 0,
      fetch() {
        hits += 1;
        return new Response(null, { status: 403 });
      }
    });
    try {
      const repository = createSqliteRepository();
      await repository.putMojangServicesKeys("2020-01-01T00:00:00.000Z", JSON.stringify([keys.servicesPublicB64]));
      const provider = new MojangServicesKeyProvider(repository, {
        MOJANG_SERVICES_PUBLICKEYS_ENDPOINT: `http://127.0.0.1:${server.port}/publickeys`
      });
      expect(await provider.playerCertificateKeys(new Date())).toHaveLength(1);
      expect(await provider.playerCertificateKeys(new Date())).toHaveLength(1);
      expect(hits).toBe(1);
      // The row re-expires later (one retry per throttle window), rather than
      // being refreshed to a full TTL by a failure.
      const restamped = await repository.getMojangServicesKeys();
      expect(restamped).not.toBeNull();
      const age = Date.now() - new Date(restamped!.fetchedAt).getTime();
      expect(age).toBeGreaterThan(60 * 60_000);
    } finally {
      server.stop(true);
    }
  });
});
