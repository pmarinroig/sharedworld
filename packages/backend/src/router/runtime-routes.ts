import type {
  AbandonFinalizationRequest,
  BeginFinalizationRequest,
  CancelWaitingRequest,
  CompleteFinalizationRequest,
  EnterSessionRequest,
  HeartbeatRequest,
  HostStartupProgressRequest,
  ObserveWaitingRequest,
  PresenceHeartbeatRequest,
  RefreshWaitingRequest,
  ReleaseHostRequest
} from "../../../shared/src/index.ts";

import { json, readJson } from "../http.ts";
import type { RouterService } from "./shared.ts";
import { requireParam, RouteDefinition, UrlPattern } from "./shared.ts";

export function runtimeRoutes(
  service: Pick<
    RouterService,
    | "abandonFinalization"
    | "beginFinalization"
    | "cancelWaiting"
    | "completeFinalization"
    | "enterSession"
    | "heartbeatHost"
    | "observeWaiting"
    | "refreshWaiting"
    | "releaseHost"
    | "runtimeStatus"
    | "setHostStartupProgress"
    | "setPlayerPresence"
  >
): RouteDefinition[] {
  return [
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/session/enter" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.enterSession(ctx, requireParam(params.worldId, "worldId"), await readJson<EnterSessionRequest>(request), new Date()))
    },
    {
      method: "GET",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/runtime" }),
      auth: true,
      handler: async (_request, params, ctx) => json(await service.runtimeStatus(ctx, requireParam(params.worldId, "worldId"), new Date()))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/session/waiting/refresh" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.refreshWaiting(ctx, requireParam(params.worldId, "worldId"), await readJson<RefreshWaitingRequest>(request), new Date()))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/session/waiting/observe" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.observeWaiting(ctx, requireParam(params.worldId, "worldId"), await readJson<ObserveWaitingRequest>(request), new Date()))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/session/waiting/cancel" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.cancelWaiting(ctx, requireParam(params.worldId, "worldId"), await readJson<CancelWaitingRequest>(request), new Date()))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/heartbeat" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.heartbeatHost(ctx, requireParam(params.worldId, "worldId"), await readJson<HeartbeatRequest>(request)))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/host-startup-progress" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.setHostStartupProgress(ctx, requireParam(params.worldId, "worldId"), await readJson<HostStartupProgressRequest>(request)))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/presence" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.setPlayerPresence(ctx, requireParam(params.worldId, "worldId"), await readJson<PresenceHeartbeatRequest>(request)))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/begin-finalization" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.beginFinalization(ctx, requireParam(params.worldId, "worldId"), await readJson<BeginFinalizationRequest>(request)))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/complete-finalization" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.completeFinalization(ctx, requireParam(params.worldId, "worldId"), await readJson<CompleteFinalizationRequest>(request)))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/abandon-finalization" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.abandonFinalization(ctx, requireParam(params.worldId, "worldId"), await readJson<AbandonFinalizationRequest>(request)))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/worlds/:worldId/release-host" }),
      auth: true,
      handler: async (request, params, ctx) => json(await service.releaseHost(ctx, requireParam(params.worldId, "worldId"), await readJson<ReleaseHostRequest>(request)))
    }
  ];
}
