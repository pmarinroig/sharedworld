//! Player gateway (port of `UserGatewayDO`): the set of live sockets per
//! player, host watches, announced presence worlds, last-seen tracking, and
//! the bridge from client frames to coordinator pokes. Sockets themselves
//! live in the edge (or the dev WS handler); the gateway only holds a
//! [`ConnSink`] per connection.

use std::collections::HashSet;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;

use dashmap::DashMap;
use metrics::{counter, gauge};
use smallvec::SmallVec;
use sw_contracts::{RealtimeClientFrame, RealtimeClientFrameBody, RealtimeServerFrame};

use super::registry::CoordinatorRegistry;
use crate::time::{self, Instant};

pub type ConnId = u64;

/// Where frames for one connection go (edge IPC, dev WebSocket, test buffer).
pub trait ConnSink: Send + Sync {
    fn send_text(&self, text: String);
    fn close(&self);
}

pub struct Conn {
    pub id: ConnId,
    pub sink: Arc<dyn ConnSink>,
    pub connected_at_ms: i64,
    pub last_seen_ms: AtomicI64,
}

#[derive(Default)]
pub struct PlayerState {
    pub conns: SmallVec<[Arc<Conn>; 2]>,
    /// Coordinator-owned host plumbing (claim/retire write it).
    pub watches: HashSet<String>,
    /// Client-announced presence worlds; cleared on last socket close.
    pub presence_worlds: HashSet<String>,
}

pub struct Gateway {
    players: DashMap<String, PlayerState>,
    conn_owner: DashMap<ConnId, String>,
    registry: Arc<CoordinatorRegistry>,
}

impl Gateway {
    pub fn new(registry: Arc<CoordinatorRegistry>) -> Arc<Self> {
        Arc::new(Self { players: DashMap::new(), conn_owner: DashMap::new(), registry })
    }

    pub fn connection_count(&self) -> usize {
        self.conn_owner.len()
    }

    /// A socket for `player_uuid` is open. Sends `welcome`, registers the
    /// connection and pokes `hostSocketConnected` for every watched world
    /// (exactly what the gateway DO did on `/connect`).
    pub async fn attach(
        &self,
        player_uuid: &str,
        conn_id: ConnId,
        sink: Arc<dyn ConnSink>,
        connected_at: Instant,
        last_seen: Option<Instant>,
        send_welcome: bool,
    ) {
        let conn = Arc::new(Conn {
            id: conn_id,
            sink,
            connected_at_ms: time::to_millis(connected_at),
            last_seen_ms: AtomicI64::new(
                last_seen.map(time::to_millis).unwrap_or_else(|| time::to_millis(connected_at)),
            ),
        });
        if send_welcome {
            conn.sink.send_text(serde_json::to_string(&RealtimeServerFrame::welcome()).expect("json"));
        }
        let watches: Vec<String> = {
            let mut entry = self.players.entry(player_uuid.to_string()).or_default();
            entry.conns.retain(|c| c.id != conn_id);
            entry.conns.push(conn);
            entry.watches.iter().cloned().collect()
        };
        self.conn_owner.insert(conn_id, player_uuid.to_string());
        gauge!("ws_connections").set(self.conn_owner.len() as f64);
        counter!("ws_attach_total").increment(1);
        let now = time::now();
        for world_id in watches {
            let p = player_uuid.to_string();
            self.poke(&world_id, "hostSocketConnected", move |c| {
                Box::pin(async move { c.host_socket_connected(&p, now).await })
            })
            .await;
        }
    }

    /// Re-attach after a core restart: register without replaying pokes; the
    /// caller runs one reconciliation pass per watched world afterwards.
    pub fn reattach(
        &self,
        player_uuid: &str,
        conn_id: ConnId,
        sink: Arc<dyn ConnSink>,
        connected_at: Instant,
        last_seen: Option<Instant>,
    ) {
        let conn = Arc::new(Conn {
            id: conn_id,
            sink,
            connected_at_ms: time::to_millis(connected_at),
            last_seen_ms: AtomicI64::new(
                last_seen.map(time::to_millis).unwrap_or_else(|| time::to_millis(connected_at)),
            ),
        });
        let mut entry = self.players.entry(player_uuid.to_string()).or_default();
        entry.conns.retain(|c| c.id != conn_id);
        entry.conns.push(conn);
        drop(entry);
        self.conn_owner.insert(conn_id, player_uuid.to_string());
        gauge!("ws_connections").set(self.conn_owner.len() as f64);
    }

    /// A socket closed. When it was the player's last one: poke
    /// `hostSocketClosed` per watched world, then forget announced presence
    /// worlds and poke `presenceSocketClosed` for each.
    pub async fn detach(&self, conn_id: ConnId) {
        let Some((_, player_uuid)) = self.conn_owner.remove(&conn_id) else { return };
        gauge!("ws_connections").set(self.conn_owner.len() as f64);
        counter!("ws_detach_total").increment(1);
        let (last_socket, watches, presence_worlds) = {
            let Some(mut entry) = self.players.get_mut(&player_uuid) else { return };
            entry.conns.retain(|c| c.id != conn_id);
            if !entry.conns.is_empty() {
                return;
            }
            let watches: Vec<String> = entry.watches.iter().cloned().collect();
            let presence: Vec<String> = entry.presence_worlds.drain().collect();
            (true, watches, presence)
        };
        if !last_socket {
            return;
        }
        let now = time::now();
        for world_id in watches {
            let p = player_uuid.clone();
            self.poke(&world_id, "hostSocketClosed", move |c| {
                Box::pin(async move { c.host_socket_closed(&p, now).await })
            })
            .await;
        }
        for world_id in presence_worlds {
            let p = player_uuid.clone();
            self.poke(&world_id, "presenceSocketClosed", move |c| {
                Box::pin(async move { c.presence_socket_closed(&p, now).await })
            })
            .await;
        }
        // Drop empty player entries with no watches to bound memory.
        self.players.remove_if(&player_uuid, |_, s| {
            s.conns.is_empty() && s.watches.is_empty() && s.presence_worlds.is_empty()
        });
    }

