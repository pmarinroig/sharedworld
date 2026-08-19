//! Snapshot, upload-plan and blob relay routes (`router/snapshot-routes.ts`).

use std::collections::HashMap;
use std::sync::Arc;

use axum::body::Body;
use axum::extract::{Path, Request, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post, put};
use axum::Router;
use futures::TryStreamExt;
use sw_contracts::*;
use sw_core::service::signer::{BLOB_STAMP_HEADER, HOST_TOKEN_HEADER, RUNTIME_EPOCH_HEADER};
use sw_core::service::sync_plan::{RelayDownloadInput, RelayUploadInput};
use sw_core::service::{snapshots, sync_plan};
use sw_core::{time, HttpError};

use super::{decode_storage_key, param};
use crate::auth::{Auth, AuthOrRelayToken};
use crate::body::JsonBody;
use crate::error::{no_content, ok_json, ApiError, ApiResult};
use crate::state::AppState;

pub fn routes() -> Router<Arc<AppState>> {
    Router::new()
        .route("/worlds/{worldId}/snapshots/latest-manifest", get(latest_manifest))
        .route("/worlds/{worldId}/snapshots", get(list_snapshots))
        .route("/worlds/{worldId}/snapshots/delete", post(delete_snapshots))
        .route("/worlds/{worldId}/snapshots/{snapshotId}/restore", post(restore_snapshot))
        .route("/worlds/{worldId}/snapshots/{snapshotId}", delete(delete_snapshot))
        .route("/worlds/{worldId}/uploads/prepare", post(prepare_uploads))
        .route("/worlds/{worldId}/uploads/finalize-snapshot", post(finalize_snapshot))
        .route("/worlds/{worldId}/downloads/plan", get(download_plan_legacy).post(download_plan))
        .route("/worlds/{worldId}/uploads/blob-session", post(blob_session))
        .route("/worlds/{worldId}/uploads/blob-commit", post(blob_commit))
        .route("/worlds/{worldId}/storage/blob/{*storageKey}", put(upload_blob).get(download_blob))
}

type P = Path<HashMap<String, String>>;

async fn latest_manifest(State(s): State<Arc<AppState>>, Auth(ctx): Auth, p: P) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&snapshots::latest_manifest(&s.svc(), &ctx, &w).await?))
}

async fn list_snapshots(State(s): State<Arc<AppState>>, Auth(ctx): Auth, p: P) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&snapshots::list_snapshots(&s.svc(), &ctx, &w).await?))
}

async fn restore_snapshot(State(s): State<Arc<AppState>>, Auth(ctx): Auth, p: P) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    let sid = param(&p, "snapshotId")?;
    Ok(ok_json(&snapshots::restore_snapshot(&s.svc(), &ctx, &w, &sid, time::now()).await?))
}

async fn delete_snapshots(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<DeleteSnapshotsRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    let ids: Vec<String> = req
        .snapshot_ids
        .as_ref()
        .and_then(|v| v.as_array())
        .map(|a| a.iter().filter_map(|x| x.as_str().map(|s| s.to_string())).collect())
        .unwrap_or_default();
    Ok(ok_json(&snapshots::delete_snapshots(&s.svc(), &ctx, &w, &ids).await?))
}

async fn delete_snapshot(State(s): State<Arc<AppState>>, Auth(ctx): Auth, p: P) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    let sid = param(&p, "snapshotId")?;
    Ok(ok_json(&snapshots::delete_snapshot(&s.svc(), &ctx, &w, &sid).await?))
}

