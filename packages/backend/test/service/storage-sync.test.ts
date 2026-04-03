import { describe, expect, test } from "bun:test";

import type { LocalFileDescriptor } from "../../../shared/src/index.ts";

import { MemorySharedWorldRepository } from "../../src/memory-repository.ts";
import {
  authVerifier,
  claimHostForTest,
  createBlobSigner,
  createTestService,
  createStorageProviderSpy,
  googleDriveStorageProvider
} from "../support/service-fixtures.ts";

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

describe("SharedWorldService storage and sync planning", () => {
  test("storage usage counts all referenced stored objects across retained backups", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Storage Test",
      "storage-test",
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
      storageKey: "blobs/aa/one.bin",
      objectId: "obj-1",
      contentType: "application/octet-stream",
      size: 11,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    await repository.upsertStorageObject({
      provider: "google-drive",
      storageAccountId: "storage-account-1",
      storageKey: "blobs/bb/two.bin",
      objectId: "obj-2",
      contentType: "application/octet-stream",
      size: 22,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "hash-1",
            size: 100,
            compressedSize: 5,
            storageKey: "blobs/aa/one.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-01-01T00:01:00.000Z")
    );
    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "hash-2",
            size: 100,
            compressedSize: 6,
            storageKey: "blobs/bb/two.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-01-01T00:02:00.000Z")
    );

    const usage = await instance.getStorageUsage({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(usage.usedBytes).toBe(33);
  });

  test("storage usage dedupes repeated references to the same stored object and includes custom icons", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Storage Test",
      "storage-test",
      { provider: "google-drive", storageAccountId: "storage-account-1" },
      null,
      "icons/cc/icon.png"
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
      storageKey: "blobs/aa/shared.bin",
      objectId: "obj-shared",
      contentType: "application/octet-stream",
      size: 40,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    await repository.upsertStorageObject({
      provider: "google-drive",
      storageAccountId: "storage-account-1",
      storageKey: "icons/cc/icon.png",
      objectId: "obj-icon",
      contentType: "image/png",
      size: 7,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "hash-1",
            size: 100,
            compressedSize: 5,
            storageKey: "blobs/aa/shared.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-01-01T00:01:00.000Z")
    );
    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "hash-2",
            size: 100,
            compressedSize: 5,
            storageKey: "blobs/aa/shared.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-01-01T00:02:00.000Z")
    );

    const usage = await instance.getStorageUsage({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(usage.usedBytes).toBe(47);
  });

  test("storage usage is scoped to the current world even on the same storage account", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const firstWorld = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "World One",
      "world-one",
      { provider: "google-drive", storageAccountId: "storage-account-1" }
    );
    const secondWorld = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "World Two",
      "world-two",
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
      storageKey: "blobs/aa/world-one.bin",
      objectId: "obj-1",
      contentType: "application/octet-stream",
      size: 10,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    await repository.upsertStorageObject({
      provider: "google-drive",
      storageAccountId: "storage-account-1",
      storageKey: "blobs/bb/world-two.bin",
      objectId: "obj-2",
      contentType: "application/octet-stream",
      size: 20,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, firstWorld.id, new Date("2099-01-01T00:00:00.000Z"));
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, secondWorld.id, new Date("2099-01-01T00:00:01.000Z"));

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      firstWorld.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "hash-1",
            size: 100,
            compressedSize: 5,
            storageKey: "blobs/aa/world-one.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-01-01T00:01:00.000Z")
    );
    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      secondWorld.id,
      {
        files: [
          {
            path: "level.dat",
            hash: "hash-2",
            size: 100,
            compressedSize: 5,
            storageKey: "blobs/bb/world-two.bin",
            contentType: "application/octet-stream"
          }
        ]
      },
      new Date("2026-01-01T00:02:00.000Z")
    );

    const firstUsage = await instance.getStorageUsage({ playerUuid: "player-owner", playerName: "Owner" }, firstWorld.id);
    const secondUsage = await instance.getStorageUsage({ playerUuid: "player-owner", playerName: "Owner" }, secondWorld.id);
    expect(firstUsage.usedBytes).toBe(10);
    expect(secondUsage.usedBytes).toBe(20);
  });

  test("finalizeSnapshot rejects storage keys missing from storage metadata", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Drive World",
      "drive-world",
      { provider: "google-drive", storageAccountId: "storage-account-1" }
    );
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    await expect(instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        baseSnapshotId: null,
        files: [
          {
            path: "level.dat",
            hash: "missing",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/missing/object.bin",
            contentType: "application/octet-stream",
            transferMode: "whole-gzip"
          }
        ],
        packs: []
      },
      new Date("2026-01-01T00:01:00.000Z")
    )).rejects.toMatchObject({ status: 400, code: "snapshot_storage_missing" });

    expect(await repository.listSnapshotsForWorld(world.id)).toHaveLength(0);
  });

  test("finalizeSnapshot rejects unknown base snapshots", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Drive World",
      "drive-world",
      { provider: "google-drive", storageAccountId: "storage-account-1" }
    );
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    await seedGoogleDriveStorageObject(repository, "blobs/aa/base.bin", 5);

    await expect(instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        baseSnapshotId: "snapshot_missing",
        files: [
          {
            path: "level.dat",
            hash: "base",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/aa/base.bin",
            contentType: "application/octet-stream",
            transferMode: "whole-gzip"
          }
        ],
        packs: []
      },
      new Date("2026-01-01T00:01:00.000Z")
    )).rejects.toMatchObject({ status: 400, code: "snapshot_base_not_found" });

    expect(await repository.listSnapshotsForWorld(world.id)).toHaveLength(0);
  });

  test("finalizeSnapshot rejects inconsistent delta ancestry metadata", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Drive World",
      "drive-world",
      { provider: "google-drive", storageAccountId: "storage-account-1" }
    );
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    await seedGoogleDriveStorageObject(repository, "blobs/rg/base.bin", 10);
    await seedGoogleDriveStorageObject(repository, "blobs/rg/delta.bin", 5);

    const baseSnapshot = await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        baseSnapshotId: null,
        files: [
          {
            path: "region/r.0.0.mca",
            hash: "base-hash",
            size: 128,
            compressedSize: 10,
            storageKey: "blobs/rg/base.bin",
            contentType: "application/octet-stream",
            transferMode: "region-full"
          }
        ],
        packs: []
      },
      new Date("2026-01-01T00:01:00.000Z")
    );

    await expect(instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        baseSnapshotId: baseSnapshot.snapshotId,
        files: [
          {
            path: "region/r.0.0.mca",
            hash: "next-hash",
            size: 130,
            compressedSize: 5,
            storageKey: "blobs/rg/delta.bin",
            contentType: "application/octet-stream",
            transferMode: "region-delta",
            baseSnapshotId: baseSnapshot.snapshotId,
            baseHash: "wrong-hash",
            chainDepth: 1
          }
        ],
        packs: []
      },
      new Date("2026-01-01T00:02:00.000Z")
    )).rejects.toMatchObject({ status: 400, code: "snapshot_base_hash_mismatch" });

    await expect(instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        baseSnapshotId: baseSnapshot.snapshotId,
        files: [
          {
            path: "region/r.0.0.mca",
            hash: "next-hash",
            size: 130,
            compressedSize: 5,
            storageKey: "blobs/rg/delta.bin",
            contentType: "application/octet-stream",
            transferMode: "region-delta",
            baseSnapshotId: baseSnapshot.snapshotId,
            baseHash: "base-hash",
            chainDepth: 2
          }
        ],
        packs: []
      },
      new Date("2026-01-01T00:03:00.000Z")
    )).rejects.toMatchObject({ status: 400, code: "snapshot_chain_depth_mismatch" });

    expect(await repository.listSnapshotsForWorld(world.id)).toHaveLength(1);
  });

  test("finalizeSnapshot rejects duplicate paths and duplicate pack ids", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const { storageProvider } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Drive World",
      "drive-world",
      { provider: "google-drive", storageAccountId: "storage-account-1" }
    );
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    await seedGoogleDriveStorageObject(repository, "blobs/aa/one.bin", 5);
    await seedGoogleDriveStorageObject(repository, "packs/full/pa/one.pack", 10);
    await seedGoogleDriveStorageObject(repository, "packs/full/pa/two.pack", 10);

    await expect(instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        baseSnapshotId: null,
        files: [
          {
            path: "level.dat",
            hash: "hash-1",
            size: 10,
            compressedSize: 5,
            storageKey: "blobs/aa/one.bin",
            contentType: "application/octet-stream",
            transferMode: "whole-gzip"
          },
          {
            path: "level.dat",
            hash: "hash-2",
            size: 11,
            compressedSize: 6,
            storageKey: "blobs/aa/one.bin",
            contentType: "application/octet-stream",
            transferMode: "whole-gzip"
          }
        ],
        packs: []
      },
      new Date("2026-01-01T00:01:00.000Z")
    )).rejects.toMatchObject({ status: 400, code: "duplicate_snapshot_path" });

    await expect(instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        baseSnapshotId: null,
        files: [],
        packs: [
          {
            packId: "non-region",
            hash: "pack-1",
            size: 10,
            storageKey: "packs/full/pa/one.pack",
            transferMode: "pack-full",
            baseSnapshotId: null,
            baseHash: null,
            chainDepth: 0,
            files: [{ path: "level.dat", hash: "hash-1", size: 10, contentType: "application/octet-stream" }]
          },
          {
            packId: "non-region",
            hash: "pack-2",
            size: 10,
            storageKey: "packs/full/pa/two.pack",
            transferMode: "pack-full",
            baseSnapshotId: null,
            baseHash: null,
            chainDepth: 0,
            files: [{ path: "data/foo.dat", hash: "hash-2", size: 10, contentType: "application/octet-stream" }]
          }
        ]
      },
      new Date("2026-01-01T00:02:00.000Z")
    )).rejects.toMatchObject({ status: 400, code: "duplicate_snapshot_pack" });

    expect(await repository.listSnapshotsForWorld(world.id)).toHaveLength(0);
  });

  test("download and upload planning skip unchanged files", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");

    const bundleId = "region-bundle:region:0:0";
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    await repository.finalizeSnapshot(
      world.id,
      { playerUuid: "player-owner", playerName: "Owner" },
      {
        baseSnapshotId: null,
        files: [],
        packs: [
          {
            packId: "non-region",
            hash: "same-pack",
            size: 32,
            storageKey: "packs/full/sa/same-pack.pack",
            transferMode: "pack-full",
            baseSnapshotId: null,
            baseHash: null,
            chainDepth: 0,
            files: [
              { path: "level.dat", hash: "same", size: 10, contentType: "application/octet-stream" }
            ]
          },
          {
            packId: bundleId,
            hash: "region",
            size: 100,
            storageKey: "region-bundles/full/re/region.bundle",
            transferMode: "region-full",
            baseSnapshotId: null,
            baseHash: null,
            chainDepth: 0,
            files: [
              { path: "region/r.0.0.mca", hash: "region", size: 100, contentType: "application/octet-stream" }
            ]
          }
        ]
      },
      new Date()
    );

    const localFiles: LocalFileDescriptor[] = [
      { path: "level.dat", hash: "same", size: 10, compressedSize: 5, deltaCapable: false },
      { path: "region/r.0.0.mca", hash: "changed", size: 100, compressedSize: 58, deltaCapable: true }
    ];

    const uploadPlan = await instance.prepareUploads(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: localFiles,
        nonRegionPack: {
          packId: "non-region",
          hash: "same-pack",
          size: 32,
          fileCount: 1,
          files: [
            { path: "level.dat", hash: "same", size: 10, contentType: "application/octet-stream" }
          ]
        },
        regionBundles: [
          {
            packId: bundleId,
            hash: "changed-bundle",
            size: 104,
            fileCount: 1,
            files: [
              { path: "region/r.0.0.mca", hash: "changed", size: 100, contentType: "application/octet-stream" }
            ]
          }
        ]
      }
    );
    expect(uploadPlan.nonRegionPackUpload?.alreadyPresent).toBe(true);
    expect(uploadPlan.regionBundleUploads?.[0]?.alreadyPresent).toBe(false);
    expect(uploadPlan.syncPolicy.maxConcurrentUploads).toBe(4);
    expect(uploadPlan.syncPolicy.maxParallelDownloads).toBe(16);

    const downloadPlan = await instance.downloadPlan(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: localFiles,
        nonRegionPack: {
          packId: "non-region",
          hash: "same-pack",
          size: 32,
          fileCount: 1,
          files: []
        },
        regionBundles: [
          {
            packId: bundleId,
            hash: "changed-bundle",
            size: 104,
            fileCount: 1,
            files: []
          }
        ]
      }
    );
    expect(downloadPlan.retainedPaths).toEqual(["level.dat"]);
    expect(downloadPlan.downloads).toHaveLength(0);
    expect(downloadPlan.regionBundleDownloads).toHaveLength(1);
    expect(downloadPlan.regionBundleDownloads?.[0]?.packId).toBe(bundleId);
    expect(downloadPlan.syncPolicy.maxConcurrentUploads).toBe(4);
    expect(downloadPlan.syncPolicy.maxParallelDownloads).toBe(16);
  });

  test("region uploads expose delta candidates and cold downloads receive a reconstruction chain", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Delta World", "delta-world");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    const bundleId = "region-bundle:region:0:0";

    const snap1 = await repository.finalizeSnapshot(
      world.id,
      { playerUuid: "player-owner", playerName: "Owner" },
      {
        baseSnapshotId: null,
        files: [],
        packs: [
          {
            packId: bundleId,
            hash: "basehash",
            size: 128,
            storageKey: "region-bundles/full/ba/basehash.bundle",
            transferMode: "region-full",
            baseSnapshotId: null,
            baseHash: null,
            chainDepth: 0,
            files: [
              { path: "region/r.0.0.mca", hash: "basehash", size: 128, contentType: "application/octet-stream" }
            ]
          }
        ]
      },
      new Date("2099-01-03T00:00:00.000Z")
    );
    await repository.finalizeSnapshot(
      world.id,
      { playerUuid: "player-owner", playerName: "Owner" },
      {
        baseSnapshotId: snap1.snapshotId,
        files: [],
        packs: [
          {
            packId: bundleId,
            hash: "newhash",
            size: 130,
            storageKey: "region-bundles/delta/ba/basehash-newhash.bin",
            transferMode: "region-delta",
            baseSnapshotId: snap1.snapshotId,
            baseHash: "basehash",
            chainDepth: 1,
            files: [
              { path: "region/r.0.0.mca", hash: "newhash", size: 130, contentType: "application/octet-stream" }
            ]
          }
        ]
      },
      new Date("2099-01-03T00:01:00.000Z")
    );

    const uploadPlan = await instance.prepareUploads(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [{ path: "region/r.0.0.mca", hash: "thirdhash", size: 132, compressedSize: 132, deltaCapable: true }],
        regionBundles: [
          {
            packId: bundleId,
            hash: "thirdhash",
            size: 132,
            fileCount: 1,
            files: [
              { path: "region/r.0.0.mca", hash: "thirdhash", size: 132, contentType: "application/octet-stream" }
            ]
          }
        ]
      }
    );
    expect(uploadPlan.regionBundleUploads?.[0]?.fullStorageKey).toContain("region-bundles/full/");
    expect(uploadPlan.regionBundleUploads?.[0]?.deltaStorageKey).toContain("region-bundles/delta/");
    expect(uploadPlan.regionBundleUploads?.[0]?.baseHash).toBe("newhash");

    const warmDownloadPlan = await instance.downloadPlan(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [{ path: "region/r.0.0.mca", hash: "basehash", size: 128, compressedSize: 128, deltaCapable: true }],
        regionBundles: [
          {
            packId: bundleId,
            hash: "basehash",
            size: 128,
            fileCount: 1,
            files: []
          }
        ]
      }
    );
    expect(warmDownloadPlan.regionBundleDownloads?.[0]?.steps).toHaveLength(1);
    expect(warmDownloadPlan.regionBundleDownloads?.[0]?.steps[0]?.transferMode).toBe("region-delta");

    const coldDownloadPlan = await instance.downloadPlan(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { files: [], regionBundles: [] }
    );
    expect(coldDownloadPlan.regionBundleDownloads?.[0]?.steps).toHaveLength(2);
    expect(coldDownloadPlan.regionBundleDownloads?.[0]?.steps[0]?.transferMode).toBe("region-full");
    expect(coldDownloadPlan.regionBundleDownloads?.[0]?.steps[1]?.transferMode).toBe("region-delta");
  });

  test("google drive worlds advertise a conservative sync policy", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Drive World",
      "drive-world",
      { provider: "google-drive", storageAccountId: "storage-account-1" }
    );
    const instance = createTestService(repository, authVerifier, signer, googleDriveStorageProvider(), {});
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    const uploadPlan = await instance.prepareUploads(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { files: [] }
    );
    const downloadPlan = await instance.downloadPlan(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      []
    );

    expect(uploadPlan.syncPolicy.maxConcurrentUploads).toBe(3);
    expect(uploadPlan.syncPolicy.maxConcurrentUploadPreparations).toBe(2);
    expect(uploadPlan.syncPolicy.maxUploadStartsPerSecond).toBe(3);
    expect(downloadPlan.syncPolicy.maxParallelDownloads).toBe(8);
  });

  test("non-region packs plan delta uploads and warm download tails", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Packed World", "packed-world");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);

    const snap1 = await repository.finalizeSnapshot(
      world.id,
      { playerUuid: "player-owner", playerName: "Owner" },
      {
        baseSnapshotId: null,
        files: [],
        packs: [
          {
            packId: "non-region",
            hash: "pack-base",
            size: 256,
            storageKey: "packs/full/pa/pack-base.pack",
            transferMode: "pack-full",
            baseSnapshotId: null,
            baseHash: null,
            chainDepth: 0,
            files: [
              { path: "level.dat", hash: "level-base", size: 10, contentType: "application/octet-stream" },
              { path: "data/foo.dat", hash: "foo-base", size: 8, contentType: "application/octet-stream" }
            ]
          }
        ]
      },
      new Date("2099-01-04T00:00:00.000Z")
    );
    await repository.finalizeSnapshot(
      world.id,
      { playerUuid: "player-owner", playerName: "Owner" },
      {
        baseSnapshotId: snap1.snapshotId,
        files: [],
        packs: [
          {
            packId: "non-region",
            hash: "pack-next",
            size: 64,
            storageKey: "packs/delta/pa/pack-base-pack-next.bin",
            transferMode: "pack-delta",
            baseSnapshotId: snap1.snapshotId,
            baseHash: "pack-base",
            chainDepth: 1,
            files: [
              { path: "level.dat", hash: "level-next", size: 10, contentType: "application/octet-stream" },
              { path: "data/foo.dat", hash: "foo-next", size: 8, contentType: "application/octet-stream" }
            ]
          }
        ]
      },
      new Date("2099-01-04T00:01:00.000Z")
    );

    const uploadPlan = await instance.prepareUploads(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [],
        nonRegionPack: {
          packId: "non-region",
          hash: "pack-third",
          size: 260,
          fileCount: 2,
          files: [
            { path: "level.dat", hash: "level-third", size: 10, contentType: "application/octet-stream" },
            { path: "data/foo.dat", hash: "foo-third", size: 8, contentType: "application/octet-stream" }
          ]
        }
      }
    );
    expect(uploadPlan.nonRegionPackUpload?.fullStorageKey).toContain("packs/full/");
    expect(uploadPlan.nonRegionPackUpload?.deltaStorageKey).toContain("packs/delta/");
    expect(uploadPlan.nonRegionPackUpload?.baseHash).toBe("pack-next");

    const warmDownloadPlan = await instance.downloadPlan(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [
          { path: "level.dat", hash: "level-base", size: 10, compressedSize: 10, deltaCapable: false },
          { path: "data/foo.dat", hash: "foo-base", size: 8, compressedSize: 8, deltaCapable: false }
        ],
        nonRegionPack: {
          packId: "non-region",
          hash: "pack-base",
          size: 256,
          fileCount: 2,
          files: []
        }
      }
    );
    expect(warmDownloadPlan.nonRegionPackDownload?.steps).toHaveLength(1);
    expect(warmDownloadPlan.nonRegionPackDownload?.steps[0].transferMode).toBe("pack-delta");

    const coldDownloadPlan = await instance.downloadPlan(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { files: [], nonRegionPack: null }
    );
    expect(coldDownloadPlan.nonRegionPackDownload?.steps).toHaveLength(2);
    expect(coldDownloadPlan.nonRegionPackDownload?.steps[0].transferMode).toBe("pack-full");
    expect(coldDownloadPlan.nonRegionPackDownload?.steps[1].transferMode).toBe("pack-delta");
  });
});