    /// Inbound client text frame (keepalives are answered upstream and
    /// never reach here). Unknown/invalid frames are ignored.
    pub async fn on_text(&self, conn_id: ConnId, text: &str) {
        let Some(player_uuid) = self.conn_owner.get(&conn_id).map(|p| p.clone()) else { return };
        let Ok(frame) = serde_json::from_str::<RealtimeClientFrame>(text) else {
            counter!("ws_frames_in_total", "type" => "invalid").increment(1);
            return;
        };
        let now = time::now();
        match frame.body {
            RealtimeClientFrameBody::HostPlayers { world_id, runtime_epoch, players } => {
                counter!("ws_frames_in_total", "type" => "host-players").increment(1);
                let p = player_uuid.clone();
                self.poke(&world_id, "reportHostPlayers", move |c| {
                    Box::pin(async move { c.report_host_players(&p, runtime_epoch, players, now).await })
                })
                .await;
            }
            RealtimeClientFrameBody::WorldPresence { world_id, present } => {
                counter!("ws_frames_in_total", "type" => "world-presence").increment(1);
                if let Some(mut entry) = self.players.get_mut(&player_uuid) {
                    if present {
                        if !world_id.is_empty() {
                            entry.presence_worlds.insert(world_id.clone());
                        }
                    } else {
                        entry.presence_worlds.remove(&world_id);
                    }
                }
                let p = player_uuid.clone();
                self.poke(&world_id, "reportSocketPresence", move |c| {
                    Box::pin(async move { c.report_socket_presence(&p, present, now).await })
                })
                .await;
            }
        }
    }

    pub fn mark_seen(&self, conn_id: ConnId, at: Instant) {
        let Some(player_uuid) = self.conn_owner.get(&conn_id).map(|p| p.clone()) else { return };
        if let Some(entry) = self.players.get(&player_uuid) {
            if let Some(c) = entry.conns.iter().find(|c| c.id == conn_id) {
                c.last_seen_ms.fetch_max(time::to_millis(at), Ordering::Relaxed);
            }
        }
    }

    /// Broadcast one already-encoded frame to every socket of the player.
    pub fn notify(&self, player_uuid: &str, text: &str) -> bool {
        let Some(entry) = self.players.get(player_uuid) else {
            tracing::debug!(player_uuid, "notify: no socket");
            return false;
        };
        tracing::debug!(player_uuid, conns = entry.conns.len(), "notify");
        for c in &entry.conns {
            c.sink.send_text(text.to_string());
        }
        counter!("ws_frames_out_total", "type" => "event").increment(entry.conns.len() as u64);
        !entry.conns.is_empty()
    }

    pub fn has_socket(&self, player_uuid: &str) -> bool {
        self.players.get(player_uuid).is_some_and(|e| !e.conns.is_empty())
    }

    /// Coordinator-owned: set/clear a host watch; returns current connectivity.
    pub fn set_watch(&self, player_uuid: &str, world_id: &str, watching: bool) -> bool {
        let mut entry = self.players.entry(player_uuid.to_string()).or_default();
        if watching {
            if !world_id.is_empty() {
                entry.watches.insert(world_id.to_string());
            }
        } else {
            entry.watches.remove(world_id);
        }
        !entry.conns.is_empty()
    }

    /// Last keepalive/connect seen on any of the player's sockets.
    pub fn last_seen(&self, player_uuid: &str) -> Option<Instant> {
        let entry = self.players.get(player_uuid)?;
        entry
            .conns
            .iter()
            .map(|c| c.last_seen_ms.load(Ordering::Relaxed).max(c.connected_at_ms))
            .max()
            .map(time::from_millis)
    }

    /// Worlds watched for a player (for restart reconciliation).
    pub fn watches_of(&self, player_uuid: &str) -> Vec<String> {
        self.players.get(player_uuid).map(|e| e.watches.iter().cloned().collect()).unwrap_or_default()
    }

    /// Close every socket of a player (e.g. session revoked).
    pub fn close_player(&self, player_uuid: &str) {
        if let Some(entry) = self.players.get(player_uuid) {
            for c in &entry.conns {
                c.sink.close();
            }
        }
    }

    async fn poke<F>(&self, world_id: &str, method: &'static str, f: F)
    where
        F: for<'a> FnOnce(
                &'a mut super::coordinator::WorldCoordinator,
            ) -> std::pin::Pin<
                Box<dyn std::future::Future<Output = crate::http_error::HttpResult<()>> + Send + 'a>,
            > + Send,
    {
        if let Err(e) = self.registry.call(world_id, f).await {
            tracing::warn!(world_id, method, error = %e, "SharedWorld gateway dropped a coordinator poke");
        }
    }
}
