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

use crate::fake_s3::FakeS3;
use crate::integration_drive::{DriveFailKind, DriveFailMode, IntegrationDriveProvider};
use crate::integration_router::IntegrationStorageRouter;

pub const DEV_AUTH_SECRET: &str = "test-dev-auth-secret";

#[derive(Clone, Default)]
pub struct IntegrationPersistence {
    pub db_path: Option<PathBuf>,
    pub blob_dir: Option<PathBuf>,
}

/// `POST /__test/drive-mode` handle over the current universe's provider.
struct DriveFailureHook(Arc<IntegrationDriveProvider>);

impl sw_http::routes::testkit::DriveFailureControl for DriveFailureHook {
    fn set_drive_fail_mode(&self, mode: &str, fail_count: Option<u32>) -> Result<(), String> {
        let kind = match mode {
            "normal" => None,
            "storage-full" => Some(DriveFailKind::StorageFull),
            "reauth-required" => Some(DriveFailKind::ReauthRequired),
            other => return Err(format!("unknown drive mode '{other}'")),
        };
        self.0.set_fail_mode(DriveFailMode { kind, remaining: fail_count });
        Ok(())
    }
}

/// `GET /__test/s3` handle: the long-lived fake S3 service + its origin.
struct S3TestHook {
    fake: FakeS3,
    endpoint: String,
}

impl sw_http::routes::testkit::S3TestInfo for S3TestHook {
    fn s3_info(&self) -> serde_json::Value {
        self.fake.info(&self.endpoint)
    }
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
        // The in-process fake S3 lives on a loopback http origin.
        allow_insecure_s3_endpoint: true,
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
    s3: Option<(FakeS3, String)>,
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
    let drive =
        Arc::new(IntegrationDriveProvider::new(repo.clone(), public_base_url, persistence.blob_dir.clone()));
    // Per-binding routing like production: Drive worlds hit the fake, S3
    // worlds hit the REAL S3 provider aimed at the fake S3 service.
    let http = reqwest::Client::builder().user_agent("sharedworld-integration").build().expect("http");
    let router = Arc::new(IntegrationStorageRouter {
        drive: drive.clone(),
        s3: sw_core::storage::s3::S3StorageProvider::new(repo, http, config.signed_url_ttl_seconds),
    });
    let inner = build_inner(&opts, db, Some(router)).await.expect("build inner");
    // Rebuild with the testkit hooks populated (AppStateInner fields are plain data).
    Arc::new(AppStateInner {
        svc: inner.svc.clone(),
        realtime: inner.realtime.clone(),
        sessions: sw_core::caches::SessionCache::new(),
        fs: None,
        test_storage: Some(drive.clone()),
        fake_drive: Some(drive.clone()),
        drive_fail: Some(Arc::new(DriveFailureHook(drive))),
        test_s3: s3.map(|(fake, endpoint)| {
            Arc::new(S3TestHook { fake, endpoint }) as Arc<dyn sw_http::routes::testkit::S3TestInfo>
        }),
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
    // One long-lived fake S3 service per process: its port must survive
    // `/__test/reset` (linked accounts persist the endpoint), so reset only
    // clears its objects.
    let fake_s3 = FakeS3::default();
    let s3_endpoint = fake_s3.spawn().await;
    // Startup keeps persisted files (a harness restart is deploy-faithful);
    // only `/__test/reset` wipes them.
    let inner = build_integration_inner(
        config.clone(),
        public_base_url,
        &persistence,
        &keys,
        Some((fake_s3.clone(), s3_endpoint.clone())),
        false,
    )
    .await;
    let state = AppState::new(inner, config.clone());
    let base = public_base_url.to_string();
    let keys2 = keys.clone();
    let config2 = config.clone();
    state.with_rebuild(Box::new(move || {
        let (base, persistence, keys, config, fake_s3, s3_endpoint) = (
            base.clone(),
            persistence.clone(),
            keys2.clone(),
            config2.clone(),
            fake_s3.clone(),
            s3_endpoint.clone(),
        );
        Box::pin(async move {
            fake_s3.clear();
            build_integration_inner(config, &base, &persistence, &keys, Some((fake_s3, s3_endpoint)), true)
                .await
        })
    }))
}
