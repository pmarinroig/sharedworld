import type { CreateWorldRequest, RedeemInviteRequest, UpdateWorldRequest } from "../../../shared/src/index.ts";

import { json, ok, readJson } from "../http.ts";
import type { RouterService } from "./shared.ts";
import { requireParam, RouteDefinition, UrlPattern } from "./shared.ts";

export function worldRoutes(
  service: Pick<
    RouterService,
    | "createInvite"
    | "createWorld"
    | "deleteWorld"
    | "getWorld"
    | "kickMember"
    | "listWorlds"
    | "redeemInvite"
    | "resetInvite"
    | "updateWorld"
  >
): RouteDefinition[] {
  return [
    {
      method: "GET",
      pattern: new UrlPattern({ pathname: "/worlds" }),
      auth: true,
      handler: async (_request, _params, ctx) => json(await service.listWorlds(ctx))
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
      handler: async (_request, params, ctx) => json(await service.getWorld(ctx, requireParam(params.worldId, "worldId")))
    },
    {
      method: "PATCH",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId" }),
      auth: true,
      handler: async (request, params, ctx) =>
        json(await service.updateWorld(ctx, requireParam(params.worldId, "worldId"), await readJson<UpdateWorldRequest>(request)))
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
    }
  ];
}
