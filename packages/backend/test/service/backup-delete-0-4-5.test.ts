import { describe, expect, test } from "bun:test";

import type { RequestContext } from "../../src/repository.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { claimHostForTest, createBlobSigner, createStorageProviderSpy, createTestService } from "../support/service-fixtures.ts";

/**
 * 0.4.5 backup management: bulk/instant delete, the thinner default
 * schedule, maxBackups down to 1 with immediate effect, and the cron drain
 * of the blob GC queue. Motivated by a user deleting old backups one by one
 * because Google Drive filled up — each delete waited on every Drive DELETE
 * inline (past the mod's 20s timeout on big worlds), and the 24h keep-all
 * window pinned a day of near-duplicate autosaves.
 */
const OWNER = { playerUuid: "player-owner", playerName: "Owner" };

function file(hash: string, key: string, path = "level.dat") {
  return { path, hash, size: 10, compressedSize: 5, storageKey: key, contentType: "application/octet-stream" };
}

/** Same shape as the deferred-retention test: R2-mode service, blob deletes observed through the signer. */
async function seedWorld(name: string, slug: string) {
  const repository = createSqliteRepository();
  const { signer, deleted } = createBlobSigner();
  // Blob deletes can be held behind a gate so a test can observe the response
  // arriving while the deferred cleanup is still pending (with in-memory
  // mocks everything else settles within the same microtask turn).
  let gate: Promise<void> = Promise.resolve();
  const holdDeletes = (): (() => void) => {
    let release: () => void = () => undefined;
    gate = new Promise<void>((resolve) => { release = resolve; });
    return release;
  };
  const gatedSigner = {
    ...signer,
    async deleteBlob(storageKey: string) {
      await gate;
      await signer.deleteBlob?.(storageKey);
    }
  };
  const instance = createTestService(repository, gatedSigner, {});
  await repository.upsertUser({ ...OWNER, createdAt: new Date().toISOString() });
  const world = await repository.createWorld(OWNER, name, slug);
  await claimHostForTest(instance, OWNER, world.id);
  const finalize = (ctx: RequestContext, files: ReturnType<typeof file>[], at: string) =>
    instance.finalizeSnapshot(ctx, world.id, { files }, new Date(at));
  return { repository, instance, world, finalize, deleted, holdDeletes };
}

