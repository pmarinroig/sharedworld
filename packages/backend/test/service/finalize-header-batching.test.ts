import { describe, expect, test } from "bun:test";

import type { SnapshotPack } from "../../../shared/src/index.ts";

import type { SharedWorldRepository } from "../../src/repository.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { createBlobSigner, createTestService } from "../support/service-fixtures.ts";

/**
 * Finalize validates, accounts (chainDeltaBytes) and stamps (chainSteps)
 * every delta pack against its base snapshot. Those three passes used to
 * each load base headers one snapshot at a time; now one batch primes a
 * cache shared by all three. These tests pin both the call shape and that
 * the batched result is byte-identical to the per-id path.
 */
const OWNER = { playerUuid: "player-owner", playerName: "Owner", clientVersion: "0.4.3" };

function member(path: string, hash: string) {
  return { path, hash, size: 10, contentType: "application/octet-stream" };
}

function fullPack(packId: string, hash: string): SnapshotPack {
  return { packId, hash, size: 100, storageKey: `packs/full/${hash}.pack`, transferMode: "pack-full", chainDepth: 0, files: [member(`${packId}.dat`, `${hash}-m`)] };
}

function deltaPack(packId: string, hash: string, base: SnapshotPack, baseSnapshotId: string): SnapshotPack {
  return {
    packId,
    hash,
    size: 100,
    storageKey: `packs/delta2/${hash}.bin`,
    transferMode: "pack-delta",
    baseSnapshotId,
    baseHash: base.hash,
    chainDepth: (base.chainDepth ?? 0) + 1,
    deltaFormatVersion: 2,
    deltaBlobSize: 40,
    files: [member(`${packId}.dat`, `${hash}-m`)]
  };
}

type Counts = { single: number; batch: number; batchIds: string[][] };

function countingRepository(repository: SharedWorldRepository, counts: Counts, disableBatch: boolean): SharedWorldRepository {
  return new Proxy(repository, {
    get(target, property, receiver) {
      if (property === "getSnapshotHeaders") {
        return async (worldId: string, snapshotId: string) => {
          counts.single += 1;
          return target.getSnapshotHeaders(worldId, snapshotId);
        };
      }
      if (property === "getSnapshotHeadersBatch") {
        return async (worldId: string, ids: readonly string[]) => {
          counts.batch += 1;
          counts.batchIds.push([...ids].sort());
          return disableBatch ? new Map() : target.getSnapshotHeadersBatch(worldId, ids);
        };
      }
      const value = Reflect.get(target, property, receiver) as unknown;
      return typeof value === "function" ? (value as (...args: unknown[]) => unknown).bind(target) : value;
    }
  });
}

async function runChain(disableBatch: boolean) {
  const counts: Counts = { single: 0, batch: 0, batchIds: [] };
  const base = createSqliteRepository();
  const repository = countingRepository(base, counts, disableBatch);
  const instance = createTestService(repository, createBlobSigner().signer, {});
  await repository.upsertUser({ playerUuid: OWNER.playerUuid, playerName: OWNER.playerName, createdAt: new Date().toISOString() });
  const world = await repository.createWorld(OWNER, "Delta World", "delta-world", { provider: "google-drive", storageAccountId: null });
  await instance.claimHost(OWNER, world.id, { joinTarget: "example.test:25565" }, new Date());
  const runtime = instance.realtimeLocal.runtimeRecord(world.id);
  const authority = { runtimeEpoch: runtime?.runtimeEpoch, hostToken: runtime?.runtimeToken };
  const finalize = async (packs: SnapshotPack[], baseSnapshotId: string | null, at: string) => {
    const manifest = await instance.finalizeSnapshot(OWNER, world.id, { ...authority, baseSnapshotId, files: [], packs } as never, new Date(at));
    return manifest.snapshotId;
  };

  const a1 = fullPack("a", "a1");
  const b1 = fullPack("b", "b1");
  const c1 = fullPack("c", "c1");
  const s1 = await finalize([a1, b1, c1], null, "2026-01-01T00:00:00.000Z");
  const a2 = deltaPack("a", "a2", a1, s1);
  const s2 = await finalize([a2, b1, c1], s1, "2026-01-01T00:05:00.000Z");
  const b3 = deltaPack("b", "b3", b1, s1);
  const a3 = deltaPack("a", "a3", a2, s2);
  const s3 = await finalize([a3, b3, c1], s2, "2026-01-01T00:10:00.000Z");

  // The measured shape: every pack is a delta and the bases span several
  // distinct snapshots.
  counts.single = 0;
  counts.batch = 0;
  counts.batchIds = [];
  const s4 = await finalize(
    [deltaPack("a", "a4", a3, s3), deltaPack("b", "b4", b3, s3), deltaPack("c", "c4", c1, s1)],
    s3,
    "2026-01-01T00:15:00.000Z"
  );
  const headers = await base.getSnapshotHeaders(world.id, s4);
  // Ids are random per run; rename them positionally so two runs compare.
  const idNames = new Map([[world.id, "world"], [s1, "s1"], [s2, "s2"], [s3, "s3"], [s4, "s4"]]);
  const normalized = JSON.parse(JSON.stringify(headers, (_key, value: unknown) =>
    (typeof value === "string" && idNames.has(value) ? idNames.get(value) : value))) as typeof headers;
  return { counts, s1, s3, headers: normalized };
}

describe("finalize base-header batching", () => {
  test("one batch primes every pass; no per-id header loads remain", async () => {
    const { counts, s1, s3 } = await runChain(false);
    expect(counts.batch).toBe(1);
    expect(counts.batchIds).toEqual([[s1, s3].sort()]);
    expect(counts.single).toBe(0);
  });

  test("the batched result is identical to the per-id path (recipes, chain accounting, depths)", async () => {
    const batched = await runChain(false);
    const perId = await runChain(true);
    expect(perId.counts.single).toBeGreaterThan(0);
    expect(batched.headers).toEqual(perId.headers);
    const packs = batched.headers?.packs ?? [];
    expect(packs.map((pack) => [pack.packId, pack.chainDepth, pack.chainDeltaBytes, pack.chainSteps?.length])).toEqual([
      ["a", 3, 120, 4],
      ["b", 2, 80, 3],
      ["c", 1, 40, 2]
    ]);
  });
});
