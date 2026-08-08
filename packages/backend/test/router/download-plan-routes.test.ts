import { describe, expect, test } from "bun:test";

import type { UploadPlanRequest } from "../../../shared/src/index.ts";

import { lifecycleRouter } from "../support/router.ts";

describe("download plan routes", () => {
  const emptyPlan = {
    worldId: "world-1",
    snapshotId: null,
    downloads: [],
    nonRegionPackDownload: null,
    regionBundleDownloads: [],
    retainedPaths: [],
    syncPolicy: {
      maxParallelDownloads: 1,
      maxConcurrentUploadPreparations: 1,
      maxConcurrentUploads: 1,
      maxUploadStartsPerSecond: 1,
      retryBaseDelayMs: 1,
      retryMaxDelayMs: 1,
      maxUploadBodyBytes: 95_000_000
    }
  };

  test("POST carries the local state in the body — headers overflow edge limits on many-file worlds", async () => {
    const seen: UploadPlanRequest[] = [];
    const router = lifecycleRouter({
      async downloadPlan(_ctx: { playerUuid: string }, _worldId: string, payload: UploadPlanRequest) {
        seen.push(payload);
        return emptyPlan;
      }
    });

    const body: UploadPlanRequest = {
      files: [{ path: "data/foo.dat", hash: "h1", size: 4, compressedSize: 4, deltaCapable: false }],
      nonRegionPack: null,
      regionBundles: [{ packId: "region-bundle:superpack:data", hash: "h2", size: 4, fileCount: 1, files: [] }]
    };
    const response = await router(new Request("http://127.0.0.1:8787/worlds/world-1/downloads/plan", {
      method: "POST",
      headers: { authorization: "Bearer session-token", "content-type": "application/json" },
      body: JSON.stringify(body)
    }));

    expect(response.status).toBe(200);
    expect(seen).toHaveLength(1);
    expect(seen[0].files[0]?.path).toBe("data/foo.dat");
    expect(seen[0].regionBundles?.[0]?.packId).toBe("region-bundle:superpack:data");
  });

  test("legacy GET with x-sharedworld-* headers keeps working for 0.3.0 clients", async () => {
    const seen: UploadPlanRequest[] = [];
    const router = lifecycleRouter({
      async downloadPlan(_ctx: { playerUuid: string }, _worldId: string, payload: UploadPlanRequest) {
        seen.push(payload);
        return emptyPlan;
      }
    });

    const response = await router(new Request("http://127.0.0.1:8787/worlds/world-1/downloads/plan", {
      method: "GET",
      headers: {
        authorization: "Bearer session-token",
        "x-sharedworld-files": JSON.stringify([{ path: "level.dat", hash: "h", size: 1, compressedSize: 1, deltaCapable: false }]),
        "x-sharedworld-pack": "null",
        "x-sharedworld-region-bundles": "[]"
      }
    }));

    expect(response.status).toBe(200);
    expect(seen).toHaveLength(1);
    expect(seen[0].files[0]?.path).toBe("level.dat");
    expect(seen[0].nonRegionPack ?? null).toBeNull();
  });
});
