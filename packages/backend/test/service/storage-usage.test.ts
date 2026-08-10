import { afterEach, describe, expect, test } from "bun:test";

import { createRouter } from "../../src/router.ts";
import { workersStorageUsageCache } from "../../src/storage-usage-cache.ts";
import { createRouterService } from "../support/router.ts";
import { authVerifier, createBlobSigner, createTestService } from "../support/service-fixtures.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * EB3: storage usage is decoupled from the world-details polling path.
 * Clients >=0.4.1 get storageUsage: null inline and fetch it on demand via
 * GET /worlds/:id/storage-usage; older clients keep the inline value.
 */
describe("storage usage decoupling", () => {
  const owner = { playerUuid: "player-owner", playerName: "Owner" };

  async function seeded() {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: owner.playerUuid, playerName: owner.playerName, createdAt: new Date().toISOString() });
    const world = await repository.createWorld(owner, "Friends SMP", "friends-smp");
    return { repository, instance, world };
  }

  test("world details omit storageUsage for 0.4.1+ clients and keep it for older ones", async () => {
    const { instance, world } = await seeded();

    const modern = await instance.getWorld({ ...owner, clientVersion: "0.4.1" }, world.id);
    expect(modern.storageUsage).toBeNull();

    const legacy = await instance.getWorld({ ...owner, clientVersion: "0.4.0" }, world.id);
    expect(legacy.storageUsage).not.toBeNull();
    expect(legacy.storageUsage?.usedBytes).toBe(0);

    const headerless = await instance.getWorld(owner, world.id);
    expect(headerless.storageUsage).not.toBeNull();
  });

  test("the dedicated endpoint serves usage to members and is membership-gated", async () => {
    const { instance, world } = await seeded();

    const usage = await instance.getStorageUsage({ ...owner, clientVersion: "0.4.1" }, world.id);
    expect(usage.usedBytes).toBe(0);
    expect(usage.provider).toBe("google-drive");

    await expect(instance.getStorageUsage({ playerUuid: "player-outsider", playerName: "Outsider" }, world.id))
      .rejects.toMatchObject({ status: 403, code: "forbidden" });
    await expect(instance.getStorageUsage(owner, "world_missing"))
      .rejects.toMatchObject({ status: 404, code: "world_not_found" });
  });

  test("the storage-usage route is wired", async () => {
    const router = createRouter(createRouterService({
      async getStorageUsage(_ctx, _worldId) {
        return {
          provider: "google-drive",
          linked: true,
          usedBytes: 1234,
          quotaUsedBytes: 5678,
          quotaTotalBytes: 100_000,
          accountEmail: "owner@example.com"
        };
      }
    }));

    const response = await router(new Request("http://127.0.0.1:8787/worlds/world-1/storage-usage", {
      headers: { authorization: "Bearer session-token" }
    }));
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({ usedBytes: 1234, quotaTotalBytes: 100_000 });
  });
});

describe("workersStorageUsageCache adapter", () => {
  const globalWithCaches = globalThis as { caches?: { default?: unknown } };
  const originalCaches = globalWithCaches.caches;

  afterEach(() => {
    if (originalCaches === undefined) {
      delete globalWithCaches.caches;
    } else {
      globalWithCaches.caches = originalCaches;
    }
  });

  test("returns null where the Workers cache API is absent", () => {
    delete globalWithCaches.caches;
    expect(workersStorageUsageCache()).toBeNull();
  });

  test("round-trips usedBytes and quota through caches.default", async () => {
    const entries = new Map<string, string>();
    globalWithCaches.caches = {
      default: {
        async match(url: string) {
          const body = entries.get(url);
          return body == null ? undefined : { json: async () => JSON.parse(body) as unknown };
        },
        async put(url: string, response: Response) {
          entries.set(url, await response.text());
        }
      }
    };

    const cache = workersStorageUsageCache();
    expect(cache).not.toBeNull();

    expect(await cache!.getUsedBytes("world-1", "snap-1")).toBeNull();
    await cache!.putUsedBytes("world-1", "snap-1", 42);
    expect(await cache!.getUsedBytes("world-1", "snap-1")).toBe(42);
    // A new snapshot id is a different key: the stale value cannot leak.
    expect(await cache!.getUsedBytes("world-1", "snap-2")).toBeNull();
    expect(await cache!.getUsedBytes("world-1", null)).toBeNull();

    expect(await cache!.getQuota("account-1")).toBeNull();
    await cache!.putQuota("account-1", { usedBytes: 10, totalBytes: 100 });
    expect(await cache!.getQuota("account-1")).toEqual({ usedBytes: 10, totalBytes: 100 });
  });

  test("a throwing cache degrades to misses instead of failing", async () => {
    globalWithCaches.caches = {
      default: {
        async match() { throw new Error("cache exploded"); },
        async put() { throw new Error("cache exploded"); }
      }
    };
    const cache = workersStorageUsageCache();
    expect(await cache!.getUsedBytes("world-1", "snap-1")).toBeNull();
    await cache!.putUsedBytes("world-1", "snap-1", 42);
    expect(await cache!.getQuota("account-1")).toBeNull();
  });
});
