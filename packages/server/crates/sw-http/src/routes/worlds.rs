//! `/worlds*`, `/invites/redeem` (`router/world-routes.ts`).

use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Path, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post, put};
use axum::Router;
use sw_contracts::*;
use sw_core::service::{members, worlds};
use sw_core::time;

use super::param;
use crate::auth::Auth;
use crate::body::JsonBody;
use crate::error::{json_response, no_content, ok_json, ApiResult};
use crate::state::AppState;

pub fn routes() -> Router<Arc<AppState>> {
    Router::new()
        .route("/worlds", get(list_worlds).post(create_world))
        .route("/worlds/{worldId}", get(get_world).patch(update_world).delete(delete_world))
        .route("/worlds/{worldId}/storage-usage", get(storage_usage))
        .route("/worlds/{worldId}/settings", put(update_settings))
        .route("/worlds/{worldId}/invites", post(create_invite))
        .route("/worlds/{worldId}/invites/reset", post(reset_invite))
        .route("/invites/redeem", post(redeem_invite))
        .route("/worlds/{worldId}/members/{playerUuid}", delete(kick_member).patch(set_member_permission))
}

fn if_none_match_satisfied(headers: &HeaderMap, etag: &str) -> bool {
    headers
        .get(header::IF_NONE_MATCH)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|h| h.split(',').map(str::trim).any(|v| v == etag || v == "*"))
}

fn not_modified(etag: &str) -> Response {
    let mut resp = StatusCode::NOT_MODIFIED.into_response();
    if let Ok(v) = HeaderValue::from_str(etag) {
        resp.headers_mut().insert(header::ETAG, v);
    }
    resp
}

fn with_etag(mut resp: Response, etag: &str) -> Response {
    if let Ok(v) = HeaderValue::from_str(etag) {
        resp.headers_mut().insert(header::ETAG, v);
    }
    resp
}

async fn list_worlds(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    headers: HeaderMap,
) -> ApiResult<Response> {
    let svc = state.svc();
    let etag = worlds::worlds_etag(&svc, &ctx).await?;
    if if_none_match_satisfied(&headers, &etag) {
        return Ok(not_modified(&etag));
    }
    Ok(with_etag(ok_json(&worlds::list_worlds(&svc, &ctx).await?), &etag))
}

async fn create_world(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    JsonBody(req): JsonBody<CreateWorldRequest>,
) -> ApiResult<Response> {
    Ok(json_response(
        StatusCode::CREATED,
        &worlds::create_world(&state.svc(), &ctx, &req, time::now()).await?,
    ))
}

async fn get_world(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    headers: HeaderMap,
    p: Path<HashMap<String, String>>,
) -> ApiResult<Response> {
    let world_id = param(&p, "worldId")?;
    let svc = state.svc();
    // A null etag means no access — fall through so the service raises its fresh 403/404.
    let etag = worlds::world_etag(&svc, &ctx, &world_id, time::now()).await?;
    if let Some(etag) = &etag {
        if if_none_match_satisfied(&headers, etag) {
            return Ok(not_modified(etag));
        }
    }
    let resp = ok_json(&worlds::get_world(&svc, &ctx, &world_id, time::now()).await?);
    Ok(match etag {
        Some(e) => with_etag(resp, &e),
        None => resp,
    })
}

async fn storage_usage(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: Path<HashMap<String, String>>,
) -> ApiResult<Response> {
    let world_id = param(&p, "worldId")?;
    Ok(ok_json(&worlds::get_storage_usage(&state.svc(), &ctx, &world_id).await?))
}

async fn update_world(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: Path<HashMap<String, String>>,
    JsonBody(req): JsonBody<UpdateWorldRequest>,
) -> ApiResult<Response> {
    let world_id = param(&p, "worldId")?;
    Ok(ok_json(&worlds::update_world(&state.svc(), &ctx, &world_id, &req).await?))
}

async fn update_settings(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: Path<HashMap<String, String>>,
    JsonBody(req): JsonBody<UpdateWorldSettingsRequest>,
) -> ApiResult<Response> {
    let world_id = param(&p, "worldId")?;
    Ok(ok_json(&worlds::update_world_settings(&state.svc(), &ctx, &world_id, &req).await?))
}

async fn delete_world(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: Path<HashMap<String, String>>,
) -> ApiResult<Response> {
    let world_id = param(&p, "worldId")?;
    worlds::delete_world(&state.svc(), &ctx, &world_id, time::now()).await?;
    Ok(no_content())
}

async fn create_invite(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: Path<HashMap<String, String>>,
) -> ApiResult<Response> {
    let world_id = param(&p, "worldId")?;
    Ok(json_response(
        StatusCode::CREATED,
        &members::create_invite(&state.svc(), &ctx, &world_id, time::now()).await?,
    ))
}

async fn reset_invite(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: Path<HashMap<String, String>>,
) -> ApiResult<Response> {
    let world_id = param(&p, "worldId")?;
    Ok(ok_json(&members::reset_invite(&state.svc(), &ctx, &world_id, time::now()).await?))
}

async fn redeem_invite(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    JsonBody(req): JsonBody<RedeemInviteRequest>,
) -> ApiResult<Response> {
    Ok(ok_json(&members::redeem_invite(&state.svc(), &ctx, &req, time::now()).await?))
}

async fn kick_member(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: Path<HashMap<String, String>>,
) -> ApiResult<Response> {
    let world_id = param(&p, "worldId")?;
    let player = param(&p, "playerUuid")?;
    Ok(ok_json(&members::kick_member(&state.svc(), &ctx, &world_id, &player, time::now()).await?))
}

async fn set_member_permission(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: Path<HashMap<String, String>>,
    JsonBody(req): JsonBody<serde_json::Value>,
) -> ApiResult<Response> {
    let world_id = param(&p, "worldId")?;
    let player = param(&p, "playerUuid")?;
    let can = req.get("canUseCommands").and_then(|v| v.as_bool()) == Some(true);
    Ok(ok_json(&members::set_member_command_permission(&state.svc(), &ctx, &world_id, &player, can).await?))
}
