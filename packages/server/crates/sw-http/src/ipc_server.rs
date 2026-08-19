//! Core side of the edge↔core WebSocket multiplexing link (`sw-ipc`): one
//! Unix socket listener; per edge connection, frames are demultiplexed onto
//! the gateway with an IPC-backed [`ConnSink`] per client socket.

use std::collections::HashSet;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use futures::{SinkExt, StreamExt};
use parking_lot::Mutex;
use sw_core::realtime::gateway::ConnSink;
use sw_core::time;
use sw_ipc::{CoreToEdge, EdgeToCore, IpcCodec, PROTOCOL_VERSION};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::mpsc;
use tokio_util::codec::{FramedRead, FramedWrite};

use crate::auth::resolve_session;
use crate::state::{AppState, WsMode};

struct IpcSink {
    conn_id: u64,
    tx: mpsc::UnboundedSender<CoreToEdge>,
}

impl ConnSink for IpcSink {
    fn send_text(&self, text: String) {
        let _ = self.tx.send(CoreToEdge::WsSend { conn_id: self.conn_id, text });
    }
    fn close(&self) {
        let _ = self.tx.send(CoreToEdge::WsClose { conn_id: self.conn_id, code: None });
    }
}

static LINK_IDS: AtomicU64 = AtomicU64::new(1);

/// Serve the WS IPC link on `path` forever (removes a stale socket file first).
pub async fn serve_ws_ipc(state: Arc<AppState>, path: &Path) -> std::io::Result<()> {
    if path.exists() {
        let _ = std::fs::remove_file(path);
    }
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let listener = UnixListener::bind(path)?;
    tracing::info!(path = %path.display(), "swcore WS IPC listening");
    loop {
        let (stream, _) = listener.accept().await?;
        let state = state.clone();
        tokio::spawn(async move {
            let link_id = LINK_IDS.fetch_add(1, Ordering::Relaxed);
            tracing::info!(link_id, "edge link connected");
            handle_link(state, stream, link_id).await;
            tracing::info!(link_id, "edge link closed");
        });
    }
}

/// Core-side conn ids namespace the edge's ids by link so an edge restart
/// (ids start over) can never alias a stale socket.
fn core_conn_id(link_id: u64, conn_id: u64) -> u64 {
    (link_id << 40) | (conn_id & ((1u64 << 40) - 1))
}

async fn handle_link(state: Arc<AppState>, stream: UnixStream, link_id: u64) {
    let (read_half, write_half) = stream.into_split();
    let mut reader = FramedRead::new(read_half, IpcCodec::<EdgeToCore>::default());
    let mut writer = FramedWrite::new(write_half, IpcCodec::<CoreToEdge>::default());
    let (tx, mut rx) = mpsc::unbounded_channel::<CoreToEdge>();
    let writer_task = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if writer.send(msg).await.is_err() {
                break;
            }
        }
    });
    let open: Arc<Mutex<HashSet<u64>>> = Arc::new(Mutex::new(HashSet::new()));
    while let Some(frame) = reader.next().await {
        let msg = match frame {
            Ok(m) => m,
            Err(e) => {
                tracing::warn!(link_id, error = %e, "edge link decode error");
                break;
            }
        };
        tracing::debug!(link_id, kind = ?std::mem::discriminant(&msg), "edge frame");
        match msg {
            EdgeToCore::Hello { protocol, edge_version } => {
                tracing::info!(link_id, protocol, edge_version, "edge hello");
                let _ = tx.send(CoreToEdge::HelloAck {
                    protocol: PROTOCOL_VERSION,
                    core_version: env!("CARGO_PKG_VERSION").into(),
                });
            }
            EdgeToCore::WsOpen { conn_id, authorization, peer } => {
                let cid = core_conn_id(link_id, conn_id);
                match authenticate(&state, authorization.as_deref()).await {
                    Ok(player_uuid) => {
                        let _ = tx.send(CoreToEdge::WsAccept { conn_id });
                        open.lock().insert(cid);
                        let sink = Arc::new(IpcSink { conn_id, tx: tx.clone() });
                        let _ = peer;
                        state
                            .inner()
                            .realtime
                            .gateway
                            .attach(&player_uuid, cid, sink, time::now(), None, true)
                            .await;
                    }
                    Err(e) => {
                        let body = serde_json::to_string(&e.shape()).expect("json");
                        let _ = tx.send(CoreToEdge::WsReject { conn_id, status: e.status, body });
                    }
                }
            }
            EdgeToCore::WsAttach {
                conn_id, authorization, connected_at_ms, last_seen_ms, retained, ..
            } => {
                let cid = core_conn_id(link_id, conn_id);
                let auth = authenticate(&state, authorization.as_deref()).await;
                tracing::info!(
                    link_id,
                    conn_id,
                    retained = retained.len(),
                    ok = auth.is_ok(),
                    "edge re-attached socket"
                );
                match auth {
                    Ok(player_uuid) => {
                        open.lock().insert(cid);
                        let sink = Arc::new(IpcSink { conn_id, tx: tx.clone() });
                        let gateway = state.inner().realtime.gateway.clone();
                        // No welcome on re-attach: the client already has one;
                        // the watch pokes re-derive host link state.
                        gateway
                            .attach(
                                &player_uuid,
                                cid,
                                sink,
                                time::from_millis(connected_at_ms),
                                Some(time::from_millis(last_seen_ms)),
                                false,
                            )
                            .await;
                        for text in retained {
                            gateway.on_text(cid, &text).await;
                        }
                        metrics::counter!("edge_replayed_conns_total").increment(1);
                    }
                    Err(_) => {
                        // Expired/invalid session while the core was down: the
                        // client reconnects with a fresh token.
                        let _ = tx.send(CoreToEdge::WsClose { conn_id, code: Some(1008) });
                    }
                }
            }
            EdgeToCore::ReplayDone { count } => {
                let _ = tx.send(CoreToEdge::ReplayAck { count });
            }
            EdgeToCore::WsText { conn_id, text } => {
                state.inner().realtime.gateway.on_text(core_conn_id(link_id, conn_id), &text).await;
            }
            EdgeToCore::WsSeen { conn_id, at_ms } => {
                state
                    .inner()
                    .realtime
                    .gateway
                    .mark_seen(core_conn_id(link_id, conn_id), time::from_millis(at_ms));
            }
            EdgeToCore::WsClosed { conn_id, .. } => {
                let cid = core_conn_id(link_id, conn_id);
                open.lock().remove(&cid);
                state.inner().realtime.gateway.detach(cid).await;
            }
        }
    }
    // Edge gone: every socket it held is gone with it.
    let ids: Vec<u64> = open.lock().drain().collect();
    let gateway = state.inner().realtime.gateway.clone();
    for cid in ids {
        gateway.detach(cid).await;
    }
    drop(tx);
    writer_task.abort();
    let _ = writer_task.await;
}

async fn authenticate(state: &AppState, authorization: Option<&str>) -> Result<String, sw_core::HttpError> {
    if state.ws_mode() == WsMode::Reject {
        return Err(sw_core::HttpError::new(503, "realtime_unavailable", "Realtime is unavailable.")
            .with_retry_after(2));
    }
    let token = authorization
        .and_then(|h| h.strip_prefix("Bearer "))
        .ok_or_else(|| sw_core::HttpError::new(401, "missing_auth", "Authorization header is required."))?;
    Ok(resolve_session(state, token).await?.player_uuid.clone())
}
