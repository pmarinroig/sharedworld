//! `swcore` — the SharedWorld core server: HTTP/WS services over SQLite.
//! Serves on a TCP listener (dev / behind a proxy) and, in production, over
//! Unix sockets for `swedge` (Phase 8).

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

use anyhow::Context;
use clap::Parser;
use sw_core::Config;
use sw_http::bootstrap::{build_inner, open_db, BootOptions};
use sw_http::{build_router, AppState};

#[derive(Parser, Debug)]
#[command(name = "swcore", about = "SharedWorld core server")]
struct Args {
    /// TOML config file (`swcore.toml`); `SW_*` environment variables override.
    #[arg(long, env = "SW_CONFIG")]
    config: Option<PathBuf>,
    /// SQLite database path (default `./sharedworld.db`; `:memory:` for ephemeral).
    #[arg(long, env = "SW_DB_PATH")]
    db: Option<String>,
    /// TCP listen address for HTTP+WS (dev / proxy mode).
    #[arg(long, env = "SW_LISTEN", default_value = "127.0.0.1:8787")]
    listen: SocketAddr,
    /// Loopback metrics listener (`/metrics`, `/healthz`).
    #[arg(long, env = "SW_METRICS_LISTEN", default_value = "127.0.0.1:9464")]
    metrics_listen: SocketAddr,
    /// Unix socket for HTTP from `swedge` (omit to serve TCP only).
    #[arg(long, env = "SW_UDS_HTTP")]
    uds_http: Option<PathBuf>,
    /// Unix socket for the WebSocket IPC link from `swedge`.
    #[arg(long, env = "SW_UDS_WS")]
    uds_ws: Option<PathBuf>,
    /// Reader connections for the SQLite pool.
    #[arg(long, env = "SW_DB_READERS", default_value_t = 4)]
    db_readers: usize,
    /// Use the dev config profile (dev auth, mock Google OAuth, dev signing secret).
    #[arg(long, env = "SW_DEV_PROFILE", default_value_t = false)]
    dev: bool,
    /// Integration-harness mode (requires the `testkit` build feature): the Bun
    /// integration profile — dev auth, mock OAuth, fake Drive, `/__test/*`.
    #[arg(long, env = "SW_INTEGRATION", default_value_t = false)]
    integration: bool,
    /// Log as JSON lines (default: human-readable when stdout is a TTY).
    #[arg(long, env = "SW_LOG_JSON", default_value_t = false)]
    log_json: bool,
}

fn load_config(args: &Args) -> anyhow::Result<Config> {
    let mut config = if args.dev { Config::dev() } else { Config::default() };
    if let Some(path) = &args.config {
        let text = std::fs::read_to_string(path).with_context(|| format!("reading {}", path.display()))?;
        let file: Config = toml::from_str(&text).with_context(|| format!("parsing {}", path.display()))?;
        config = file;
    }
    apply_env_overrides(&mut config);
    Ok(config)
}

