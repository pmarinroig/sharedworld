import { describe, expect, test } from "bun:test";

import type { SnapshotPack } from "../../../shared/src/index.ts";

import { HttpError } from "../../src/http.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { createBlobSigner, createTestService } from "../support/service-fixtures.ts";

const OWNER = { playerUuid: "player-owner", playerName: "Owner" };
const V2_OWNER = { ...OWNER, clientVersion: "0.4.0" };
const PACK_ID = "non-region";

function localPack(hash: string, size = 100) {
  return {
    packId: PACK_ID,
    hash,
    size,
    fileCount: 1,
    files: [{ path: "level.dat", hash: `${hash}-f`, size, contentType: "application/octet-stream" }]
  };
}

function finalizePack(overrides: Record<string, unknown>): SnapshotPack {
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
  const world = await repository.createWorld(OWNER, "Delta World", "delta-world");
  await instance.claimHost(OWNER, world.id, { joinTarget: "example.test:25565" }, new Date());
  const plan = await instance.prepareUploads(OWNER, world.id, {
    files: [], nonRegionPack: localPack("seed"), regionBundles: []
  });
  const headers = plan.nonRegionPackUpload?.fullUpload?.headers ?? {};
  const authority = {
    runtimeEpoch: Number(headers["x-sharedworld-runtime-epoch"] ?? "1"),
    hostToken: headers["x-sharedworld-host-token"] ?? ""
  };
  return { repository, instance, worldId: world.id, authority };
}

