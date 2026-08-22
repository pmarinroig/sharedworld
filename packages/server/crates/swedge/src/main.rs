//! `swedge` — the rarely-restarted front door: TLS (ACME), HTTP reverse
//! proxy to `swcore` over a Unix socket, and WebSocket ownership across core
//! restarts (frames multiplexed to the core over a second Unix socket).

mod corelink;
mod proxy;
mod ws;

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use anyhow::Context;
use axum::routing::get;
use axum::Router;
use clap::Parser;
use futures::StreamExt;
use hyper_util::rt::{TokioExecutor, TokioIo, TokioTimer};
use hyper_util::server::conn::auto::Builder as AutoBuilder;
use hyper_util::service::TowerToHyperService;
use rustls_acme::caches::DirCache;
use rustls_acme::AcmeConfig;
use tokio_stream::wrappers::TcpListenerStream;
use tower::Service;

use crate::corelink::CoreLink;
use crate::proxy::ProxyState;

#[derive(Parser, Debug)]
#[command(name = "swedge", about = "SharedWorld edge: TLS + proxy + WebSocket owner")]
struct Args {
    /// Global cap on open client sockets (upgrade answers 429 above it).
    #[arg(long, env = "SW_EDGE_MAX_WS_CONNS", default_value_t = 20_000)]
    max_ws_conns: usize,
    /// Per client IP cap on open sockets (LAN parties share one address).
    #[arg(long, env = "SW_EDGE_MAX_WS_PER_IP", default_value_t = 64)]
    max_ws_per_ip: usize,
    /// TLS listen address (ACME mode). Omit to run plain HTTP only.
    #[arg(long, env = "SW_EDGE_TLS_LISTEN")]
    tls_listen: Option<SocketAddr>,
    /// ACME domains (TLS-ALPN-01; only the TLS port must be reachable).
    #[arg(long = "acme-domain", env = "SW_EDGE_ACME_DOMAINS", value_delimiter = ',')]
    acme_domains: Vec<String>,
    #[arg(long, env = "SW_EDGE_ACME_EMAIL")]
    acme_email: Option<String>,
    #[arg(long, env = "SW_EDGE_ACME_CACHE", default_value = "/var/lib/sharedworld/acme")]
    acme_cache: PathBuf,
    /// Use Let's Encrypt production (default: staging, for rehearsals).
    #[arg(long, env = "SW_EDGE_ACME_PROD", default_value_t = false)]
    acme_prod: bool,
    /// Plain HTTP listen address (dev / behind another proxy).
    #[arg(long, env = "SW_EDGE_PLAIN_LISTEN")]
    plain_listen: Option<SocketAddr>,
    #[arg(long, env = "SW_EDGE_CORE_HTTP_SOCKET", default_value = sw_ipc::DEFAULT_CORE_HTTP_SOCKET)]
    core_http_socket: PathBuf,
    #[arg(long, env = "SW_EDGE_CORE_WS_SOCKET", default_value = sw_ipc::DEFAULT_CORE_WS_SOCKET)]
    core_ws_socket: PathBuf,
    /// How long HTTP requests wait for a restarting core before 503.
    #[arg(long, env = "SW_EDGE_CORE_WAIT_SECS", default_value_t = 10)]
    core_wait_secs: u64,
    #[arg(long, env = "SW_EDGE_METRICS_LISTEN", default_value = "127.0.0.1:9465")]
    metrics_listen: SocketAddr,
    #[arg(long, env = "SW_LOG_JSON", default_value_t = false)]
    log_json: bool,
}

fn init_tracing(json: bool) {
    use tracing_subscriber::{fmt, prelude::*, EnvFilter};
    // rustls_acme::incoming logs every malformed TLS handshake at ERROR;
    // internet scanners produce hundreds of those a day, drowning real errors.
    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new("info,rustls_acme::incoming=off"));
    if json {
        tracing_subscriber::registry().with(filter).with(fmt::layer().json().flatten_event(true)).init();
    } else {
        tracing_subscriber::registry().with(filter).with(fmt::layer()).init();
    }
}

