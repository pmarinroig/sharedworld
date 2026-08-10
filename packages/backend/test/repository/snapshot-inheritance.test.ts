import { describe, expect, test } from "bun:test";

import type { FinalizeSnapshotRequest, SnapshotPack } from "../../../shared/src/index.ts";

import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * Member-row inheritance: a pack identical to the base snapshot's pack must
 * not re-insert its snapshot_files member rows (that re-insertion dominated
 * D1 rows-written), and resolution must always be one flattened hop to the
 * snapshot that physically holds the rows.
 */
describe("D1 repository snapshot pack member inheritance", () => {
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
        { path: "session.lock", hash: "hash-lock", size: 15, contentType: "application/octet-stream" }
      ],
      ...overrides
    };
  }

  function finalizeRequest(packs: SnapshotPack[], baseSnapshotId: string | null = null): FinalizeSnapshotRequest {
    return { files: [], packs, baseSnapshotId };
  }

  function memberRowCount(repository: ReturnType<typeof createSqliteRepository>, snapshotId: string, packId: string): number {
    const row = repository.raw
      .query("SELECT COUNT(*) AS count FROM snapshot_files WHERE snapshot_id = ? AND pack_id = ?")
      .get(snapshotId, packId) as { count: number };
    return Number(row.count);
  }

  function membersSnapshotId(repository: ReturnType<typeof createSqliteRepository>, snapshotId: string, packId: string): string | null {
    const row = repository.raw
      .query("SELECT members_snapshot_id FROM snapshot_packs WHERE snapshot_id = ? AND pack_id = ?")
      .get(snapshotId, packId) as { members_snapshot_id: string | null };
    return row.members_snapshot_id;
  }

  test("an identical pack inherits member rows instead of re-inserting them", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Inherit SMP", "inherit-smp");

    const first = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()]), new Date("2026-01-01T00:00:00.000Z"));
    const second = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()], first.snapshotId), new Date("2026-01-01T00:05:00.000Z"));

    expect(memberRowCount(repository, first.snapshotId, "non-region")).toBe(2);
    expect(memberRowCount(repository, second.snapshotId, "non-region")).toBe(0);
    expect(membersSnapshotId(repository, second.snapshotId, "non-region")).toBe(first.snapshotId);

    // The manifest still round-trips the full member list through the donor.
    const loaded = await repository.getSnapshot(world.id, second.snapshotId);
    expect(loaded?.packs[0]?.files.map((file) => file.path)).toEqual(["level.dat", "session.lock"]);
    repository.close();
  });

  test("inheritance flattens to the physical holder, never a chain", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Flatten SMP", "flatten-smp");

    const a = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()]), new Date("2026-01-01T00:00:00.000Z"));
    const b = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()], a.snapshotId), new Date("2026-01-01T00:05:00.000Z"));
    const c = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()], b.snapshotId), new Date("2026-01-01T00:10:00.000Z"));

    // C based on B, but B's rows live in A: C must point straight at A.
    expect(membersSnapshotId(repository, c.snapshotId, "non-region")).toBe(a.snapshotId);
    expect(memberRowCount(repository, c.snapshotId, "non-region")).toBe(0);
    const loaded = await repository.getSnapshot(world.id, c.snapshotId);
    expect(loaded?.packs[0]?.files).toHaveLength(2);
    repository.close();
  });

  test.each([
    ["hash", { hash: "pack-hash-2" }],
    ["storageKey", { storageKey: "packs/full/two.pack" }],
    ["size", { size: 41 }],
    ["chainDepth", { transferMode: "pack-delta", baseSnapshotId: "unused", baseHash: "unused", chainDepth: 1 } as Partial<SnapshotPack>],
    ["deltaFormatVersion", { deltaFormatVersion: 2 } as Partial<SnapshotPack>],
    ["deltaBlobSize", { deltaBlobSize: 123 } as Partial<SnapshotPack>],
    ["chainDeltaBytes", { chainDeltaBytes: 456 } as Partial<SnapshotPack>]
  ])("a pack differing in %s is materialized with its own member rows", async (_field, overrides) => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Change SMP", "change-smp");

    const first = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()]), new Date("2026-01-01T00:00:00.000Z"));
    const second = await repository.finalizeSnapshot(
      world.id,
      owner,
      finalizeRequest([pack(overrides)], first.snapshotId),
      new Date("2026-01-01T00:05:00.000Z")
    );

    expect(memberRowCount(repository, second.snapshotId, "non-region")).toBe(2);
    expect(membersSnapshotId(repository, second.snapshotId, "non-region")).toBeNull();
    repository.close();
  });

  test("mixed manifests only write rows for changed packs and top-level files", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Mixed SMP", "mixed-smp");

    const regionPack = pack({
      packId: "region-r.0.0",
      hash: "region-hash-1",
      storageKey: "packs/full/region.pack",
      files: [
        { path: "region/r.0.0.mca", hash: "hash-region", size: 40, contentType: "application/octet-stream" }
      ]
    });
    const first = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack(), regionPack]), new Date("2026-01-01T00:00:00.000Z"));

    const changedRegionPack = { ...regionPack, hash: "region-hash-2", storageKey: "packs/full/region2.pack" };
    const second = await repository.finalizeSnapshot(
      world.id,
      owner,
      {
        files: [
          {
            path: "icon.png",
            hash: "hash-icon",
            size: 10,
            compressedSize: 8,
            storageKey: "blobs/ic/icon.bin",
            contentType: "image/png"
          }
        ],
        packs: [pack(), changedRegionPack],
        baseSnapshotId: first.snapshotId
      },
      new Date("2026-01-01T00:05:00.000Z")
    );

    // Unchanged non-region pack inherited; changed region pack + top-level file materialized.
    expect(memberRowCount(repository, second.snapshotId, "non-region")).toBe(0);
    expect(memberRowCount(repository, second.snapshotId, "region-r.0.0")).toBe(1);
    const topLevel = repository.raw
      .query("SELECT COUNT(*) AS count FROM snapshot_files WHERE snapshot_id = ? AND pack_id IS NULL")
      .get(second.snapshotId) as { count: number };
    expect(Number(topLevel.count)).toBe(1);
    repository.close();
  });

  test("a legacy materialized snapshot (NULL members_snapshot_id) serves as donor", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Legacy SMP", "legacy-smp");

    // Any pre-inheritance snapshot is exactly a materialized one: physical
    // rows, NULL members_snapshot_id. Its heir must inherit directly from it.
    const legacy = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()]), new Date("2026-01-01T00:00:00.000Z"));
    expect(membersSnapshotId(repository, legacy.snapshotId, "non-region")).toBeNull();

    const heir = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()], legacy.snapshotId), new Date("2026-01-01T00:05:00.000Z"));
    expect(membersSnapshotId(repository, heir.snapshotId, "non-region")).toBe(legacy.snapshotId);
    expect((await repository.getSnapshot(world.id, heir.snapshotId))?.packs[0]?.files).toHaveLength(2);
    repository.close();
  });

  test("deleting a donor promotes member rows to the oldest heir and repoints the rest", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Promote SMP", "promote-smp");

    const a = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()]), new Date("2026-01-01T00:00:00.000Z"));
    const b = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()], a.snapshotId), new Date("2026-01-01T00:05:00.000Z"));
    const c = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()], b.snapshotId), new Date("2026-01-01T00:10:00.000Z"));

    const deletion = await repository.deleteSnapshots(world.id, [a.snapshotId]);
    expect(deletion.deletedSnapshotIds).toEqual([a.snapshotId]);
    // The pack blob is still referenced by the heirs' pack rows.
    expect(deletion.unreferencedStorageKeys).toEqual([]);

    // B (the oldest heir) now physically holds the rows; C points at B.
    expect(memberRowCount(repository, b.snapshotId, "non-region")).toBe(2);
    expect(membersSnapshotId(repository, b.snapshotId, "non-region")).toBeNull();
    expect(membersSnapshotId(repository, c.snapshotId, "non-region")).toBe(b.snapshotId);
    expect((await repository.getSnapshot(world.id, b.snapshotId))?.packs[0]?.files).toHaveLength(2);
    expect((await repository.getSnapshot(world.id, c.snapshotId))?.packs[0]?.files).toHaveLength(2);

    // Member pointers are deliberately not dependency edges: donors are
    // prunable precisely because promotion exists.
    const edges = await repository.listSnapshotDeltaBases(world.id);
    expect(edges).toEqual([]);
    repository.close();
  });
});
