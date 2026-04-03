import { describe, expect, test } from "bun:test";

import {
  buildUploadPlan,
  diffDownloads,
  manifestFileFromLocal,
  type LocalFileDescriptor,
  type SnapshotManifest
} from "../../src/index.ts";

describe("sync planning", () => {
  test("only changed files are uploaded", () => {
    const local: LocalFileDescriptor[] = [
      { path: "level.dat", hash: "aaa", size: 10, compressedSize: 6, deltaCapable: false },
      { path: "region/r.0.0.mca", hash: "bbb", size: 20, compressedSize: 12, deltaCapable: true }
    ];

    const manifest: SnapshotManifest = {
      worldId: "world-1",
      snapshotId: "snap-1",
      createdAt: new Date().toISOString(),
      createdByUuid: "player-1",
      files: [manifestFileFromLocal(local[0])],
      packs: []
    };

    const plan = buildUploadPlan("world-1", local, manifest);

    expect(plan.snapshotBaseId).toBe("snap-1");
    expect(plan.uploads[0].alreadyPresent).toBe(true);
    expect(plan.uploads[1].alreadyPresent).toBe(false);
    expect(plan.syncPolicy.maxConcurrentUploads).toBeGreaterThan(0);
  });

  test("download diff keeps unchanged files", () => {
    const local: LocalFileDescriptor[] = [
      { path: "level.dat", hash: "aaa", size: 10, compressedSize: 6, deltaCapable: false },
      { path: "stats.json", hash: "old", size: 4, compressedSize: 3, deltaCapable: false }
    ];

    const manifest: SnapshotManifest = {
      worldId: "world-1",
      snapshotId: "snap-2",
      createdAt: new Date().toISOString(),
      createdByUuid: "player-1",
      files: [
        manifestFileFromLocal(local[0]),
        { ...manifestFileFromLocal(local[1]), hash: "new", storageKey: "blobs/ne/new.bin" }
      ],
      packs: []
    };

    const plan = diffDownloads(local, manifest);

    expect(plan.retainedPaths).toEqual(["level.dat"]);
    expect(plan.downloads).toHaveLength(1);
    expect(plan.downloads[0].path).toBe("stats.json");
    expect(plan.downloads[0].steps).toHaveLength(1);
    expect(plan.syncPolicy.maxParallelDownloads).toBeGreaterThan(0);
  });
});
