import { describe, expect, test } from "bun:test";

import type { FinalizeSnapshotRequest, SnapshotPack } from "../../../shared/src/index.ts";

import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * GC reference resolution after the 2026-08-17 D1 rows-read incident: the
 * pack-directory legs of the reference check were fleet-wide json_each scans
 * (~875k rows per key). They are now scoped to the storage account, to
 * snapshots created since an instant, and to what a caller has not already
 * resolved in memory; key lists travel as one JSON parameter (D1 caps bound
 * parameters at 100); manifest/icon keys never touch the directory legs.
 */
describe("GC reference scoping", () => {
  const owner = { playerUuid: "player-owner", playerName: "Owner" };
  const drive = (storageAccountId: string) => ({ provider: "google-drive" as const, storageAccountId });

  function pack(storageKey: string, overrides: Partial<SnapshotPack> = {}): SnapshotPack {
    return {
      packId: `pack-${storageKey}`,
      hash: `hash-${storageKey}`,
      size: 40,
      storageKey,
      transferMode: "pack-full",
      files: [{ path: `${storageKey}.dat`, hash: `h-${storageKey}`, size: 40, contentType: "application/octet-stream" }],
      ...overrides
    };
  }

  function request(packs: SnapshotPack[]): FinalizeSnapshotRequest {
    return { files: [], packs, baseSnapshotId: null };
  }

  /**
   * 0027 doc-format snapshots carry NO pack member rows: the pack directory
   * (packs_json) is the only place their pack keys live. The repository-level
   * finalize used here still writes member rows, so drop them to make sure
   * the assertions below are answered by the directory legs, not the row legs.
   */
  function docFormat(repository: ReturnType<typeof createSqliteRepository>): void {
    repository.raw.exec("DELETE FROM snapshot_files WHERE pack_id IS NOT NULL");
    expect(repository.raw.query("SELECT COUNT(*) AS n FROM snapshot_files").get()).toEqual({ n: 0 });
  }

  test("directory references are resolved only within the storage account", async () => {
    const repository = createSqliteRepository();
    const worldA = await repository.createWorld(owner, "A", "a", drive("acct-a"));
    const worldB = await repository.createWorld(owner, "B", "b", drive("acct-b"));
    await repository.finalizeSnapshot(worldA.id, owner, request([pack("packs/full/aa/shared.pack")]), new Date("2026-01-01T00:00:00.000Z"));
    await repository.finalizeSnapshot(worldB.id, owner, request([pack("packs/full/aa/shared.pack")]), new Date("2026-01-01T00:00:00.000Z"));
    docFormat(repository);

    expect(await repository.isStorageKeyReferenced("packs/full/aa/shared.pack", drive("acct-a"))).toBe(true);
    expect(await repository.isStorageKeyReferenced("packs/full/aa/shared.pack", drive("acct-b"))).toBe(true);
    // A third account holds the same content-addressed bytes but no
    // snapshot: from its point of view the key is garbage.
    expect(await repository.isStorageKeyReferenced("packs/full/aa/shared.pack", drive("acct-c"))).toBe(false);
    // Unscoped = fleet-wide (tests/tools only).
    expect(await repository.isStorageKeyReferenced("packs/full/aa/shared.pack")).toBe(true);
    repository.close();
  });

  test("snapshotsCreatedSince sees only newer snapshots, with chain recipes included", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "W", "w", drive("acct-1"));
    const step = { storageKey: "packs/full/aa/anchor.pack", hash: "h-anchor", baseHash: null, transferMode: "pack-full" as const, size: 40, deltaFormatVersion: null };
    await repository.finalizeSnapshot(world.id, owner, request([pack("packs/full/aa/old.pack", { chainSteps: [step] })]), new Date("2026-01-01T00:00:00.000Z"));
    await repository.finalizeSnapshot(world.id, owner, request([pack("packs/full/aa/new.pack")]), new Date("2026-01-02T00:00:00.000Z"));
    docFormat(repository);

    const since = { ...drive("acct-1"), snapshotsCreatedSince: "2026-01-01T12:00:00.000Z" };
    const referenced = await repository.filterReferencedStorageKeys(
      ["packs/full/aa/old.pack", "packs/full/aa/anchor.pack", "packs/full/aa/new.pack", "packs/full/aa/never.pack"],
      since
    );
    expect([...referenced].sort()).toEqual(["packs/full/aa/new.pack"]);
    // Without the time bound the older snapshot's pack AND its recipe count.
    const all = await repository.filterReferencedStorageKeys(["packs/full/aa/old.pack", "packs/full/aa/anchor.pack"], drive("acct-1"));
    expect([...all].sort()).toEqual(["packs/full/aa/anchor.pack", "packs/full/aa/old.pack"]);
    repository.close();
  });

  test("manifest and icon keys resolve through their own columns, not the directories", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "W", "w", drive("acct-1"), null, "icons/ab/abcd.png");
    // Legacy representation on purpose (member rows kept): the pack key is
    // answered by the indexed row leg here, the other tests use docFormat().
    await repository.finalizeSnapshot(world.id, owner, request([pack("packs/full/aa/one.pack")]), new Date("2026-01-01T00:00:00.000Z"));
    repository.raw.exec(`UPDATE snapshots SET manifest_storage_key = 'manifests/cd/cdef.json'`);

    const referenced = await repository.filterReferencedStorageKeys(
      ["icons/ab/abcd.png", "icons/ab/absent.png", "manifests/cd/cdef.json", "manifests/cd/absent.json", "packs/full/aa/one.pack"],
      drive("acct-1")
    );
    expect([...referenced].sort()).toEqual(["icons/ab/abcd.png", "manifests/cd/cdef.json", "packs/full/aa/one.pack"]);
    repository.close();
  });

  test("hundreds of candidate keys and snapshot ids fit in one statement", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Big", "big", drive("acct-1"));
    // 150 snapshots × 3 packs: retention deleting all but the last must
    // report every now-orphaned key (150 × 2 unique + shared kept by the
    // survivor) without tripping D1's 100-parameter cap on any list.
    const ids: string[] = [];
    for (let index = 0; index < 150; index += 1) {
      const packs = [
        pack("packs/full/aa/shared.pack"),
        pack(`packs/full/aa/u${index}-1.pack`),
        pack(`packs/full/aa/u${index}-2.pack`)
      ];
      const created = await repository.finalizeSnapshot(world.id, owner, request(packs), new Date(Date.UTC(2026, 0, 1, 0, index)));
      ids.push(created.snapshotId);
    }
    docFormat(repository);
    const survivor = ids[ids.length - 1];
    const deletion = await repository.deleteSnapshots(world.id, ids.slice(0, -1));
    expect(deletion.deletedSnapshotIds).toHaveLength(149);
    expect(deletion.unreferencedStorageKeys).toHaveLength(298);
    expect(deletion.unreferencedStorageKeys).not.toContain("packs/full/aa/shared.pack");
    expect(deletion.unreferencedStorageKeys).not.toContain("packs/full/aa/u149-1.pack");
    expect(await repository.listSnapshotsForWorld(world.id)).toHaveLength(1);
    expect((await repository.listSnapshotsForWorld(world.id))[0]?.snapshotId).toBe(survivor);
    // Wide candidate list through the batched check too (> prefilter cutoff).
    const wide = await repository.filterReferencedStorageKeys(
      [...deletion.unreferencedStorageKeys, "packs/full/aa/shared.pack"],
      drive("acct-1")
    );
    expect([...wide]).toEqual(["packs/full/aa/shared.pack"]);
    repository.close();
  });

  test("deleteSnapshots still sees references held by another world of the same account", async () => {
    const repository = createSqliteRepository();
    const worldA = await repository.createWorld(owner, "A", "a", drive("acct-1"));
    const worldB = await repository.createWorld(owner, "B", "b", drive("acct-1"));
    const worldC = await repository.createWorld(owner, "C", "c", drive("acct-2"));
    const doomed = await repository.finalizeSnapshot(worldA.id, owner, request([pack("packs/full/aa/x.pack"), pack("packs/full/aa/y.pack")]), new Date("2026-01-01T00:00:00.000Z"));
    await repository.finalizeSnapshot(worldB.id, owner, request([pack("packs/full/aa/x.pack")]), new Date("2026-01-01T00:00:00.000Z"));
    // Same key in a different account must NOT keep account-1's copy alive.
    await repository.finalizeSnapshot(worldC.id, owner, request([pack("packs/full/aa/y.pack")]), new Date("2026-01-01T00:00:00.000Z"));
    docFormat(repository);

    const deletion = await repository.deleteSnapshots(worldA.id, [doomed.snapshotId]);
    expect(deletion.unreferencedStorageKeys).toEqual(["packs/full/aa/y.pack"]);
    repository.close();
  });
});
