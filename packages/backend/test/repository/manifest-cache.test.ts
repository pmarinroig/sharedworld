import { describe, expect, test } from "bun:test";

import type { FinalizeSnapshotRequest, SnapshotManifest, SnapshotPack } from "../../../shared/src/index.ts";

import type { SnapshotManifestCache } from "../../src/manifest-cache.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * The manifest cache exists so the polling paths (guest cache warmers, plan
 * builds) stop re-reading thousands of snapshot_files rows for content that
 * is immutable per snapshot id. These tests pin the contract: hits skip the
 * row loads entirely, finalize pre-populates, and a deleted snapshot can
 * never be served from a stale entry.
 */
describe("D1 repository manifest cache", () => {
  const owner = { playerUuid: "player-owner", playerName: "Owner" };

  function recordingCache(): SnapshotManifestCache & { matches: string[]; puts: string[] } {
    const entries = new Map<string, SnapshotManifest>();
    const matches: string[] = [];
    const puts: string[] = [];
    return {
      matches,
      puts,
      async match(worldId, snapshotId) {
        matches.push(snapshotId);
        return entries.get(`${worldId}/${snapshotId}`) ?? null;
      },
      async put(worldId, snapshotId, manifest) {
        puts.push(snapshotId);
        entries.set(`${worldId}/${snapshotId}`, manifest);
      }
    };
  }

  function pack(): SnapshotPack {
    return {
      packId: "non-region",
      hash: "pack-hash-1",
      size: 40,
      storageKey: "packs/full/one.pack",
      transferMode: "pack-full",
      files: [
        { path: "level.dat", hash: "hash-level", size: 25, contentType: "application/octet-stream" },
        { path: "icon.png", hash: "hash-icon", size: 15, contentType: "application/octet-stream" }
      ]
    };
  }

  function finalizeRequest(packs: SnapshotPack[], baseSnapshotId: string | null = null): FinalizeSnapshotRequest {
    return { files: [], packs, baseSnapshotId };
  }

  test("finalize populates the cache and later loads are served from it", async () => {
    const cache = recordingCache();
    const repository = createSqliteRepository(":memory:", cache);
    const world = await repository.createWorld(owner, "Cache SMP", "cache-smp");
    const manifest = await repository.finalizeSnapshot(
      world.id,
      owner,
      finalizeRequest([pack()]),
      new Date("2026-01-01T00:00:00.000Z")
    );
    expect(cache.puts).toEqual([manifest.snapshotId]);

    // Physically remove the file rows: if the next reads still return the
    // full manifest, they provably came from the cache, not from D1.
    repository.raw.exec("DELETE FROM snapshot_files");
    const viaLatest = await repository.getLatestSnapshot(world.id);
    const viaId = await repository.getSnapshot(world.id, manifest.snapshotId);
    expect(viaLatest?.packs[0]?.files.map((file) => file.path)).toEqual(["icon.png", "level.dat"]);
    expect(viaId?.packs[0]?.files.length).toBe(2);
    // No re-store on hits.
    expect(cache.puts).toEqual([manifest.snapshotId]);
    repository.close();
  });

  test("an evicted entry falls back to D1 and is re-stored", async () => {
    const cache = recordingCache();
    const repository = createSqliteRepository(":memory:", cache);
    const world = await repository.createWorld(owner, "Miss SMP", "miss-smp");
    const manifest = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()]), new Date("2026-01-01T00:00:00.000Z"));
    cache.puts.length = 0;
    // Evict: colo caches drop entries whenever they like.
    cache.match = async () => null;
    const loaded = await repository.getLatestSnapshot(world.id);
    expect(loaded?.snapshotId).toBe(manifest.snapshotId);
    expect(loaded?.packs[0]?.files.length).toBe(2);
    expect(cache.puts).toEqual([manifest.snapshotId]);
    repository.close();
  });

  test("a deleted snapshot returns null without consulting the cache", async () => {
    const cache = recordingCache();
    const repository = createSqliteRepository(":memory:", cache);
    const world = await repository.createWorld(owner, "Deleted SMP", "deleted-smp");
    const first = await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()]), new Date("2026-01-01T00:00:00.000Z"));
    await repository.finalizeSnapshot(world.id, owner, finalizeRequest([pack()], first.snapshotId), new Date("2026-01-01T00:05:00.000Z"));
    await repository.deleteSnapshots(world.id, [first.snapshotId]);

    cache.matches.length = 0;
    expect(await repository.getSnapshot(world.id, first.snapshotId)).toBeNull();
    expect(cache.matches).toEqual([]);
    repository.close();
  });
});