describe("0.4.5 backup deletion", () => {
  test("bulk delete drops every named row in one pass and reclaims blobs after the response", async () => {
    const { repository, instance, world, finalize, deleted, holdDeletes } = await seedWorld("Bulk", "bulk");
    // Distinct days so retention (which rides finalize) keeps them all.
    const a = await finalize(OWNER, [file("a", "blobs/a.bin"), file("shared", "blobs/shared.bin", "playerdata/o.dat")], "2026-05-01T10:00:00.000Z");
    const b = await finalize(OWNER, [file("b", "blobs/b.bin"), file("shared", "blobs/shared.bin", "playerdata/o.dat")], "2026-05-02T10:00:00.000Z");
    const c = await finalize(OWNER, [file("c", "blobs/c.bin"), file("shared", "blobs/shared.bin", "playerdata/o.dat")], "2026-05-03T10:00:00.000Z");
    const latest = await finalize(OWNER, [file("d", "blobs/d.bin")], "2026-05-04T10:00:00.000Z");
    deleted.length = 0;
    const releaseDeletes = holdDeletes();

    const deferred: Promise<unknown>[] = [];
    const ctx: RequestContext = { ...OWNER, defer: (task) => { deferred.push(task); } };
    const result = await instance.deleteSnapshots(ctx, world.id, {
      snapshotIds: [a.snapshotId, b.snapshotId, "snapshot_missing", a.snapshotId]
    });

    // Rows are gone at response time; missing ids are skipped, duplicates collapse.
    expect(result.deletedSnapshotIds.sort()).toEqual([a.snapshotId, b.snapshotId].sort());
    const remaining = (await repository.listSnapshotsForWorld(world.id)).map((snapshot) => snapshot.snapshotId).sort();
    expect(remaining).toEqual([c.snapshotId, latest.snapshotId].sort());
    // The response did not wait for the provider deletes.
    expect(deleted).toEqual([]);
    expect(deferred).toHaveLength(1);
    releaseDeletes();
    await Promise.all(deferred);
    // Only the keys no surviving snapshot references; the shared blob stays.
    expect(deleted.sort()).toEqual(["blobs/a.bin", "blobs/b.bin"]);

    // Naming the latest anywhere in the set refuses the whole request.
    await expect(instance.deleteSnapshots(OWNER, world.id, { snapshotIds: [c.snapshotId, latest.snapshotId] }))
      .rejects.toMatchObject({ status: 409, code: "cannot_delete_latest_snapshot" });
    // Nothing that exists → 404, as the single-id form.
    await expect(instance.deleteSnapshots(OWNER, world.id, { snapshotIds: ["snapshot_missing"] }))
      .rejects.toMatchObject({ status: 404 });
    await expect(instance.deleteSnapshots(OWNER, world.id, { snapshotIds: [] }))
      .rejects.toMatchObject({ status: 400 });
  });

  test("single delete answers before the provider deletes run when the runtime offers waitUntil", async () => {
    const { instance, world, finalize, deleted, holdDeletes } = await seedWorld("Single", "single");
    const old = await finalize(OWNER, [file("a", "blobs/a.bin")], "2026-05-01T10:00:00.000Z");
    await finalize(OWNER, [file("b", "blobs/b.bin")], "2026-05-02T10:00:00.000Z");
    deleted.length = 0;
    const releaseDeletes = holdDeletes();
    const deferred: Promise<unknown>[] = [];
    await instance.deleteSnapshot({ ...OWNER, defer: (task) => { deferred.push(task); } }, world.id, old.snapshotId);
    expect(deleted).toEqual([]);
    releaseDeletes();
    await Promise.all(deferred);
    expect(deleted).toEqual(["blobs/a.bin"]);
  });

  test("the age schedule keeps everything for an hour, then one per hour, then dailies", async () => {
    const { repository, world, finalize, deleted } = await seedWorld("Schedule", "schedule");
    const at = async (stamp: string, hash: string) => (await finalize(OWNER, [file(hash, `blobs/${hash}.bin`)], stamp)).createdAt;
    const t0 = await at("2026-06-01T00:00:00.000Z", "t0"); // 3.5 days old → daily
    await at("2026-06-03T20:10:00.000Z", "t1"); // same hour as t2, older → thinned
    const t2 = await at("2026-06-03T20:40:00.000Z", "t2"); // ~15h old → hourly bucket keeps the newest
    const t3 = await at("2026-06-04T08:30:00.000Z", "t3"); // 3.5h old → own hourly bucket
    const t4 = await at("2026-06-04T11:15:00.000Z", "t4"); // 45 min old → keep-all window
    const t5 = await at("2026-06-04T12:00:00.000Z", "t5"); // latest

    // Retention rode the last finalize's hourly slot (previous slot at t3).
    const kept = (await repository.listSnapshotsForWorld(world.id)).map((snapshot) => snapshot.createdAt);
    expect(kept).toEqual([t5, t4, t3, t2, t0]);
    expect(deleted).toContain("blobs/t1.bin");
    expect(deleted).not.toContain("blobs/t2.bin");
  });

  test("maxBackups 1 keeps only the current snapshot, and lowering the cap prunes right away", async () => {
    const { repository, instance, world, finalize } = await seedWorld("Latest Only", "latest-only");
    const ids: string[] = [];
    for (let day = 1; day <= 4; day += 1) {
      ids.push((await finalize(OWNER, [file(`d${day}`, `blobs/d${day}.bin`)], `2026-05-0${day}T10:00:00.000Z`)).snapshotId);
    }
    expect((await repository.listSnapshotsForWorld(world.id)).length).toBe(4);

    await expect(instance.updateWorldSettings(OWNER, world.id, { settings: { maxBackups: 0 } }))
      .rejects.toMatchObject({ status: 400 });

    // No hourly-slot wait: the settings write itself runs retention (deferred).
    const deferred: Promise<unknown>[] = [];
    await instance.updateWorldSettings({ ...OWNER, defer: (task) => { deferred.push(task); } }, world.id, { settings: { maxBackups: 1 } });
    expect(deferred).toHaveLength(1);
    await Promise.all(deferred);
    const kept = (await repository.listSnapshotsForWorld(world.id)).map((snapshot) => snapshot.snapshotId);
    expect(kept).toEqual([ids[3]]);

    // Raising the cap does not run retention.
    deferred.length = 0;
    await instance.updateWorldSettings({ ...OWNER, defer: (task) => { deferred.push(task); } }, world.id, { settings: { maxBackups: 10 } });
    expect(deferred).toHaveLength(0);
  });

  test("a capped world enforces the cap on every finalize, not just the hourly slot", async () => {
    const { repository, instance, world, finalize } = await seedWorld("Cap Each Save", "cap-each-save");
    await instance.updateWorldSettings(OWNER, world.id, { settings: { maxBackups: 1 } });
    // Saves minutes apart: the hourly retention slot is claimed by the first
    // and would skip the rest, yet "None" must hold after each one.
    const kept: string[] = [];
    for (let minute = 0; minute < 5; minute += 1) {
      const stamp = `2026-06-01T10:0${minute}:00.000Z`;
      const manifest = await finalize(OWNER, [file(`m${minute}`, `blobs/m${minute}.bin`)], stamp);
      kept.push(manifest.snapshotId);
      const ids = (await repository.listSnapshotsForWorld(world.id)).map((snapshot) => snapshot.snapshotId);
      expect(ids).toEqual([manifest.snapshotId]);
    }
  });

  test("the cron sweep drops a queued key that a snapshot created since the enqueue references again", async () => {
    // Content-addressed dedupe can resurrect a key between enqueue and sweep.
    // The re-check is scoped to snapshots created since the enqueue (with
    // slack): a newer snapshot naming the key must keep the blob, and the
    // queue row is dropped instead of deleted.
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const spy = createStorageProviderSpy("google-drive");
    const instance = createTestService(repository, signer, spy.storageProvider, {});
    await repository.upsertUser({ ...OWNER, createdAt: new Date().toISOString() });
    const world = await repository.createWorld(OWNER, "Resurrect", "resurrect", { provider: "google-drive", storageAccountId: "acct-1" });
    await repository.enqueuePendingBlobDeletes("google-drive", "acct-1", ["blobs/back.bin", "blobs/gone.bin"], "2026-05-01T00:00:00.000Z");
    // Doc-format snapshot: the pack key lives only in the directory.
    await repository.finalizeSnapshot(world.id, OWNER, {
      files: [],
      packs: [{ packId: "p", hash: "h", size: 1, storageKey: "blobs/back.bin", transferMode: "pack-full", files: [] }],
      baseSnapshotId: null
    }, new Date("2026-05-01T00:03:00.000Z"));
    repository.raw.exec("DELETE FROM snapshot_files");

    expect(await instance.sweepDuePendingBlobDeletes(new Date("2026-05-01T00:05:00.000Z"))).toBe(2);
    expect(spy.deleted).toEqual(["blobs/gone.bin"]);
    expect(await repository.listPendingBlobDeletes("google-drive", "acct-1", 10)).toEqual([]);
  });

  test("the cron sweep drains due queue entries across accounts and backs off failing keys", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const spy = createStorageProviderSpy("google-drive");
    const failing = new Set(["blobs/flaky.bin"]);
    const storageProvider = {
      ...spy.storageProvider,
      async delete(binding: Parameters<typeof spy.storageProvider.delete>[0], storageKey: string) {
        if (failing.has(storageKey)) {
          spy.deleted.push(storageKey);
          throw new Error("drive said no");
        }
        return spy.storageProvider.delete(binding, storageKey);
      }
    };
    const instance = createTestService(repository, signer, storageProvider, {});
    await repository.enqueuePendingBlobDeletes("google-drive", "acct-1", ["blobs/flaky.bin", "blobs/ok-1.bin"], "2026-05-01T00:00:00.000Z");
    await repository.enqueuePendingBlobDeletes("google-drive", "acct-2", ["blobs/ok-2.bin"], "2026-05-01T00:00:01.000Z");

    const t0 = new Date("2026-05-01T00:01:00.000Z");
    expect(await instance.sweepDuePendingBlobDeletes(t0)).toBe(3);
    expect(spy.deleted.sort()).toEqual(["blobs/flaky.bin", "blobs/ok-1.bin", "blobs/ok-2.bin"]);
    // Successful keys leave the queue; the failed one stays with attempts=1
    // and is not due again until its backoff (5 min for the first failure).
    expect(await repository.listDuePendingBlobDeletes(new Date(t0.getTime() + 60_000).toISOString(), 10)).toEqual([]);
    const later = new Date(t0.getTime() + 6 * 60_000);
    expect(await repository.listDuePendingBlobDeletes(later.toISOString(), 10)).toEqual([
      { provider: "google-drive", storageAccountId: "acct-1", storageKey: "blobs/flaky.bin", attempts: 1, enqueuedAt: "2026-05-01T00:00:00.000Z" }
    ]);
    // Second failure doubles the wait.
    expect(await instance.sweepDuePendingBlobDeletes(later)).toBe(1);
    expect(await repository.listDuePendingBlobDeletes(new Date(later.getTime() + 6 * 60_000).toISOString(), 10)).toEqual([]);
    expect((await repository.listDuePendingBlobDeletes(new Date(later.getTime() + 11 * 60_000).toISOString(), 10)).map((entry) => entry.attempts)).toEqual([2]);
    // Once the provider recovers the key drains and the queue is empty.
    failing.clear();
    expect(await instance.sweepDuePendingBlobDeletes(new Date(later.getTime() + 11 * 60_000))).toBe(1);
    expect(await repository.listDuePendingBlobDeletes("2027-01-01T00:00:00.000Z", 10)).toEqual([]);
  });
});
