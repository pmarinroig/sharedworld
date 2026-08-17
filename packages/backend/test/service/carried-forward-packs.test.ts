import { describe, expect, test } from "bun:test";

import type { SnapshotPack } from "../../../shared/src/index.ts";

import { HttpError } from "../../src/http.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { createBlobSigner, createTestService } from "../support/service-fixtures.ts";

/**
 * Field incident 2026-08-17 (withettion, "Snapshot base ... was not found
 * for this world"): S1 made base snapshot rows deletable (recipes + GC legs
 * keep the bytes), but the upload plan echoes unchanged packs' headers —
 * base references included — and finalize demanded that the base ROW
 * exist. One deleted base and the world could never finalize again.
 *
 * Now: an unchanged pack is judged by the parent snapshot's already-
 * validated header (base row or not) and inherits its recipe; a pack whose
 * chain is truly unreconstructable (no recipe AND base gone) is planned as
 * a full re-upload so the next snapshot heals.
 */
const OWNER = { playerUuid: "player-owner", playerName: "Owner", clientVersion: "0.4.4" };
const PACK_ID = "non-region";

function localPack(hash: string, size = 1000) {
  return { packId: PACK_ID, hash, size, fileCount: 1, files: [{ path: "level.dat", hash: `${hash}-f`, size, contentType: "application/octet-stream" }] };
}

function pack(overrides: Record<string, unknown>): SnapshotPack {
  return {
    packId: PACK_ID,
    files: [{ path: "level.dat", hash: "member", size: 100, contentType: "application/octet-stream" }],
    ...overrides
  } as SnapshotPack;
}

async function fixture() {
  const repository = createSqliteRepository();
  const instance = createTestService(repository, createBlobSigner().signer, {});
  await repository.upsertUser({ ...OWNER, createdAt: new Date().toISOString() });
  const world = await repository.createWorld(OWNER, "Armut gezegeni", "armut", { provider: "google-drive", storageAccountId: null });
  await instance.claimHost(OWNER, world.id, { joinTarget: "example.test:25565" }, new Date());
  const runtime = instance.realtimeLocal.runtimeRecord(world.id);
  const authority = { runtimeEpoch: runtime?.runtimeEpoch, hostToken: runtime?.runtimeToken };
  const finalize = async (packs: SnapshotPack[], baseSnapshotId: string | null) => {
    const manifest = await instance.finalizeSnapshot(OWNER, world.id, { ...authority, baseSnapshotId, files: [], packs } as never, new Date());
    await Bun.sleep(2);
    return manifest.snapshotId;
  };
  return { repository, instance, worldId: world.id, authority, finalize };
}

