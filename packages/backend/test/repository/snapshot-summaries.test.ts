import { describe, expect, test } from "bun:test";

import type { FinalizeSnapshotRequest, SnapshotPack } from "../../../shared/src/index.ts";

import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * Pins the set-based listSnapshotSummaries against the semantics of the old
 * per-snapshot implementation: counts and sizes include pack members resolved
 * through donor snapshots (member-row inheritance), stored bytes dedupe
 * storage keys per snapshot, and isLatest tracks the newest snapshot.
 */
describe("D1 repository snapshot summaries", () => {
  const owner = { playerUuid: "player-owner", playerName: "Owner" };

  function pack(overrides: Partial<SnapshotPack> = {}): SnapshotPack {
    return {
      packId: "non-region",
      hash: "pack-hash-1",
      size: 40,
      storageKey: "packs/full/one.pack",
      transferMode: "pack-full",
      files: [
        { path: "level.dat", hash: "hash-level", size: 25, contentType: "application/octet-stream" },
        { path: "icon.png", hash: "hash-icon", size: 15, contentType: "application/octet-stream" }
      ],
      ...overrides
    };
  }

  function bundle(overrides: Partial<SnapshotPack> = {}): SnapshotPack {
    return {
      packId: "region-bundle:region:0:0",
      hash: "bundle-hash-1",
      size: 100,
      storageKey: "region-bundles/full/one.bundle",
      transferMode: "region-full",
      files: [
        { path: "region/r.0.0.mca", hash: "hash-r00", size: 60, contentType: "application/octet-stream" },
        { path: "region/r.0.1.mca", hash: "hash-r01", size: 40, contentType: "application/octet-stream" }
      ],
      ...overrides
    };
  }

  function finalizeRequest(packs: SnapshotPack[], baseSnapshotId: string | null = null): FinalizeSnapshotRequest {
    return { files: [], packs, baseSnapshotId };
  }

  test("summaries count pack members through donor snapshots and mark the latest", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Summary SMP", "summary-smp");

    const first = await repository.finalizeSnapshot(
      world.id,
      owner,
      finalizeRequest([pack(), bundle()]),
      new Date("2026-01-01T00:00:00.000Z")
    );
    // Identical packs: the second snapshot inherits every member row from the
    // first (zero rows of its own), which is exactly the shape a naive
    // aggregate would undercount.
    const second = await repository.finalizeSnapshot(
      world.id,
      owner,
      finalizeRequest([pack(), bundle()], first.snapshotId),
      new Date("2026-01-01T00:05:00.000Z")
    );

    const summaries = await repository.listSnapshotSummaries(world.id);
    expect(summaries.map((summary) => summary.snapshotId)).toEqual([second.snapshotId, first.snapshotId]);
    for (const summary of summaries) {
      expect(summary.fileCount).toBe(4);
      expect(summary.totalSize).toBe(25 + 15 + 60 + 40);
    }
    // Both snapshots reference the same storage keys; the per-snapshot dedupe
    // must not double-count a key referenced by both a pack and its heir.
    expect(summaries[0].totalCompressedSize).toBe(summaries[1].totalCompressedSize);
    expect(summaries[0].isLatest).toBe(true);
    expect(summaries[1].isLatest).toBe(false);
    repository.close();
  });

  test("a snapshot with a changed pack reports its own counts, not the donor's", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Changed SMP", "changed-smp");

    const first = await repository.finalizeSnapshot(
      world.id,
      owner,
      finalizeRequest([pack()]),
      new Date("2026-01-01T00:00:00.000Z")
    );
    const grownPack = pack({
      hash: "pack-hash-2",
      size: 70,
      storageKey: "packs/full/two.pack",
      files: [
        { path: "level.dat", hash: "hash-level-2", size: 30, contentType: "application/octet-stream" },
        { path: "icon.png", hash: "hash-icon", size: 15, contentType: "application/octet-stream" },
        { path: "data/new.dat", hash: "hash-new", size: 25, contentType: "application/octet-stream" }
      ]
    });
    const second = await repository.finalizeSnapshot(
      world.id,
      owner,
      finalizeRequest([grownPack], first.snapshotId),
      new Date("2026-01-01T00:05:00.000Z")
    );

    const summaries = await repository.listSnapshotSummaries(world.id);
    const firstSummary = summaries.find((summary) => summary.snapshotId === first.snapshotId);
    const secondSummary = summaries.find((summary) => summary.snapshotId === second.snapshotId);
    expect(firstSummary?.fileCount).toBe(2);
    expect(firstSummary?.totalSize).toBe(40);
    expect(secondSummary?.fileCount).toBe(3);
    expect(secondSummary?.totalSize).toBe(70);
    repository.close();
  });

  test("a world with no snapshots lists nothing", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Empty SMP", "empty-smp");
    expect(await repository.listSnapshotSummaries(world.id)).toEqual([]);
    repository.close();
  });
});
