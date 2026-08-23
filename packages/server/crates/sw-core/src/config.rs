//! Server configuration: every `wrangler.toml`/`Env` knob has a typed field
//! (config-parity test in `sw-http`). Loaded by `swcore` from TOML + `SW_*`
//! env overrides; tests build it with `Config::dev()`.

use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use sw_contracts::StorageProviderType;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "snake_case")]
pub struct Config {
    /// `ACTIVE_STORAGE_PROVIDER`.
    pub active_storage_provider: StorageProviderType,
    /// `SESSION_TTL_HOURS`.
    pub session_ttl_hours: i64,
    /// `PUBLIC_BASE_URL`: the box's own public origin (signed URL base when
    /// no entry origin is known).
    pub public_base_url: Option<String>,
    /// Lane D: origin that relays blob downloads (the CF worker). When set,
    /// download URLs point here; uploads stay at the caller's entry origin.
    pub relay_base_url: Option<String>,
    /// `SIGNED_URL_TTL_SECONDS`.
    pub signed_url_ttl_seconds: i64,
    /// `MOJANG_PLAYER_CERTIFICATE_KEYS` (comma-separated base64 DER pins).
    pub mojang_player_certificate_keys: Option<String>,
    /// Box-only: refresh Mojang's key set ourselves (egress is not blocked here).
    pub mojang_keys_self_refresh: bool,
    pub mojang_publickeys_url: String,
    /// `SIGNING_SECRET` / `SIGNING_SECRET_PREVIOUS`.
    pub signing_secret: Option<String>,
    pub signing_secret_previous: Option<String>,
    /// `ALLOW_DEV_AUTH`, `DEV_AUTH_SECRET`, `ALLOW_DEV_INSECURE_E4MC`.
    pub allow_dev_auth: bool,
    pub dev_auth_secret: Option<String>,
    pub allow_dev_insecure_e4mc: bool,
    /// `ALLOW_DEV_GOOGLE_OAUTH`, `DEV_GOOGLE_EMAIL`.
    pub allow_dev_google_oauth: bool,
    pub dev_google_email: Option<String>,
    /// `GOOGLE_OAUTH_*`, `GOOGLE_DRIVE_API_BASE`.
    pub google_oauth_client_id: Option<String>,
    pub google_oauth_client_secret: Option<String>,
    pub google_oauth_redirect_uri: Option<String>,
    pub google_oauth_scopes: Option<String>,
    pub google_drive_api_base: Option<String>,
    /// Box-only test hook: the OAuth token endpoint (hard-coded to
    /// `https://oauth2.googleapis.com/token` in the worker).
    pub google_oauth_token_url: Option<String>,
    /// Box-only test hook: the OAuth revoke endpoint (defaults to
    /// `https://oauth2.googleapis.com/revoke`).
    pub google_oauth_revoke_url: Option<String>,
    /// `DRIVE_*` pacing/retry knobs.
    pub drive_max_parallel_downloads: Option<i64>,
    pub drive_max_upload_preparations: Option<i64>,
    pub drive_max_concurrent_uploads: Option<i64>,
    pub drive_max_upload_starts_per_second: Option<i64>,
    pub drive_retry_base_delay_ms: Option<i64>,
    pub drive_retry_max_delay_ms: Option<i64>,
    /// `UPLOAD_MAX_BODY_BYTES`.
    pub upload_max_body_bytes: Option<i64>,
    /// `SUGGESTED_*_INTERVAL_MS` remote throttle levers (absent = clients use defaults).
    pub suggested_runtime_poll_interval_ms: Option<i64>,
    pub suggested_host_heartbeat_interval_ms: Option<i64>,
    pub suggested_autosave_interval_ms: Option<i64>,
    pub suggested_presence_interval_ms: Option<i64>,
    /// `MAX_ACTIVE_WORLDS`.
    pub max_active_worlds: Option<i64>,
    /// Box-only: shared secret the CF forwarder presents (`x-sw-internal-secret`).
    pub internal_api_secret: Option<String>,
    /// Box-only: relay download token keys (Ed25519 private key, AES-GCM key), base64.
    pub relay_signing_key_b64: Option<String>,
    pub relay_token_key_b64: Option<String>,
    /// Box-only: AES-256-GCM key file for tokens at rest.
    pub master_key_file: Option<PathBuf>,
    /// Box-only: local filesystem blob root when `active_storage_provider` is `r2`
    /// (the fs provider stands in for R2).
    pub fs_blob_root: Option<PathBuf>,
    /// `S3_LINK_ENABLED`: ops kill switch for the S3 bring-your-own-bucket
    /// link flow (the browser form and new s3 link sessions).
    pub s3_link_enabled: bool,
    /// `ALLOW_INSECURE_S3_ENDPOINT`: dev/test only — accept http:// and
    /// private-network S3 endpoints (local MinIO).
    pub allow_insecure_s3_endpoint: bool,
    /// Testkit routes enabled (requires the `testkit` feature too).
    pub test_routes: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            active_storage_provider: StorageProviderType::GoogleDrive,
            session_ttl_hours: 168,
            public_base_url: None,
            relay_base_url: None,
            signed_url_ttl_seconds: 900,
            mojang_player_certificate_keys: None,
            mojang_keys_self_refresh: true,
            mojang_publickeys_url: "https://api.minecraftservices.com/publickeys".into(),
            signing_secret: None,
            signing_secret_previous: None,
            allow_dev_auth: false,
            dev_auth_secret: None,
            allow_dev_insecure_e4mc: false,
            allow_dev_google_oauth: false,
            dev_google_email: None,
            google_oauth_client_id: None,
            google_oauth_client_secret: None,
            google_oauth_redirect_uri: None,
            google_oauth_scopes: None,
            google_drive_api_base: None,
            google_oauth_token_url: None,
            google_oauth_revoke_url: None,
            drive_max_parallel_downloads: None,
            drive_max_upload_preparations: None,
            drive_max_concurrent_uploads: None,
            drive_max_upload_starts_per_second: None,
            drive_retry_base_delay_ms: None,
            drive_retry_max_delay_ms: None,
            upload_max_body_bytes: None,
            suggested_runtime_poll_interval_ms: None,
            suggested_host_heartbeat_interval_ms: None,
            suggested_autosave_interval_ms: None,
            suggested_presence_interval_ms: None,
            max_active_worlds: None,
            internal_api_secret: None,
            relay_signing_key_b64: None,
            relay_token_key_b64: None,
            master_key_file: None,
            fs_blob_root: None,
            s3_link_enabled: true,
            allow_insecure_s3_endpoint: false,
            test_routes: false,
        }
    }
}

impl Config {
    /// Dev/test profile: dev auth + mock OAuth enabled, stamps signed.
    pub fn dev() -> Self {
        Self {
            allow_dev_auth: true,
            dev_auth_secret: Some("dev-secret".into()),
            allow_dev_google_oauth: true,
            allow_insecure_s3_endpoint: true,
            signing_secret: Some("dev-signing-secret".into()),
            public_base_url: Some("http://127.0.0.1:8787".into()),
            ..Self::default()
        }
    }
}
