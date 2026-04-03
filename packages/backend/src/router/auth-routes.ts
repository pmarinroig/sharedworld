import type { AuthCompleteRequest, DevAuthCompleteRequest } from "../../../shared/src/index.ts";

import { json, readJson } from "../http.ts";
import type { RouterService } from "./shared.ts";
import { RouteDefinition, UrlPattern } from "./shared.ts";

export function authRoutes(
  service: Pick<RouterService, "completeAuth" | "completeDevAuth" | "createChallenge">
): RouteDefinition[] {
  return [
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/auth/challenge" }),
      handler: async () => json(await service.createChallenge())
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/auth/complete" }),
      handler: async (request) => json(await service.completeAuth(await readJson<AuthCompleteRequest>(request)))
    },
    {
      method: "POST",
      pattern: new UrlPattern({ pathname: "/auth/dev-complete" }),
      handler: async (request) => json(await service.completeDevAuth(await readJson<DevAuthCompleteRequest>(request)))
    }
  ];
}
