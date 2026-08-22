//! `DELETE /account`: box-only full account deletion (no TS worker ancestor;
//! the lane-D forwarder passes it through).

use std::sync::Arc;

use axum::extract::State;
use axum::response::Response;
use axum::routing::delete;
use axum::Router;
use sw_core::time;

use crate::auth::Auth;
use crate::error::{ok_json, ApiResult};
use crate::state::AppState;

pub fn routes() -> Router<Arc<AppState>> {
    Router::new().route("/account", delete(delete_account))
}

async fn delete_account(State(state): State<Arc<AppState>>, Auth(ctx): Auth) -> ApiResult<Response> {
    let outcome = sw_core::service::account::delete_account_step(&state.svc(), &ctx, time::now()).await?;
    let inner = state.inner();
    for token in &outcome.invalidated_tokens {
        inner.sessions.invalidate(token).await;
    }
    Ok(ok_json(&outcome.response))
}
