import { describe, expect, test } from "bun:test";

import { MemorySharedWorldRepository } from "../../src/memory-repository.ts";
import { authVerifier, createBlobSigner, createTestService, googleDriveStorageProvider } from "../support/service-fixtures.ts";

const OWNER_CTX = { playerUuid: "player-owner", playerName: "Owner" };
const GUEST_CTX = { playerUuid: "player-guest", playerName: "Guest" };

describe("SharedWorldService storage links", () => {
  test("creating a new storage link cancels older pending sessions for the same player", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, googleDriveStorageProvider(), {});

    const first = await instance.createStorageLink(OWNER_CTX, {}, new Date("2099-04-04T10:00:00.000Z"));
    const second = await instance.createStorageLink(OWNER_CTX, {}, new Date("2099-04-04T10:01:00.000Z"));

    expect(second.id).not.toBe(first.id);
    await expect(instance.getStorageLinkSession(OWNER_CTX, first.id)).resolves.toMatchObject({
      status: "cancelled",
      errorMessage: null
    });
    await expect(instance.getStorageLinkSession(OWNER_CTX, second.id)).resolves.toMatchObject({
      status: "pending"
    });
  });

  test("cancelling a pending storage link marks it cancelled", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, googleDriveStorageProvider(), {});

    const session = await instance.createStorageLink(OWNER_CTX, {}, new Date("2099-04-04T10:00:00.000Z"));
    const cancelled = await instance.cancelStorageLink(OWNER_CTX, session.id, new Date("2099-04-04T10:02:00.000Z"));

    expect(cancelled.status).toBe("cancelled");
    expect(cancelled.errorMessage).toBeNull();
  });

  test("cancelling another player's storage link is forbidden", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, googleDriveStorageProvider(), {});

    const session = await instance.createStorageLink(OWNER_CTX, {}, new Date("2099-04-04T10:00:00.000Z"));

    await expect(instance.cancelStorageLink(GUEST_CTX, session.id, new Date("2099-04-04T10:02:00.000Z"))).rejects.toThrow("does not belong");
  });

  test("completing a cancelled storage link is rejected", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, googleDriveStorageProvider(), {});

    const session = await instance.createStorageLink(OWNER_CTX, {}, new Date("2099-04-04T10:00:00.000Z"));
    await instance.cancelStorageLink(OWNER_CTX, session.id, new Date("2099-04-04T10:01:00.000Z"));

    await expect(instance.completeStorageLink(session.id, { sessionId: session.id }, new Date("2099-04-04T10:02:00.000Z"))).rejects.toThrow("no longer active");
  });
});