/// `SW_<FIELD>` environment overrides for the most common knobs.
fn apply_env_overrides(config: &mut Config) {
    fn s(name: &str) -> Option<String> {
        std::env::var(name).ok().filter(|v| !v.is_empty())
    }
    fn b(name: &str) -> Option<bool> {
        s(name).map(|v| v.eq_ignore_ascii_case("true") || v == "1")
    }
    fn i(name: &str) -> Option<i64> {
        s(name).and_then(|v| v.parse().ok())
    }
    if let Some(v) = s("SW_PUBLIC_BASE_URL") {
        config.public_base_url = Some(v);
    }
    if let Some(v) = s("SW_RELAY_BASE_URL") {
        config.relay_base_url = Some(v);
    }
    if let Some(v) = s("SW_SIGNING_SECRET") {
        config.signing_secret = Some(v);
    }
    if let Some(v) = s("SW_SIGNING_SECRET_PREVIOUS") {
        config.signing_secret_previous = Some(v);
    }
    if let Some(v) = b("SW_ALLOW_DEV_AUTH") {
        config.allow_dev_auth = v;
    }
    if let Some(v) = s("SW_DEV_AUTH_SECRET") {
        config.dev_auth_secret = Some(v);
    }
    if let Some(v) = b("SW_ALLOW_DEV_INSECURE_E4MC") {
        config.allow_dev_insecure_e4mc = v;
    }
    if let Some(v) = b("SW_ALLOW_DEV_GOOGLE_OAUTH") {
        config.allow_dev_google_oauth = v;
    }
    if let Some(v) = s("SW_DEV_GOOGLE_EMAIL") {
        config.dev_google_email = Some(v);
    }
    if let Some(v) = s("SW_GOOGLE_OAUTH_CLIENT_ID") {
        config.google_oauth_client_id = Some(v);
    }
    if let Some(v) = s("SW_GOOGLE_OAUTH_CLIENT_SECRET") {
        config.google_oauth_client_secret = Some(v);
    }
    if let Some(v) = s("SW_GOOGLE_OAUTH_REDIRECT_URI") {
        config.google_oauth_redirect_uri = Some(v);
    }
    if let Some(v) = s("SW_MOJANG_PLAYER_CERTIFICATE_KEYS") {
        config.mojang_player_certificate_keys = Some(v);
    }
    if let Some(v) = s("SW_INTERNAL_API_SECRET") {
        config.internal_api_secret = Some(v);
    }
    if let Some(v) = i("SW_SESSION_TTL_HOURS") {
        config.session_ttl_hours = v;
    }
    if let Some(v) = i("SW_MAX_ACTIVE_WORLDS") {
        config.max_active_worlds = Some(v);
    }
    if let Some(v) = i("SW_UPLOAD_MAX_BODY_BYTES") {
        config.upload_max_body_bytes = Some(v);
    }
    if let Some(v) = i("SW_SUGGESTED_AUTOSAVE_INTERVAL_MS") {
        config.suggested_autosave_interval_ms = Some(v);
    }
    if let Some(v) = i("SW_SUGGESTED_RUNTIME_POLL_INTERVAL_MS") {
        config.suggested_runtime_poll_interval_ms = Some(v);
    }
    if let Some(v) = s("SW_ACTIVE_STORAGE_PROVIDER") {
        if let Some(p) = sw_contracts::StorageProviderType::parse(&v) {
            config.active_storage_provider = p;
        }
    }
    if let Some(v) = s("SW_FS_BLOB_ROOT") {
        config.fs_blob_root = Some(PathBuf::from(v));
    }
    if let Some(v) = b("SW_S3_LINK_ENABLED") {
        config.s3_link_enabled = v;
    }
    if let Some(v) = b("SW_ALLOW_INSECURE_S3_ENDPOINT") {
        config.allow_insecure_s3_endpoint = v;
    }
    if let Some(v) = b("SW_TEST_ROUTES") {
        config.test_routes = v;
    }
}

fn init_tracing(json: bool) {
    use tracing_subscriber::{fmt, prelude::*, EnvFilter};
    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new("info,sw_core=info,sw_http=info,sw_db=info"));
    if json {
        tracing_subscriber::registry().with(filter).with(fmt::layer().json().flatten_event(true)).init();
    } else {
        tracing_subscriber::registry().with(filter).with(fmt::layer()).init();
    }
}

#[cfg(feature = "testkit")]
async fn run_integration(args: &Args) -> anyhow::Result<()> {
    use sw_testkit::integration::{build_integration_state, IntegrationPersistence};
    let base_url = format!("http://{}", args.listen);
    let persistence = IntegrationPersistence {
        db_path: std::env::var("SHAREDWORLD_INTEGRATION_DB_FILE")
            .ok()
            .filter(|v| !v.is_empty())
            .map(PathBuf::from),
        blob_dir: std::env::var("SHAREDWORLD_INTEGRATION_BLOB_DIR")
            .ok()
            .filter(|v| !v.is_empty())
            .map(PathBuf::from),
    };
    let state = build_integration_state(&base_url, persistence).await;
    let _ = sw_http::metrics::install_recorder();
    let app = build_router(state.clone());
    let listener = tokio::net::TcpListener::bind(args.listen)
        .await
        .with_context(|| format!("binding {}", args.listen))?;
    tracing::info!(listen = %args.listen, "swcore integration harness listening");
    axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>())
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;
    Ok(())
}

