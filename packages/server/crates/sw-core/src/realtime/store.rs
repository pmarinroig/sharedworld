//! Coordinator store + effects seams (`CoordinatorStore`/`CoordinatorEffects`).
//!
//! The store is synchronous (single-threaded per world by construction;
//! the actor serializes calls). [`KvStore`] mirrors the DO's kv table
//! exactly (same keys, same JSON shapes) so a coordinator dump from the
//! worker imports as-is; persistence is flushed by the actor after each
//! call (`take_dirty`).

use std::collections::{BTreeMap, HashMap};

use async_trait::async_trait;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use sw_contracts::{RealtimeEvent, RoomPlayer, UncleanShutdownWarning, WorldRuntimeStatus};

use super::runtime_protocol::{RuntimeMembership, RuntimeWaiter, WorldRuntimeRecord};
use crate::time::Instant;

/// Legacy 0.2.x self-reported presence entry (tombstones when present=false).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LegacyPresenceEntry {
    pub player_uuid: String,
    pub player_name: String,
    pub present: bool,
    pub guest_session_epoch: i64,
    pub presence_sequence: i64,
    pub expires_at: String,
}

/// Socket-derived guest presence (0.4.1 world-presence frames).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SocketPresenceEntry {
    pub player_uuid: String,
    pub player_name: String,
    pub grace_deadline_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostLink {
    pub connected: bool,
    pub grace_deadline_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MembershipCache {
    pub members: Vec<RuntimeMembership>,
    pub fetched_at: String,
}

pub trait CoordinatorStore: Send {
    fn as_any_mut(&mut self) -> &mut dyn std::any::Any;
    fn get_runtime(&self) -> Option<WorldRuntimeRecord>;
    fn put_runtime(&mut self, runtime: &WorldRuntimeRecord);
    fn delete_runtime(&mut self);
    fn get_warning(&self) -> Option<UncleanShutdownWarning>;
    fn set_warning(&mut self, warning: &UncleanShutdownWarning);
    fn clear_warning(&mut self);
    fn get_last_epoch(&self) -> i64;
    fn set_last_epoch(&mut self, epoch: i64);
    fn list_waiters(&self) -> Vec<RuntimeWaiter>;
    fn upsert_waiter(&mut self, waiter: &RuntimeWaiter);
    fn delete_waiter(&mut self, player_uuid: &str);
    fn clear_waiters(&mut self);
    fn get_room_players(&self) -> Option<Vec<RoomPlayer>>;
    fn set_room_players(&mut self, players: Option<&[RoomPlayer]>);
    fn list_legacy_presence(&self) -> Vec<LegacyPresenceEntry>;
    fn upsert_legacy_presence(&mut self, entry: &LegacyPresenceEntry);
    fn delete_legacy_presence(&mut self, player_uuid: &str);
    fn clear_legacy_presence(&mut self);
    fn list_socket_presence(&self) -> Vec<SocketPresenceEntry>;
    fn upsert_socket_presence(&mut self, entry: &SocketPresenceEntry);
    fn delete_socket_presence(&mut self, player_uuid: &str);
    fn clear_socket_presence(&mut self);
    fn get_host_link(&self) -> HostLink;
    fn set_host_link(&mut self, link: &HostLink);
    fn get_membership_cache(&self) -> Option<MembershipCache>;
    fn set_membership_cache(&mut self, cache: &MembershipCache);
    fn clear_membership_cache(&mut self);
    fn get_status_fingerprint(&self) -> Option<String>;
    fn set_status_fingerprint(&mut self, fingerprint: &str);
    fn get_presence_fingerprint(&self) -> Option<String>;
    fn set_presence_fingerprint(&mut self, fingerprint: &str);
    fn clear_all(&mut self);
}

/// JSON kv store with the DO's key vocabulary. Values are kept as JSON text
/// and re-parsed per read (callers can never mutate shared state). Dirty
/// keys are tracked for the actor's post-call flush.
#[derive(Debug, Default, Clone)]
pub struct KvStore {
    values: BTreeMap<String, String>,
    dirty: HashMap<String, Option<String>>,
    cleared: bool,
}

impl KvStore {
    pub fn from_rows(rows: impl IntoIterator<Item = (String, String)>) -> Self {
        Self { values: rows.into_iter().collect(), dirty: HashMap::new(), cleared: false }
    }

    pub fn rows(&self) -> impl Iterator<Item = (&str, &str)> {
        self.values.iter().map(|(k, v)| (k.as_str(), v.as_str()))
    }

    /// Pending writes since the last take: `(cleared_all, key → Some(value) | None=delete)`.
    pub fn take_dirty(&mut self) -> (bool, Vec<(String, Option<String>)>) {
        let cleared = std::mem::take(&mut self.cleared);
        let dirty = std::mem::take(&mut self.dirty).into_iter().collect();
        (cleared, dirty)
    }

    fn read<T: DeserializeOwned>(&self, key: &str) -> Option<T> {
        self.values.get(key).and_then(|v| serde_json::from_str(v).ok())
    }

    fn write<T: Serialize>(&mut self, key: &str, value: &T) {
        let text = serde_json::to_string(value).expect("coordinator value serializes");
        // Rewriting an identical value is a no-op for the row (the DO paid a
        // storage.put for it; here it would be a needless write-txn row).
        if self.values.get(key).is_some_and(|current| *current == text) && !self.cleared {
            return;
        }
        self.values.insert(key.to_string(), text.clone());
        self.dirty.insert(key.to_string(), Some(text));
    }

    fn remove(&mut self, key: &str) {
        if self.values.remove(key).is_some() || !self.dirty.contains_key(key) {
            self.dirty.insert(key.to_string(), None);
        }
    }
}

impl CoordinatorStore for KvStore {
    fn as_any_mut(&mut self) -> &mut dyn std::any::Any {
        self
    }
    fn get_runtime(&self) -> Option<WorldRuntimeRecord> {
        self.read("runtime")
    }
    fn put_runtime(&mut self, runtime: &WorldRuntimeRecord) {
        self.write("runtime", runtime)
    }
    fn delete_runtime(&mut self) {
        self.remove("runtime")
    }
    fn get_warning(&self) -> Option<UncleanShutdownWarning> {
        self.read("warning")
    }
    fn set_warning(&mut self, warning: &UncleanShutdownWarning) {
        self.write("warning", warning)
    }
    fn clear_warning(&mut self) {
        self.remove("warning")
    }
    fn get_last_epoch(&self) -> i64 {
        self.read("lastEpoch").unwrap_or(0)
    }
    fn set_last_epoch(&mut self, epoch: i64) {
        self.write("lastEpoch", &epoch)
    }
    fn list_waiters(&self) -> Vec<RuntimeWaiter> {
        self.read("waiters").unwrap_or_default()
    }
    fn upsert_waiter(&mut self, waiter: &RuntimeWaiter) {
        let mut w: Vec<RuntimeWaiter> =
            self.list_waiters().into_iter().filter(|e| e.player_uuid != waiter.player_uuid).collect();
        w.push(waiter.clone());
        self.write("waiters", &w)
    }
    fn delete_waiter(&mut self, player_uuid: &str) {
        let w: Vec<RuntimeWaiter> =
            self.list_waiters().into_iter().filter(|e| e.player_uuid != player_uuid).collect();
        self.write("waiters", &w)
    }
    fn clear_waiters(&mut self) {
        self.remove("waiters")
    }
    fn get_room_players(&self) -> Option<Vec<RoomPlayer>> {
        self.read("roomPlayers")
    }
    fn set_room_players(&mut self, players: Option<&[RoomPlayer]>) {
        match players {
            None => self.remove("roomPlayers"),
            Some(p) => self.write("roomPlayers", &p),
        }
    }
    fn list_legacy_presence(&self) -> Vec<LegacyPresenceEntry> {
        self.read("legacyPresence").unwrap_or_default()
    }
    fn upsert_legacy_presence(&mut self, entry: &LegacyPresenceEntry) {
        let mut e: Vec<LegacyPresenceEntry> =
            self.list_legacy_presence().into_iter().filter(|x| x.player_uuid != entry.player_uuid).collect();
        e.push(entry.clone());
        self.write("legacyPresence", &e)
    }
    fn delete_legacy_presence(&mut self, player_uuid: &str) {
        let e: Vec<LegacyPresenceEntry> =
            self.list_legacy_presence().into_iter().filter(|x| x.player_uuid != player_uuid).collect();
        self.write("legacyPresence", &e)
    }
    fn clear_legacy_presence(&mut self) {
        self.remove("legacyPresence")
    }
    fn list_socket_presence(&self) -> Vec<SocketPresenceEntry> {
        self.read("socketPresence").unwrap_or_default()
    }
    fn upsert_socket_presence(&mut self, entry: &SocketPresenceEntry) {
        let mut e: Vec<SocketPresenceEntry> =
            self.list_socket_presence().into_iter().filter(|x| x.player_uuid != entry.player_uuid).collect();
        e.push(entry.clone());
        self.write("socketPresence", &e)
    }
    fn delete_socket_presence(&mut self, player_uuid: &str) {
        let e: Vec<SocketPresenceEntry> =
            self.list_socket_presence().into_iter().filter(|x| x.player_uuid != player_uuid).collect();
        self.write("socketPresence", &e)
    }
    fn clear_socket_presence(&mut self) {
        self.remove("socketPresence")
    }
    fn get_host_link(&self) -> HostLink {
        self.read("hostLink").unwrap_or_default()
    }
    fn set_host_link(&mut self, link: &HostLink) {
        self.write("hostLink", link)
    }
    fn get_membership_cache(&self) -> Option<MembershipCache> {
        self.read("membershipCache")
    }
    fn set_membership_cache(&mut self, cache: &MembershipCache) {
        self.write("membershipCache", cache)
    }
    fn clear_membership_cache(&mut self) {
        self.remove("membershipCache")
    }
    fn get_status_fingerprint(&self) -> Option<String> {
        self.read("statusFingerprint")
    }
    fn set_status_fingerprint(&mut self, fingerprint: &str) {
        self.write("statusFingerprint", &fingerprint)
    }
    fn get_presence_fingerprint(&self) -> Option<String> {
        self.read("presenceFingerprint")
    }
    fn set_presence_fingerprint(&mut self, fingerprint: &str) {
        self.write("presenceFingerprint", &fingerprint)
    }
    fn clear_all(&mut self) {
        self.values.clear();
        self.dirty.clear();
        self.cleared = true;
    }
}

/// Everything with a side effect outside this world's own state.
#[async_trait]
pub trait CoordinatorEffects: Send + Sync {
    async fn list_memberships(&self, world_id: &str) -> Result<Vec<RuntimeMembership>, crate::HttpError>;
    /// Single-writer display mirror: the full public status for summary reads.
    async fn mirror_runtime(&self, world_id: &str, status: &WorldRuntimeStatus);
    async fn mirror_presence(&self, world_id: &str, players: &[RoomPlayer]);
    /// Fan out one event to member gateways (or an explicit recipient list).
    async fn publish(&self, event: RealtimeEvent, recipients: Option<Vec<String>>);
    /// Replace the single pending alarm; `None` cancels it.
    async fn schedule_alarm(&self, world_id: &str, at: Option<Instant>);
    /// Ask the host's gateway to report socket open/close for this world;
    /// returns whether the host's socket is connected now.
    async fn set_host_watch(&self, world_id: &str, host_uuid: &str, watching: bool) -> bool;
    /// Last keepalive seen on the host's socket. `Err` = probe failed
    /// (callers skip expiry; never forfeit a possibly healthy host).
    async fn probe_host_reachability(&self, host_uuid: &str) -> Result<Option<Instant>, crate::HttpError>;
}
