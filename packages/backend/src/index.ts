import { D1SharedWorldRepository } from "./d1-repository.ts";
import type { Env } from "./env.ts";
import { MemorySharedWorldRepository } from "./memory-repository.ts";
import { createRouter } from "./router.ts";
import {
  MinecraftSessionServerAuthVerifier,
  SharedWorldService,
  WorkerSignedUrlSigner
} from "./service.ts";
import { createStorageProvider } from "./storage.ts";

const fallbackRepository = new MemorySharedWorldRepository();

export function createApp(env: Env): { fetch(request: Request): Promise<Response> } {
  const repository = env.DB ? new D1SharedWorldRepository(env.DB) : fallbackRepository;
  const service = new SharedWorldService(
    repository,
    new MinecraftSessionServerAuthVerifier(
      env.MOJANG_HAS_JOINED_ENDPOINT ?? "https://sessionserver.mojang.com/session/minecraft/hasJoined"
    ),
    new WorkerSignedUrlSigner(env),
    createStorageProvider(env, repository),
    env
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
