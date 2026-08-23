//! The edge's link to the core over the WS IPC Unix socket: one connection,
//! auto-reconnect, replay of every open socket on reconnect.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use futures::{SinkExt, StreamExt};
use metrics::{counter, gauge};
use parking_lot::Mutex;
use sw_ipc::{ConnId, CoreToEdge, EdgeToCore, IpcCodec, PROTOCOL_VERSION};
use tokio::net::UnixStream;
use tokio::sync::{mpsc, oneshot, Notify};
use tokio_util::codec::{FramedRead, FramedWrite};

pub struct ConnState {
    pub authorization: Option<String>,
    pub peer: String,
    pub connected_at_ms: i64,
    pub last_seen_ms: AtomicU64,
    /// Newest retained client frames per key (`world-presence:<w>`, `host-players:<w>`).
    pub retained: Mutex<HashMap<String, String>>,
    /// Frames from core for this socket (drained by the socket task).
    pub to_client: mpsc::UnboundedSender<CoreToEdge>,
}

/// Edge→core link with registry of open sockets.
/// Socket admission limits (global and per client IP). Over the cap a
/// `/ws` upgrade answers 429 and the client reconnects with backoff.
pub struct Limits {
    pub max_conns: usize,
    pub max_per_ip: usize,
    per_ip: Mutex<HashMap<std::net::IpAddr, usize>>,
    total: AtomicU64,
}

impl Limits {
    pub fn new(max_conns: usize, max_per_ip: usize) -> Self {
        Self { max_conns, max_per_ip, per_ip: Mutex::new(HashMap::new()), total: AtomicU64::new(0) }
    }

    /// Admit one socket from `ip`; `false` when a cap is reached.
    pub fn admit(&self, ip: std::net::IpAddr) -> bool {
        let mut per_ip = self.per_ip.lock();
        let n = per_ip.entry(ip).or_insert(0);
        if *n >= self.max_per_ip || self.total.load(Ordering::Relaxed) as usize >= self.max_conns {
            if *n == 0 {
                per_ip.remove(&ip);
            }
            return false;
        }
        *n += 1;
        self.total.fetch_add(1, Ordering::Relaxed);
        true
    }

    pub fn release(&self, ip: std::net::IpAddr) {
        let mut per_ip = self.per_ip.lock();
        if let Some(n) = per_ip.get_mut(&ip) {
            *n -= 1;
            if *n == 0 {
                per_ip.remove(&ip);
            }
            self.total.fetch_sub(1, Ordering::Relaxed);
        }
    }
}

pub struct CoreLink {
    path: PathBuf,
    pub limits: Limits,
    outbound: Mutex<Option<mpsc::UnboundedSender<EdgeToCore>>>,
    conns: Mutex<HashMap<ConnId, Arc<ConnState>>>,
    pending_open: Mutex<HashMap<ConnId, oneshot::Sender<Result<(), (u16, String)>>>>,
    next_conn: AtomicU64,
    connected: AtomicBool,
    pub link_up: Notify,
}

