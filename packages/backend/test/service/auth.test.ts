import { describe, expect, test } from "bun:test";

import { HttpError } from "../../src/http.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { createBlobSigner, createTestService, service } from "../support/service-fixtures.ts";

const DEV_AUTH_SECRET = "test-dev-auth-secret";

describe("SharedWorldService auth", () => {
  test("the legacy join flow is a network-free tombstone: terminal 403 telling the client to update", async () => {
    // Mojang answers 403 to all Cloudflare Workers egress on sessionserver,
    // so /auth/complete must never burn a subrequest (or even a D1 read) on
    // a flow that cannot succeed. Only clients ≤0.2.1 still call it.
    const instance = service();
    let caught: unknown = null;
    try {
      await instance.completeAuth({ serverId: "0000000000000000000000000000dead", playerName: "Owner" });
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).status).toBe(403);
    expect((caught as HttpError).code).toBe("identity_verification_failed");
    expect((caught as HttpError).message).toContain("update SharedWorld");
    // Terminal, not retryable: no Retry-After hint that would make an old
    // client loop against a permanently closed flow.
    expect((caught as HttpError).retryAfterSeconds).toBeUndefined();
  });

  test("the tombstone answers without consuming the challenge, which stays usable for cert auth", async () => {
    const repository = createSqliteRepository();
    const instance = createTestService(repository);
    const challenge = await instance.createChallenge();
    await expect(
      instance.completeAuth({ serverId: challenge.serverId, playerName: "Owner" })
    ).rejects.toThrow("update SharedWorld");
    // The challenge was never marked used — a mixed-version client that
    // retries through the cert flow still finds it consumable (cert-flow
    // challenge semantics are pinned in auth-cert.test.ts).
    const stored = await repository.getChallenge(challenge.serverId);
    expect(stored?.usedAt ?? null).toBeNull();
  });

  test("developer auth uses the dedicated dev endpoint", async () => {
    const instance = createTestService(
      createSqliteRepository(),
      createBlobSigner().signer,
      {
        ALLOW_DEV_AUTH: "true",
        ALLOW_DEV_INSECURE_E4MC: "true",
        DEV_AUTH_SECRET
      }
    );

    const session = await instance.completeDevAuth({
      playerUuid: "22222222222222222222222222222222",
      playerName: "GuestB",
      secret: DEV_AUTH_SECRET
    });

    expect(session.playerUuid).toBe("22222222222222222222222222222222");
    expect(session.playerName).toBe("GuestB");
    expect(session.allowInsecureE4mc).toBe(true);
  });

  test("developer auth keeps insecure e4mc disabled unless the backend allows it", async () => {
    const instance = createTestService(
      createSqliteRepository(),
      createBlobSigner().signer,
      {
        ALLOW_DEV_AUTH: "true",
        DEV_AUTH_SECRET
      }
    );

    const session = await instance.completeDevAuth({
      playerUuid: "33333333333333333333333333333333",
      playerName: "GuestC",
      secret: DEV_AUTH_SECRET
    });

    expect(session.allowInsecureE4mc).toBe(false);
  });
});
