//! `TestEnv`: everything a service test needs, built in a few milliseconds.

use std::sync::{Arc, Mutex};

use sw_contracts::StorageProviderType;
use sw_core::config::Config;
use sw_core::realtime::local::Realtime;
use sw_core::request::{BoxFuture, RequestContext};
use sw_core::service::signer::ServerSignedUrlSigner;
use sw_core::service::{ServiceContext, Svc};
use sw_core::storage::fs::FsStorageProvider;
use sw_core::storage::StorageProvider;
use sw_db::repo::{StorageAccountRecord, UserRecord};
use sw_db::{migrate, time, Db, Repository};

use crate::fake_drive::FakeDriveProvider;

pub struct TestEnv {
    pub svc: Svc,
    pub repo: Repository,
    pub realtime: Arc<Realtime>,
    pub fs: Arc<FsStorageProvider>,
    /// Present only for [`TestEnv::with_fake_drive`] environments.
    pub fake_drive: Option<Arc<FakeDriveProvider>>,
    pub dir: tempfile::TempDir,
    /// Deferred tasks captured by [`TestEnv::deferring_ctx`]; run them with [`TestEnv::run_deferred`].
    pub deferred: Arc<Mutex<Vec<BoxFuture>>>,
}

impl TestEnv {
    pub async fn new() -> TestEnv {
        Self::with_config(Config::dev()).await
    }

    pub async fn with_config(config: Config) -> TestEnv {
        Self::build(config, false).await
    }

    /// A world-storage environment backed by the in-memory resumable
    /// [`FakeDriveProvider`] (direct uploads, Drive-bound existence checks,
    /// the conservative Drive sync policy).
    pub async fn with_fake_drive() -> TestEnv {
        Self::with_fake_drive_config(Config::dev()).await
    }

    pub async fn with_fake_drive_config(config: Config) -> TestEnv {
        Self::build(config, true).await
    }

    async fn build(mut config: Config, fake_drive: bool) -> TestEnv {
        let dir = tempfile::tempdir().expect("tempdir");
        let db = Db::open_memory().expect("db");
        migrate::migrate(&db).expect("migrate");
        let repo = Repository::new(db, None);
        for (uuid, name) in crate::fixtures::SEEDED_PLAYERS {
            repo.upsert_user(UserRecord {
                player_uuid: uuid.to_string(),
                player_name: name.to_string(),
                created_at: time::now_iso(),
            })
            .await
            .expect("seed user");
        }
        // Tests run over the filesystem provider ("r2" mode like the TS
        // fixtures' R2 path) unless the caller wants the fake Drive.
        config.active_storage_provider =
            if fake_drive { StorageProviderType::GoogleDrive } else { StorageProviderType::R2 };
        let config = Arc::new(config);
        let fs = Arc::new(FsStorageProvider::new(dir.path().join("blobs")));
        let drive = fake_drive.then(|| Arc::new(FakeDriveProvider::new(repo.clone())));
        let provider: Arc<dyn StorageProvider> = match &drive {
            Some(d) => d.clone(),
            None => fs.clone(),
        };
        let (realtime, _wake) = Realtime::new_manual(repo.clone());
        let signer = Arc::new(ServerSignedUrlSigner::new(&config));
        let svc = ServiceContext::new(repo.clone(), signer, provider, config, realtime.clone());
        TestEnv {
            svc,
            repo,
            realtime,
            fs,
            fake_drive: drive,
            dir,
            deferred: Arc::new(Mutex::new(Vec::new())),
        }
    }

    /// The fake Drive provider (panics outside a [`TestEnv::with_fake_drive`] env).
    pub fn drive(&self) -> &Arc<FakeDriveProvider> {
        self.fake_drive.as_ref().expect("TestEnv::with_fake_drive")
    }

    /// Inserts a linked Google Drive account owned by `player_uuid` so worlds
    /// created with `use_linked_storage_account` bind to it.
    pub async fn link_drive_account(&self, player_uuid: &str) -> String {
        let id = format!("storage-account-{player_uuid}");
        let now = time::now_iso();
        self.repo
            .create_or_update_storage_account(StorageAccountRecord {
                id: id.clone(),
                provider: StorageProviderType::GoogleDrive,
                owner_player_uuid: player_uuid.to_string(),
                external_account_id: format!("external-{player_uuid}"),
                email: Some(format!("{player_uuid}@example.com")),
                display_name: Some("Owner Drive".into()),
                access_token: Some("access-token".into()),
                refresh_token: Some("refresh-token".into()),
                token_expires_at: Some(time::plus_ms_iso(time::now(), 3_600_000)),
                created_at: now.clone(),
                updated_at: now,
            })
            .await
            .expect("link drive account");
        id
    }

    /// A context whose `defer` captures tasks instead of running them.
    pub fn deferring_ctx(&self, base: RequestContext) -> RequestContext {
        let store = self.deferred.clone();
        RequestContext {
            defer: Some(Arc::new(move |fut: BoxFuture| store.lock().unwrap().push(fut))),
            ..base
        }
    }

    /// Run every captured deferred task.
    pub async fn run_deferred(&self) {
        let tasks: Vec<BoxFuture> = std::mem::take(&mut *self.deferred.lock().unwrap());
        for t in tasks {
            t.await;
        }
    }

    pub fn now() -> time::Instant {
        time::now()
    }
}
