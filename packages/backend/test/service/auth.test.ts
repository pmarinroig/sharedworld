import { describe, expect, test } from "bun:test";

import type { AuthCompleteRequest } from "../../../shared/src/index.ts";

import { MemorySharedWorldRepository } from "../../src/memory-repository.ts";
import { createBlobSigner, createTestService, service } from "../support/service-fixtures.ts";

const DEV_AUTH_SECRET = "test-dev-auth-secret";

describe("SharedWorldService auth", () => {
  test("auth challenge cannot be replayed", async () => {
    const instance = service();
    const challenge = await instance.createChallenge();
    expect(challenge.serverId).toMatch(/^[0-9a-f]{32}$/);
    const request: AuthCompleteRequest = {
      serverId: challenge.serverId,
      playerName: "Owner"
    };

    const session = await instance.completeAuth(request);
    expect(session.playerUuid).toBe("player-owner");
    await expect(instance.completeAuth(request)).rejects.toThrow("already been used");
  });

  test("retries short propagation lag before succeeding", async () => {
    let attempts = 0;
    const instance = createTestService(
      new MemorySharedWorldRepository(),
      {
        async verifyJoin(playerName, serverId) {
          attempts += 1;
          if (attempts < 3) {
            return null;
          }
          expect(playerName).toBe("Owner");
          expect(serverId).toMatch(/^[0-9a-f]{32}$/);
          return {
            playerUuid: "player-owner",
            playerName: "Owner"
          };
        }
      }
    );

    const challenge = await instance.createChallenge();
    const session = await instance.completeAuth({
      serverId: challenge.serverId,
      playerName: "Owner"
    });

    expect(session.playerUuid).toBe("player-owner");
    expect(attempts).toBe(3);
  });

  test("developer auth uses the dedicated dev endpoint", async () => {
    const instance = createTestService(
      new MemorySharedWorldRepository(),
      {
        async verifyJoin() {
          throw new Error("should not call Mojang verifier in dev mode");
        }
      },
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
      new MemorySharedWorldRepository(),
      {
        async verifyJoin() {
          throw new Error("should not call Mojang verifier in dev mode");
        }
      },
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
