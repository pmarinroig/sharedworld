import { describe, expect, test } from "bun:test";

import type { StorageAccountRecord } from "../../src/repository.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { createBlobSigner, createTestService, googleDriveStorageProvider } from "../support/service-fixtures.ts";

const OWNER_CTX = { playerUuid: "player-owner", playerName: "Owner" };
const GUEST_CTX = { playerUuid: "player-guest", playerName: "Guest" };

function storageAccountFixture(overrides: Partial<StorageAccountRecord> = {}): StorageAccountRecord {
  return {
    id: overrides.id ?? "storage-1",
    provider: overrides.provider ?? "google-drive",
    ownerPlayerUuid: overrides.ownerPlayerUuid ?? OWNER_CTX.playerUuid,
    externalAccountId: overrides.externalAccountId ?? "google-sub-1",
    email: overrides.email ?? "owner@gmail.com",
    displayName: overrides.displayName ?? "Owner",
    accessToken: overrides.accessToken ?? "at-1",
    refreshToken: overrides.refreshToken !== undefined ? overrides.refreshToken : "rt-1",
    tokenExpiresAt: overrides.tokenExpiresAt ?? "2099-01-01T00:00:00.000Z",
    createdAt: overrides.createdAt ?? "2099-01-01T00:00:00.000Z",
    updatedAt: overrides.updatedAt ?? "2099-01-01T00:00:00.000Z"
  };
}

describe("SharedWorldService storage links", () => {
  test("creating a new storage link cancels older pending sessions for the same player", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});

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
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});

    const session = await instance.createStorageLink(OWNER_CTX, {}, new Date("2099-04-04T10:00:00.000Z"));
    const cancelled = await instance.cancelStorageLink(OWNER_CTX, session.id, new Date("2099-04-04T10:02:00.000Z"));

    expect(cancelled.status).toBe("cancelled");
    expect(cancelled.errorMessage).toBeNull();
  });

  test("cancelling another player's storage link is forbidden", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});

    const session = await instance.createStorageLink(OWNER_CTX, {}, new Date("2099-04-04T10:00:00.000Z"));

    await expect(instance.cancelStorageLink(GUEST_CTX, session.id, new Date("2099-04-04T10:02:00.000Z"))).rejects.toThrow("does not belong");
  });

  test("a first-time link forces the Google consent screen", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});

    const session = await instance.createStorageLink(OWNER_CTX, {}, new Date("2099-04-04T10:00:00.000Z"));

    expect(session.authUrl).toContain("prompt=consent");
    expect(session.authUrl).toContain("access_type=offline");
  });

  test("a player with a refreshable linked account skips the consent screen", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});
    await repository.createOrUpdateStorageAccount(storageAccountFixture({ refreshToken: "rt-1" }));

    const session = await instance.createStorageLink(OWNER_CTX, {}, new Date("2099-04-04T10:00:00.000Z"));

    expect(session.authUrl).not.toContain("prompt=consent");
    expect(session.authUrl).toContain("access_type=offline");
  });

  test("forceConsent overrides the consent skip", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});
    await repository.createOrUpdateStorageAccount(storageAccountFixture({ refreshToken: "rt-1" }));

    const session = await instance.createStorageLink(OWNER_CTX, { forceConsent: true }, new Date("2099-04-04T10:00:00.000Z"));

    expect(session.authUrl).toContain("prompt=consent");
  });

  test("an account whose refresh token was lost still forces consent", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});
    await repository.createOrUpdateStorageAccount(storageAccountFixture({ refreshToken: null }));

    const session = await instance.createStorageLink(OWNER_CTX, {}, new Date("2099-04-04T10:00:00.000Z"));

    expect(session.authUrl).toContain("prompt=consent");
  });

  test("getStorageAccount reports not linked, unhealthy, and healthy states", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});

    await expect(instance.getStorageAccount(OWNER_CTX)).resolves.toEqual({
      linked: false,
      provider: "google-drive",
      email: null,
      displayName: null,
      healthy: false
    });

    await repository.createOrUpdateStorageAccount(storageAccountFixture({ id: "storage-broken", externalAccountId: "sub-broken", refreshToken: null }));
    await expect(instance.getStorageAccount(OWNER_CTX)).resolves.toMatchObject({ linked: true, healthy: false });

    await repository.createOrUpdateStorageAccount(
      storageAccountFixture({ id: "storage-good", externalAccountId: "sub-good", email: "owner@gmail.com", refreshToken: "rt-1" })
    );
    await expect(instance.getStorageAccount(OWNER_CTX)).resolves.toMatchObject({
      linked: true,
      healthy: true,
      email: "owner@gmail.com"
    });
  });

  test("another player's account does not satisfy the consent skip or the summary", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});
    await repository.createOrUpdateStorageAccount(storageAccountFixture({ refreshToken: "rt-1" }));

    const session = await instance.createStorageLink(GUEST_CTX, {}, new Date("2099-04-04T10:00:00.000Z"));

    expect(session.authUrl).toContain("prompt=consent");
    await expect(instance.getStorageAccount(GUEST_CTX)).resolves.toMatchObject({ linked: false });
  });

  test("completing a cancelled storage link is rejected", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});

    const session = await instance.createStorageLink(OWNER_CTX, {}, new Date("2099-04-04T10:00:00.000Z"));
    await instance.cancelStorageLink(OWNER_CTX, session.id, new Date("2099-04-04T10:01:00.000Z"));

    await expect(instance.completeStorageLink(session.id, { sessionId: session.id }, new Date("2099-04-04T10:02:00.000Z"))).rejects.toThrow("no longer active");
  });
});
