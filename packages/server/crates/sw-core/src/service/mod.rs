//! Service layer: `ServiceContext` (the dependencies every domain module
//! operates on) and the `Service` façade the HTTP layer calls.

pub mod account;
pub mod members;
pub mod runtime_access;
pub mod session;
pub mod signer;
pub mod snapshots;
pub mod sync_plan;
pub mod worlds;

use std::sync::Arc;

use sw_db::Repository;

use crate::auth::AuthService;
use crate::caches::StorageUsageCache;
use crate::config::Config;
use crate::realtime::local::Realtime;
use crate::stamp::StampKeys;
use crate::storage::link_service::StorageLinkService;
use crate::storage::StorageProvider;

pub use signer::{BlobUrlSigner, SignedBlobRequest};

pub struct ServiceContext {
    pub repository: Repository,
    pub blob_signer: Arc<dyn BlobUrlSigner>,
    pub storage_provider: Arc<dyn StorageProvider>,
    pub storage_links: StorageLinkService,
    pub realtime: Arc<Realtime>,
    pub config: Arc<Config>,
    pub stamp_keys: StampKeys,
    pub storage_usage_cache: StorageUsageCache,
    pub auth: AuthService,
    pub http: reqwest::Client,
    /// Lane-D relay token keys (None = plans carry no relay tokens).
    pub relay_keys: Option<Arc<crate::relay::RelayKeys>>,
}

pub type Svc = Arc<ServiceContext>;

impl ServiceContext {
    pub fn new(
        repository: Repository,
        blob_signer: Arc<dyn BlobUrlSigner>,
        storage_provider: Arc<dyn StorageProvider>,
        config: Arc<Config>,
        realtime: Arc<Realtime>,
    ) -> Arc<Self> {
        let http =
            reqwest::Client::builder().user_agent("sharedworld-server").build().expect("reqwest client");
        let storage_links = StorageLinkService::new(
            repository.clone(),
            config.clone(),
            storage_provider.provider(),
            http.clone(),
        );
        let auth = AuthService::new(repository.clone(), config.clone());
        let relay_keys = match (&config.relay_signing_key_b64, &config.relay_token_key_b64) {
            (Some(s), Some(t)) => match crate::relay::RelayKeys::from_config(s, t) {
                Ok(k) => {
                    tracing::info!("relay tokens enabled (download plans carry x-sharedworld-relay-token)");
                    Some(k)
                }
                Err(e) => {
                    tracing::error!(error = %e, "relay keys misconfigured; relay tokens disabled");
                    None
                }
            },
            (None, None) => {
                if config.relay_base_url.is_some() {
                    tracing::error!("relay_base_url is set but relay keys are missing: downloads through the relay will fail (the relay forwards tokenless GETs to the box, which rejects them without a bearer)");
                }
                None
            }
            _ => {
                // One key present, one missing; what a quoting mistake in the
                // TOML looks like (a stray `""` opens a multi-line string).
                tracing::error!("only one of relay_signing_key_b64 / relay_token_key_b64 is configured; relay tokens disabled");
                None
            }
        };
        // 0027: the document resolver is built over the provider, which is
        // built over this repository, so it is attached post-construction.
        repository.attach_manifest_document_reader(Arc::new(
            crate::storage::manifest_doc::ProviderManifestDocumentReader::new(storage_provider.clone()),
        ));
        Arc::new(Self {
            stamp_keys: StampKeys::new(config.signing_secret.clone(), config.signing_secret_previous.clone()),
            repository,
            blob_signer,
            storage_provider,
            storage_links,
            realtime,
            config,
            storage_usage_cache: StorageUsageCache::new(),
            auth,
            http,
            relay_keys,
        })
    }
}
