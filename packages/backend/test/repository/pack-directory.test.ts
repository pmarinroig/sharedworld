import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { Database } from "bun:sqlite";
import { describe, expect, test } from "bun:test";

import type { FinalizeSnapshotRequest, SnapshotPack } from "../../../shared/src/index.ts";

import { SqliteD1Database, createSqliteRepository } from "../support/sqlite-d1.ts";
import { D1SharedWorldRepository } from "../../src/d1-repository.ts";

/**
 * EB7: pack headers moved into the snapshots row's packs_json directory
 * (migration 0026). These tests pin the transition: the backfill converts
 * live pre-0026 rows losslessly, legacy rows without a directory stay
 * readable, promotion works across mixed representations, and the retention
 * slot is an hourly CAS.
 */
describe("snapshot pack directory (0026)", () => {
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

  test("migration 0026 backfills directories and aggregates from live pre-0026 rows", async () => {
    const backendRoot = join(import.meta.dir, "../..");
    const migrationFiles = readdirSync(join(backendRoot, "migrations")).filter((name) => name.endsWith(".sql")).sort();
    const db = new Database(":memory:");
    for (const fileName of migrationFiles.filter((name) => name < "0026")) {
      db.exec(readFileSync(join(backendRoot, "migrations", fileName), "utf8"));
    }

    // A pre-0026 world: base snapshot with materialized rows, heir inheriting
    // member rows via members_snapshot_id, one loose file each.
    db.exec(`
      INSERT INTO users (player_uuid, player_name, created_at) VALUES ('player-owner', 'Owner', '2026-01-01T00:00:00.000Z');
      INSERT INTO worlds (id, slug, name, owner_uuid, created_at) VALUES ('world-1', 'w1', 'World', 'player-owner', '2026-01-01T00:00:00.000Z');
      INSERT INTO snapshots (id, world_id, created_at, created_by_uuid) VALUES ('snap-a', 'world-1', '2026-01-01T01:00:00.000Z', 'player-owner');
      INSERT INTO snapshots (id, world_id, created_at, created_by_uuid, base_snapshot_id) VALUES ('snap-b', 'world-1', '2026-01-01T02:00:00.000Z', 'player-owner', 'snap-a');
      INSERT INTO snapshot_files (snapshot_id, path, hash, size, compressed_size, pack_id, storage_key, content_type)
        VALUES ('snap-a', 'icon.png', 'h-icon', 10, 8, NULL, 'blobs/icon.bin', 'image/png');
      INSERT INTO snapshot_files (snapshot_id, path, hash, size, compressed_size, pack_id, storage_key, content_type)
        VALUES ('snap-a', 'level.dat', 'h-level', 25, 25, 'non-region', 'packs/full/one.pack', 'application/octet-stream');
      INSERT INTO snapshot_files (snapshot_id, path, hash, size, compressed_size, pack_id, storage_key, content_type)
        VALUES ('snap-a', 'session.lock', 'h-lock', 15, 15, 'non-region', 'packs/full/one.pack', 'application/octet-stream');
      INSERT INTO snapshot_packs (snapshot_id, pack_id, hash, size, storage_key, transfer_mode)
        VALUES ('snap-a', 'non-region', 'pack-hash-1', 40, 'packs/full/one.pack', 'pack-full');
      INSERT INTO snapshot_packs (snapshot_id, pack_id, hash, size, storage_key, transfer_mode, members_snapshot_id)
        VALUES ('snap-b', 'non-region', 'pack-hash-1', 40, 'packs/full/one.pack', 'pack-full', 'snap-a');
    `);

    db.exec(readFileSync(join(backendRoot, "migrations", migrationFiles.find((name) => name.startsWith("0026"))!), "utf8"));

    const snapA = db.query("SELECT packs_json, loose_file_count, loose_total_size FROM snapshots WHERE id = 'snap-a'").get() as Record<string, unknown>;
    expect(Number(snapA.loose_file_count)).toBe(1);
    expect(Number(snapA.loose_total_size)).toBe(10);
    const directoryA = JSON.parse(String(snapA.packs_json)) as Array<Record<string, unknown>>;
    expect(directoryA).toHaveLength(1);
    expect(directoryA[0]).toMatchObject({
      packId: "non-region",
      hash: "pack-hash-1",
      storageKey: "packs/full/one.pack",
      membersSnapshotId: null,
      memberCount: 2,
      memberTotalSize: 40
    });
    const directoryB = JSON.parse(String((db.query("SELECT packs_json FROM snapshots WHERE id = 'snap-b'").get() as Record<string, unknown>).packs_json)) as Array<Record<string, unknown>>;
    expect(directoryB[0]).toMatchObject({ membersSnapshotId: "snap-a", memberCount: 2, memberTotalSize: 40 });

    // The repository over the migrated DB serves both manifests intact.
    // Deploys apply every migration before the new worker serves, so the
    // remaining ones (0027+) land here too.
    for (const fileName of migrationFiles.filter((name) => name >= "0027")) {
      db.exec(readFileSync(join(backendRoot, "migrations", fileName), "utf8"));
    }
    const repository = new D1SharedWorldRepository(new SqliteD1Database(db));
    const manifestB = await repository.getSnapshot("world-1", "snap-b");
    expect(manifestB?.packs[0]?.files.map((file) => file.path)).toEqual(["level.dat", "session.lock"]);
    const summaries = await repository.listSnapshotSummaries("world-1");
    expect(summaries.find((entry) => entry.snapshotId === "snap-a")?.fileCount).toBe(3);
    expect(summaries.find((entry) => entry.snapshotId === "snap-b")?.fileCount).toBe(2);
    db.close(false);
  });

  test("a legacy snapshot without a directory stays fully readable and can donate", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Legacy SMP", "legacy-smp");
    const legacy = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()]), new Date("2026-01-01T00:00:00.000Z"));
    // Rewind the representation: pretend a pre-0026 worker wrote this row.
    repository.raw.exec(
      `UPDATE snapshots SET packs_json = NULL, loose_file_count = NULL, loose_total_size = NULL WHERE id = '${legacy.snapshotId}'`
    );
    repository.raw.exec(
      `INSERT INTO snapshot_packs (snapshot_id, pack_id, hash, size, storage_key, transfer_mode)
       VALUES ('${legacy.snapshotId}', 'non-region', 'pack-hash-1', 40, 'packs/full/one.pack', 'pack-full')`
    );

    const loaded = await repository.getSnapshot(world.id, legacy.snapshotId);
    expect(loaded?.packs[0]?.files).toHaveLength(2);
    const summaries = await repository.listSnapshotSummaries(world.id);
    expect(summaries[0]?.fileCount).toBe(2);

    // A new-representation heir inherits from the legacy donor…
    const heir = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()], legacy.snapshotId), new Date("2026-01-01T00:05:00.000Z"));
    const heirManifest = await repository.getSnapshot(world.id, heir.snapshotId);
    expect(heirManifest?.packs[0]?.files).toHaveLength(2);

    // …and deleting the legacy donor promotes its member rows to the heir.
    const deletion = await repository.deleteSnapshots(world.id, [legacy.snapshotId]);
    expect(deletion.deletedSnapshotIds).toEqual([legacy.snapshotId]);
    expect(deletion.unreferencedStorageKeys).toEqual([]);
    const promoted = await repository.getSnapshot(world.id, heir.snapshotId);
    expect(promoted?.packs[0]?.files.map((file) => file.path)).toEqual(["level.dat", "session.lock"]);
    repository.close();
  });

  test("deleting the last reference reports the pack blob as unreferenced across representations", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Cleanup SMP", "cleanup-smp");
    const only = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()]), new Date("2026-01-01T00:00:00.000Z"));

    expect(await repository.isStorageKeyReferenced("packs/full/one.pack")).toBe(true);
    const deletion = await repository.deleteSnapshots(world.id, [only.snapshotId]);
    expect(deletion.unreferencedStorageKeys).toEqual(["packs/full/one.pack"]);
    expect(await repository.isStorageKeyReferenced("packs/full/one.pack")).toBe(false);
    repository.close();
  });

  test("claimRetentionSlot is an hourly CAS", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(owner, "Throttle SMP", "throttle-smp");
    const hour = 60 * 60_000;

    expect(await repository.claimRetentionSlot(world.id, new Date("2026-01-01T10:00:00.000Z"), hour)).toBe(true);
    expect(await repository.claimRetentionSlot(world.id, new Date("2026-01-01T10:10:00.000Z"), hour)).toBe(false);
    expect(await repository.claimRetentionSlot(world.id, new Date("2026-01-01T11:00:01.000Z"), hour)).toBe(true);
    expect(await repository.claimRetentionSlot("world_missing", new Date("2026-01-01T12:00:00.000Z"), hour)).toBe(false);
    repository.close();
  });
});
