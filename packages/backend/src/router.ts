import { errorResponse, HttpError } from "./http.ts";
import type { RequestContext } from "./repository.ts";
import { authRoutes } from "./router/auth-routes.ts";
import { runtimeRoutes } from "./router/runtime-routes.ts";
import { snapshotRoutes } from "./router/snapshot-routes.ts";
import { authenticate, decodeStorageKey, type RouteDefinition, type RouterService } from "./router/shared.ts";
import { storageRoutes } from "./router/storage-routes.ts";
import { worldRoutes } from "./router/world-routes.ts";

export function createRouter(service: RouterService) {
  const routes: RouteDefinition[] = [
    ...authRoutes(service),
    ...storageRoutes(service),
    ...worldRoutes(service),
    ...runtimeRoutes(service),
    ...snapshotRoutes(service)
  ];

  return async function route(request: Request): Promise<Response> {
    try {
      for (const route of routes) {
        const match = route.pattern.exec(request.url);
        if (!match) {
          continue;
        }
        if (route.method !== request.method) {
          continue;
        }
        const ctx: RequestContext = route.auth
          ? await authenticate(request, service)
          : { playerUuid: "", playerName: "" };
        return await route.handler(request, match.pathname.groups, ctx);
      }
      throw new HttpError(404, "not_found", "Route not found.");
    } catch (error) {
      return errorResponse(error);
    }
  };
}

export { decodeStorageKey };
export type { RouterService };
