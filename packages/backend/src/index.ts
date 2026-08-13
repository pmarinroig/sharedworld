import { D1SharedWorldRepository } from "./d1-repository.ts";
import type { Env } from "./env.ts";
import { workersSnapshotManifestCache } from "./manifest-cache.ts";
import { providerManifestDocumentReader } from "./manifest-doc.ts";
import { DoRealtimeService } from "./realtime/service.ts";
import { createRouter } from "./router.ts";
import { SharedWorldService, WorkerSignedUrlSigner } from "./service.ts";
import { createStorageProvider } from "./storage.ts";

export { UserGatewayDO, WorldCoordinatorDO } from "./realtime/do.ts";

export function createApp(env: Env): { fetch(request: Request): Promise<Response> } {
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

  return {
    fetch: createRouter(service)
  };
}

export default {
  fetch(request: Request, env: Env) {
    return createApp(env).fetch(request);
  }
};
