import type { CreateStorageLinkRequest } from "../../../shared/src/index.ts";

import { HttpError, json, readJson } from "../http.ts";
import type { RouterService } from "./shared.ts";
import { renderStorageLinkPage, requireParam, RouteDefinition, UrlPattern } from "./shared.ts";

export function storageRoutes(
  service: Pick<RouterService, "cancelStorageLink" | "completeStorageLink" | "createStorageLink" | "getStorageLinkSession">
): RouteDefinition[] {
  return [
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/storage/link-sessions" }),
      auth: true,
      handler: async (request, _params, ctx) => json(await service.createStorageLink(ctx, await readJson<CreateStorageLinkRequest>(request)), { status: 201 })
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/storage/link-sessions/:sessionId/cancel" }),
      auth: true,
      handler: async (_request, params, ctx) => json(await service.cancelStorageLink(ctx, requireParam(params.sessionId, "sessionId")))
    },
    {
      method: "GET",
      pattern: new UrlPattern({ pathname: "/storage/link-sessions/:sessionId" }),
      auth: true,
      handler: async (_request, params, ctx) => json(await service.getStorageLinkSession(ctx, requireParam(params.sessionId, "sessionId")))
    },
    {
      method: "GET",
      pattern: new UrlPattern({ pathname: "/storage/google/callback" }),
      handler: async (request) => {
        const url = new URL(request.url);
        const sessionId = url.searchParams.get("sessionId") ?? url.searchParams.get("state")?.split(":")[0] ?? "";
        const state = url.searchParams.get("state");
        const code = url.searchParams.get("code");
        const mockEmail = url.searchParams.get("mockEmail");
        try {
          const session = await service.completeStorageLink(sessionId, { sessionId, code, state, mockEmail });
          return renderStorageLinkPage({
            status: 200,
            tone: "success",
            title: "Google Drive linked",
            message: "Return to Minecraft.",
            linkedAccountEmail: session.linkedAccountEmail ?? "linked account"
          });
        } catch (error) {
          const cancelled = error instanceof HttpError && error.code === "storage_link_cancelled";
          return renderStorageLinkPage({
            status: error instanceof HttpError ? error.status : 500,
            tone: "error",
            title: cancelled ? "Link no longer active" : "Link failed",
            message: cancelled
              ? "This Google Drive link was replaced or cancelled. Return to Minecraft and start again."
              : (error instanceof Error ? error.message : "Unexpected error."),
            linkedAccountEmail: null
          });
        }
      }
    }
  ];
}
