//! `/ws` at the edge: authenticate via the core (WsOpen → accept/reject),
//! then own the socket: answer keepalives locally, forward text frames,
//! deliver core sends, report close.

use std::sync::Arc;

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{ConnectInfo, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use futures::{SinkExt, StreamExt};
use sw_ipc::CoreToEdge;
use tokio::sync::mpsc;

use crate::corelink::CoreLink;

const KEEPALIVE_REQUEST: &str = "sw-keepalive";
const KEEPALIVE_RESPONSE: &str = "sw-keepalive-ack";
const OPEN_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(10);

pub async fn ws_handler(
    State(link): State<Arc<CoreLink>>,
    ConnectInfo(peer): ConnectInfo<std::net::SocketAddr>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    let authorization =
        headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()).map(|s| s.to_string());
    if !link.limits.admit(peer.ip()) {
        metrics::counter!("edge_ws_rejected_total", "reason" => "limit").increment(1);
        let mut resp = (
            StatusCode::TOO_MANY_REQUESTS,
            r#"{"error":"too_many_connections","message":"Too many realtime connections. Retry later.","status":429}"#,
        )
            .into_response();
        resp.headers_mut()
            .insert(header::CONTENT_TYPE, HeaderValue::from_static("application/json; charset=utf-8"));
        resp.headers_mut().insert(header::RETRY_AFTER, HeaderValue::from_static("30"));
        return resp;
    }
    let conn_id = link.next_conn_id();
    let (to_client_tx, to_client_rx) = mpsc::unbounded_channel::<CoreToEdge>();
    match link.open(conn_id, authorization, peer.to_string(), to_client_tx, OPEN_TIMEOUT).await {
        Ok(_) => {
            let link2 = link.clone();
            // Frames are small JSON; tungstenite's default 128 KiB read buffer
            // per socket would dominate RSS at thousands of connections.
            ws.max_message_size(256 * 1024)
                .max_frame_size(256 * 1024)
                .read_buffer_size(4 * 1024)
                .write_buffer_size(0)
                .max_write_buffer_size(512 * 1024)
                .on_failed_upgrade({
                    let link3 = link.clone();
                    move |_| {
                        link3.closed(conn_id, None);
                        link3.limits.release(peer.ip());
                    }
                })
                .on_upgrade(move |socket| async move {
                    socket_loop(link2.clone(), conn_id, socket, to_client_rx).await;
                    link2.limits.release(peer.ip());
                })
                .into_response()
        }
        Err((status, body)) => {
            link.limits.release(peer.ip());
            let mut resp = (StatusCode::from_u16(status).unwrap_or(StatusCode::SERVICE_UNAVAILABLE), body)
                .into_response();
            resp.headers_mut()
                .insert(header::CONTENT_TYPE, HeaderValue::from_static("application/json; charset=utf-8"));
            if status == 503 {
                resp.headers_mut().insert(header::RETRY_AFTER, HeaderValue::from_static("2"));
            }
            resp
        }
    }
}

async fn socket_loop(
    link: Arc<CoreLink>,
    conn_id: u64,
    socket: WebSocket,
    mut from_core: mpsc::UnboundedReceiver<CoreToEdge>,
) {
    let (mut sink, mut stream) = socket.split();
    let (out_tx, mut out_rx) = mpsc::unbounded_channel::<Option<String>>();
    // Writer: frames from core + local keepalive acks, in order.
    let writer = tokio::spawn(async move {
        loop {
            tokio::select! {
                Some(msg) = out_rx.recv() => match msg {
                    Some(text) => { if sink.send(Message::Text(text.into())).await.is_err() { break; } }
                    None => { let _ = sink.send(Message::Close(None)).await; break; }
                },
                Some(msg) = from_core.recv() => match msg {
                    CoreToEdge::WsSend { text, .. } => { if sink.send(Message::Text(text.into())).await.is_err() { break; } }
                    CoreToEdge::WsClose { .. } => { let _ = sink.send(Message::Close(None)).await; break; }
                    _ => {}
                },
                else => break,
            }
        }
    });
    let mut close_code: Option<u16> = None;
    while let Some(Ok(msg)) = stream.next().await {
        match msg {
            Message::Text(text) => {
                let text: &str = &text;
                if text == KEEPALIVE_REQUEST {
                    link.seen(conn_id);
                    let _ = out_tx.send(Some(KEEPALIVE_RESPONSE.to_string()));
                    continue;
                }
                link.text(conn_id, text);
            }
            Message::Close(frame) => {
                close_code = frame.map(|f| f.code);
                break;
            }
            _ => {}
        }
    }
    link.closed(conn_id, close_code);
    let _ = out_tx.send(None);
    let _ = writer.await;
}
