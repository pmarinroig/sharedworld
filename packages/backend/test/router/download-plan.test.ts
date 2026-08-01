import { describe, expect, test } from "bun:test";

import { parseDownloadPlanRequest } from "../../src/router/shared.ts";

/**
 * parseDownloadPlanRequest had no coverage at all; this pins its contract
 * before the Phase 1 fix. Tests marked CURRENT-BUG document behavior scheduled
 * to change.
 */
describe("parseDownloadPlanRequest", () => {
  function request(headers: Record<string, string>): Request {
    return new Request("https://backend.example/worlds/w1/download-plan", { headers });
  }

  test("no headers yields an empty plan request", async () => {
    const parsed = await parseDownloadPlanRequest(request({}));
    expect(parsed).toEqual({ files: [], nonRegionPack: null, regionBundles: [] });
  });

  test("valid files, pack, and region-bundle headers parse", async () => {
    const files = [{ path: "level.dat", hash: "h1", size: 10, compressedSize: 8, contentType: "application/octet-stream", deltaCapable: false }];
    const pack = { packId: "pack", hash: "h2", size: 20, fileCount: 1, files: [{ path: "level.dat", hash: "h1", size: 10, contentType: "application/octet-stream" }] };
    const bundles = [{ packId: "region:r.0.0", hash: "h3", size: 30, fileCount: 1, files: [{ path: "region/r.0.0.mca", hash: "h4", size: 30, contentType: "application/octet-stream" }] }];
    const parsed = await parseDownloadPlanRequest(request({
      "x-sharedworld-files": JSON.stringify(files),
      "x-sharedworld-pack": JSON.stringify(pack),
      "x-sharedworld-region-bundles": JSON.stringify(bundles)
    }));
    expect(parsed.files).toEqual(files);
    expect(parsed.nonRegionPack).toEqual(pack);
    expect(parsed.regionBundles).toEqual(bundles);
  });

  test("a malformed files header is a 400 invalid_download_plan_header", async () => {
    expect(parseDownloadPlanRequest(request({ "x-sharedworld-files": "{not json" })))
      .rejects.toMatchObject({ status: 400, code: "invalid_download_plan_header" });
  });

  test("a malformed pack header alongside files is a 400", async () => {
    expect(parseDownloadPlanRequest(request({
      "x-sharedworld-files": "[]",
      "x-sharedworld-pack": "{not json"
    }))).rejects.toMatchObject({ status: 400, code: "invalid_download_plan_header" });
  });

  test("[S1 fixed] a malformed pack header with no files header is a 400 invalid_download_plan_header", async () => {
    expect(parseDownloadPlanRequest(request({ "x-sharedworld-pack": "{not json" })))
      .rejects.toMatchObject({ status: 400, code: "invalid_download_plan_header" });
  });

  test("[S1 fixed] a malformed region-bundles header with no files header is a 400 invalid_download_plan_header", async () => {
    expect(parseDownloadPlanRequest(request({ "x-sharedworld-region-bundles": "{not json" })))
      .rejects.toMatchObject({ status: 400, code: "invalid_download_plan_header" });
  });
});
