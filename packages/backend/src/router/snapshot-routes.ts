import type { FinalizeSnapshotRequest, UploadPlanRequest } from "../../../shared/src/index.ts";

import { json, ok, readJson } from "../http.ts";
import type { RouterService } from "./shared.ts";
import { decodeStorageKey, parseDownloadPlanRequest, requireParam, RouteDefinition, UrlPattern } from "./shared.ts";

export function snapshotRoutes(
  service: Pick<
    RouterService,
    | "deleteSnapshot"
    | "downloadPlan"
    | "downloadStorageBlob"
    | "finalizeSnapshot"
    | "latestManifest"
    | "listSnapshots"
    | "prepareUploads"
    | "restoreSnapshot"
    | "uploadStorageBlob"
  >
): RouteDefinition[] {
  return [
    {
      method: "GET",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/snapshots/latest-manifest" }),
      auth: true,
      handler: async (_request, params, ctx) => json(await service.latestManifest(ctx, requireParam(params.worldId, "worldId")))
    },
    {
      method: "GET",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/snapshots" }),
      auth: true,
      handler: async (_request, params, ctx) => json(await service.listSnapshots(ctx, requireParam(params.worldId, "worldId")))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/snapshots/:snapshotId/restore" }),
      auth: true,
      handler: async (_request, params, ctx) => json(await service.restoreSnapshot(ctx, requireParam(params.worldId, "worldId"), requireParam(params.snapshotId, "snapshotId")))
    },
    {
      method: "DELETE",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/snapshots/:snapshotId" }),
      auth: true,
      handler: async (_request, params, ctx) => json(await service.deleteSnapshot(ctx, requireParam(params.worldId, "worldId"), requireParam(params.snapshotId, "snapshotId")))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/uploads/prepare" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.prepareUploads(ctx, requireParam(params.worldId, "worldId"), await readJson<UploadPlanRequest>(request)))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/uploads/finalize-snapshot" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.finalizeSnapshot(ctx, requireParam(params.worldId, "worldId"), await readJson<FinalizeSnapshotRequest>(request)))
    },
    {
      method: "GET",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/downloads/plan" }),
      auth: true,
      handler: async (request, params, ctx) => {
        const payload = await parseDownloadPlanRequest(request);
        return json(await service.downloadPlan(ctx, requireParam(params.worldId, "worldId"), payload));
      }
    },
    {
      method: "PUT",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/storage/blob/:storageKey*" }),
      auth: true,
      handler: async (request, params, ctx) => {
        await service.uploadStorageBlob(ctx, requireParam(params.worldId, "worldId"), decodeStorageKey(requireParam(params.storageKey, "storageKey")), request);
        return ok();
      }
    },
    {
      method: "GET",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/storage/blob/:storageKey*" }),
      auth: true,
      handler: async (_request, params, ctx) => service.downloadStorageBlob(ctx, requireParam(params.worldId, "worldId"), decodeStorageKey(requireParam(params.storageKey, "storageKey")))
    }
  ];
}
