import { describe, expect, test } from "bun:test";

import { authRoutes } from "../../src/router/auth-routes.ts";
import { runtimeRoutes } from "../../src/router/runtime-routes.ts";
import { snapshotRoutes } from "../../src/router/snapshot-routes.ts";
import type { RouteDefinition, RouterService } from "../../src/router/shared.ts";
import { storageRoutes } from "../../src/router/storage-routes.ts";
import { worldRoutes } from "../../src/router/world-routes.ts";

import { readJavaSource } from "./support.ts";

/**
 * Every route the Java SharedWorldApiClient calls must exist in the backend's
 * route table with the same method. The mod side is extracted from source (the
 * same technique SharedWorldLocalizationParityTest uses for lang keys); the
 * backend side is the real RouteDefinition list, matched by executing each
 * route's UrlPattern against a concretized path.
 */

// The handlers are never invoked — only method + pattern are read — so a
// throwing Proxy stands in for the whole service.
const stubService = new Proxy({}, {
  get: () => () => {
    throw new Error("route handler must not run in the parity test");
  }
}) as RouterService;

const backendRoutes: RouteDefinition[] = [
  ...authRoutes(stubService),
  ...storageRoutes(stubService),
  ...worldRoutes(stubService),
  ...runtimeRoutes(stubService),
  ...snapshotRoutes(stubService)
];

type ModRoute = {
  method: string;
  template: string;
};

/**
 * Routes the mod calls that the backend is known not to register. Each entry
 * is a defect or intentional gap; an entry that stops failing must be removed.
 */
const KNOWN_MISSING_ROUTES: ModRoute[] = [
  // KNOWN-DEFECT(storage-usage-404): SharedWorldApiClient.getStorageUsage
  // calls this, but no route serves it — the service method exists and is not
  // wired into any router module, so the call would 404. No production caller
  // exists today; either wire the route or delete the client method.
  { method: "GET", template: "/worlds/:worldId/storage/usage" }
];

function extractModRoutes(source: string): ModRoute[] {
  const routes: ModRoute[] = [];
  const requestCall = /request\(\s*"(GET|POST|PATCH|DELETE|PUT)",\s*([^,]+),/g;
  for (const match of source.matchAll(requestCall)) {
    routes.push({ method: match[1], template: javaPathExpressionToTemplate(match[2]) });
  }
  return routes;
}

function javaPathExpressionToTemplate(expression: string): string {
  let paramIndex = 0;
  return expression
    .split("+")
    .map((part) => part.trim())
    .map((part) => {
      if (part.startsWith('"')) {
        return part.slice(1, -1);
      }
      paramIndex += 1;
      return `:param${paramIndex}`;
    })
    .join("");
}

function concretize(template: string): string {
  return template.replace(/:[A-Za-z0-9]+/g, "pv");
}

function backendMatches(method: string, template: string): boolean {
  const url = `https://backend.example${concretize(template)}`;
  return backendRoutes.some((route) => route.method === method && route.pattern.exec(url) !== null);
}

describe("mod-to-backend route parity", () => {
  const apiClientSource = readJavaSource("api/SharedWorldApiClient.java");
  const modRoutes = extractModRoutes(apiClientSource);

  // downloadPlan builds its request by hand instead of going through
  // request(), so it is asserted and appended separately.
  test("the hand-built downloadPlan request still targets /downloads/plan", () => {
    expect(apiClientSource).toContain('"/worlds/" + worldId + "/downloads/plan"');
  });
  modRoutes.push({ method: "GET", template: "/worlds/:worldId/downloads/plan" });

  test("the extraction regex still finds the client's request() calls", () => {
    // 30+ call sites exist today; a large drop means the regex rotted, not
    // that the client shrank.
    expect(modRoutes.length).toBeGreaterThanOrEqual(25);
  });

  test("every route the mod calls is served by the backend router", () => {
    const missing = modRoutes.filter((modRoute) => {
      const known = KNOWN_MISSING_ROUTES.some(
        (entry) => entry.method === modRoute.method && concretize(entry.template) === concretize(modRoute.template)
      );
      return !known && !backendMatches(modRoute.method, modRoute.template);
    });
    expect(missing).toEqual([]);
  });

  test("every known-missing route is still actually missing", () => {
    for (const entry of KNOWN_MISSING_ROUTES) {
      expect(
        backendMatches(entry.method, entry.template),
        `${entry.method} ${entry.template} is now served — remove it from KNOWN_MISSING_ROUTES`
      ).toBe(false);
    }
  });
});