impl CoreLink {
    pub fn new(path: PathBuf, limits: Limits) -> Arc<Self> {
        Arc::new(Self {
            path,
            limits,
            outbound: Mutex::new(None),
            conns: Mutex::new(HashMap::new()),
            pending_open: Mutex::new(HashMap::new()),
            next_conn: AtomicU64::new(1),
            connected: AtomicBool::new(false),
            link_up: Notify::new(),
        })
    }

    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::Relaxed)
    }

    pub fn next_conn_id(&self) -> ConnId {
        self.next_conn.fetch_add(1, Ordering::Relaxed)
    }

    fn send(&self, msg: EdgeToCore) -> bool {
        match &*self.outbound.lock() {
            Some(tx) => tx.send(msg).is_ok(),
            None => false,
        }
    }

    /// Wait until the link is up (or the timeout elapses).
    pub async fn wait_connected(&self, timeout: Duration) -> bool {
        if self.is_connected() {
            return true;
        }
        tokio::time::timeout(timeout, self.link_up.notified()).await.is_ok() && self.is_connected()
    }

    /// Register a new client socket and ask the core to authenticate it.
    pub async fn open(
        self: &Arc<Self>,
        conn_id: ConnId,
        authorization: Option<String>,
        peer: String,
        to_client: mpsc::UnboundedSender<CoreToEdge>,
        timeout: Duration,
    ) -> Result<Arc<ConnState>, (u16, String)> {
        if !self.wait_connected(timeout).await {
            return Err((
                503,
                r#"{"error":"realtime_unavailable","message":"Realtime is unavailable.","status":503}"#
                    .into(),
            ));
        }
        let state = Arc::new(ConnState {
            authorization: authorization.clone(),
            peer: peer.clone(),
            connected_at_ms: now_ms(),
            last_seen_ms: AtomicU64::new(now_ms() as u64),
            retained: Mutex::new(HashMap::new()),
            to_client,
        });
        let (tx, rx) = oneshot::channel();
        self.pending_open.lock().insert(conn_id, tx);
        self.conns.lock().insert(conn_id, state.clone());
        gauge!("edge_conns").set(self.conns.lock().len() as f64);
        if !self.send(EdgeToCore::WsOpen { conn_id, authorization, peer }) {
            self.pending_open.lock().remove(&conn_id);
            self.conns.lock().remove(&conn_id);
            return Err((
                503,
                r#"{"error":"realtime_unavailable","message":"Realtime is unavailable.","status":503}"#
                    .into(),
            ));
        }
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(Ok(()))) => Ok(state),
            Ok(Ok(Err(rej))) => {
                self.conns.lock().remove(&conn_id);
                gauge!("edge_conns").set(self.conns.lock().len() as f64);
                Err(rej)
            }
            _ => {
                self.pending_open.lock().remove(&conn_id);
                self.conns.lock().remove(&conn_id);
                gauge!("edge_conns").set(self.conns.lock().len() as f64);
                Err((
                    503,
                    r#"{"error":"realtime_unavailable","message":"Realtime is unavailable.","status":503}"#
                        .into(),
                ))
            }
        }
    }

    pub fn text(&self, conn_id: ConnId, text: &str) {
        if let Some(c) = self.conns.lock().get(&conn_id).cloned() {
            // Retain the newest state-carrying frames for replay.
            if let Some(key) = retain_key(text) {
                c.retained.lock().insert(key, text.to_string());
            }
        }
        counter!("edge_ws_frames_in_total").increment(1);
        self.send(EdgeToCore::WsText { conn_id, text: text.to_string() });
    }

    pub fn seen(&self, conn_id: ConnId) {
        let now = now_ms();
        let Some(c) = self.conns.lock().get(&conn_id).cloned() else { return };
        let prev = c.last_seen_ms.load(Ordering::Relaxed) as i64;
        c.last_seen_ms.store(now as u64, Ordering::Relaxed);
        // Batch: at most one WsSeen per 5 s per socket.
        if now - prev >= 5_000 {
            self.send(EdgeToCore::WsSeen { conn_id, at_ms: now });
        }
    }

    pub fn closed(&self, conn_id: ConnId, code: Option<u16>) {
        self.conns.lock().remove(&conn_id);
        self.pending_open.lock().remove(&conn_id);
        gauge!("edge_conns").set(self.conns.lock().len() as f64);
        self.send(EdgeToCore::WsClosed { conn_id, code });
    }

    /// Run the link forever: connect, replay, pump, reconnect with backoff.
    pub async fn run(self: Arc<Self>) {
        let mut backoff = Duration::from_millis(100);
        loop {
            match UnixStream::connect(&self.path).await {
                Ok(stream) => {
                    backoff = Duration::from_millis(100);
                    self.pump(stream).await;
                    self.connected.store(false, Ordering::Relaxed);
                    gauge!("edge_core_link_up").set(0.0);
                    *self.outbound.lock() = None;
                    tracing::warn!("core link lost; reconnecting");
                }
                Err(e) => {
                    tracing::debug!(error = %e, "core link connect failed");
                }
            }
            tokio::time::sleep(backoff).await;
            backoff = (backoff * 2).min(Duration::from_millis(500));
        }
    }

    async fn pump(self: &Arc<Self>, stream: UnixStream) {
        let (rh, wh) = stream.into_split();
        let mut reader = FramedRead::new(rh, IpcCodec::<CoreToEdge>::default());
        let mut writer = FramedWrite::new(wh, IpcCodec::<EdgeToCore>::default());
        let (tx, mut rx) = mpsc::unbounded_channel::<EdgeToCore>();
        *self.outbound.lock() = Some(tx.clone());
        let _ = tx.send(EdgeToCore::Hello {
            protocol: PROTOCOL_VERSION,
            edge_version: env!("CARGO_PKG_VERSION").into(),
        });
        // Replay every open socket (core restart), including those whose
        // open was still pending: they are re-opened as fresh WsOpen.
        let snapshot: Vec<(ConnId, Arc<ConnState>)> =
            self.conns.lock().iter().map(|(k, v)| (*k, v.clone())).collect();
        let pending: std::collections::HashSet<ConnId> = self.pending_open.lock().keys().copied().collect();
        let replay_count = snapshot.len() as u32;
        for (conn_id, c) in snapshot {
            if pending.contains(&conn_id) {
                let _ = tx.send(EdgeToCore::WsOpen {
                    conn_id,
                    authorization: c.authorization.clone(),
                    peer: c.peer.clone(),
                });
            } else {
                let retained: Vec<String> = c.retained.lock().values().cloned().collect();
                let _ = tx.send(EdgeToCore::WsAttach {
                    conn_id,
                    authorization: c.authorization.clone(),
                    peer: c.peer.clone(),
                    connected_at_ms: c.connected_at_ms,
                    last_seen_ms: c.last_seen_ms.load(Ordering::Relaxed) as i64,
                    retained,
                });
            }
        }
        let _ = tx.send(EdgeToCore::ReplayDone { count: replay_count });
        // `connected` flips only on ReplayAck: HTTP stays queued until the
        // core has applied every re-attach, so no publish can miss a socket.
        tracing::info!(replaying = replay_count, "core link connected; awaiting replay ack");
        let writer_task = tokio::spawn(async move {
            while let Some(msg) = rx.recv().await {
                tracing::debug!(kind = ?std::mem::discriminant(&msg), "edge → core");
                if let Err(e) = writer.send(msg).await {
                    tracing::warn!(error = %e, "core link write failed");
                    break;
                }
            }
        });
        while let Some(frame) = reader.next().await {
            let msg = match frame {
                Ok(m) => m,
                Err(e) => {
                    tracing::warn!(error = %e, "core link decode error");
                    break;
                }
            };
            match msg {
                CoreToEdge::HelloAck { protocol, core_version } => {
                    tracing::info!(protocol, core_version, "core hello ack");
                }
                CoreToEdge::ReplayAck { count } => {
                    self.connected.store(true, Ordering::Relaxed);
                    gauge!("edge_core_link_up").set(1.0);
                    self.link_up.notify_waiters();
                    tracing::info!(count, "core link ready (replay acked)");
                }
                CoreToEdge::WsAccept { conn_id } => {
                    if let Some(p) = self.pending_open.lock().remove(&conn_id) {
                        let _ = p.send(Ok(()));
                    }
                }
                CoreToEdge::WsReject { conn_id, status, body } => {
                    if let Some(p) = self.pending_open.lock().remove(&conn_id) {
                        let _ = p.send(Err((status, body)));
                    }
                }
                CoreToEdge::WsSend { conn_id, text } => {
                    counter!("edge_ws_frames_out_total").increment(1);
                    if let Some(c) = self.conns.lock().get(&conn_id).cloned() {
                        let _ = c.to_client.send(CoreToEdge::WsSend { conn_id, text });
                    }
                }
                CoreToEdge::WsClose { conn_id, code } => {
                    if let Some(c) = self.conns.lock().get(&conn_id).cloned() {
                        let _ = c.to_client.send(CoreToEdge::WsClose { conn_id, code });
                    }
                }
            }
        }
        // Drop every sender (including the registry's) so the writer ends.
        *self.outbound.lock() = None;
        drop(tx);
        writer_task.abort();
        let _ = writer_task.await;
    }
}

/// Which client frames carry replayable state, keyed so the newest wins.
fn retain_key(text: &str) -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(text).ok()?;
    let world = v.get("worldId")?.as_str()?;
    match v.get("type")?.as_str()? {
        "world-presence" => Some(format!("world-presence:{world}")),
        "host-players" => Some(format!("host-players:{world}")),
        _ => None,
    }
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod limits_tests {
    use super::Limits;

    #[test]
    fn per_ip_and_global_caps() {
        let l = Limits::new(3, 2);
        let a: std::net::IpAddr = "10.0.0.1".parse().unwrap();
        let b: std::net::IpAddr = "10.0.0.2".parse().unwrap();
        assert!(l.admit(a));
        assert!(l.admit(a));
        assert!(!l.admit(a), "per-ip cap");
        assert!(l.admit(b));
        assert!(!l.admit(b), "global cap");
        l.release(a);
        assert!(l.admit(b));
        l.release(a);
        l.release(b);
        l.release(b);
        l.release(b);
        assert!(l.admit(a));
    }
}
