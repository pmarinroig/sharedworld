//! HTTP layer: axum router over `sw-core` services, auth extractor, error
//! mapping, metrics, the dev-mode WebSocket handler, internal/testkit routes.

pub mod app;
pub mod auth;
pub mod body;
pub mod bootstrap;
pub mod error;
pub mod ipc_server;
pub mod metrics;
pub mod routes;
pub mod state;
pub mod ws_dev;

pub use app::{build_metrics_router, build_router};
pub use state::{AppState, AppStateInner, WsMode};
