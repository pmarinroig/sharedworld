import { describe, expect, test } from "bun:test";

import type { FinalizeSnapshotRequest } from "../../../shared/src/index.ts";

import { MemorySharedWorldRepository } from "../../src/memory-repository.ts";
import { authVerifier, claimHostForTest, createBlobSigner, createStorageProviderSpy, createTestService } from "../support/service-fixtures.ts";

describe("SharedWorldService snapshots and retention", () => {
  test("snapshot summaries use actual stored bytes for pack-backed artifacts", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Pack Size Test",
      "pack-size-test",
      { provider: "google-drive", storageAccountId: "storage-account-1" }
    );
    await repository.createOrUpdateStorageAccount({
      id: "storage-account-1",
      provider: "google-drive",
      ownerPlayerUuid: "player-owner",
      externalAccountId: "external-1",
      email: "owner@example.com",
      displayName: "Owner Drive",
      accessToken: null,
      refreshToken: null,
      tokenExpiresAt: null,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    await repository.upsertStorageObject({
      provider: "google-drive",
      storageAccountId: "storage-account-1",
      storageKey: "packs/full/test.pack",
      objectId: "obj-pack",
      contentType: "application/octet-stream",
      size: 12,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [],
        packs: [
          {
            packId: "non-region",
            hash: "pack-hash",
            size: 99,
            storageKey: "packs/full/test.pack",
            transferMode: "pack-full",
            files: [
              { path: "level.dat", hash: "hash-1", size: 50, contentType: "application/octet-stream" },
              { path: "session.lock", hash: "hash-2", size: 49, contentType: "application/octet-stream" }
            ]
          }
        ]
      },
      new Date("2026-01-01T00:01:00.000Z")
    );

    const snapshots = await instance.listSnapshots({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(snapshots).toHaveLength(1);
    expect(snapshots[0]?.totalCompressedSize).toBe(12);
  });

  test("one remaining snapshot size matches used by this world", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Backup Alignment Test",
      "backup-alignment-test",
      { provider: "google-drive", storageAccountId: "storage-account-1" }
    );
    await repository.createOrUpdateStorageAccount({
      id: "storage-account-1",
      provider: "google-drive",
      ownerPlayerUuid: "player-owner",
      externalAccountId: "external-1",
      email: "owner@example.com",
      displayName: "Owner Drive",
      accessToken: null,
      refreshToken: null,
      tokenExpiresAt: null,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    await repository.upsertStorageObject({
      provider: "google-drive",
      storageAccountId: "storage-account-1",
      storageKey: "packs/old.pack",
      objectId: "obj-old",
      contentType: "application/octet-stream",
      size: 30,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    await repository.upsertStorageObject({
      provider: "google-drive",
      storageAccountId: "storage-account-1",
      storageKey: "packs/new.pack",
      objectId: "obj-new",
      contentType: "application/octet-stream",
      size: 18,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [],
        packs: [
          {
            packId: "non-region",
            hash: "old-pack",
            size: 45,
            storageKey: "packs/old.pack",
            transferMode: "pack-full",
            files: [
              { path: "level.dat", hash: "hash-old", size: 45, contentType: "application/octet-stream" }
            ]
          }
        ]
      },
      new Date("2026-01-01T00:01:00.000Z")
    );
    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [],
        packs: [
          {
            packId: "non-region",
            hash: "new-pack",
            size: 60,
            storageKey: "packs/new.pack",
            transferMode: "pack-full",
            files: [
              { path: "level.dat", hash: "hash-new", size: 60, contentType: "application/octet-stream" }
            ]
          }
        ]
      },
      new Date("2026-01-01T00:02:00.000Z")
    );

    const initialSnapshots = await instance.listSnapshots({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(initialSnapshots).toHaveLength(2);

    await instance.deleteSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      initialSnapshots[1]?.snapshotId ?? "missing-snapshot"
    );

    const remainingSnapshots = await instance.listSnapshots({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    const usage = await instance.getStorageUsage({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(remainingSnapshots).toHaveLength(1);
    expect(remainingSnapshots[0]?.totalCompressedSize).toBe(usage.usedBytes);
    expect(usage.usedBytes).toBe(18);
  });

  test("first snapshot can be finalized when baseSnapshotId is omitted", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    const snapshot = await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "hash",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/ha/hash.bin",
            contentType: "application/octet-stream"
          }
        ]
      } as FinalizeSnapshotRequest,
      new Date()
    );

    expect(snapshot.files).toHaveLength(1);
    expect(snapshot.snapshotId.startsWith("snapshot_")).toBe(true);
  });

  test("restoring a packed snapshot preserves packs and yields a usable latest manifest", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Packed Restore", "packed-restore");

    const snapshotA = await repository.finalizeSnapshot(
      world.id,
      { playerUuid: "player-owner", playerName: "Owner" },
      {
        baseSnapshotId: null,
        files: [],
        packs: [
          {
            packId: "non-region",
            hash: "pack-a",
            size: 256,
            storageKey: "packs/full/pa/pack-a.pack",
            transferMode: "pack-full",
            baseSnapshotId: null,
            baseHash: null,
            chainDepth: 0,
            files: [
              { path: "level.dat", hash: "level-a", size: 10, contentType: "application/octet-stream" },
              { path: "data/foo.dat", hash: "foo-a", size: 8, contentType: "application/octet-stream" }
            ]
          },
          {
            packId: "region-bundle:region:0:0",
            hash: "region-a",
            size: 128,
            storageKey: "region-bundles/full/re/region-a.bundle",
            transferMode: "region-full",
            baseSnapshotId: null,
            baseHash: null,
            chainDepth: 0,
            files: [
              { path: "region/r.0.0.mca", hash: "region-a", size: 128, contentType: "application/octet-stream" }
            ]
          }
        ]
      },
      new Date("2099-01-05T00:00:00.000Z")
    );

    await repository.finalizeSnapshot(
      world.id,
      { playerUuid: "player-owner", playerName: "Owner" },
      {
        baseSnapshotId: snapshotA.snapshotId,
        files: [],
        packs: [
          {
            packId: "non-region",
            hash: "pack-b",
            size: 64,
            storageKey: "packs/delta/pa/pack-a-pack-b.bin",
            transferMode: "pack-delta",
            baseSnapshotId: snapshotA.snapshotId,
            baseHash: "pack-a",
            chainDepth: 1,
            files: [
              { path: "level.dat", hash: "level-b", size: 10, contentType: "application/octet-stream" },
              { path: "data/foo.dat", hash: "foo-b", size: 8, contentType: "application/octet-stream" }
            ]
          },
          {
            packId: "region-bundle:region:0:0",
            hash: "region-b",
            size: 32,
            storageKey: "region-bundles/delta/re/region-a-region-b.bin",
            transferMode: "region-delta",
            baseSnapshotId: snapshotA.snapshotId,
            baseHash: "region-a",
            chainDepth: 1,
            files: [
              { path: "region/r.0.0.mca", hash: "region-b", size: 132, contentType: "application/octet-stream" }
            ]
          }
        ]
      },
      new Date("2099-01-05T00:01:00.000Z")
    );

    await instance.restoreSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      snapshotA.snapshotId,
      new Date("2099-01-05T00:02:00.000Z")
    );

    const snapshots = await instance.listSnapshots({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(snapshots).toHaveLength(3);
    expect(snapshots[0]?.isLatest).toBe(true);
    expect(snapshots[0]?.fileCount).toBe(3);
    expect(snapshots[0]?.totalSize).toBe(146);

    const latestManifest = await instance.latestManifest({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(latestManifest?.snapshotId).not.toBe(snapshotA.snapshotId);
    expect(latestManifest?.files).toHaveLength(0);
    expect(latestManifest?.packs).toHaveLength(2);
    expect(latestManifest?.packs.map((pack) => pack.packId)).toEqual(["non-region", "region-bundle:region:0:0"]);
    expect(latestManifest?.packs.map((pack) => pack.hash)).toEqual(["pack-a", "region-a"]);
    expect(latestManifest?.packs[0]?.files.map((file) => file.path)).toEqual(["level.dat", "data/foo.dat"]);
    expect(latestManifest?.packs[1]?.files.map((file) => file.path)).toEqual(["region/r.0.0.mca"]);
    expect(latestManifest?.packs[0]?.baseSnapshotId).toBeNull();
    expect(latestManifest?.packs[1]?.baseSnapshotId).toBeNull();

    const downloadPlan = await instance.downloadPlan(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { files: [], nonRegionPack: null, regionBundles: [] }
    );
    expect(downloadPlan.downloads).toHaveLength(0);
    expect(downloadPlan.nonRegionPackDownload?.hash).toBe("pack-a");
    expect(downloadPlan.nonRegionPackDownload?.steps).toHaveLength(1);
    expect(downloadPlan.nonRegionPackDownload?.steps[0]?.transferMode).toBe("pack-full");
    expect(downloadPlan.regionBundleDownloads).toHaveLength(1);
    expect(downloadPlan.regionBundleDownloads?.[0]?.hash).toBe("region-a");
    expect(downloadPlan.regionBundleDownloads?.[0]?.steps).toHaveLength(1);
    expect(downloadPlan.regionBundleDownloads?.[0]?.steps[0]?.transferMode).toBe("region-full");
  });

  test("snapshot retention keeps recent snapshots, thins older history, and only deletes unreferenced blobs", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer, deleted } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Retention Test", "retention-test");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "jan-old",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/ja/jan-old.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-01-01T00:00:00.000Z")
    );

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "jan-keep",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/ja/jan-keep.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-01-20T12:00:00.000Z")
    );

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "march-old",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/ma/march-old.bin",
            contentType: "application/octet-stream"
          },
          {
            path: "playerdata/owner.dat",
            hash: "shared",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/sh/shared.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-03-01T10:00:00.000Z")
    );

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "march-keep",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/ma/march-keep.bin",
            contentType: "application/octet-stream"
          },
          {
            path: "playerdata/owner.dat",
            hash: "shared",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/sh/shared.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-03-01T12:00:00.000Z")
    );

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "recent-a",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/re/recent-a.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-03-30T10:00:00.000Z")
    );

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "recent-b",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/re/recent-b.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-03-31T00:00:00.000Z")
    );

    const keptSnapshots = await repository.listSnapshotsForWorld(world.id);
    expect(keptSnapshots.map((snapshot) => snapshot.createdAt)).toEqual([
      "2026-03-31T00:00:00.000Z",
      "2026-03-30T10:00:00.000Z",
      "2026-03-01T12:00:00.000Z",
      "2026-01-20T12:00:00.000Z"
    ]);
    expect(deleted).toContain("blobs/ja/jan-old.bin");
    expect(deleted).toContain("blobs/ma/march-old.bin");
    expect(deleted).not.toContain("blobs/sh/shared.bin");
  });
});
