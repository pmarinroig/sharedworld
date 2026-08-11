import { describe, expect, test } from "bun:test";

import type { StorageAccountRecord } from "../../src/repository.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { createBlobSigner, createTestService, googleDriveStorageProvider } from "../support/service-fixtures.ts";

const owner = { playerUuid: "player-owner", playerName: "Owner" };
const guest = { playerUuid: "player-guest", playerName: "Guest" };

function storageAccountFixture(overrides: Partial<StorageAccountRecord> = {}): StorageAccountRecord {
  return {
    id: overrides.id ?? "storage-1",
    provider: overrides.provider ?? "google-drive",
    ownerPlayerUuid: overrides.ownerPlayerUuid ?? owner.playerUuid,
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

async function worldWithGuestMember(repository: ReturnType<typeof createSqliteRepository>, instance: ReturnType<typeof createTestService>) {
  await repository.upsertUser({ playerUuid: owner.playerUuid, playerName: owner.playerName, createdAt: new Date().toISOString() });
  const world = await repository.createWorld(owner, "Friends SMP", "friends-smp");
  const invite = await instance.createInvite(owner, world.id, new Date("2026-01-01T00:00:00.000Z"));
  await instance.redeemInvite(guest, { code: invite.code }, new Date("2026-01-01T00:05:00.000Z"));
  return world;
}

describe("SharedWorldService world settings", () => {
  test("owner saves settings; details carry them and every save bumps the revision", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    const world = await worldWithGuestMember(repository, instance);

    const first = await instance.updateWorldSettings(owner, world.id, {
      settings: { difficulty: "hard", gamerules: { keepInventory: true } }
    });
    expect(first.settings).toEqual({ difficulty: "hard", gamerules: { keepInventory: true } });
    expect(first.settingsRevision).toBe(1);

    const second = await instance.updateWorldSettings(owner, world.id, {
      settings: { difficulty: "peaceful", defaultGameMode: "creative" }
    });
    expect(second.settings).toEqual({ difficulty: "peaceful", defaultGameMode: "creative" });
    expect(second.settingsRevision).toBe(2);

    const reloaded = await instance.getWorld(owner, world.id, new Date("2026-01-01T01:00:00.000Z"));
    expect(reloaded.settings).toEqual({ difficulty: "peaceful", defaultGameMode: "creative" });
    expect(reloaded.settingsRevision).toBe(2);
  });

  test("members cannot change world settings", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    const world = await worldWithGuestMember(repository, instance);

    await expect(
      instance.updateWorldSettings(guest, world.id, { settings: { difficulty: "hard" } })
    ).rejects.toThrow("owner");
  });

  test("settings are whitelist-validated", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    const world = await worldWithGuestMember(repository, instance);

    await expect(
      instance.updateWorldSettings(owner, world.id, { settings: { difficulty: "impossible" } as never })
    ).rejects.toMatchObject({ code: "invalid_world_settings" });
    await expect(
      instance.updateWorldSettings(owner, world.id, { settings: { defaultGameMode: "spectator" } as never })
    ).rejects.toMatchObject({ code: "invalid_world_settings" });
    await expect(
      instance.updateWorldSettings(owner, world.id, { settings: { gamerules: { fireTick: true } } as never })
    ).rejects.toMatchObject({ code: "invalid_world_settings" });
    await expect(
      instance.updateWorldSettings(owner, world.id, { settings: { gamerules: { keepInventory: "yes" } } as never })
    ).rejects.toMatchObject({ code: "invalid_world_settings" });
    await expect(
      instance.updateWorldSettings(owner, world.id, { settings: { motd: "sneaky" } as never })
    ).rejects.toMatchObject({ code: "invalid_world_settings" });

    const untouched = await instance.getWorld(owner, world.id, new Date("2026-01-01T01:00:00.000Z"));
    expect(untouched.settings).toBeNull();
    expect(untouched.settingsRevision).toBe(0);
  });

  test("host heartbeat responses carry settings and revision as flat siblings", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    await instance.updateWorldSettings(owner, world.id, {
      settings: { difficulty: "normal", gamerules: { mobGriefing: false } }
    });

    const entered = await instance.enterSession(owner, world.id, {}, new Date("2026-01-03T00:00:00.000Z"));
    expect(entered.action).toBe("host");

    const heartbeat = await instance.heartbeatHost(
      owner,
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        joinTarget: "join.example"
      },
      new Date("2026-01-03T00:00:10.000Z")
    );

    expect(heartbeat.settings).toEqual({ difficulty: "normal", gamerules: { mobGriefing: false } });
    expect(heartbeat.settingsRevision).toBe(1);

    // Same flat-superset pin as memberships: runtime fields and the settings
    // data sit at the same JSON depth so 0.1.5/0.1.6 clients ignore them.
    const serialized = JSON.parse(JSON.stringify(heartbeat)) as Record<string, unknown>;
    expect(Object.keys(serialized)).toContain("phase");
    expect(Object.keys(serialized)).toContain("settings");
    expect(Object.keys(serialized)).toContain("settingsRevision");
  });

  test("createWorld binds to the caller's linked storage account on request", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});
    await repository.createOrUpdateStorageAccount(storageAccountFixture());

    const created = await instance.createWorld(owner, {
      name: "Weekend World",
      importSource: { type: "local-save", id: "save-1", name: "Save 1" },
      useLinkedStorageAccount: true
    });

    expect(created.world.storageProvider).toBe("google-drive");
    expect(created.world.storageLinked).toBe(true);
    expect(created.world.storageAccountEmail).toBe("owner@gmail.com");
  });

  test("createWorld with useLinkedStorageAccount fails cleanly when nothing usable is linked", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, googleDriveStorageProvider(), {});
    // An account that lost its refresh token does not qualify.
    await repository.createOrUpdateStorageAccount(storageAccountFixture({ refreshToken: null }));

    await expect(
      instance.createWorld(owner, {
        name: "Weekend World",
        importSource: { type: "local-save", id: "save-1", name: "Save 1" },
        useLinkedStorageAccount: true
      })
    ).rejects.toMatchObject({ code: "storage_not_linked" });
  });
});
