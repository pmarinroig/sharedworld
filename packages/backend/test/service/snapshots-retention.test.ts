import { describe, expect, test } from "bun:test";

import type { FinalizeSnapshotRequest, SnapshotPack } from "../../../shared/src/index.ts";

import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { claimHostForTest, createBlobSigner, createStorageProviderSpy, createTestService } from "../support/service-fixtures.ts";

function packDirectoryMembersSnapshotId(raw: { query(sql: string): { get(...args: unknown[]): unknown } }, snapshotId: string): { members_snapshot_id: string | null } {
  // 0026: pack headers live in the snapshots row's JSON directory; keep the
  // legacy row shape so the assertions below read unchanged.
  const row = raw.query("SELECT packs_json FROM snapshots WHERE id = ?").get(snapshotId) as { packs_json: string | null } | null;
  const directory = JSON.parse(row?.packs_json ?? "[]") as Array<{ membersSnapshotId: string | null }>;
  if (directory.length === 0) {
    throw new Error(`no pack directory entry on ${snapshotId}`);
  }
  return { members_snapshot_id: directory[0].membersSnapshotId };
}

describe("SharedWorldService snapshots and retention", () => {
  test("snapshot summaries use actual stored bytes for pack-backed artifacts", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const { storageProvider } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, signer, storageProvider, {});
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
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const { storageProvider } = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, signer, storageProvider, {});
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
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
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
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
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
    expect(latestManifest?.packs[0]?.files.map((file) => file.path)).toEqual(["data/foo.dat", "level.dat"]);
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
    const repository = createSqliteRepository();
    const { signer, deleted } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
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

  test("retention keeps every delta base a surviving snapshot still needs", async () => {
    const repository = createSqliteRepository();
    const { signer, deleted } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Chain Retention", "chain-retention");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    const owner = { playerUuid: "player-owner", playerName: "Owner" };

    // Two saves on day one: a full pack, then a delta on top of it.
    const snapshotA = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [{
        packId: "non-region",
        hash: "pack-a",
        size: 100,
        storageKey: "packs/full/a.pack",
        transferMode: "pack-full",
        files: [{ path: "level.dat", hash: "level-a", size: 90, contentType: "application/octet-stream" }]
      }]
    }, new Date("2026-01-01T10:00:00.000Z"));
    const snapshotB = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [{
        packId: "non-region",
        hash: "pack-b",
        size: 20,
        storageKey: "packs/delta/a-b.bin",
        transferMode: "pack-delta",
        baseSnapshotId: snapshotA.snapshotId,
        baseHash: "pack-a",
        chainDepth: 1,
        files: [{ path: "level.dat", hash: "level-b", size: 91, contentType: "application/octet-stream" }]
      }]
    }, new Date("2026-01-01T11:00:00.000Z"));

    // Two days later a third save extends the chain. Age-based retention alone
    // would prune snapshot A (its day bucket is already represented by B) and
    // delete the full artifact every reconstruction of B and C starts from.
    const snapshotC = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [{
        packId: "non-region",
        hash: "pack-c",
        size: 21,
        storageKey: "packs/delta/b-c.bin",
        transferMode: "pack-delta",
        baseSnapshotId: snapshotB.snapshotId,
        baseHash: "pack-b",
        chainDepth: 2,
        files: [{ path: "level.dat", hash: "level-c", size: 92, contentType: "application/octet-stream" }]
      }]
    }, new Date("2026-01-03T12:00:00.000Z"));

    // S1: age policy is real now — snapshot A's day bucket is represented by
    // B, so its ROW is pruned. The chain BLOBS survive because B and C are
    // self-contained and their recipes reference them.
    const kept = await repository.listSnapshotsForWorld(world.id);
    expect(kept.map((snapshot) => snapshot.snapshotId).sort()).toEqual(
      [snapshotB.snapshotId, snapshotC.snapshotId].sort()
    );
    expect(deleted).toHaveLength(0);

    // A cold client still receives the full reconstruction chain — built
    // from the recipe, not from the pruned snapshot rows.
    const plan = await instance.downloadPlan(owner, world.id, { files: [], nonRegionPack: null, regionBundles: [] });
    expect(plan.nonRegionPackDownload?.steps.map((step) => step.transferMode)).toEqual(["pack-full", "pack-delta", "pack-delta"]);
    expect(plan.nonRegionPackDownload?.steps[0]?.storageKey).toBe("packs/full/a.pack");
  });

  test("a backup another backup builds on cannot be deleted until its dependant is gone", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Delete Guard", "delete-guard");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    const owner = { playerUuid: "player-owner", playerName: "Owner" };

    const snapshotA = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [{
        packId: "non-region",
        hash: "pack-a",
        size: 100,
        storageKey: "packs/full/a.pack",
        transferMode: "pack-full",
        files: [{ path: "level.dat", hash: "level-a", size: 90, contentType: "application/octet-stream" }]
      }]
    }, new Date("2026-01-01T10:00:00.000Z"));
    const snapshotB = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [{
        packId: "non-region",
        hash: "pack-b",
        size: 20,
        storageKey: "packs/delta/a-b.bin",
        transferMode: "pack-delta",
        baseSnapshotId: snapshotA.snapshotId,
        baseHash: "pack-a",
        chainDepth: 1,
        files: [{ path: "level.dat", hash: "level-b", size: 91, contentType: "application/octet-stream" }]
      }]
    }, new Date("2026-01-01T11:00:00.000Z"));
    await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [{
        packId: "non-region",
        hash: "pack-c",
        size: 100,
        storageKey: "packs/full/c.pack",
        transferMode: "pack-full",
        files: [{ path: "level.dat", hash: "level-c", size: 92, contentType: "application/octet-stream" }]
      }]
    }, new Date("2026-01-01T12:00:00.000Z"));

    // S1: stamped snapshots pin nothing — any old backup deletes freely, in
    // any order, and the shared chain blobs stay referenced by B's recipe.
    await instance.deleteSnapshot(owner, world.id, snapshotA.snapshotId);
    await instance.deleteSnapshot(owner, world.id, snapshotB.snapshotId);
    const remaining = await repository.listSnapshotsForWorld(world.id);
    expect(remaining).toHaveLength(1);
  });

  test("maxBackups caps the age-kept set, oldest first, latest always retained", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Capped SMP", "capped-smp");
    await repository.updateWorldSettings(world.id, JSON.stringify({ maxBackups: 3 }));
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    const owner = { playerUuid: "player-owner", playerName: "Owner" };

    // Six full snapshots on six different days: age policy alone keeps all
    // six as dailies; the cap trims to the newest three.
    const ids: string[] = [];
    for (let day = 1; day <= 6; day += 1) {
      const manifest = await instance.finalizeSnapshot(owner, world.id, {
        files: [],
        packs: [{
          packId: "non-region",
          hash: `pack-${day}`,
          size: 10,
          storageKey: `packs/full/day-${day}.pack`,
          transferMode: "pack-full",
          files: [{ path: "level.dat", hash: `h-${day}`, size: 10, contentType: "application/octet-stream" }]
        }]
      }, new Date(`2026-01-0${day}T10:00:00.000Z`));
      ids.push(manifest.snapshotId);
    }

    const kept = (await repository.listSnapshotsForWorld(world.id)).map((snapshot) => snapshot.snapshotId).sort();
    expect(kept).toEqual(ids.slice(-3).sort());
  });

  test("retention lazily upgrades kept legacy snapshots and then prunes their unpinned ancestry", async () => {
    const repository = createSqliteRepository();
    const { signer, deleted } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Bloated SMP", "bloated-smp");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    const owner = { playerUuid: "player-owner", playerName: "Owner" };

    // A pre-chainSteps history: full → delta → delta, one per day.
    const packFor = (hash: string, storageKey: string, base: { snapshotId: string; hash: string } | null, depth: number): SnapshotPack => ({
      packId: "non-region",
      hash,
      size: 100,
      storageKey,
      transferMode: base == null ? "pack-full" : "pack-delta",
      baseSnapshotId: base?.snapshotId ?? null,
      baseHash: base?.hash ?? null,
      chainDepth: depth,
      files: [{ path: "level.dat", hash: `${hash}-file`, size: 90, contentType: "application/octet-stream" }]
    });
    const snapA = await instance.finalizeSnapshot(owner, world.id, {
      files: [], packs: [packFor("pack-a", "packs/full/a.pack", null, 0)]
    }, new Date("2026-01-01T10:00:00.000Z"));
    const snapB = await instance.finalizeSnapshot(owner, world.id, {
      files: [], packs: [packFor("pack-b", "packs/delta/a-b.bin", { snapshotId: snapA.snapshotId, hash: "pack-a" }, 1)]
    }, new Date("2026-01-02T10:00:00.000Z"));
    const snapC = await instance.finalizeSnapshot(owner, world.id, {
      files: [], packs: [packFor("pack-c", "packs/delta/b-c.bin", { snapshotId: snapB.snapshotId, hash: "pack-b" }, 2)]
    }, new Date("2026-01-03T10:00:00.000Z"));

    // Rewind all three to a pre-stamping worker's representation.
    for (const id of [snapA.snapshotId, snapB.snapshotId, snapC.snapshotId]) {
      repository.raw.exec(`
        UPDATE snapshots
        SET packs_json = (
          SELECT json_group_array(json_remove(pack.value, '$.chainSteps'))
          FROM json_each(packs_json) AS pack
        )
        WHERE id = '${id}'
      `);
    }

    // Months later a new finalize claims the hourly retention slot: kept
    // snapshots get recipes synthesized from the legacy walk, after which
    // the unpinned ancestry is pruned wholesale.
    const snapD = await instance.finalizeSnapshot(owner, world.id, {
      files: [], packs: [packFor("pack-d", "packs/delta/c-d.bin", { snapshotId: snapC.snapshotId, hash: "pack-c" }, 3)]
    }, new Date("2026-06-01T10:00:00.000Z"));

    // Only the monthly-kept C and the new latest D survive as ROWS.
    const kept = (await repository.listSnapshotsForWorld(world.id)).map((snapshot) => snapshot.snapshotId).sort();
    expect(kept).toEqual([snapC.snapshotId, snapD.snapshotId].sort());

    // C was lazily upgraded: its directory now carries a synthesized recipe.
    const cRow = repository.raw
      .query("SELECT packs_json FROM snapshots WHERE id = ?")
      .get(snapC.snapshotId) as { packs_json: string };
    const cDirectory = JSON.parse(cRow.packs_json) as Array<{ chainSteps: Array<{ storageKey: string }> | null }>;
    expect(cDirectory[0]?.chainSteps?.map((step) => step.storageKey))
      .toEqual(["packs/full/a.pack", "packs/delta/a-b.bin", "packs/delta/b-c.bin"]);

    // The chain blobs survive (still referenced by recipes); a cold client
    // reconstructs the latest through the full recipe.
    expect(deleted).not.toContain("packs/full/a.pack");
    expect(deleted).not.toContain("packs/delta/a-b.bin");
    const plan = await instance.downloadPlan(owner, world.id, { files: [], nonRegionPack: null, regionBundles: [] });
    expect(plan.nonRegionPackDownload?.steps.map((step) => step.storageKey)).toEqual([
      "packs/full/a.pack",
      "packs/delta/a-b.bin",
      "packs/delta/b-c.bin",
      "packs/delta/c-d.bin"
    ]);
  });

  test("a legacy (unstamped) dependant still blocks deleting its base with the residual 409", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Legacy Guard", "legacy-guard");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    const owner = { playerUuid: "player-owner", playerName: "Owner" };

    const snapshotA = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [{
        packId: "non-region",
        hash: "pack-a",
        size: 100,
        storageKey: "packs/full/a.pack",
        transferMode: "pack-full",
        files: [{ path: "level.dat", hash: "level-a", size: 90, contentType: "application/octet-stream" }]
      }]
    }, new Date("2026-01-01T10:00:00.000Z"));
    const snapshotB = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [{
        packId: "non-region",
        hash: "pack-b",
        size: 20,
        storageKey: "packs/delta/a-b.bin",
        transferMode: "pack-delta",
        baseSnapshotId: snapshotA.snapshotId,
        baseHash: "pack-a",
        chainDepth: 1,
        files: [{ path: "level.dat", hash: "level-b", size: 91, contentType: "application/octet-stream" }]
      }]
    }, new Date("2026-01-01T11:00:00.000Z"));
    await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [{
        packId: "non-region",
        hash: "pack-c",
        size: 100,
        storageKey: "packs/full/c.pack",
        transferMode: "pack-full",
        files: [{ path: "level.dat", hash: "level-c", size: 92, contentType: "application/octet-stream" }]
      }]
    }, new Date("2026-01-01T12:00:00.000Z"));

    // Rewind B to a pre-stamping worker's representation: strip its recipe.
    repository.raw.exec(`
      UPDATE snapshots
      SET packs_json = (
        SELECT json_group_array(json_remove(pack.value, '$.chainSteps'))
        FROM json_each(packs_json) AS pack
      )
      WHERE id = '${snapshotB.snapshotId}'
    `);

    await expect(instance.deleteSnapshot(owner, world.id, snapshotA.snapshotId))
      .rejects.toThrow("A newer backup still needs this one");

    // Deleting the legacy dependant first unblocks the base, as before.
    await instance.deleteSnapshot(owner, world.id, snapshotB.snapshotId);
    await instance.deleteSnapshot(owner, world.id, snapshotA.snapshotId);
    expect(await repository.listSnapshotsForWorld(world.id)).toHaveLength(1);
  });

  test("the latest backup is well-defined even when two snapshots share a timestamp", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Latest Tie", "latest-tie");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    const owner = { playerUuid: "player-owner", playerName: "Owner" };
    const sameInstant = new Date("2026-01-01T10:00:00.000Z");

    // A duplicated finalize (client retry) lands two snapshots with one timestamp.
    for (const suffix of ["one", "two"]) {
      await instance.finalizeSnapshot(owner, world.id, {
        files: [{
          path: "level.dat",
          hash: `level-${suffix}`,
          size: 10,
          compressedSize: 5,
          storageKey: `blobs/le/level-${suffix}.bin`,
          contentType: "application/octet-stream"
        }]
      }, sameInstant);
    }

    const summaries = await instance.listSnapshots(owner, world.id);
    expect(summaries).toHaveLength(2);
    const flaggedLatest = summaries.filter((summary) => summary.isLatest);
    expect(flaggedLatest).toHaveLength(1);

    const worldDetails = await instance.getWorld(owner, world.id);
    expect(worldDetails.lastSnapshotId).toBe(flaggedLatest[0].snapshotId);
    const latestManifest = await instance.latestManifest(owner, world.id);
    expect(latestManifest?.snapshotId).toBe(flaggedLatest[0].snapshotId);

    // The delete guard protects exactly the snapshot everything else calls latest.
    await expect(instance.deleteSnapshot(owner, world.id, flaggedLatest[0].snapshotId))
      .rejects.toThrow("The latest backup cannot be deleted.");
    const other = summaries.find((summary) => !summary.isLatest)!;
    await instance.deleteSnapshot(owner, world.id, other.snapshotId);
  });

  const inheritedPack = (hash: string, storageKey: string) => ({
    packId: "non-region",
    hash,
    size: 100,
    storageKey,
    transferMode: "pack-full" as const,
    files: [
      { path: "level.dat", hash: `level-${hash}`, size: 60, contentType: "application/octet-stream" },
      { path: "data/foo.dat", hash: `foo-${hash}`, size: 40, contentType: "application/octet-stream" }
    ]
  });

  test("retention prunes member donors and promotes their rows to a surviving heir", async () => {
    const repository = createSqliteRepository();
    const { signer, deleted } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Donor Retention", "donor-retention");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    const owner = { playerUuid: "player-owner", playerName: "Owner" };

    // Two identical autosaves on day one: A materializes, B inherits from A.
    const snapshotA = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [inheritedPack("pack-a", "packs/full/a.pack")]
    }, new Date("2026-01-01T10:00:00.000Z"));
    const snapshotB = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [inheritedPack("pack-a", "packs/full/a.pack")],
      baseSnapshotId: snapshotA.snapshotId
    }, new Date("2026-01-01T11:00:00.000Z"));

    // Two days later a third identical save (donor flattened to A). Retention
    // prunes A by age — donors must NOT be kept alive, or the donor closure
    // would retain every autosave forever — and promotion hands A's member
    // rows to B so the surviving manifests stay complete.
    const snapshotC = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [inheritedPack("pack-a", "packs/full/a.pack")],
      baseSnapshotId: snapshotB.snapshotId
    }, new Date("2026-01-03T12:00:00.000Z"));

    const kept = await repository.listSnapshotsForWorld(world.id);
    expect(kept.map((snapshot) => snapshot.snapshotId).sort()).toEqual(
      [snapshotB.snapshotId, snapshotC.snapshotId].sort()
    );
    // The shared pack blob survives: B's and C's pack rows still reference it.
    expect(deleted).toHaveLength(0);
    for (const survivor of [snapshotB.snapshotId, snapshotC.snapshotId]) {
      const manifest = await repository.getSnapshot(world.id, survivor);
      expect(manifest?.packs[0]?.files.map((file) => file.path)).toEqual(["data/foo.dat", "level.dat"]);
    }
    const promoted = packDirectoryMembersSnapshotId(repository.raw, snapshotB.snapshotId);
    expect(promoted.members_snapshot_id).toBeNull();
    const repointed = packDirectoryMembersSnapshotId(repository.raw, snapshotC.snapshotId);
    expect(repointed.members_snapshot_id).toBe(snapshotB.snapshotId);
  });

  test("deleting a member donor promotes rows to its heir instead of refusing or orphaning", async () => {
    const repository = createSqliteRepository();
    const { signer, deleted } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Donor Delete", "donor-delete");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    const owner = { playerUuid: "player-owner", playerName: "Owner" };

    const snapshotA = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [inheritedPack("pack-a", "packs/full/a.pack")]
    }, new Date("2026-01-01T10:00:00.000Z"));
    const snapshotB = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [inheritedPack("pack-a", "packs/full/a.pack")],
      baseSnapshotId: snapshotA.snapshotId
    }, new Date("2026-01-01T11:00:00.000Z"));
    await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [inheritedPack("pack-c", "packs/full/c.pack")],
      baseSnapshotId: snapshotB.snapshotId
    }, new Date("2026-01-01T12:00:00.000Z"));

    // A plain member donor is deletable: its rows move to heir B, and the
    // shared pack blob survives because B's pack row still references it.
    await instance.deleteSnapshot(owner, world.id, snapshotA.snapshotId);
    expect(deleted).not.toContain("packs/full/a.pack");
    const manifest = await repository.getSnapshot(world.id, snapshotB.snapshotId);
    expect(manifest?.packs[0]?.files.map((file) => file.path)).toEqual(["data/foo.dat", "level.dat"]);

    // Once the last snapshot referencing the pack is gone, the blob goes too.
    await instance.deleteSnapshot(owner, world.id, snapshotB.snapshotId);
    expect(deleted).toContain("packs/full/a.pack");
    const remaining = await repository.listSnapshotsForWorld(world.id);
    expect(remaining).toHaveLength(1);
  });

  test("restore flattens through an inheriting snapshot, so the intermediate stays deletable", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Restore Flatten", "restore-flatten");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    const owner = { playerUuid: "player-owner", playerName: "Owner" };

    const snapshotA = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [inheritedPack("pack-a", "packs/full/a.pack")]
    }, new Date("2026-01-01T10:00:00.000Z"));
    const snapshotB = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [inheritedPack("pack-a", "packs/full/a.pack")],
      baseSnapshotId: snapshotA.snapshotId
    }, new Date("2026-01-01T11:00:00.000Z"));

    // Restoring B republishes its manifest; its pack is identical to B's, so
    // the restored snapshot inherits too — flattened straight to A, not B.
    // (Restore is only legal once the test claim's runtime has expired.)
    // Restore-time retention immediately prunes ancient A (monthly bucket
    // kept by B), promoting A's member rows into B.
    await instance.restoreSnapshot(owner, world.id, snapshotB.snapshotId, new Date("2099-01-05T00:00:00.000Z"));
    const restored = (await instance.listSnapshots(owner, world.id)).find((summary) => summary.isLatest)!;
    expect(restored.snapshotId).not.toBe(snapshotB.snapshotId);
    const keptIds = (await repository.listSnapshotsForWorld(world.id)).map((snapshot) => snapshot.snapshotId);
    expect(keptIds.sort()).toEqual([snapshotB.snapshotId, restored.snapshotId].sort());

    // Deleting the intermediate donor promotes the rows into the restored
    // snapshot itself, which stays fully loadable.
    await instance.deleteSnapshot(owner, world.id, snapshotB.snapshotId);
    const manifest = await repository.getSnapshot(world.id, restored.snapshotId);
    expect(manifest?.packs[0]?.files.map((file) => file.path)).toEqual(["data/foo.dat", "level.dat"]);
    const promoted = packDirectoryMembersSnapshotId(repository.raw, restored.snapshotId);
    expect(promoted.members_snapshot_id).toBeNull();
  });

  test("a pruned donor's delta chain stays reconstructable through the surviving heir", async () => {
    const repository = createSqliteRepository();
    const { signer, deleted } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Mixed Edges", "mixed-edges");
    await claimHostForTest(instance, { playerUuid: "player-owner", playerName: "Owner" }, world.id);
    const owner = { playerUuid: "player-owner", playerName: "Owner" };

    // E holds the full pack; D is a delta on E; F wins day one's age bucket;
    // K (day three) inherits D's member rows. D is prunable (promotion), but
    // E must survive: K's own pack row carries the delta edge onto E.
    const snapshotE = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [inheritedPack("pack-e", "packs/full/e.pack")]
    }, new Date("2026-01-01T09:00:00.000Z"));
    const snapshotD = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [{
        ...inheritedPack("pack-d", "packs/delta/e-d.bin"),
        transferMode: "pack-delta" as const,
        baseSnapshotId: snapshotE.snapshotId,
        baseHash: "pack-e",
        chainDepth: 1
      }],
      baseSnapshotId: snapshotE.snapshotId
    }, new Date("2026-01-01T10:00:00.000Z"));
    const snapshotF = await instance.finalizeSnapshot(owner, world.id, {
      files: [{
        path: "icon.png",
        hash: "icon-f",
        size: 10,
        compressedSize: 5,
        storageKey: "blobs/ic/icon-f.bin",
        contentType: "image/png"
      }],
      baseSnapshotId: snapshotD.snapshotId
    }, new Date("2026-01-01T11:00:00.000Z"));
    const snapshotK = await instance.finalizeSnapshot(owner, world.id, {
      files: [],
      packs: [{
        ...inheritedPack("pack-d", "packs/delta/e-d.bin"),
        transferMode: "pack-delta" as const,
        baseSnapshotId: snapshotE.snapshotId,
        baseHash: "pack-e",
        chainDepth: 1
      }],
      baseSnapshotId: snapshotD.snapshotId
    }, new Date("2026-01-03T12:00:00.000Z"));

    // S1: age keeps K (recent) and F (day-one bucket) — nothing else. D is
    // pruned with its member rows promoted into K; E's ROW is pruned too
    // because K's recipe is self-contained and no longer needs it.
    const kept = await repository.listSnapshotsForWorld(world.id);
    expect(kept.map((snapshot) => snapshot.snapshotId).sort()).toEqual(
      [snapshotF.snapshotId, snapshotK.snapshotId].sort()
    );
    // Both pack artifacts stay referenced through K's chain recipe.
    expect(deleted).toHaveLength(0);
    const manifest = await repository.getSnapshot(world.id, snapshotK.snapshotId);
    expect(manifest?.packs[0]?.files).toHaveLength(2);

    // A cold client can still reconstruct the pack: full artifact + delta tail.
    const plan = await instance.downloadPlan(owner, world.id, { files: [], nonRegionPack: null, regionBundles: [] });
    expect(plan.nonRegionPackDownload?.steps.map((step) => step.transferMode)).toEqual(["pack-full", "pack-delta"]);
  });
});
