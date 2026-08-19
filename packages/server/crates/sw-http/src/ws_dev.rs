//! `/ws` in dev/TCP mode: the process terminates the WebSocket itself (the
//! edge does this in production). Answers the bare keepalive locally (the
//! edge-auto-response equivalent) and bridges frames to the gateway.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::State;
use axum::http::request::Parts;
use axum::response::{IntoResponse, Response};
use futures::{SinkExt, StreamExt};
use sw_contracts::{REALTIME_KEEPALIVE_REQUEST, REALTIME_KEEPALIVE_RESPONSE};
use sw_core::realtime::gateway::ConnSink;
use sw_core::{time, HttpError};
use tokio::sync::mpsc;

use crate::auth::{bearer_of, resolve_session};
use crate::error::ApiError;
use crate::state::{AppState, WsMode};

struct ChannelSink {
    tx: mpsc::UnboundedSender<Option<String>>,
    muted: Arc<AtomicBool>,
}

impl ConnSink for ChannelSink {
    fn send_text(&self, text: String) {
        if self.muted.load(Ordering::Relaxed) {
            return;
        }
        let _ = self.tx.send(Some(text));
    }
    fn close(&self) {
        let _ = self.tx.send(None);
    }
}

pub async fn ws_handler(
    State(state): State<Arc<AppState>>,
    ws: WebSocketUpgrade,
    parts: Parts,
) -> Result<Response, ApiError> {
    // Authenticate before upgrading (401 like every route).
    let token = bearer_of(&parts)?.to_string();
    let session = resolve_session(&state, &token).await?;
    if state.ws_mode() == WsMode::Reject {
        return Err(ApiError(
            HttpError::new(503, "realtime_unavailable", "Realtime is unavailable.").with_retry_after(2),
        ));
    }
    let blackhole = state.ws_mode() == WsMode::Blackhole;
    let player_uuid = session.player_uuid.clone();
    let inner = state.inner();
    let conn_id = state.next_conn_id();
    Ok(ws
        .max_message_size(256 * 1024)
        .max_frame_size(256 * 1024)
        .read_buffer_size(4 * 1024)
        .write_buffer_size(0)
        .max_write_buffer_size(512 * 1024)
        .on_upgrade(move |socket| async move {
            handle_socket(socket, inner, conn_id, player_uuid, blackhole).await;
        })
        .into_response())
}

async fn handle_socket(
    socket: WebSocket,
    inner: Arc<crate::state::AppStateInner>,
    conn_id: u64,
    player_uuid: String,
    blackhole: bool,
) {
    let gateway = inner.realtime.gateway.clone();
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Option<String>>();
    let muted = Arc::new(AtomicBool::new(blackhole));
    let conn_sink = Arc::new(ChannelSink { tx: tx.clone(), muted: muted.clone() });
    let writer = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            match msg {
                Some(text) => {
                    if sink.send(Message::Text(text.into())).await.is_err() {
                        break;
                    }
                }
                None => {
                    let _ = sink.send(Message::Close(None)).await;
                    break;
                }
            }
        }
    });
    gateway.attach(&player_uuid, conn_id, conn_sink.clone(), time::now(), None, !blackhole).await;
    while let Some(Ok(msg)) = stream.next().await {
        match msg {
            Message::Text(text) => {
                let text: &str = &text;
                if text == REALTIME_KEEPALIVE_REQUEST {
                    gateway.mark_seen(conn_id, time::now());
                    if !blackhole {
                        let _ = tx.send(Some(REALTIME_KEEPALIVE_RESPONSE.to_string()));
                    }
                    continue;
                }
                if !blackhole {
                    gateway.on_text(conn_id, text).await;
                }
            }
            Message::Close(_) => break,
            _ => {}
        }
    }
    gateway.detach(conn_id).await;
    let _ = tx.send(None);
    let _ = writer.await;
}
