//! Router assembly (`router.ts`): pattern-before-method semantics (unknown
//! method on a known path is 404), auth per route, metrics middleware,
//! optional testkit routes.

use std::sync::Arc;

use axum::http::StatusCode;
use axum::middleware;
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use sw_core::HttpError;

use crate::error::ApiError;
use crate::state::AppState;

async fn not_found() -> Response {
    ApiError(HttpError::new(404, "not_found", "Route not found.")).into_response()
}

async fn metrics_endpoint() -> Response {
    let handle = crate::metrics::install_recorder();
    (StatusCode::OK, [("content-type", "text/plain; version=0.0.4; charset=utf-8")], handle.render())
        .into_response()
}

/// Build the application router. The `/__test/*` routes are compiled with the
/// `testkit` feature and only mounted when `config.test_routes` is set.
pub fn build_router(state: Arc<AppState>) -> Router {
    let mut router = Router::new()
        .merge(crate::routes::auth::routes())
        .merge(crate::routes::storage::routes())
        .merge(crate::routes::worlds::routes())
        .merge(crate::routes::runtime::routes())
        .route("/ws", get(crate::ws_dev::ws_handler));
    if let Some(extra) = crate::routes::extra_routes() {
        router = router.merge(extra);
    }
    #[cfg(feature = "testkit")]
    if state.config.test_routes {
        router = router.merge(crate::routes::testkit::routes());
    }
    #[allow(unused_mut)]
    let mut router = router
        .fallback(not_found)
        .method_not_allowed_fallback(not_found)
        .layer(middleware::from_fn_with_state(state.clone(), crate::metrics::request_metrics));
    #[cfg(feature = "testkit")]
    if state.config.test_routes {
        router = router.layer(middleware::from_fn_with_state(
            state.clone(),
            crate::routes::testkit::request_log_middleware,
        ));
    }
    router.with_state(state)
}

/// A separate, loopback-only router for `/metrics` (and health).
pub fn build_metrics_router() -> Router {
    Router::new().route("/metrics", get(metrics_endpoint)).route("/healthz", get(|| async { "ok" }))
}
