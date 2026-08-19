//! Integration-server bootstrap: the Bun `createIntegrationTestApp` profile
//! (dev auth secret, mock Google OAuth, pinned services key whose private
//! half is served on `/__test/cert-signing-key`, fake Drive provider).

use std::path::PathBuf;
use std::sync::Arc;

use base64::Engine;
use rsa::pkcs8::{EncodePrivateKey, EncodePublicKey};
use rsa::RsaPrivateKey;
use sw_core::Config;
use sw_http::bootstrap::{build_inner, open_db, BootOptions};
use sw_http::state::{AppState, AppStateInner};

use crate::integration_drive::IntegrationDriveProvider;

pub const DEV_AUTH_SECRET: &str = "test-dev-auth-secret";

#[derive(Clone, Default)]
pub struct IntegrationPersistence {
    pub db_path: Option<PathBuf>,
    pub blob_dir: Option<PathBuf>,
}

pub struct IntegrationKeys {
    pub services_public_key_b64: String,
    pub services_private_key_pkcs8_b64: String,
}

/// A fresh RSA-2048 services keypair (SPKI public / PKCS#8 private, base64 DER).
pub fn generate_services_keys() -> IntegrationKeys {
    struct Rng;
    impl rsa::rand_core::RngCore for Rng {
        fn next_u32(&mut self) -> u32 {
            rand::random()
        }
        fn next_u64(&mut self) -> u64 {
            rand::random()
        }
        fn fill_bytes(&mut self, dest: &mut [u8]) {
            rand::fill(dest)
        }
        fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), rsa::rand_core::Error> {
            rand::fill(dest);
            Ok(())
        }
    }
    impl rsa::rand_core::CryptoRng for Rng {}
    let key = RsaPrivateKey::new(&mut Rng, 2048).expect("rsa keygen");
    let public_der = key.to_public_key().to_public_key_der().expect("spki").into_vec();
    let private_der = key.to_pkcs8_der().expect("pkcs8");
    IntegrationKeys {
        services_public_key_b64: base64::engine::general_purpose::STANDARD.encode(public_der),
        services_private_key_pkcs8_b64: base64::engine::general_purpose::STANDARD
            .encode(private_der.as_bytes()),
    }
}

pub fn integration_config(public_base_url: &str, services_public_key_b64: &str) -> Config {
    Config {
        public_base_url: Some(public_base_url.to_string()),
        signing_secret: Some("sharedworld-integration-secret".into()),
        session_ttl_hours: 24,
        allow_dev_auth: true,
        dev_auth_secret: Some(DEV_AUTH_SECRET.into()),
        allow_dev_insecure_e4mc: true,
        allow_dev_google_oauth: true,
        dev_google_email: Some("integration-drive@example.com".into()),
        mojang_player_certificate_keys: Some(services_public_key_b64.to_string()),
        active_storage_provider: sw_contracts::StorageProviderType::GoogleDrive,
        test_routes: true,
        // Lane-D forwarder smoke (`scripts/cf-lane-d-smoke.sh`): with the
        // secret set, the worker's `x-sw-entry-origin` is trusted and signed
        // URLs point at the worker, exactly as for a forwarded legacy client.
        internal_api_secret: std::env::var("SW_INTERNAL_API_SECRET").ok().filter(|v| !v.is_empty()),
        ..Config::default()
    }
}

/// Build one "universe" (called again on `/__test/reset`, which wipes the
/// persisted files first).
pub async fn build_integration_inner(
    config: Arc<Config>,
    public_base_url: &str,
    persistence: &IntegrationPersistence,
    keys: &IntegrationKeys,
    fresh: bool,
) -> Arc<AppStateInner> {
    if fresh {
        if let Some(p) = &persistence.db_path {
            let _ = std::fs::remove_file(p);
            let _ = std::fs::remove_file(p.with_extension("db-wal"));
            let _ = std::fs::remove_file(p.with_extension("db-shm"));
        }
        if let Some(d) = &persistence.blob_dir {
            let _ = std::fs::remove_dir_all(d);
        }
    }
    let opts = BootOptions {
        config: config.clone(),
        db_path: persistence.db_path.clone(),
        db_readers: 2,
        start_realtime_loops: true,
        seed_test_players: true,
    };
    let db = open_db(&opts).expect("open db");
    let repo = sw_db::Repository::new(db.clone(), None);
    let provider =
        Arc::new(IntegrationDriveProvider::new(repo, public_base_url, persistence.blob_dir.clone()));
    let inner = build_inner(&opts, db, Some(provider.clone())).await.expect("build inner");
    // Rebuild with the testkit hooks populated (AppStateInner fields are plain data).
    Arc::new(AppStateInner {
        svc: inner.svc.clone(),
        realtime: inner.realtime.clone(),
        sessions: sw_core::caches::SessionCache::new(),
        fs: None,
        test_storage: Some(provider.clone()),
        fake_drive: Some(provider),
        test_cert_private_key_pkcs8_b64: Some(keys.services_private_key_pkcs8_b64.clone()),
    })
}

/// Full integration `AppState` with a `rebuild` hook for `/__test/reset`.
pub async fn build_integration_state(
    public_base_url: &str,
    persistence: IntegrationPersistence,
) -> Arc<AppState> {
    let keys = Arc::new(generate_services_keys());
    let config = Arc::new(integration_config(public_base_url, &keys.services_public_key_b64));
    // Startup keeps persisted files (a harness restart is deploy-faithful);
    // only `/__test/reset` wipes them.
    let inner = build_integration_inner(config.clone(), public_base_url, &persistence, &keys, false).await;
    let state = AppState::new(inner, config.clone());
    let base = public_base_url.to_string();
    let keys2 = keys.clone();
    let config2 = config.clone();
    state.with_rebuild(Box::new(move || {
        let (base, persistence, keys, config) =
            (base.clone(), persistence.clone(), keys2.clone(), config2.clone());
        Box::pin(async move { build_integration_inner(config, &base, &persistence, &keys, true).await })
    }))
}