describe("carried-forward packs over a deleted base snapshot", () => {
  test("finalize accepts the unchanged pack and inherits the parent's recipe and accounting", async () => {
    const { repository, instance, worldId, finalize } = await fixture();
    const full = pack({ hash: "full1", size: 1000, storageKey: "packs/full/fu/full1.pack", transferMode: "pack-full", chainDepth: 0 });
    const s1 = await finalize([full], null);
    const delta = pack({
      hash: "d1", size: 1000, storageKey: "packs/delta2/fu/full1-d1.bin", transferMode: "pack-delta",
      baseSnapshotId: s1, baseHash: "full1", chainDepth: 1, deltaFormatVersion: 2, deltaBlobSize: 150
    });
    const s2 = await finalize([delta], s1);
    // s3 carries the delta pack forward unchanged (this is what the plan's
    // alreadyPresent echo produces): same header, base still s1.
    const s3 = await finalize([delta], s2);
    const s3Headers = await repository.getSnapshotHeaders(worldId, s3);
    expect(s3Headers?.packs[0].chainSteps?.length).toBe(2);

    // The base row goes away (retention on a legacy chain, or a manual
    // backup delete — allowed for stamped referrers since S1).
    await repository.deleteSnapshots(worldId, [s1]);
    expect(await repository.getSnapshotHeaders(worldId, s1)).toBeNull();

    // Before the fix: 400 snapshot_base_not_found here.
    const s4 = await finalize([delta], s3);
    const s4Headers = await repository.getSnapshotHeaders(worldId, s4);
    expect(s4Headers?.packs[0].chainSteps).toEqual(s3Headers?.packs[0].chainSteps);
    expect(s4Headers?.packs[0].chainDeltaBytes).toBe(150);

    // And a guest can still plan a download of it purely from the recipe.
    const plan = await instance.downloadPlan(OWNER, worldId, { files: [], nonRegionPack: null, regionBundles: [] });
    expect(plan.nonRegionPackDownload?.steps.map((step) => step.storageKey)).toEqual([
      "packs/full/fu/full1.pack",
      "packs/delta2/fu/full1-d1.bin"
    ]);
  });

  test("a genuinely changed pack over a missing base is still validated against the base", async () => {
    const { repository, worldId, finalize } = await fixture();
    const s1 = await finalize([pack({ hash: "full1", size: 1000, storageKey: "packs/full/fu/full1.pack", transferMode: "pack-full", chainDepth: 0 })], null);
    await repository.deleteSnapshots(worldId, [s1]);
    let caught: unknown = null;
    try {
      // Not carried forward from any parent: a fresh delta claiming s1 as its base.
      await finalize([pack({
        hash: "d1", size: 1000, storageKey: "packs/delta2/fu/full1-d1.bin", transferMode: "pack-delta",
        baseSnapshotId: s1, baseHash: "full1", chainDepth: 1, deltaFormatVersion: 2, deltaBlobSize: 150
      })], null);
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).code).toBe("snapshot_base_not_found");
  });

  test("an unreconstructable pack (no recipe, base gone) is planned as a full re-upload", async () => {
    const { repository, instance, worldId, authority } = await fixture();
    // Repository-level write bypasses stamping: a legacy-shaped delta pack
    // whose base never existed here (mirrors a pre-S1 chain whose base was
    // pruned).
    await repository.finalizeSnapshot(worldId, OWNER, {
      baseSnapshotId: null,
      files: [],
      packs: [pack({
        hash: "d9", size: 1000, storageKey: "packs/delta/fu/full1-d9.bin", transferMode: "pack-delta",
        baseSnapshotId: "snapshot_gone", baseHash: "full1", chainDepth: 1
      })]
    } as never, new Date());
    await Bun.sleep(2);
    const latest = await repository.getLatestSnapshotHeaders(worldId);
    expect(latest?.packs[0].chainSteps ?? null).toBeNull();

    // The host's local pack is unchanged (same hash) — before the fix this
    // came back alreadyPresent with the dead base echoed.
    const plan = await instance.prepareUploads(OWNER, worldId, { files: [], nonRegionPack: localPack("d9"), regionBundles: [], ...authority });
    expect(plan.nonRegionPackUpload?.alreadyPresent).toBe(false);
    expect(plan.nonRegionPackUpload?.deltaStorageKey ?? null).toBeNull();
    expect(plan.nonRegionPackUpload?.fullStorageKey).toContain("packs/full/");
    expect(plan.nonRegionPackUpload?.fullUpload).toBeDefined();
  });

  test("a stamped pack over a missing base is NOT forced to re-upload", async () => {
    const { repository, instance, worldId, authority, finalize } = await fixture();
    const s1 = await finalize([pack({ hash: "full1", size: 1000, storageKey: "packs/full/fu/full1.pack", transferMode: "pack-full", chainDepth: 0 })], null);
    await finalize([pack({
      hash: "d1", size: 1000, storageKey: "packs/delta2/fu/full1-d1.bin", transferMode: "pack-delta",
      baseSnapshotId: s1, baseHash: "full1", chainDepth: 1, deltaFormatVersion: 2, deltaBlobSize: 150
    })], s1);
    await repository.deleteSnapshots(worldId, [s1]);
    const plan = await instance.prepareUploads(OWNER, worldId, { files: [], nonRegionPack: localPack("d1"), regionBundles: [], ...authority });
    expect(plan.nonRegionPackUpload?.alreadyPresent).toBe(true);
  });
});
