//! Prometheus exporter + per-request accounting middleware (route label from
//! the matched path; DB rows attributed via the `sw_db::pool::ROUTE` task-local).

use std::sync::Arc;
use std::time::Instant;

use axum::extract::{MatchedPath, Request, State};
use axum::middleware::Next;
use axum::response::Response;
use metrics::{counter, gauge, histogram};
use metrics_exporter_prometheus::{PrometheusBuilder, PrometheusHandle};

use crate::auth::CLIENT_VERSION_HEADER;
use crate::error::ErrorInfo;
use crate::state::AppState;

/// Install the global recorder once; returns the handle that renders `/metrics`.
pub fn install_recorder() -> PrometheusHandle {
    static HANDLE: std::sync::OnceLock<PrometheusHandle> = std::sync::OnceLock::new();
    HANDLE
        .get_or_init(|| {
            PrometheusBuilder::new()
                .set_buckets_for_metric(
                    metrics_exporter_prometheus::Matcher::Suffix("duration_seconds".into()),
                    &[0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0],
                )
                .expect("buckets")
                .install_recorder()
                .expect("prometheus recorder")
        })
        .clone()
}

fn client_label(v: Option<&str>) -> String {
    // major.minor only, to bound cardinality.
    let Some(v) = v else { return "none".into() };
    let mut parts = v.split('.');
    match (parts.next(), parts.next()) {
        (Some(a), Some(b))
            if a.chars().all(|c| c.is_ascii_digit()) && b.chars().all(|c| c.is_ascii_digit()) =>
        {
            format!("{a}.{b}")
        }
        _ => "other".into(),
    }
}

/// Records request metrics, sets the DB route scope, and emits the worker's
/// `request rejected/failed` log lines.
pub async fn request_metrics(State(_state): State<Arc<AppState>>, req: Request, next: Next) -> Response {
    let route: Arc<str> = req
        .extensions()
        .get::<MatchedPath>()
        .map(|m| Arc::from(m.as_str()))
        .unwrap_or_else(|| Arc::from("unmatched"));
    let method = req.method().to_string();
    let path = req.uri().path().to_string();
    let client = client_label(req.headers().get(CLIENT_VERSION_HEADER).and_then(|v| v.to_str().ok()));
    let client_version =
        req.headers().get(CLIENT_VERSION_HEADER).and_then(|v| v.to_str().ok()).map(|s| s.to_string());
    let started = Instant::now();
    gauge!("http_inflight").increment(1.0);
    let resp = sw_db::pool::ROUTE.scope(route.clone(), next.run(req)).await;
    gauge!("http_inflight").decrement(1.0);
    let status = resp.status().as_u16();
    counter!("http_requests_total", "route" => route.to_string(), "method" => method.clone(), "status" => status.to_string(), "client" => client).increment(1);
    histogram!("http_request_duration_seconds", "route" => route.to_string())
        .record(started.elapsed().as_secs_f64());
    if let Some(info) = resp.extensions().get::<ErrorInfo>() {
        let route_label = format!("{method} {path}");
        if info.status >= 500 {
            tracing::warn!(code = info.code, status = info.status, message = %info.message, route = %route_label, client_version = ?client_version, "SharedWorld request failed");
        } else if info.code != "not_found"
            && !(info.code == "host_not_active" && path.ends_with("/host-startup-progress"))
        {
            tracing::warn!(code = info.code, status = info.status, route = %route_label, client_version = ?client_version, "SharedWorld request rejected");
        }
    }
    resp
}
