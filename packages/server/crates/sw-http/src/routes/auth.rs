//! `/auth/*` (`router/auth-routes.ts`). No bearer auth.

use std::sync::Arc;

use axum::extract::State;
use axum::response::Response;
use axum::routing::post;
use axum::Router;
use sw_contracts::*;
use sw_core::time;

use crate::body::JsonBody;
use crate::error::{ok_json, ApiError, ApiResult};
use crate::state::AppState;

pub fn routes() -> Router<Arc<AppState>> {
    Router::new()
        .route("/auth/challenge", post(challenge))
        .route("/auth/complete", post(complete))
        .route("/auth/complete-cert", post(complete_cert))
        .route("/auth/dev-complete", post(dev_complete))
}

async fn challenge(State(state): State<Arc<AppState>>) -> ApiResult<Response> {
    Ok(ok_json(&state.svc().auth.create_challenge(time::now()).await?))
}

async fn complete(
    State(state): State<Arc<AppState>>,
    _body: JsonBody<serde_json::Value>,
) -> ApiResult<Response> {
    Err(ApiError(state.svc().auth.complete_auth()))
}

async fn complete_cert(
    State(state): State<Arc<AppState>>,
    JsonBody(req): JsonBody<AuthCompleteCertRequest>,
) -> ApiResult<Response> {
    Ok(ok_json(&state.svc().auth.complete_cert_auth(&req, time::now()).await?))
}

async fn dev_complete(
    State(state): State<Arc<AppState>>,
    JsonBody(req): JsonBody<DevAuthCompleteRequest>,
) -> ApiResult<Response> {
    Ok(ok_json(&state.svc().auth.complete_dev_auth(&req, time::now()).await?))
}
