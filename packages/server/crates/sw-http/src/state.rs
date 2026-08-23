//! Application state shared by handlers.

use std::sync::atomic::{AtomicU64, AtomicU8, Ordering};
use std::sync::Arc;

use parking_lot::RwLock;
use sw_core::caches::SessionCache;
use sw_core::realtime::local::Realtime;
use sw_core::service::Svc;
use sw_core::storage::fs::FsStorageProvider;
use sw_core::Config;

/// Testkit socket mode (`/__test/ws-mode`): normal | blackhole | reject.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum WsMode {
    Normal = 0,
    Blackhole = 1,
    Reject = 2,
}

impl WsMode {
    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "normal" => Some(Self::Normal),
            "blackhole" => Some(Self::Blackhole),
            "reject" => Some(Self::Reject),
            _ => None,
        }
    }
}

/// Everything that is rebuilt on `/__test/reset` (a "fresh universe").
pub struct AppStateInner {
    pub svc: Svc,
    pub realtime: Arc<Realtime>,
    pub sessions: SessionCache,
    /// The fs provider when running in fs/"r2" mode.
    pub fs: Option<Arc<FsStorageProvider>>,
    /// Testkit: storage introspection + fake Drive upload endpoint + the
    /// services-key private half the Java integration test forges certs with.
    pub test_storage: Option<Arc<dyn crate::routes::testkit::TestStorageInspector>>,
    pub fake_drive: Option<Arc<dyn crate::routes::testkit::FakeDriveUploads>>,
    /// Testkit: Drive failure injection (`POST /__test/drive-mode`).
    pub drive_fail: Option<Arc<dyn crate::routes::testkit::DriveFailureControl>>,
    /// Testkit: the in-process fake S3 service (`GET /__test/s3`).
    pub test_s3: Option<Arc<dyn crate::routes::testkit::S3TestInfo>>,
    pub test_cert_private_key_pkcs8_b64: Option<String>,
}

pub struct AppState {
    inner: RwLock<Arc<AppStateInner>>,
    pub config: Arc<Config>,
    pub ws_mode: AtomicU8,
    pub conn_ids: AtomicU64,
    /// Rebuilds the inner state (testkit reset); `None` in production.
    pub rebuild: Option<
        Box<
            dyn Fn() -> std::pin::Pin<Box<dyn std::future::Future<Output = Arc<AppStateInner>> + Send>>
                + Send
                + Sync,
        >,
    >,
    pub request_log: crate::routes::testkit::RequestLog,
}

impl AppState {
    pub fn new(inner: Arc<AppStateInner>, config: Arc<Config>) -> Arc<Self> {
        Arc::new(Self {
            inner: RwLock::new(inner),
            config,
            ws_mode: AtomicU8::new(WsMode::Normal as u8),
            conn_ids: AtomicU64::new(1),
            rebuild: None,
            request_log: crate::routes::testkit::RequestLog::default(),
        })
    }

    pub fn with_rebuild(
        mut self: Arc<Self>,
        rebuild: Box<
            dyn Fn() -> std::pin::Pin<Box<dyn std::future::Future<Output = Arc<AppStateInner>> + Send>>
                + Send
                + Sync,
        >,
    ) -> Arc<Self> {
        Arc::get_mut(&mut self).expect("unshared state").rebuild = Some(rebuild);
        self
    }

    pub fn inner(&self) -> Arc<AppStateInner> {
        self.inner.read().clone()
    }

    pub fn svc(&self) -> Svc {
        self.inner.read().svc.clone()
    }

    pub fn replace_inner(&self, inner: Arc<AppStateInner>) {
        *self.inner.write() = inner;
    }

    pub fn ws_mode(&self) -> WsMode {
        match self.ws_mode.load(Ordering::Relaxed) {
            1 => WsMode::Blackhole,
            2 => WsMode::Reject,
            _ => WsMode::Normal,
        }
    }

    pub fn next_conn_id(&self) -> u64 {
        self.conn_ids.fetch_add(1, Ordering::Relaxed)
    }
}