describe("delta v2 gating and size-aware re-full", () => {
  test("capable clients get delta2 slots; old clients keep legacy v1 slots", async () => {
    const { instance, worldId, authority } = await fixture();
    await instance.finalizeSnapshot(V2_OWNER, worldId, {
      ...authority,
      baseSnapshotId: null,
      files: [],
      packs: [finalizePack({ hash: "full1", size: 1000, storageKey: "packs/full/fu/full1.pack", transferMode: "pack-full", chainDepth: 0 })]
    });
    await Bun.sleep(2);

    const v2Plan = await instance.prepareUploads(V2_OWNER, worldId, {
      files: [], nonRegionPack: localPack("next", 1000), regionBundles: [], ...authority
    });
    expect(v2Plan.nonRegionPackUpload?.deltaStorageKey).toContain("packs/delta2/");
    expect(v2Plan.nonRegionPackUpload?.deltaFormatVersion).toBe(2);

    const legacyPlan = await instance.prepareUploads(OWNER, worldId, {
      files: [], nonRegionPack: localPack("next", 1000), regionBundles: [], ...authority
    });
    expect(legacyPlan.nonRegionPackUpload?.deltaStorageKey).toContain("packs/delta/");
    expect(legacyPlan.nonRegionPackUpload?.deltaFormatVersion ?? null).toBeNull();
  });

  test("finalize accumulates chain delta bytes and the budget forces a re-full", async () => {
    const { repository, instance, worldId, authority } = await fixture();
    await instance.finalizeSnapshot(V2_OWNER, worldId, {
      ...authority,
      baseSnapshotId: null,
      files: [],
      packs: [finalizePack({ hash: "full1", size: 1000, storageKey: "packs/full/fu/full1.pack", transferMode: "pack-full", chainDepth: 0 })]
    });
    await Bun.sleep(2);
    const base = await repository.getLatestSnapshot(worldId);
    await instance.finalizeSnapshot(V2_OWNER, worldId, {
      ...authority,
      baseSnapshotId: base!.snapshotId,
      files: [],
      packs: [finalizePack({
        hash: "d1", size: 1000, storageKey: "packs/delta2/fu/full1-d1.bin", transferMode: "pack-delta",
        baseSnapshotId: base!.snapshotId, baseHash: "full1", chainDepth: 1,
        deltaFormatVersion: 2, deltaBlobSize: 400
      })]
    });
    await Bun.sleep(2);
    const afterFirst = await repository.getLatestSnapshot(worldId);
    expect(afterFirst!.packs[0].chainDeltaBytes).toBe(400);

    // A second delta pushes the accumulator to 900 <= 1000: still offered.
    await instance.finalizeSnapshot(V2_OWNER, worldId, {
      ...authority,
      baseSnapshotId: afterFirst!.snapshotId,
      files: [],
      packs: [finalizePack({
        hash: "d2", size: 1000, storageKey: "packs/delta2/d1/d1-d2.bin", transferMode: "pack-delta",
        baseSnapshotId: afterFirst!.snapshotId, baseHash: "d1", chainDepth: 2,
        deltaFormatVersion: 2, deltaBlobSize: 500
      })]
    });
    await Bun.sleep(2);
    const under = await instance.prepareUploads(V2_OWNER, worldId, {
      files: [], nonRegionPack: localPack("d3", 1000), regionBundles: [], ...authority
    });
    expect(under.nonRegionPackUpload?.deltaStorageKey).toContain("delta2/");

    // A third delta blows the budget (1600 > 1000): the slot disappears.
    const secondLatest = await repository.getLatestSnapshot(worldId);
    await instance.finalizeSnapshot(V2_OWNER, worldId, {
      ...authority,
      baseSnapshotId: secondLatest!.snapshotId,
      files: [],
      packs: [finalizePack({
        hash: "d3", size: 1000, storageKey: "packs/delta2/d2/d2-d3.bin", transferMode: "pack-delta",
        baseSnapshotId: secondLatest!.snapshotId, baseHash: "d2", chainDepth: 3,
        deltaFormatVersion: 2, deltaBlobSize: 700
      })]
    });
    await Bun.sleep(2);
    const over = await instance.prepareUploads(V2_OWNER, worldId, {
      files: [], nonRegionPack: localPack("d4", 1000), regionBundles: [], ...authority
    });
    expect(over.nonRegionPackUpload?.deltaStorageKey ?? null).toBeNull();
    expect(over.nonRegionPackUpload?.fullStorageKey).toContain("packs/full/");
  });

  test("a legacy v1 delta chain (NULL accumulator) forces a capable client to full", async () => {
    const { repository, instance, worldId, authority } = await fixture();
    const snap1 = await repository.finalizeSnapshot(worldId, OWNER, {
      baseSnapshotId: null,
      files: [],
      packs: [finalizePack({ hash: "full1", size: 1000, storageKey: "packs/full/fu/full1.pack", transferMode: "pack-full", chainDepth: 0 })]
    } as never, new Date());
    await Bun.sleep(2);
    await repository.finalizeSnapshot(worldId, OWNER, {
      baseSnapshotId: snap1.snapshotId,
      files: [],
      packs: [finalizePack({
        hash: "v1d", size: 1000, storageKey: "packs/delta/fu/full1-v1d.bin", transferMode: "pack-delta",
        baseSnapshotId: snap1.snapshotId, baseHash: "full1", chainDepth: 1
      })]
    } as never, new Date());
    await Bun.sleep(2);

    const plan = await instance.prepareUploads(V2_OWNER, worldId, {
      files: [], nonRegionPack: localPack("next", 1000), regionBundles: [], ...authority
    });
    expect(plan.nonRegionPackUpload?.deltaStorageKey ?? null).toBeNull();
  });

  test("an old client meeting a v2 step gets an explicit client_update_required", async () => {
    const { repository, instance, worldId, authority } = await fixture();
    await instance.finalizeSnapshot(V2_OWNER, worldId, {
      ...authority,
      baseSnapshotId: null,
      files: [],
      packs: [finalizePack({ hash: "full1", size: 1000, storageKey: "packs/full/fu/full1.pack", transferMode: "pack-full", chainDepth: 0 })]
    });
    await Bun.sleep(2);
    const base = await repository.getLatestSnapshot(worldId);
    await instance.finalizeSnapshot(V2_OWNER, worldId, {
      ...authority,
      baseSnapshotId: base!.snapshotId,
      files: [],
      packs: [finalizePack({
        hash: "d1", size: 1000, storageKey: "packs/delta2/fu/full1-d1.bin", transferMode: "pack-delta",
        baseSnapshotId: base!.snapshotId, baseHash: "full1", chainDepth: 1,
        deltaFormatVersion: 2, deltaBlobSize: 400,
        files: [{ path: "level.dat", hash: "member-2", size: 100, contentType: "application/octet-stream" }]
      })]
    });

    let caught: unknown = null;
    try {
      await instance.downloadPlan(OWNER, worldId, { files: [], nonRegionPack: null, regionBundles: [] });
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).status).toBe(409);
    expect((caught as HttpError).code).toBe("client_update_required");

    const capable = await instance.downloadPlan(V2_OWNER, worldId, { files: [], nonRegionPack: null, regionBundles: [] });
    const steps = capable.nonRegionPackDownload?.steps ?? [];
    expect(steps.map((step) => step.deltaFormatVersion ?? null)).toEqual([null, 2]);
  });
});