#[cfg(not(feature = "testkit"))]
async fn run_integration(_args: &Args) -> anyhow::Result<()> {
    anyhow::bail!("--integration requires a build with --features testkit")
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args = Args::parse();
    init_tracing(args.log_json);
    if args.integration {
        return run_integration(&args).await;
    }
    let config = Arc::new(load_config(&args)?);
    let db_path = match args.db.as_deref() {
        Some(":memory:") => None,
        Some(p) => Some(PathBuf::from(p)),
        None => Some(PathBuf::from("./sharedworld.db")),
    };
    let opts = BootOptions {
        config: config.clone(),
        db_path,
        db_readers: args.db_readers,
        start_realtime_loops: true,
        seed_test_players: false,
    };
    let db = open_db(&opts).context("opening database")?;
    let inner = build_inner(&opts, db, None).await.map_err(|e| anyhow::anyhow!("{e}"))?;
    let state = AppState::new(inner, config.clone());
    let _ = sw_http::metrics::install_recorder();
    sw_ipc::spawn_process_metrics();
    sw_core::jobs::start(state.svc(), sw_core::jobs::JobsConfig::default());

    let app = build_router(state.clone());
    if let Some(path) = &args.uds_http {
        if path.exists() {
            let _ = std::fs::remove_file(path);
        }
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).ok();
        }
        let uds =
            tokio::net::UnixListener::bind(path).with_context(|| format!("binding {}", path.display()))?;
        tracing::info!(path = %path.display(), "swcore HTTP (unix socket) listening");
        let app_uds = app.clone();
        tokio::spawn(async move {
            if let Err(e) = axum::serve(uds, app_uds).await {
                tracing::error!(error = %e, "unix HTTP listener failed");
            }
        });
    }
    if let Some(path) = args.uds_ws.clone() {
        let state_ipc = state.clone();
        tokio::spawn(async move {
            if let Err(e) = sw_http::ipc_server::serve_ws_ipc(state_ipc, &path).await {
                tracing::error!(error = %e, "WS IPC listener failed");
            }
        });
    }
    let listener = tokio::net::TcpListener::bind(args.listen)
        .await
        .with_context(|| format!("binding {}", args.listen))?;
    tracing::info!(listen = %args.listen, metrics = %args.metrics_listen, provider = ?config.active_storage_provider, "swcore listening");
    let metrics_listener = tokio::net::TcpListener::bind(args.metrics_listen)
        .await
        .with_context(|| format!("binding {}", args.metrics_listen))?;
    let metrics_app = sw_http::build_metrics_router();
    tokio::spawn(async move {
        if let Err(e) = axum::serve(metrics_listener, metrics_app).await {
            tracing::error!(error = %e, "metrics listener failed");
        }
    });
    axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>())
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
            tracing::info!("swcore shutting down");
        })
        .await?;
    Ok(())
}

#[cfg(test)]
mod config_example_tests {
    /// `ops/swcore.toml.example` must parse as a `Config` (every key is a
    /// real field), so the operator copy-edit path cannot silently drift.
    #[test]
    fn example_toml_parses() {
        let text = include_str!("../../../ops/swcore.toml.example");
        let config: sw_core::Config = toml::from_str(text).expect("swcore.toml.example parses");
        assert_eq!(config.public_base_url.as_deref(), Some("https://api.sharedworld.example"));
        assert!(config.master_key_file.is_some());
        assert!(config.relay_signing_key_b64.is_some() && config.relay_token_key_b64.is_some());
        assert!(config.internal_api_secret.is_some());
        assert!(!config.allow_dev_auth && !config.test_routes);
    }
}
