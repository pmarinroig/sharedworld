import { describe, expect, test } from "bun:test";

import {
  diffDownloads,
  groupTerrainRegionMembers,
  isBundledIntoSuperpack,
  NON_REGION_PACK_ID,
  PACK_DELTA_TRANSFER_MODE,
  snapshotPackFromLocal,
  storageKeyForPackDelta,
  type LocalFileDescriptor,
  type LocalPackDescriptor,
  type SnapshotManifest
} from "../../src/index.ts";

describe("sync packs", () => {
  test("pack diff returns one non-region pack download", () => {
    const local: LocalFileDescriptor[] = [
      { path: "level.dat", hash: "old-level", size: 10, compressedSize: 6, deltaCapable: false },
      { path: "data/foo.dat", hash: "old-foo", size: 5, compressedSize: 4, deltaCapable: false }
    ];
    const pack: LocalPackDescriptor = {
      packId: NON_REGION_PACK_ID,
      hash: "pack-new",
      size: 64,
      fileCount: 2,
      files: [
        { path: "level.dat", hash: "new-level", size: 10, contentType: "application/octet-stream" },
        { path: "data/foo.dat", hash: "new-foo", size: 5, contentType: "application/octet-stream" }
      ]
    };
    const manifest: SnapshotManifest = {
      worldId: "world-1",
      snapshotId: "snap-3",
      createdAt: new Date().toISOString(),
      createdByUuid: "player-1",
      files: [],
      packs: [
        {
          ...snapshotPackFromLocal(pack),
          transferMode: PACK_DELTA_TRANSFER_MODE,
          storageKey: storageKeyForPackDelta("pack-old", pack.hash),
          baseSnapshotId: "snap-2",
          baseHash: "pack-old",
          chainDepth: 2
        }
      ]
    };

    const plan = diffDownloads(local, manifest);

    expect(plan.downloads).toHaveLength(0);
    expect(plan.nonRegionPackDownload?.packId).toBe(NON_REGION_PACK_ID);
    expect(plan.nonRegionPackDownload?.files).toHaveLength(2);
  });

  test("superpack classification keeps non-region mca files bundled and groups terrain regions in 2x2 tiles", () => {
    expect(isBundledIntoSuperpack("entities/r.0.0.mca")).toBe(true);
    expect(isBundledIntoSuperpack("poi/r.0.0.mca")).toBe(true);
    expect(isBundledIntoSuperpack("region/r.0.0.mca")).toBe(false);
    expect(isBundledIntoSuperpack("DIM-1/region/r.3.2.mca")).toBe(false);

    const groups = groupTerrainRegionMembers([
      { path: "region/r.-1.-1.mca", size: 6_000_000 },
      { path: "region/r.-1.0.mca", size: 6_000_000 },
      { path: "region/r.0.-1.mca", size: 6_000_000 },
      { path: "region/r.0.0.mca", size: 6_000_000 },
      { path: "DIM-1/region/r.2.2.mca", size: 6_000_000 }
    ]);

    expect(groups).toEqual([
      { bundleId: "region-bundle:DIM-1/region:1:1", paths: ["DIM-1/region/r.2.2.mca"] },
      {
        bundleId: "region-bundle:region:-1:-1",
        paths: ["region/r.-1.-1.mca", "region/r.-1.0.mca", "region/r.0.-1.mca", "region/r.0.0.mca"]
      }
    ]);
  });
});
