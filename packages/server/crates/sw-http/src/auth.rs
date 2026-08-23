//! Bearer authentication (`authenticate` in `router/shared.ts`) as an axum
//! extractor. Builds the `RequestContext` (entry origin, client version,
//! post-response deferrer).

use std::sync::Arc;

use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use sw_contracts::SessionToken;
use sw_core::request::{BoxFuture, RequestContext};
use sw_core::{time, HttpError};

use crate::error::ApiError;
use crate::state::AppState;

pub const CLIENT_VERSION_HEADER: &str = "x-sharedworld-version";
pub const ENTRY_ORIGIN_HEADER: &str = "x-sw-entry-origin";
pub const INTERNAL_SECRET_HEADER: &str = "x-sw-internal-secret";

pub struct Auth(pub RequestContext);

/// The request's origin as the client addressed it: the CF forwarder's
/// `x-sw-entry-origin` (only with the internal secret), else scheme + host
/// from the request itself.
pub fn request_origin(parts: &Parts, state: &AppState) -> Option<String> {
    let h = |name: &str| parts.headers.get(name).and_then(|v| v.to_str().ok()).map(|s| s.to_string());
    if let (Some(origin), Some(secret), Some(expected)) =
        (h(ENTRY_ORIGIN_HEADER), h(INTERNAL_SECRET_HEADER), state.config.internal_api_secret.as_deref())
    {
        if secret == expected && !origin.is_empty() {
            return Some(origin.trim_end_matches('/').to_string());
        }
    }
    let host = h("host")?;
    let proto = h("x-forwarded-proto").unwrap_or_else(|| "http".into());
    Some(format!("{proto}://{host}"))
}

fn spawning_deferrer() -> Arc<dyn Fn(BoxFuture) + Send + Sync> {
    Arc::new(|fut: BoxFuture| {
        tokio::spawn(fut);
    })
}

/// Resolve a bearer token to a session (cache → repository), enforcing expiry.
pub async fn resolve_session(state: &AppState, token: &str) -> Result<Arc<SessionToken>, HttpError> {
    let inner = state.inner();
    let session = match inner.sessions.get(token).await {
        Some(s) => s,
        None => {
            let fresh = inner
                .svc
                .auth
                .get_session(token)
                .await?
                .ok_or_else(|| HttpError::new(401, "invalid_session", "Session token is invalid."))?;
            let fresh = Arc::new(fresh);
            inner.sessions.put(fresh.clone()).await;
            fresh
        }
    };
    if time::parse_iso(&session.expires_at).is_none_or(|t| t < time::now()) {
        return Err(HttpError::new(401, "expired_session", "Session token has expired."));
    }
    Ok(session)
}

pub fn bearer_of(parts: &Parts) -> Result<&str, HttpError> {
    let header = parts.headers.get("authorization").and_then(|v| v.to_str().ok()).unwrap_or("");
    header
        .strip_prefix("Bearer ")
        .ok_or_else(|| HttpError::new(401, "missing_auth", "Authorization header is required."))
}

/// Like [`Auth`], but a valid lane-D relay token (`x-sharedworld-relay-token`,
/// verified with the box's own public key) also authenticates; the CF relay
/// forwards blob GETs it could not serve itself without the client's bearer
/// (new clients never attach one to the relay origin).
pub struct AuthOrRelayToken(pub RequestContext);

impl FromRequestParts<Arc<AppState>> for AuthOrRelayToken {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &Arc<AppState>) -> Result<Self, Self::Rejection> {
        if parts.headers.contains_key("authorization") {
            return Auth::from_request_parts(parts, state).await.map(|Auth(ctx)| AuthOrRelayToken(ctx));
        }
        let token =
            parts.headers.get(sw_core::service::signer::RELAY_TOKEN_HEADER).and_then(|v| v.to_str().ok());
        let keys = state.svc().relay_keys.clone();
        if let (Some(token), Some(keys)) = (token, keys) {
            if let Some(claims) =
                sw_core::relay::RelayKeys::verify(&keys.verifying_key_b64(), token, time::now())
            {
                return Ok(AuthOrRelayToken(RequestContext {
                    player_uuid: claims.p,
                    player_name: String::new(),
                    request_origin: request_origin(parts, state),
                    client_version: parts
                        .headers
                        .get(CLIENT_VERSION_HEADER)
                        .and_then(|v| v.to_str().ok())
                        .map(|s| s.to_string()),
                    defer: Some(spawning_deferrer()),
                }));
            }
        }
        Err(ApiError(HttpError::new(401, "missing_auth", "Authorization header is required.")))
    }
}

impl FromRequestParts<Arc<AppState>> for Auth {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &Arc<AppState>) -> Result<Self, Self::Rejection> {
        let token = bearer_of(parts)?.to_string();
        let session = resolve_session(state, &token).await?;
        Ok(Auth(RequestContext {
            player_uuid: session.player_uuid.clone(),
            player_name: session.player_name.clone(),
            request_origin: request_origin(parts, state),
            client_version: parts
                .headers
                .get(CLIENT_VERSION_HEADER)
                .and_then(|v| v.to_str().ok())
                .map(|s| s.to_string()),
            defer: Some(spawning_deferrer()),
        }))
    }
}
