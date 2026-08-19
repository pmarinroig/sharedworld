//! In-process caches replacing the Workers Cache API: manifests (immutable
//! per snapshot id), storage usage/quota (15 min), sessions (5 min).

use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use moka::future::Cache;
use sw_contracts::{SessionToken, SnapshotManifest};
use sw_db::repo::ManifestCache;

use crate::storage::StorageQuota;

pub struct MokaManifestCache {
    cache: Cache<String, Arc<SnapshotManifest>>,
}

impl MokaManifestCache {
    /// `max_entries` manifests; weighed by pack+file count to bound memory.
    pub fn new(max_weight: u64) -> Arc<Self> {
        Arc::new(Self {
            cache: Cache::builder()
                .weigher(|_k: &String, v: &Arc<SnapshotManifest>| {
                    (1 + v.files.len() + v.packs.iter().map(|p| 1 + p.files.len()).sum::<usize>())
                        .min(u32::MAX as usize) as u32
                })
                .max_capacity(max_weight)
                .time_to_live(Duration::from_secs(24 * 3600))
                .build(),
        })
    }
}

#[async_trait]
impl ManifestCache for MokaManifestCache {
    async fn get(&self, world_id: &str, snapshot_id: &str) -> Option<Arc<SnapshotManifest>> {
        self.cache.get(&format!("{world_id}/{snapshot_id}")).await
    }
    async fn put(&self, world_id: &str, snapshot_id: &str, manifest: Arc<SnapshotManifest>) {
        self.cache.insert(format!("{world_id}/{snapshot_id}"), manifest).await
    }
}

/// `StorageUsageCache`: usedBytes keyed (world, latest snapshot), quota per account.
pub struct StorageUsageCache {
    used: Cache<String, i64>,
    quota: Cache<String, StorageQuota>,
}

impl Default for StorageUsageCache {
    fn default() -> Self {
        Self::new()
    }
}

impl StorageUsageCache {
    pub fn new() -> Self {
        let ttl = Duration::from_secs(15 * 60);
        Self {
            used: Cache::builder().max_capacity(10_000).time_to_live(ttl).build(),
            quota: Cache::builder().max_capacity(10_000).time_to_live(ttl).build(),
        }
    }
    fn used_key(world_id: &str, latest: Option<&str>) -> String {
        format!("{world_id}/{}", latest.unwrap_or("none"))
    }
    pub async fn get_used_bytes(&self, world_id: &str, latest_snapshot_id: Option<&str>) -> Option<i64> {
        self.used.get(&Self::used_key(world_id, latest_snapshot_id)).await
    }
    pub async fn put_used_bytes(&self, world_id: &str, latest_snapshot_id: Option<&str>, used: i64) {
        self.used.insert(Self::used_key(world_id, latest_snapshot_id), used).await
    }
    pub async fn get_quota(&self, account_id: &str) -> Option<StorageQuota> {
        self.quota.get(account_id).await
    }
    pub async fn put_quota(&self, account_id: &str, quota: StorageQuota) {
        self.quota.insert(account_id.to_string(), quota).await
    }
    pub async fn invalidate_quota(&self, account_id: &str) {
        self.quota.invalidate(account_id).await
    }
}

/// Bearer → session (5 min TTL, 512 entries like the worker's in-isolate map).
pub struct SessionCache {
    cache: Cache<String, Arc<SessionToken>>,
}

impl Default for SessionCache {
    fn default() -> Self {
        Self::new()
    }
}

impl SessionCache {
    pub fn new() -> Self {
        Self { cache: Cache::builder().max_capacity(4096).time_to_live(Duration::from_secs(5 * 60)).build() }
    }
    pub async fn get(&self, token: &str) -> Option<Arc<SessionToken>> {
        self.cache.get(token).await
    }
    pub async fn put(&self, session: Arc<SessionToken>) {
        self.cache.insert(session.token.clone(), session).await
    }
    pub async fn invalidate(&self, token: &str) {
        self.cache.invalidate(token).await
    }
}
