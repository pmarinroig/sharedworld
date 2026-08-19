//! Building a running application from a `Config` (shared by the `swcore`
//! binary and in-process tests): DB, migrations, repository, storage
//! provider, realtime, service context, app state.

use std::sync::Arc;

use sw_core::caches::{MokaManifestCache, SessionCache};
use sw_core::realtime::local::Realtime;
use sw_core::service::signer::ServerSignedUrlSigner;
use sw_core::service::ServiceContext;
use sw_core::storage::fs::FsStorageProvider;
use sw_core::storage::StorageProvider;
use sw_core::{Config, HttpError};
use sw_db::repo::UserRecord;
use sw_db::{migrate, time, Db, DbOptions, Repository};

use crate::state::AppStateInner;

pub struct BootOptions {
    pub config: Arc<Config>,
    /// `None` = in-memory database (tests).
    pub db_path: Option<std::path::PathBuf>,
    pub db_readers: usize,
    /// Start the alarm/eviction loops (false = tests drive alarms by hand).
    pub start_realtime_loops: bool,
    /// Seed the 13 harness players (tests).
    pub seed_test_players: bool,
}

/// Open (or create) the database and apply migrations.
pub fn open_db(opts: &BootOptions) -> Result<Db, sw_db::DbError> {
    let db = match &opts.db_path {
        Some(p) => {
            Db::open(DbOptions { path: Some(p.clone()), readers: opts.db_readers, busy_timeout_ms: 5_000 })?
        }
        None => Db::open_memory()?,
    };
    let applied = migrate::migrate(&db)?;
    if !applied.is_empty() {
        tracing::info!(count = applied.len(), last = ?applied.last(), "SharedWorld migrations applied");
    }
    Ok(db)
}

/// Build the inner state over an existing database.
pub async fn build_inner(
    opts: &BootOptions,
    db: Db,
    provider_override: Option<Arc<dyn StorageProvider>>,
) -> Result<Arc<AppStateInner>, HttpError> {
    let mut repo = Repository::new(db, Some(MokaManifestCache::new(200_000)));
    if let Some(path) = &opts.config.master_key_file {
        let cipher = sw_db::TokenCipher::from_key_file(path)
            .map_err(|e| HttpError::internal(format!("master key: {e}")))?;
        repo = repo.with_token_cipher(Arc::new(cipher));
        tracing::info!(path = %path.display(), "tokens-at-rest encryption enabled");
    }
    if opts.seed_test_players {
        for (uuid, name) in sw_testkit_players() {
            repo.upsert_user(UserRecord {
                player_uuid: uuid.to_string(),
                player_name: name.to_string(),
                created_at: time::now_iso(),
            })
            .await?;
        }
    }
    let http = reqwest::Client::builder().user_agent("sharedworld-server").build().expect("reqwest client");
    let mut fs: Option<Arc<FsStorageProvider>> = None;
    let provider: Arc<dyn StorageProvider> = match provider_override {
        Some(p) => p,
        None => match opts.config.active_storage_provider {
            sw_contracts::StorageProviderType::R2 => {
                let root =
                    opts.config.fs_blob_root.clone().unwrap_or_else(|| std::path::PathBuf::from("./blobs"));
                let f = Arc::new(FsStorageProvider::new(root));
                fs = Some(f.clone());
                f
            }
            sw_contracts::StorageProviderType::GoogleDrive => {
                sw_core::storage::create_storage_provider(&opts.config, &repo, http.clone())
            }
        },
    };
    let realtime = if opts.start_realtime_loops {
        Realtime::start(repo.clone()).await?
    } else {
        Realtime::new_manual(repo.clone()).0
    };
    let signer = Arc::new(ServerSignedUrlSigner::new(&opts.config));
    let svc = ServiceContext::new(repo, signer, provider, opts.config.clone(), realtime.clone());
    Ok(Arc::new(AppStateInner {
        svc,
        realtime,
        sessions: SessionCache::new(),
        fs,
        test_storage: None,
        fake_drive: None,
        test_cert_private_key_pkcs8_b64: None,
    }))
}

fn sw_testkit_players() -> &'static [(&'static str, &'static str)] {
    &[
        ("owner-uuid", "Owner"),
        ("guest-uuid", "Guest"),
        ("host-member-uuid", "HostMember"),
        ("third-uuid", "Third"),
        ("player-owner", "Owner"),
        ("player-guest", "Guest"),
        ("player-host", "Host"),
        ("player-other", "Other"),
        ("player-kicked", "Kicked"),
        ("alice-uuid", "Alice"),
        ("bob-uuid", "Bob"),
        ("carol-uuid", "Carol"),
        ("dave-uuid", "Dave"),
    ]
}
