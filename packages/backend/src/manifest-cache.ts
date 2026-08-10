import type { SnapshotManifest } from "../../shared/src/index.ts";

/**
 * Cache for fully-loaded snapshot manifests, keyed by (worldId, snapshotId).
 * Manifest content is immutable per snapshot id — snapshots are never edited
 * in place and ids are never reused (retention's member-row promotion only
 * repoints where rows physically live, not what a manifest contains) — so
 * entries never need invalidation, only eviction. Lookups and stores are
 * best-effort: a broken cache must degrade to plain D1 loads, never fail the
 * request.
 */
export interface SnapshotManifestCache {
  match(worldId: string, snapshotId: string): Promise<SnapshotManifest | null>;
  put(worldId: string, snapshotId: string, manifest: SnapshotManifest): Promise<void>;
}

const CACHE_BASE_URL = "https://sharedworld-manifest.internal/";
const CACHE_MAX_AGE_SECONDS = 24 * 60 * 60;

function cacheKeyUrl(worldId: string, snapshotId: string): string {
  return `${CACHE_BASE_URL}${encodeURIComponent(worldId)}/${encodeURIComponent(snapshotId)}`;
}

interface WorkersCache {
  match(url: string): Promise<{ json(): Promise<unknown> } | undefined>;
  put(url: string, response: Response): Promise<void>;
}

/**
 * The Workers Cache API (`caches.default`) adapter, or null where that API
 * does not exist (Bun tests). Colo-local, which fits the hot path exactly: a
 * guest's cache warmer re-polls the same manifest from the same colo every
 * 30 seconds.
 */
export function workersSnapshotManifestCache(): SnapshotManifestCache | null {
  const cachesGlobal = (globalThis as { caches?: { default?: WorkersCache } }).caches;
  const cache = cachesGlobal?.default;
  if (cache == null) {
    return null;
  }
  return {
    async match(worldId, snapshotId) {
      try {
        const hit = await cache.match(cacheKeyUrl(worldId, snapshotId));
        if (!hit) {
          return null;
        }
        return await hit.json() as SnapshotManifest;
      } catch (error) {
        console.warn("SharedWorld manifest cache read failed", { worldId, snapshotId, cause: String(error) });
        return null;
      }
    },
    async put(worldId, snapshotId, manifest) {
      try {
        await cache.put(cacheKeyUrl(worldId, snapshotId), new Response(JSON.stringify(manifest), {
          headers: {
            "content-type": "application/json",
            "cache-control": `max-age=${CACHE_MAX_AGE_SECONDS}`
          }
        }));
      } catch (error) {
        console.warn("SharedWorld manifest cache write failed", { worldId, snapshotId, cause: String(error) });
      }
    }
  };
}
