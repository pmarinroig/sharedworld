import { describe, expect, test } from "bun:test";

import type { Env } from "../../src/env.ts";
import { MemorySharedWorldRepository } from "../../src/memory-repository.ts";
import {
  authVerifier,
  claimHostForTest,
  createBlobBucket,
  createBlobSigner,
  createPng64Bytes,
  createStorageProviderSpy,
  createTestService
} from "../support/service-fixtures.ts";

function iconBase64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

async function seedGoogleDriveStorageObject(
  repository: MemorySharedWorldRepository,
  storageKey: string,
  size = 1,
  contentType = "application/octet-stream"
) {
  await repository.upsertStorageObject({
    provider: "google-drive",
    storageAccountId: "storage-account-1",
    storageKey,
    objectId: `obj-${storageKey.replace(/[^a-z0-9]+/gi, "-")}`,
    contentType,
    size,
    createdAt: "2026-01-01T00:00:00.000Z",
    updatedAt: "2026-01-01T00:00:00.000Z"
  });
}

describe("SharedWorldService world management", () => {
  test("world summaries include live online player names and count", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:00.000Z"));
    await instance.setPlayerPresence(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { present: true, guestSessionEpoch: 1, presenceSequence: 1 },
      new Date("2099-01-03T00:00:10.000Z")
    );

    const worlds = await instance.listWorlds({ playerUuid: "player-owner", playerName: "Owner" });
    expect(worlds).toHaveLength(1);
    expect(worlds[0].onlinePlayerCount).toBe(2);
    expect(worlds[0].onlinePlayerNames).toEqual(["Guest", "Owner"]);
  });

  test("different players can create worlds with the same name", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-a", playerName: "Alpha", createdAt: new Date().toISOString() });
    await repository.upsertUser({ playerUuid: "player-b", playerName: "Bravo", createdAt: new Date().toISOString() });

    const first = await instance.createWorld({ playerUuid: "player-a", playerName: "Alpha" }, { name: "Weekend World" });
    const second = await instance.createWorld({ playerUuid: "player-b", playerName: "Bravo" }, { name: "Weekend World" });

    expect(first.world.name).toBe("Weekend World");
    expect(second.world.name).toBe("Weekend World");
    expect(first.world.id).not.toBe(second.world.id);
    expect(first.world.slug).not.toBe(second.world.slug);
  });

  test("deleting the last member purges world snapshots and orphaned blobs", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer, deleted } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Cleanup Test", "cleanup-test");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "cleanup",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/cl/cleanup.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-03-31T00:00:00.000Z")
    );

    await instance.deleteWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-03-31T00:05:00.000Z")
    );

    expect(await repository.listSnapshotsForWorld(world.id)).toHaveLength(0);
    expect(deleted).toContain("blobs/cl/cleanup.bin");
  });

  test("deleting a google drive world removes snapshot blobs and custom icons through the storage provider", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider, deleted } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Drive Cleanup Test",
      "drive-cleanup-test",
      { provider: "google-drive", storageAccountId: "storage-account-1" },
      null,
      "icons/drive/icon.png"
    );
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    await seedGoogleDriveStorageObject(repository, "blobs/gd/cleanup.bin", 5);
    await seedGoogleDriveStorageObject(repository, "icons/drive/icon.png", 1, "image/png");

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "drive-cleanup",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/gd/cleanup.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-03-31T00:00:00.000Z")
    );

    await instance.deleteWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-03-31T00:05:00.000Z")
    );

    expect(await repository.listSnapshotsForWorld(world.id)).toHaveLength(0);
    expect(deleted.sort()).toEqual(["blobs/gd/cleanup.bin", "icons/drive/icon.png"]);
  });

  test("owner delete removes world invites", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Invite Cleanup", "invite-cleanup");
    const invite = await instance.createInvite(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-03-31T00:00:00.000Z")
    );

    await instance.deleteWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-03-31T00:05:00.000Z")
    );

    await expect(repository.getInviteByCode(invite.code)).resolves.toBeNull();
  });

  test("member delete does not remove shared storage artifacts", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider, deleted } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Member Cleanup Test",
      "member-cleanup-test",
      { provider: "google-drive", storageAccountId: "storage-account-1" },
      null,
      "icons/member/icon.png"
    );
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2026-03-31T00:01:00.000Z",
      deletedAt: null
    });
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    await seedGoogleDriveStorageObject(repository, "blobs/member/cleanup.bin", 5);
    await seedGoogleDriveStorageObject(repository, "icons/member/icon.png", 1, "image/png");
    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "member-cleanup",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/member/cleanup.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-03-31T00:02:00.000Z")
    );

    await instance.deleteWorld(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      new Date("2026-03-31T00:05:00.000Z")
    );

    expect(deleted).toHaveLength(0);
    await expect(instance.getWorld({ playerUuid: "player-owner", playerName: "Owner" }, world.id)).resolves.toBeTruthy();
  });

  test("owner delete is best effort when storage cleanup fails", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider, deleted } = createStorageProviderSpy("google-drive", {
      failDeletesFor: ["blobs/fail/cleanup.bin"]
    });
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Failure Cleanup Test",
      "failure-cleanup-test",
      { provider: "google-drive", storageAccountId: "storage-account-1" },
      null,
      "icons/fail/icon.png"
    );
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    await seedGoogleDriveStorageObject(repository, "blobs/fail/cleanup.bin", 5);
    await seedGoogleDriveStorageObject(repository, "icons/fail/icon.png", 1, "image/png");
    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "failure-cleanup",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/fail/cleanup.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-03-31T00:00:00.000Z")
    );

    const originalWarn = console.warn;
    const warnings: unknown[][] = [];
    console.warn = (...args: unknown[]) => {
      warnings.push(args);
    };
    try {
      await expect(
        instance.deleteWorld(
          { playerUuid: "player-owner", playerName: "Owner" },
          world.id,
          new Date("2026-03-31T00:05:00.000Z")
        )
      ).resolves.toBeUndefined();
    } finally {
      console.warn = originalWarn;
    }

    expect(deleted).toContain("blobs/fail/cleanup.bin");
    expect(deleted).toContain("icons/fail/icon.png");
    expect(warnings.some((args) => String(args[0]).includes("SharedWorld blob cleanup failed for"))).toBe(true);
    await expect(instance.getWorld({ playerUuid: "player-owner", playerName: "Owner" }, world.id)).rejects.toThrow("not found");
  });

  test("only the owner can rename a world", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: new Date().toISOString(),
      deletedAt: null
    });

    await expect(
      instance.updateWorld({ playerUuid: "player-guest", playerName: "Guest" }, world.id, { name: "Not Allowed" })
    ).rejects.toThrow("Only the SharedWorld owner can edit this world.");

    const updated = await instance.updateWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { name: "Weekend Realm" }
    );
    expect(updated.name).toBe("Weekend Realm");
  });

  test("owner can save MOTD and custom icon metadata", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const iconBytes = createPng64Bytes();
    const env: Env = { BLOBS: createBlobBucket({}) };
    const instance = createTestService(repository, authVerifier, signer, env);
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });

    const created = await instance.createWorld(
      { playerUuid: "player-owner", playerName: "Owner", requestOrigin: "http://127.0.0.1:8787" },
      {
        name: "Weekend World",
        motdLine1: "§aHello",
        motdLine2: "§lFriends",
        customIconPngBase64: iconBase64(iconBytes)
      }
    );

    expect(created.world.motd).toBe("§aHello\n§lFriends");
    expect(created.world.customIconStorageKey).toBeTruthy();
    expect(created.world.customIconDownload?.url).toContain(created.world.customIconStorageKey!);
  });

  test("replacing a custom icon deletes the old unreferenced icon blob", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer, deleted } = createBlobSigner();
    const oldIconBytes = createPng64Bytes();
    const newIconBytes = createPng64Bytes();
    newIconBytes[15] = 0x53;
    const env: Env = { BLOBS: createBlobBucket({}) };
    const instance = createTestService(repository, authVerifier, signer, env);
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await instance.createWorld(
      { playerUuid: "player-owner", playerName: "Owner", requestOrigin: "http://127.0.0.1:8787" },
      { name: "Weekend World", customIconPngBase64: iconBase64(oldIconBytes) }
    );
    const oldIcon = world.world.customIconStorageKey;
    expect(oldIcon).toBeTruthy();

    const updated = await instance.updateWorld(
      { playerUuid: "player-owner", playerName: "Owner", requestOrigin: "http://127.0.0.1:8787" },
      world.world.id,
      { name: "Weekend World", customIconPngBase64: iconBase64(newIconBytes) }
    );

    expect(updated.customIconStorageKey).toBeTruthy();
    expect(updated.customIconStorageKey).not.toBe(oldIcon);
    expect(deleted).toContain(oldIcon!);
  });

  test("request-side customIconStorageKey is ignored on update", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Weekend World", "weekend-world");

    const requestWithIgnoredField = {
      name: "Weekend World",
      customIconStorageKey: "icons/legacy/forbidden.png"
    } satisfies { name: string; customIconStorageKey: string };

    const updated = await instance.updateWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      requestWithIgnoredField
    );

    expect(updated.customIconStorageKey).toBeNull();
    expect(updated.customIconDownload).toBeNull();
  });

  test("deleting as owner permanently deletes the world for all members", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-alpha",
      playerName: "Alpha",
      role: "member",
      joinedAt: "2026-01-01T00:00:00.000Z",
      deletedAt: null
    });
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-bravo",
      playerName: "Bravo",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.deleteWorld({ playerUuid: "player-owner", playerName: "Owner" }, world.id, new Date("2026-01-03T00:00:00.000Z"));

    await expect(instance.getWorld({ playerUuid: "player-owner", playerName: "Owner" }, world.id)).rejects.toThrow("not found");
    await expect(instance.getWorld({ playerUuid: "player-alpha", playerName: "Alpha" }, world.id)).rejects.toThrow("not found");
    expect(await repository.hasActiveWorld(world.id)).toBe(false);
  });

  test("deleting as non-owner leaves the world intact for other members", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.deleteWorld({ playerUuid: "player-guest", playerName: "Guest" }, world.id, new Date("2026-01-03T00:00:00.000Z"));

    const ownerView = await instance.getWorld({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(ownerView.memberCount).toBe(1);
    await expect(instance.getWorld({ playerUuid: "player-guest", playerName: "Guest" }, world.id)).rejects.toThrow("not found");
    expect(await repository.hasActiveWorld(world.id)).toBe(true);
  });

  test("deleting an active world makes session endpoints report it as deleted", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { joinTarget: "example.test:25565" },
      new Date("2026-01-03T00:00:00.000Z")
    );
    await instance.handoffReady(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { waiting: true },
      new Date("2026-01-03T00:00:30.000Z")
    );
    await instance.setPlayerPresence(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { present: true, guestSessionEpoch: 1, presenceSequence: 1 },
      new Date("2026-01-03T00:01:00.000Z")
    );

    await instance.deleteWorld({ playerUuid: "player-owner", playerName: "Owner" }, world.id, new Date("2026-01-03T00:02:00.000Z"));

    await expect(instance.activeHost({ playerUuid: "player-guest", playerName: "Guest" }, world.id)).rejects.toThrow("SharedWorld server not found.");
    await expect(instance.resolveJoin({ playerUuid: "player-guest", playerName: "Guest" }, world.id)).rejects.toThrow("SharedWorld server not found.");
    await expect(
      instance.releaseHost(
        { playerUuid: "player-owner", playerName: "Owner" },
        world.id,
        { graceful: false },
        new Date("2026-01-03T00:02:30.000Z")
      )
    ).rejects.toThrow("SharedWorld server not found.");
  });

  test("stale guest presence heartbeats cannot restore a player after a newer disconnect", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Ordering Test", "ordering-test");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:00.000Z"));
    await instance.setPlayerPresence(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { present: true, guestSessionEpoch: 1, presenceSequence: 1 },
      new Date("2099-01-03T00:00:10.000Z")
    );
    await instance.setPlayerPresence(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { present: false, guestSessionEpoch: 1, presenceSequence: 2 },
      new Date("2099-01-03T00:00:11.000Z")
    );
    await instance.setPlayerPresence(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { present: true, guestSessionEpoch: 1, presenceSequence: 1 },
      new Date("2099-01-03T00:00:12.000Z")
    );

    const worlds = await instance.listWorlds({ playerUuid: "player-owner", playerName: "Owner" });
    expect(worlds[0].onlinePlayerCount).toBe(1);
    expect(worlds[0].onlinePlayerNames).toEqual(["Owner"]);
  });

  test("new guest sessions can replace older disconnect tombstones", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Rejoin Test", "rejoin-test");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:00.000Z"));
    await instance.setPlayerPresence(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { present: false, guestSessionEpoch: 1, presenceSequence: 2 },
      new Date("2099-01-03T00:00:10.000Z")
    );
    await instance.setPlayerPresence(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { present: true, guestSessionEpoch: 2, presenceSequence: 1 },
      new Date("2099-01-03T00:00:11.000Z")
    );

    const worlds = await instance.listWorlds({ playerUuid: "player-owner", playerName: "Owner" });
    expect(worlds[0].onlinePlayerCount).toBe(2);
    expect(worlds[0].onlinePlayerNames).toEqual(["Guest", "Owner"]);
  });
});