async fn prepare_uploads(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<UploadPlanRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&sync_plan::prepare_uploads(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

async fn finalize_snapshot(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<FinalizeSnapshotRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&snapshots::finalize_snapshot(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

/// Legacy 0.3.0 shape: local state rides in `x-sharedworld-*` headers.
fn parse_download_plan_headers(headers: &HeaderMap) -> Result<UploadPlanRequest, ApiError> {
    let invalid = || {
        ApiError(HttpError::new(
            400,
            "invalid_download_plan_header",
            "download plan headers must be valid JSON.",
        ))
    };
    let h = |name: &str| headers.get(name).and_then(|v| v.to_str().ok()).filter(|s| !s.is_empty());
    let files: Vec<LocalFileDescriptor> = match h("x-sharedworld-files") {
        Some(v) => serde_json::from_str(v).map_err(|_| invalid())?,
        None => vec![],
    };
    let non_region_pack: Option<LocalPackDescriptor> = match h("x-sharedworld-pack") {
        Some(v) => serde_json::from_str(v).map_err(|_| invalid())?,
        None => None,
    };
    let region_bundles: Option<Vec<LocalPackDescriptor>> = match h("x-sharedworld-region-bundles") {
        Some(v) => Some(serde_json::from_str(v).map_err(|_| invalid())?),
        None => Some(vec![]),
    };
    Ok(UploadPlanRequest { runtime_epoch: None, host_token: None, files, non_region_pack, region_bundles })
}

async fn download_plan_legacy(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    headers: HeaderMap,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    let req = parse_download_plan_headers(&headers)?;
    Ok(ok_json(&sync_plan::download_plan(&s.svc(), &ctx, &w, &req).await?))
}

async fn download_plan(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<UploadPlanRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&sync_plan::download_plan(&s.svc(), &ctx, &w, &req).await?))
}

async fn blob_session(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<CreateBlobSessionRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&sync_plan::create_blob_upload_session(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

async fn blob_commit(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    JsonBody(req): JsonBody<CommitBlobSessionRequest>,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    Ok(ok_json(&sync_plan::commit_blob_upload_session(&s.svc(), &ctx, &w, &req, time::now()).await?))
}

fn header_str(headers: &HeaderMap, name: &str) -> Option<String> {
    headers.get(name).and_then(|v| v.to_str().ok()).map(|s| s.to_string())
}

async fn upload_blob(
    State(s): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: P,
    req: Request,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    let key = decode_storage_key(&param(&p, "storageKey")?)?;
    let headers = req.headers().clone();
    let content_length = header_str(&headers, "content-length").and_then(|v| v.parse::<i64>().ok());
    let body = req.into_body().into_data_stream().map_err(std::io::Error::other);
    let input = RelayUploadInput {
        content_length,
        content_type: header_str(&headers, "content-type"),
        runtime_epoch: header_str(&headers, RUNTIME_EPOCH_HEADER).and_then(|v| v.parse().ok()),
        host_token: header_str(&headers, HOST_TOKEN_HEADER),
        blob_stamp: header_str(&headers, BLOB_STAMP_HEADER),
        body: Box::pin(body),
    };
    sync_plan::upload_storage_blob(&s.svc(), &ctx, &w, &key, input, time::now()).await?;
    Ok(no_content())
}

async fn download_blob(
    State(s): State<Arc<AppState>>,
    AuthOrRelayToken(ctx): AuthOrRelayToken,
    p: P,
    headers: HeaderMap,
) -> ApiResult<Response> {
    let w = param(&p, "worldId")?;
    let key = decode_storage_key(&param(&p, "storageKey")?)?;
    let input = RelayDownloadInput {
        range: header_str(&headers, "range"),
        blob_stamp: header_str(&headers, BLOB_STAMP_HEADER),
    };
    let blob = sync_plan::download_storage_blob(&s.svc(), &ctx, &w, &key, &input, time::now()).await?;
    let mut resp = Response::new(Body::from_stream(blob.body));
    *resp.status_mut() = StatusCode::from_u16(blob.status).unwrap_or(StatusCode::OK);
    let h = resp.headers_mut();
    if let Ok(v) = HeaderValue::from_str(&blob.content_type) {
        h.insert(header::CONTENT_TYPE, v);
    }
    h.insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
    if let Some(size) = blob.size {
        if let Ok(v) = HeaderValue::from_str(&size.to_string()) {
            h.insert(header::CONTENT_LENGTH, v);
        }
    }
    if let Some(cr) = &blob.content_range {
        if let Ok(v) = HeaderValue::from_str(cr) {
            h.insert(header::CONTENT_RANGE, v);
        }
    }
    Ok(resp.into_response())
}