fn build_app(link: Arc<CoreLink>, proxy_state: Arc<ProxyState>) -> Router {
    let ws_router = Router::new().route("/ws", get(ws::ws_handler)).with_state(link);
    let proxy_router = Router::new().fallback(proxy::proxy).with_state(proxy_state);
    ws_router.fallback_service(proxy_router)
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args = Args::parse();
    init_tracing(args.log_json);
    let _ = rustls::crypto::ring::default_provider().install_default();
    let handle = metrics_exporter_prometheus::PrometheusBuilder::new()
        .install_recorder()
        .context("metrics recorder")?;
    sw_ipc::spawn_process_metrics();

    let link = CoreLink::new(
        args.core_ws_socket.clone(),
        crate::corelink::Limits::new(args.max_ws_conns, args.max_ws_per_ip),
    );
    tokio::spawn(link.clone().run());
    let proxy_state = |forwarded_proto: &'static str| {
        Arc::new(ProxyState {
            client: crate::proxy::build_client(
                args.core_http_socket.clone(),
                Duration::from_secs(args.core_wait_secs),
            ),
            connect_retry_for: Duration::from_secs(args.core_wait_secs),
            link: link.clone(),
            forwarded_proto,
        })
    };
    let plain_app = build_app(link.clone(), proxy_state("http"));
    let tls_app = build_app(link.clone(), proxy_state("https"));

    // Metrics on loopback.
    let metrics_listener = tokio::net::TcpListener::bind(args.metrics_listen)
        .await
        .with_context(|| format!("binding {}", args.metrics_listen))?;
    let metrics_app = Router::new()
        .route(
            "/metrics",
            get(move || {
                let h = handle.clone();
                async move { h.render() }
            }),
        )
        .route("/healthz", get(|| async { "ok" }));
    tokio::spawn(async move {
        let _ = axum::serve(metrics_listener, metrics_app).await;
    });

    let mut tasks = Vec::new();
    if let Some(addr) = args.plain_listen {
        let listener =
            tokio::net::TcpListener::bind(addr).await.with_context(|| format!("binding {addr}"))?;
        tracing::info!(%addr, "swedge plain HTTP listening");
        let app = plain_app.clone();
        tasks.push(tokio::spawn(async move {
            let _ = axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await;
        }));
    }
    if let Some(addr) = args.tls_listen {
        anyhow::ensure!(!args.acme_domains.is_empty(), "--acme-domain is required with --tls-listen");
        let listener =
            tokio::net::TcpListener::bind(addr).await.with_context(|| format!("binding {addr}"))?;
        tracing::info!(%addr, domains = ?args.acme_domains, prod = args.acme_prod, "swedge TLS listening");
        let mut incoming = AcmeConfig::new(args.acme_domains.clone())
            .contact(args.acme_email.iter().map(|e| format!("mailto:{e}")))
            .cache(DirCache::new(args.acme_cache.clone()))
            .directory_lets_encrypt(args.acme_prod)
            .tokio_incoming(TcpListenerStream::new(listener), vec![b"h2".to_vec(), b"http/1.1".to_vec()]);
        let app = tls_app.clone();
        tasks.push(tokio::spawn(async move {
            let mut make = app.into_make_service_with_connect_info::<SocketAddr>();
            while let Some(tls) = incoming.next().await {
                let tls = match tls {
                    Ok(t) => t,
                    Err(e) => {
                        tracing::debug!(error = %e, "tls accept failed");
                        continue;
                    }
                };
                let peer: SocketAddr = tls
                    .get_ref()
                    .get_ref()
                    .0
                    .get_ref()
                    .peer_addr()
                    .unwrap_or_else(|_| "0.0.0.0:0".parse().unwrap());
                let svc = match make.call(peer).await {
                    Ok(s) => s,
                    Err(_) => continue,
                };
                tokio::spawn(async move {
                    let mut builder = AutoBuilder::new(TokioExecutor::new());
                    // hyper needs an explicit timer for any timeout/keep-alive
                    // (the plain listener gets one from axum::serve).
                    builder
                        .http1()
                        .timer(TokioTimer::new())
                        .header_read_timeout(Duration::from_secs(15))
                        .keep_alive(true);
                    builder
                        .http2()
                        .timer(TokioTimer::new())
                        .keep_alive_interval(Some(Duration::from_secs(30)));
                    if let Err(e) = builder
                        .serve_connection_with_upgrades(TokioIo::new(tls), TowerToHyperService::new(svc))
                        .await
                    {
                        tracing::debug!(error = %e, "connection ended with error");
                    }
                });
            }
        }));
    }
    anyhow::ensure!(!tasks.is_empty(), "nothing to listen on: pass --tls-listen and/or --plain-listen");
    tokio::select! {
        _ = futures::future::join_all(tasks) => {}
        _ = tokio::signal::ctrl_c() => { tracing::info!("swedge shutting down"); }
    }
    Ok(())
}
