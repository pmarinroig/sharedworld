//! Session/runtime routes (`router/runtime-routes.ts`); `/ws` lives in `ws_dev.rs`.

use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Path, State};
use axum::response::Response;
use axum::routing::{get, post};
use axum::Router;
use sw_contracts::*;
use sw_core::service::{session, worlds};
use sw_core::time;

use super::param;
use crate::auth::Auth;
use crate::body::JsonBody;
use crate::error::{ok_json, ApiResult};
use crate::state::AppState;

pub fn routes() -> Router<Arc<AppState>> {
    Router::new()
        .route("/worlds/{worldId}/session/enter", post(enter))
        .route("/worlds/{worldId}/runtime", get(runtime_status))
        .route("/worlds/{worldId}/session/waiting/observe", post(observe))
        .route("/worlds/{worldId}/session/waiting/cancel", post(cancel))
        .route("/worlds/{worldId}/heartbeat", post(heartbeat))
        .route("/worlds/{worldId}/host-gamerules", post(host_gamerules))
        .route("/worlds/{worldId}/host-startup-progress", post(startup_progress))
        .route("/worlds/{worldId}/presence", post(presence))
        .route("/worlds/{worldId}/begin-finalization", post(begin_finalization))
        .route("/worlds/{worldId}/complete-finalization", post(complete_finalization))
        .route("/worlds/{worldId}/abandon-finalization", post(abandon_finalization))
        .route("/worlds/{worldId}/release-host", post(release_host))
}

type P = Path<HashMap<String, String>>;

async fn enter(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<EnterSessionRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&session::enter_session(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

async fn runtime_status(State(s): State<Arc<AppState>>, Auth(ctx): Auth, p: P) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&session::runtime_status(&s.svc(), &ctx, &w, time::now()).await?))
}

async fn observe(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<ObserveWaitingRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&session::observe_waiting(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

async fn cancel(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<CancelWaitingRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&session::cancel_waiting(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

async fn heartbeat(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<HeartbeatRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&session::heartbeat_host(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

async fn host_gamerules(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<HostGameRulesReportRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&worlds::report_host_game_rules(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

async fn startup_progress(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<HostStartupProgressRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&session::set_host_startup_progress(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

async fn presence(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<PresenceHeartbeatRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&session::set_player_presence(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

async fn begin_finalization(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<BeginFinalizationRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&session::begin_finalization(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

async fn complete_finalization(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<CompleteFinalizationRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&session::complete_finalization(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

async fn abandon_finalization(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    _body: JsonBody<serde_json::Value>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&session::abandon_finalization(&s.svc(), &ctx, &w, time::now()).await?))
}

async fn release_host(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<ReleaseHostRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    let result = session::release_host(&s.svc(), &ctx, &w, &req, time::now()).await?;
    Ok(ok_json(&serde_json::json!({
        "worldId": result.world_id,
        "releasedAt": result.released_at,
        "graceful": result.graceful,
        "nextHostUuid": result.next_host_uuid,
        "nextHostPlayerName": result.next_host_player_name,
    })))
}
