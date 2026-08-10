/**
 * Cache for the two expensive halves of a StorageUsageSummary, serving the
 * legacy world-details path (clients <0.4.1 read storageUsage off every
 * GET /worlds/:id — which the old cache warmer polls every 30s):
 *
 * - usedBytes: keyed (worldId, latestSnapshotId) — the referenced-keys CTE
 *   scans every snapshot_files/snapshot_packs row of the world, and its
 *   result only changes when a snapshot finalizes/deletes or the icon
 *   changes, all of which move latestSnapshotId or are advisory-display
 *   noise. Retention drift self-corrects within the TTL.
 * - quota: keyed per storage account — a live Google Drive `/about` call
 *   otherwise fired once per poll (~80% of all googleapis subrequests).
 *
 * Best-effort like the manifest cache: a broken cache degrades to fresh
 * computation, never fails the request.
 */
export interface StorageUsageCache {
  getUsedBytes(worldId: string, latestSnapshotId: string | null): Promise<number | null>;
  putUsedBytes(worldId: string, latestSnapshotId: string | null, usedBytes: number): Promise<void>;
  getQuota(accountId: string): Promise<{ usedBytes: number | null; totalBytes: number | null } | null>;
  putQuota(accountId: string, quota: { usedBytes: number | null; totalBytes: number | null }): Promise<void>;
}

const CACHE_BASE_URL = "https://sharedworld-storage-usage.internal/";
const CACHE_MAX_AGE_SECONDS = 15 * 60;

function usedBytesKey(worldId: string, latestSnapshotId: string | null): string {
  return `${CACHE_BASE_URL}${encodeURIComponent(worldId)}/${encodeURIComponent(latestSnapshotId ?? "none")}`;
}

function quotaKey(accountId: string): string {
  return `${CACHE_BASE_URL}quota/${encodeURIComponent(accountId)}`;
}

interface WorkersCache {
  match(url: string): Promise<{ json(): Promise<unknown> } | undefined>;
  put(url: string, response: Response): Promise<void>;
}

/** The `caches.default` adapter, or null where that API is absent (Bun tests). */
export function workersStorageUsageCache(): StorageUsageCache | null {
  const cachesGlobal = (globalThis as { caches?: { default?: WorkersCache } }).caches;
  const cache = cachesGlobal?.default;
  if (cache == null) {
    return null;
  }

  async function read<T>(url: string): Promise<T | null> {
    try {
      const hit = await cache!.match(url);
      if (!hit) {
        return null;
      }
      return await hit.json() as T;
    } catch (error) {
      console.warn("SharedWorld storage-usage cache read failed", { url, cause: String(error) });
      return null;
    }
  }

  async function write(url: string, value: unknown): Promise<void> {
    try {
      await cache!.put(url, new Response(JSON.stringify(value), {
        headers: {
          "content-type": "application/json",
          "cache-control": `max-age=${CACHE_MAX_AGE_SECONDS}`
        }
      }));
    } catch (error) {
      console.warn("SharedWorld storage-usage cache write failed", { url, cause: String(error) });
    }
  }

  return {
    async getUsedBytes(worldId, latestSnapshotId) {
      const value = await read<{ usedBytes: number }>(usedBytesKey(worldId, latestSnapshotId));
      return value?.usedBytes ?? null;
    },
    async putUsedBytes(worldId, latestSnapshotId, usedBytes) {
      await write(usedBytesKey(worldId, latestSnapshotId), { usedBytes });
    },
    async getQuota(accountId) {
      return read(quotaKey(accountId));
    },
    async putQuota(accountId, quota) {
      await write(quotaKey(accountId), quota);
    }
  };
}
