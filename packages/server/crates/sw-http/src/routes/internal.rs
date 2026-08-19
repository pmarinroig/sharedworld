//! Box-only routes: the public `/healthz` (uptime checks, scripts) and the
//! secret-gated `/internal/*` API used by the lane-D relay worker.

use std::sync::Arc;

use axum::extract::State;
use axum::http::HeaderMap;
use axum::response::Response;
use axum::routing::{get, post};
use axum::Router;
use sw_core::HttpError;

use crate::auth::INTERNAL_SECRET_HEADER;
use crate::body::JsonBody;
use crate::error::{ok_json, ApiError, ApiResult};
use crate::state::AppState;

pub fn routes() -> Router<Arc<AppState>> {
    Router::new().route("/healthz", get(healthz)).route("/internal/relay/authorize", post(relay_authorize))
}

async fn healthz() -> Response {
    ok_json(&serde_json::json!({ "status": "ok" }))
}

fn require_internal_secret(state: &AppState, headers: &HeaderMap) -> Result<(), ApiError> {
    let expected = state.config.internal_api_secret.as_deref().filter(|s| !s.is_empty());
    let presented = headers.get(INTERNAL_SECRET_HEADER).and_then(|v| v.to_str().ok());
    match (expected, presented) {
        (Some(e), Some(p)) if e == p => Ok(()),
        _ => Err(ApiError(HttpError::new(404, "not_found", "Route not found."))),
    }
}

async fn relay_authorize(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    JsonBody(body): JsonBody<serde_json::Value>,
) -> ApiResult<Response> {
    require_internal_secret(&state, &headers)?;
    let token = body.get("token").and_then(|t| t.as_str()).unwrap_or("");
    Ok(ok_json(&sw_core::relay::authorize_relay_token(&state.svc(), token).await?))
}
