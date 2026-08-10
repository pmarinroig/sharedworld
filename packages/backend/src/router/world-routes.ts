import type { CreateWorldRequest, RedeemInviteRequest, UpdateMemberPermissionsRequest, UpdateWorldRequest, UpdateWorldSettingsRequest } from "../../../shared/src/index.ts";

import { json, ok, readJson } from "../http.ts";
import type { RouterService } from "./shared.ts";
import { requireParam, RouteDefinition, UrlPattern } from "./shared.ts";

function ifNoneMatchSatisfied(request: Request, etag: string): boolean {
  const header = request.headers.get("if-none-match");
  if (header == null) {
    return false;
  }
  return header.split(",").map((value) => value.trim()).some((value) => value === etag || value === "*");
}

function notModified(etag: string): Response {
  return new Response(null, { status: 304, headers: { etag } });
}

export function worldRoutes(
  service: Pick<
    RouterService,
    | "createInvite"
    | "createWorld"
    | "deleteWorld"
    | "getStorageUsage"
    | "getWorld"
    | "kickMember"
    | "listWorlds"
    | "redeemInvite"
    | "resetInvite"
    | "setMemberCommandPermission"
    | "updateWorld"
    | "updateWorldSettings"
    | "worldsEtag"
    | "worldEtag"
  >
): RouteDefinition[] {
  return [
    {
      // Conditional GET: a matching If-None-Match answers 304 from the cheap
      // change-facts queries without ever building the summary list. Old
      // clients never send the header and see byte-identical behavior.
      method: "GET",
      pattern: new UrlPattern({ pathname: "/worlds" }),
      auth: true,
      handler: async (request, _params, ctx) => {
        const etag = await service.worldsEtag(ctx);
        if (ifNoneMatchSatisfied(request, etag)) {
          return notModified(etag);
        }
        return json(await service.listWorlds(ctx), { headers: { etag } });
      }
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds" }),
      auth: true,
      handler: async (request, _params, ctx) => json(await service.createWorld(ctx, await readJson<CreateWorldRequest>(request)), { status: 201 })
    },
    {
      method: "GET",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId" }),
      auth: true,
      handler: async (request, params, ctx) => {
        const worldId = requireParam(params.worldId, "worldId");
        // A null etag means no access — fall through so the service raises
        // its fresh 403/404 instead of a misleading 304.
        const etag = await service.worldEtag(ctx, worldId);
        if (etag != null && ifNoneMatchSatisfied(request, etag)) {
          return notModified(etag);
        }
        return json(await service.getWorld(ctx, worldId), etag == null ? undefined : { headers: { etag } });
      }
    },
    {
      // On-demand storage usage for 0.4.1+ clients (edit screen only) —
      // world details no longer carry it inline for them.
      method: "GET",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/storage-usage" }),
      auth: true,
      handler: async (_request, params, ctx) => json(await service.getStorageUsage(ctx, requireParam(params.worldId, "worldId")))
    },
    {
      method: "PATCH",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId" }),
      auth: true,
      handler: async (request, params, ctx) =>
        json(await service.updateWorld(ctx, requireParam(params.worldId, "worldId"), await readJson<UpdateWorldRequest>(request)))
    },
    {
      method: "PUT",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/settings" }),
      auth: true,
      handler: async (request, params, ctx) =>
        json(await service.updateWorldSettings(ctx, requireParam(params.worldId, "worldId"), await readJson<UpdateWorldSettingsRequest>(request)))
    },
    {
      method: "DELETE",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId" }),
      auth: true,
      handler: async (_request, params, ctx) => {
        await service.deleteWorld(ctx, requireParam(params.worldId, "worldId"));
        return ok();
      }
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/invites" }),
      auth: true,
      handler: async (_request, params, ctx) => json(await service.createInvite(ctx, requireParam(params.worldId, "worldId")), { status: 201 })
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/invites/reset" }),
      auth: true,
      handler: async (_request, params, ctx) => json(await service.resetInvite(ctx, requireParam(params.worldId, "worldId")))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/invites/redeem" }),
      auth: true,
      handler: async (request, _params, ctx) => json(await service.redeemInvite(ctx, await readJson<RedeemInviteRequest>(request)))
    },
    {
      method: "DELETE",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/members/:playerUuid" }),
      auth: true,
      handler: async (_request, params, ctx) => json(await service.kickMember(ctx, requireParam(params.worldId, "worldId"), requireParam(params.playerUuid, "playerUuid")))
    },
    {
      method: "PATCH",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/members/:playerUuid" }),
      auth: true,
      handler: async (request, params, ctx) =>
        json(await service.setMemberCommandPermission(
          ctx,
          requireParam(params.worldId, "worldId"),
          requireParam(params.playerUuid, "playerUuid"),
          await readJson<UpdateMemberPermissionsRequest>(request)
        ))
    }
  ];
}
