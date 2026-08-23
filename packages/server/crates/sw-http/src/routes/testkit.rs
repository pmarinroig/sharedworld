//! Test-only routes (`/__test/*`, `/__fake-drive/*`) mirroring the Bun
//! integration harness. Compiled only with the `testkit` feature AND enabled
//! at runtime with `config.test_routes`; a production binary 404s them.

use std::collections::VecDeque;
use std::sync::Arc;

use axum::extract::{Request, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{any, get, post};
use axum::Router;
use parking_lot::Mutex;
use serde::Serialize;

use crate::error::ok_json;
use crate::state::{AppState, WsMode};

/// Ring buffer of every non-`__test` request, attributed to the bearer's player.
#[derive(Default)]
pub struct RequestLog {
    entries: Mutex<VecDeque<RequestLogEntry>>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestLogEntry {
    pub at: String,
    pub player_uuid: Option<String>,
    pub method: String,
    pub path: String,
    pub status: u16,
}

const REQUEST_LOG_CAPACITY: usize = 5_000;

impl RequestLog {
    pub fn push(&self, entry: RequestLogEntry) {
        let mut q = self.entries.lock();
        if q.len() >= REQUEST_LOG_CAPACITY {
            q.pop_front();
        }
        q.push_back(entry);
    }
    pub fn reset(&self) {
        self.entries.lock().clear();
    }
    pub fn snapshot(&self) -> Vec<RequestLogEntry> {
        self.entries.lock().iter().cloned().collect()
    }
}

/// Storage introspection for `/__test/storage` (implemented by the fake Drive provider).
pub trait TestStorageInspector: Send + Sync {
    fn snapshot(&self) -> serde_json::Value;
}

/// Drive failure injection (`POST /__test/drive-mode`).
pub trait DriveFailureControl: Send + Sync {
    /// mode: "normal" | "storage-full" | "reauth-required"; fail_count None = sticky.
    fn set_drive_fail_mode(&self, mode: &str, fail_count: Option<u32>) -> Result<(), String>;
}

/// The in-process fake S3 service (`GET /__test/s3`): endpoint + creds +
/// object listing for the e2e orchestrator.
pub trait S3TestInfo: Send + Sync {
    fn s3_info(&self) -> serde_json::Value;
}

/// Fake Drive resumable-upload endpoint (`/__fake-drive/upload/:id`).
#[async_trait::async_trait]
pub trait FakeDriveUploads: Send + Sync {
    async fn handle_upload_request(
        &self,
        method: &str,
        upload_id: &str,
        headers: &axum::http::HeaderMap,
        body: bytes::Bytes,
    ) -> Response;
}

/// Middleware recording every non-`__test` request (testkit only).
pub async fn request_log_middleware(
    State(state): State<Arc<AppState>>,
    req: Request,
    next: axum::middleware::Next,
) -> Response {
    let path = req.uri().path().to_string();
    if path.starts_with("/__test/") {
        return next.run(req).await;
    }
    let method = req.method().to_string();
    let token = req
        .headers()
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|h| h.strip_prefix("Bearer "))
        .map(|s| s.to_string());
    let player_uuid = match token {
        Some(t) => crate::auth::resolve_session(&state, &t).await.ok().map(|s| s.player_uuid.clone()),
        None => None,
    };
    let resp = next.run(req).await;
    state.request_log.push(RequestLogEntry {
        at: sw_core::time::now_iso(),
        player_uuid,
        method,
        path,
        status: resp.status().as_u16(),
    });
    resp
}

pub fn routes() -> Router<Arc<AppState>> {
    Router::new()
        .route("/__test/health", get(|| async { ok_json(&serde_json::json!({"status": "ok"})) }))
        .route("/__test/reset", post(reset))
        .route("/__test/storage", get(storage))
        .route("/__test/cert-signing-key", get(cert_signing_key))
        .route("/__test/ws-mode", post(ws_mode))
        .route("/__test/drive-mode", post(drive_mode))
        .route("/__test/s3", get(s3_info))
        .route("/__test/request-log", get(request_log))
        .route("/__test/request-log/reset", post(request_log_reset))
        .route("/__fake-drive/upload/{uploadId}", any(fake_drive_upload))
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct DriveModeRequest {
    mode: String,
    #[serde(default)]
    fail_count: Option<u32>,
}

async fn drive_mode(
    State(state): State<Arc<AppState>>,
    axum::Json(req): axum::Json<DriveModeRequest>,
) -> Response {
    match &state.inner().drive_fail {
        Some(control) => match control.set_drive_fail_mode(&req.mode, req.fail_count) {
            Ok(()) => ok_json(&serde_json::json!({"status": "ok", "mode": req.mode})),
            Err(message) => (StatusCode::BAD_REQUEST, message).into_response(),
        },
        None => (StatusCode::NOT_FOUND, "no drive failure control installed").into_response(),
    }
}

async fn s3_info(State(state): State<Arc<AppState>>) -> Response {
    match &state.inner().test_s3 {
        Some(s3) => ok_json(&s3.s3_info()),
        None => (StatusCode::NOT_FOUND, "no fake s3 installed").into_response(),
    }
}

async fn reset(State(state): State<Arc<AppState>>) -> Response {
    match &state.rebuild {
        Some(rebuild) => {
            let inner = rebuild().await;
            state.replace_inner(inner);
            state.request_log.reset();
            ok_json(&serde_json::json!({"status": "reset"}))
        }
        None => (StatusCode::NOT_IMPLEMENTED, "reset not available").into_response(),
    }
}

async fn storage(State(state): State<Arc<AppState>>) -> Response {
    match &state.inner().test_storage {
        Some(inspector) => ok_json(&inspector.snapshot()),
        None => {
            ok_json(&serde_json::json!({"provider": "r2", "objects": [], "uploads": [], "downloads": []}))
        }
    }
}

async fn cert_signing_key(State(state): State<Arc<AppState>>) -> Response {
    match &state.inner().test_cert_private_key_pkcs8_b64 {
        Some(key) => ok_json(&serde_json::json!({"privateKeyPkcs8": key})),
        None => (StatusCode::NOT_FOUND, "no test signing key").into_response(),
    }
}

async fn ws_mode(State(state): State<Arc<AppState>>, body: String) -> Response {
    let mode = serde_json::from_str::<serde_json::Value>(&body)
        .ok()
        .and_then(|v| v.get("mode").and_then(|m| m.as_str()).and_then(WsMode::parse));
    match mode {
        Some(m) => {
            state.ws_mode.store(m as u8, std::sync::atomic::Ordering::Relaxed);
            ok_json(&serde_json::json!({"status": "ok"}))
        }
        None => (StatusCode::BAD_REQUEST, "mode must be normal|blackhole|reject").into_response(),
    }
}

async fn request_log(State(state): State<Arc<AppState>>) -> Response {
    ok_json(&serde_json::json!({ "requests": state.request_log.snapshot() }))
}

async fn request_log_reset(State(state): State<Arc<AppState>>) -> Response {
    state.request_log.reset();
    ok_json(&serde_json::json!({"status": "reset"}))
}

async fn fake_drive_upload(State(state): State<Arc<AppState>>, req: Request) -> Response {
    let Some(uploads) = state.inner().fake_drive.clone() else {
        return (StatusCode::NOT_FOUND, "fake drive not configured").into_response();
    };
    let upload_id = req.uri().path().rsplit('/').next().unwrap_or("").to_string();
    let method = req.method().to_string();
    let headers = req.headers().clone();
    let body = match axum::body::to_bytes(req.into_body(), 4 * 1024 * 1024 * 1024).await {
        Ok(b) => b,
        Err(_) => return (StatusCode::BAD_REQUEST, "body read failed").into_response(),
    };
    uploads.handle_upload_request(&method, &upload_id, &headers, body).await
}
