import { describe, expect, test } from "bun:test";

import type { RequestContext } from "../../src/repository.ts";
import type { ServiceContext } from "../../src/service/context.ts";
import { deleteUnreferencedBlobs } from "../../src/service/snapshots.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { claimHostForTest, createBlobSigner, createStorageProviderSpy, createTestService } from "../support/service-fixtures.ts";

/**
 * Finalize answers as soon as the snapshot is durable; the hourly retention
 * pass rides `ctx.defer` (Workers waitUntil) when the runtime offers it.
 * Measured inline it took 19-46s — past the mod's 20s request timeout, so a
 * finalize that had SUCCEEDED was reported as a transient failure. Deferred
 * work can be cut off, so blob deletes run under a budget and hand the
 * remainder to the pending_blob_deletes queue.
 */
const OWNER = { playerUuid: "player-owner", playerName: "Owner" };

function file(hash: string, key: string, path = "level.dat") {
  return { path, hash, size: 10, compressedSize: 5, storageKey: key, contentType: "application/octet-stream" };
}

describe("deferred retention", () => {
  test("finalize returns before retention; the deferred pass reaches the same end state as inline", async () => {
    const repository = createSqliteRepository();
    const { signer, deleted } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ ...OWNER, createdAt: new Date().toISOString() });
    const world = await repository.createWorld(OWNER, "Retention Test", "retention-test");
    await claimHostForTest(instance, OWNER, world.id);

    const deferred: Promise<unknown>[] = [];
    const ctx: RequestContext = { ...OWNER, defer: (task) => { deferred.push(task); } };
    const finalize = (files: ReturnType<typeof file>[], at: string) => instance.finalizeSnapshot(ctx, world.id, { files }, new Date(at));

    await finalize([file("jan-old", "blobs/ja/jan-old.bin")], "2026-01-01T00:00:00.000Z");
    await finalize([file("jan-keep", "blobs/ja/jan-keep.bin")], "2026-01-20T12:00:00.000Z");
    await finalize([file("march-old", "blobs/ma/march-old.bin"), file("shared", "blobs/sh/shared.bin", "playerdata/owner.dat")], "2026-03-01T10:00:00.000Z");
    await finalize([file("march-keep", "blobs/ma/march-keep.bin"), file("shared", "blobs/sh/shared.bin", "playerdata/owner.dat")], "2026-03-01T12:00:00.000Z");
    await finalize([file("recent-a", "blobs/re/recent-a.bin")], "2026-03-30T10:00:00.000Z");
    const manifest = await finalize([file("recent-b", "blobs/re/recent-b.bin")], "2026-03-31T00:00:00.000Z");
    expect(manifest.snapshotId).toBeDefined();

    // Every finalize claimed a fresh hourly slot and handed retention off.
    expect(deferred).toHaveLength(6);
    await Promise.all(deferred);

    const kept = await repository.listSnapshotsForWorld(world.id);
    expect(kept.map((snapshot) => snapshot.createdAt)).toEqual([
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

describe("budgeted blob deletion", () => {
  async function fixture() {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const { storageProvider, deleted } = createStorageProviderSpy("google-drive");
    const svc = { repository, blobSigner: signer, storageProvider } as unknown as ServiceContext;
    const binding = { provider: "google-drive" as const, storageAccountId: "storage-account-1" };
    return { svc, repository, binding, deleted };
  }

  test("an exhausted budget queues every remaining key instead of deleting", async () => {
    const { svc, repository, binding, deleted } = await fixture();
    await deleteUnreferencedBlobs(svc, binding, ["k/one", "k/two", "k/three"], 0);
    expect(deleted).toEqual([]);
    const queued = await repository.listPendingBlobDeletes("google-drive", "storage-account-1", 10);
    expect(queued.map((entry) => entry.storageKey).sort()).toEqual(["k/one", "k/three", "k/two"]);
  });

  test("no budget deletes everything and queues nothing", async () => {
    const { svc, repository, binding, deleted } = await fixture();
    await deleteUnreferencedBlobs(svc, binding, ["k/one", "k/two"]);
    expect(deleted.sort()).toEqual(["k/one", "k/two"]);
    expect(await repository.listPendingBlobDeletes("google-drive", "storage-account-1", 10)).toEqual([]);
  });
});
