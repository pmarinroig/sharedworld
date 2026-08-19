import { D1SharedWorldRepository } from "./d1-repository.ts";
import type { Env } from "./env.ts";
import { createLaneDApp, isLaneD, type LaneDEnv } from "./lane-d.ts";
import { workersSnapshotManifestCache } from "./manifest-cache.ts";
import { providerManifestDocumentReader } from "./manifest-doc.ts";
import { DoRealtimeService } from "./realtime/service.ts";
import { createRouter } from "./router.ts";
import { SharedWorldService, WorkerSignedUrlSigner } from "./service.ts";
import { createStorageProvider } from "./storage.ts";

export { UserGatewayDO, WorldCoordinatorDO } from "./realtime/do.ts";

export interface SharedWorldApp {
  fetch(request: Request, executionContext?: { waitUntil(task: Promise<unknown>): void }): Promise<Response>;
  /** Cron tick (wrangler `[triggers] crons`): drains the blob GC retry queue. */
  scheduled(now?: Date): Promise<number>;
}

/**
 * MODE=maintenance: the cutover freeze (docs/cutover-runbook.md). Every
 * request — including socket upgrades — answers 503 with Retry-After, so no
 * write can land on D1 between the export and the flip to the box. Clients
 * treat 5xx as transient and keep retrying; sockets reconnect with backoff.
 */
export function isMaintenance(env: Env): boolean {
  return ((env as Env & { MODE?: string }).MODE ?? "").toLowerCase() === "maintenance";
}

export function maintenanceResponse(): Response {
  return Response.json(
    { error: "maintenance", message: "SharedWorld is moving servers and will be back in a few minutes. Please retry.", status: 503 },
    { status: 503, headers: { "retry-after": "60", "cache-control": "no-store" } }
  );
}

export function createApp(env: Env): SharedWorldApp {
  if (isMaintenance(env)) {
    // The cutover's coordinator dump runs DURING the freeze: admin routes
    // (ADMIN_SECRET-gated, read-only) stay reachable; everything else is 503.
    const admin = createAdminRoutes(env);
    return { fetch: async (request) => (await admin(request)) ?? maintenanceResponse(), scheduled: async () => 0 };
  }
  if (isLaneD(env as LaneDEnv)) {
    // Lane D: thin front for the Rust server — no D1, no DOs (see lane-d.ts).
    const laneD = createLaneDApp(env as LaneDEnv);
    return { fetch: (request) => laneD.fetch(request), scheduled: () => laneD.scheduled() };
  }
  if (!env.DB) {
    throw new Error("SharedWorld backend requires a D1 database binding (DB).");
  }
  if (!env.WORLD_COORDINATOR || !env.USER_GATEWAY) {
    throw new Error("SharedWorld backend requires the WORLD_COORDINATOR and USER_GATEWAY Durable Object bindings.");
  }
  const repository = new D1SharedWorldRepository(env.DB, workersSnapshotManifestCache());
  const storageProvider = createStorageProvider(env, repository);
  // 0027: doc-format snapshots resolve member lists through the provider;
  // attached post-construction because the provider is built over the
  // repository.
  repository.attachManifestDocumentReader(providerManifestDocumentReader(storageProvider));
  const service = new SharedWorldService(
    repository,
    new WorkerSignedUrlSigner(env),
    storageProvider,
    env,
    new DoRealtimeService(env.WORLD_COORDINATOR, env.USER_GATEWAY)
  );

  const route = createRouter(service);
  const admin = createAdminRoutes(env);
  return {
    fetch: async (request, executionContext) => (await admin(request)) ?? route(request, executionContext),
    scheduled: (now = new Date()) => service.sweepDuePendingBlobDeletes(now)
  };
}

/**
 * Lane-D cutover tooling (`scripts/dump-coordinators.ts`), enabled only while
 * ADMIN_SECRET is set: list world ids and dump one coordinator's DO state.
 */
function createAdminRoutes(env: Env): (request: Request) => Promise<Response | null> {
  return async (request) => {
    const url = new URL(request.url);
    if (!url.pathname.startsWith("/__admin/")) {
      return null;
    }
    const secret = (env as Env & { ADMIN_SECRET?: string }).ADMIN_SECRET;
    if (!secret || request.headers.get("x-sw-admin-secret") !== secret) {
      return Response.json({ error: "not_found", message: "Route not found.", status: 404 }, { status: 404 });
    }
    if (url.pathname === "/__admin/worlds" && request.method === "GET") {
      const rows = await env.DB!.prepare("SELECT id FROM worlds ORDER BY id").all<{ id: string }>();
      return Response.json({ worldIds: rows.results.map((row) => row.id) });
    }
    const match = /^\/__admin\/coordinator\/([^/]+)$/.exec(url.pathname);
    if (match && request.method === "GET") {
      const worldId = decodeURIComponent(match[1]);
      const stub = env.WORLD_COORDINATOR!.get(env.WORLD_COORDINATOR!.idFromName(worldId));
      return stub.fetch("https://do/dump");
    }
    return Response.json({ error: "not_found", message: "Route not found.", status: 404 }, { status: 404 });
  };
}

export default {
  fetch(request: Request, env: Env, ctx: { waitUntil(task: Promise<unknown>): void }) {
    return createApp(env).fetch(request, ctx);
  },
  // 0.4.5: unattended drain of pending_blob_deletes. Instant-ack backup
  // deletes and post-response GC hand their overflow to that queue; before
  // this the queue was only swept by later requests, so a world that went
  // quiet could leave Drive bytes stranded indefinitely.
  scheduled(controller: { scheduledTime: number }, env: Env, ctx: { waitUntil(task: Promise<unknown>): void }) {
    ctx.waitUntil(createApp(env).scheduled(new Date(controller.scheduledTime)).then((attempted) => {
      if (attempted > 0) {
        console.log("SharedWorld blob GC sweep", { attempted });
      }
    }));
  }
};
