//! `FakeDriveProvider`: an in-memory resumable-capable provider standing in
//! for Google Drive. It behaves like the real Drive provider where the service
//! layer can observe the difference — `storage_objects` rows are the
//! authoritative index, sessions carry received bytes, and probes report the
//! provider's own view — so direct-upload and Drive-binding paths can be
//! exercised without a network.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};

use async_trait::async_trait;
use bytes::Bytes;
use parking_lot::Mutex;
use sw_contracts::StorageProviderType;
use sw_core::http_error::{HttpError, HttpResult};
use sw_core::storage::{
    AccountCleanupCapable, BlobRange, PutBody, ResumableProbe, ResumableUploadCapable, StorageBinding,
    StorageProvider, StorageQuota, StoredBlob,
};
use sw_db::repo::StorageObjectRecord;
use sw_db::{time, Repository};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionState {
    Incomplete,
    Complete,
    Expired,
}

#[derive(Debug, Clone)]
pub struct FakeSession {
    pub storage_key: String,
    pub content_type: String,
    pub expected_size: i64,
    pub state: SessionState,
    pub received: i64,
    pub file_id: Option<String>,
    pub size: Option<i64>,
}

#[derive(Debug, Clone)]
struct StoredObject {
    bytes: Bytes,
    content_type: String,
}

#[derive(Default)]
struct Counters {
    uploads: HashMap<String, u32>,
    downloads: HashMap<String, u32>,
}

pub struct FakeDriveProvider {
    repo: Repository,
    objects: Mutex<HashMap<String, StoredObject>>,
    sessions: Mutex<HashMap<String, FakeSession>>,
    deleted_file_ids: Mutex<Vec<String>>,
    /// The provider's own per-account view of the appDataFolder: file ids that
    /// exist regardless of `storage_objects` rows (account-cleanup sweeps).
    app_files: Mutex<HashMap<String, Vec<String>>>,
    revoked_accounts: Mutex<Vec<String>>,
    auth_dead_accounts: Mutex<Vec<String>>,
    counters: Mutex<Counters>,
    quota: Mutex<StorageQuota>,
    next_id: AtomicU64,
}

impl FakeDriveProvider {
    pub fn new(repo: Repository) -> Self {
        Self {
            repo,
            objects: Mutex::new(HashMap::new()),
            sessions: Mutex::new(HashMap::new()),
            deleted_file_ids: Mutex::new(Vec::new()),
            app_files: Mutex::new(HashMap::new()),
            revoked_accounts: Mutex::new(Vec::new()),
            auth_dead_accounts: Mutex::new(Vec::new()),
            counters: Mutex::new(Counters::default()),
            quota: Mutex::new(StorageQuota::default()),
            next_id: AtomicU64::new(0),
        }
    }

    fn register_app_file(&self, account_id: &str, file_id: &str) {
        let mut files = self.app_files.lock();
        let entry = files.entry(account_id.to_string()).or_default();
        if !entry.iter().any(|f| f == file_id) {
            entry.push(file_id.to_string());
        }
    }

    fn unregister_app_file(&self, account_id: &str, file_id: &str) {
        if let Some(entry) = self.app_files.lock().get_mut(account_id) {
            entry.retain(|f| f != file_id);
        }
    }

    fn next(&self, prefix: &str) -> String {
        format!("{prefix}-{}", self.next_id.fetch_add(1, Ordering::SeqCst) + 1)
    }

    fn account_of(binding: &StorageBinding) -> HttpResult<String> {
        binding.storage_account_id.clone().ok_or_else(|| {
            HttpError::new(400, "missing_storage_account", "World is not linked to a storage account.")
        })
    }

    async fn record_row(
        &self,
        account_id: &str,
        storage_key: &str,
        object_id: &str,
        content_type: &str,
        size: i64,
    ) -> HttpResult<()> {
        let now = time::now_iso();
        self.register_app_file(account_id, object_id);
        self.repo
            .upsert_storage_object(StorageObjectRecord {
                provider: StorageProviderType::GoogleDrive,
                storage_account_id: account_id.to_string(),
                storage_key: storage_key.to_string(),
                object_id: object_id.to_string(),
                content_type: content_type.to_string(),
                size,
                created_at: now.clone(),
                updated_at: now,
            })
            .await?;
        Ok(())
    }

    // -- test controls -------------------------------------------------------

    /// Feeds bytes to an open session the way the client's chunk PUTs do.
    /// Reaching the declared size completes the session.
    pub fn append_chunk(&self, session_url: &str, bytes: &[u8]) {
        let mut sessions = self.sessions.lock();
        let Some(session) = sessions.get_mut(session_url) else { return };
        if session.state == SessionState::Expired {
            return;
        }
        session.received += bytes.len() as i64;
        if session.received >= session.expected_size {
            session.state = SessionState::Complete;
            session.size = Some(session.received);
            if session.file_id.is_none() {
                session.file_id = Some(format!("file-{}", self.next_id.fetch_add(1, Ordering::SeqCst) + 1));
            }
        }
    }

    /// Force-completes a session with provider-reported facts (the shape a
    /// size mismatch takes: the stored object is not what was declared).
    pub fn complete_session(&self, session_url: &str, file_id: &str, size: i64) {
        if let Some(session) = self.sessions.lock().get_mut(session_url) {
            session.state = SessionState::Complete;
            session.file_id = Some(file_id.to_string());
            session.size = Some(size);
            session.received = size;
        }
    }

    pub fn expire_session(&self, session_url: &str) {
        if let Some(session) = self.sessions.lock().get_mut(session_url) {
            session.state = SessionState::Expired;
        }
    }

    pub fn session(&self, session_url: &str) -> Option<FakeSession> {
        self.sessions.lock().get(session_url).cloned()
    }

    pub fn deleted_file_ids(&self) -> Vec<String> {
        self.deleted_file_ids.lock().clone()
    }

    /// Plants a Drive file that has no `storage_objects` row (the orphan an
    /// account-cleanup sweep must still find and delete).
    pub fn add_orphan_app_file(&self, account_id: &str, file_id: &str) {
        self.register_app_file(account_id, file_id);
    }

    /// File ids the provider still holds for an account.
    pub fn app_file_ids(&self, account_id: &str) -> Vec<String> {
        self.app_files.lock().get(account_id).cloned().unwrap_or_default()
    }

    /// Storage account ids whose OAuth access was revoked.
    pub fn revoked_accounts(&self) -> Vec<String> {
        self.revoked_accounts.lock().clone()
    }

    /// Simulates a grant revoked at Google: cleanup listings for this account
    /// fail the way a dead refresh token does.
    pub fn set_cleanup_auth_dead(&self, account_id: &str) {
        self.auth_dead_accounts.lock().push(account_id.to_string());
    }

    pub fn upload_count(&self, storage_key: &str) -> u32 {
        self.counters.lock().uploads.get(storage_key).copied().unwrap_or(0)
    }

    pub fn download_count(&self, storage_key: &str) -> u32 {
        self.counters.lock().downloads.get(storage_key).copied().unwrap_or(0)
    }

    pub fn set_quota(&self, used_bytes: Option<i64>, total_bytes: Option<i64>) {
        *self.quota.lock() = StorageQuota { used_bytes, total_bytes };
    }

    /// Bytes stored under a key (tests assert relayed uploads landed).
    pub fn read_all(&self, storage_key: &str) -> Option<Bytes> {
        self.objects.lock().get(storage_key).map(|o| o.bytes.clone())
    }
}

#[async_trait]
impl StorageProvider for FakeDriveProvider {
    fn provider(&self) -> StorageProviderType {
        StorageProviderType::GoogleDrive
    }

    async fn exists(&self, binding: &StorageBinding, storage_key: &str) -> HttpResult<bool> {
        let account = Self::account_of(binding)?;
        Ok(self
            .repo
            .get_storage_object(StorageProviderType::GoogleDrive, &account, storage_key)
            .await?
            .is_some())
    }

    async fn put(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        body: PutBody,
        content_type: &str,
    ) -> HttpResult<()> {
        let account = Self::account_of(binding)?;
        let bytes = body.into_bytes().await?;
        let size = bytes.len() as i64;
        let object_id = self.next("obj");
        self.objects
            .lock()
            .insert(storage_key.to_string(), StoredObject { bytes, content_type: content_type.into() });
        *self.counters.lock().uploads.entry(storage_key.to_string()).or_insert(0) += 1;
        self.record_row(&account, storage_key, &object_id, content_type, size).await
    }

    async fn get(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        range: Option<&BlobRange>,
    ) -> HttpResult<Option<StoredBlob>> {
        let account = Self::account_of(binding)?;
        if self
            .repo
            .get_storage_object(StorageProviderType::GoogleDrive, &account, storage_key)
            .await?
            .is_none()
        {
            return Ok(None);
        }
        let Some(object) = self.objects.lock().get(storage_key).cloned() else { return Ok(None) };
        *self.counters.lock().downloads.entry(storage_key.to_string()).or_insert(0) += 1;
        let total = object.bytes.len() as i64;
        let Some(r) = range else {
            return Ok(Some(StoredBlob::from_bytes(object.bytes, object.content_type)));
        };
        if r.offset >= total {
            return Err(HttpError::new(
                416,
                "range_not_satisfiable",
                "Requested range is beyond the end of the stored blob.",
            ));
        }
        let end = r.end_inclusive.unwrap_or(total - 1).min(total - 1);
        let slice = object.bytes.slice(r.offset as usize..=(end as usize));
        let mut blob = StoredBlob::from_bytes(slice, object.content_type);
        blob.status = 206;
        blob.content_range = Some(format!("bytes {}-{}/{}", r.offset, end, total));
        Ok(Some(blob))
    }

    async fn delete(&self, binding: &StorageBinding, storage_key: &str) -> HttpResult<()> {
        let account = Self::account_of(binding)?;
        if let Some(object) =
            self.repo.get_storage_object(StorageProviderType::GoogleDrive, &account, storage_key).await?
        {
            self.unregister_app_file(&account, &object.object_id);
        }
        self.objects.lock().remove(storage_key);
        self.repo.delete_storage_object(StorageProviderType::GoogleDrive, &account, storage_key).await?;
        Ok(())
    }

    async fn quota(&self, _binding: &StorageBinding) -> HttpResult<StorageQuota> {
        Ok(*self.quota.lock())
    }

    fn resumable(&self) -> Option<&dyn ResumableUploadCapable> {
        Some(self)
    }

    fn account_cleanup(&self) -> Option<&dyn AccountCleanupCapable> {
        Some(self)
    }
}

#[async_trait]
impl AccountCleanupCapable for FakeDriveProvider {
    async fn list_account_object_ids(
        &self,
        binding: &StorageBinding,
        _page_token: Option<&str>,
    ) -> HttpResult<(Vec<String>, Option<String>)> {
        let account = Self::account_of(binding)?;
        if self.auth_dead_accounts.lock().contains(&account) {
            return Err(HttpError::new(
                401,
                "drive_reauth_required",
                "Google Drive authorization needs to be refreshed.",
            ));
        }
        Ok((self.app_file_ids(&account), None))
    }

    async fn delete_account_object(&self, binding: &StorageBinding, file_id: &str) -> HttpResult<()> {
        let account = Self::account_of(binding)?;
        self.unregister_app_file(&account, file_id);
        self.deleted_file_ids.lock().push(file_id.to_string());
        Ok(())
    }

    async fn revoke_account_access(&self, binding: &StorageBinding) -> HttpResult<()> {
        let account = Self::account_of(binding)?;
        self.revoked_accounts.lock().push(account);
        Ok(())
    }
}

#[async_trait]
impl ResumableUploadCapable for FakeDriveProvider {
    async fn create_resumable_session(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        content_type: &str,
        expected_size: i64,
    ) -> HttpResult<String> {
        Self::account_of(binding)?;
        let url = format!("https://drive.invalid/session/{}", self.next("s"));
        self.sessions.lock().insert(
            url.clone(),
            FakeSession {
                storage_key: storage_key.to_string(),
                content_type: content_type.to_string(),
                expected_size,
                state: SessionState::Incomplete,
                received: 0,
                file_id: None,
                size: None,
            },
        );
        Ok(url)
    }

    async fn probe_resumable_session(
        &self,
        _binding: &StorageBinding,
        session_url: &str,
        _expected_size: i64,
    ) -> HttpResult<ResumableProbe> {
        let sessions = self.sessions.lock();
        let Some(session) = sessions.get(session_url) else { return Ok(ResumableProbe::Expired) };
        Ok(match session.state {
            SessionState::Expired => ResumableProbe::Expired,
            SessionState::Incomplete => ResumableProbe::Incomplete { received_up_to: session.received },
            SessionState::Complete => ResumableProbe::Complete {
                file_id: session.file_id.clone().unwrap_or_default(),
                size: session.size.unwrap_or(session.received),
            },
        })
    }

    async fn register_uploaded_object(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        file_id: &str,
        size: i64,
        content_type: &str,
    ) -> HttpResult<()> {
        let account = Self::account_of(binding)?;
        // Direct uploads bypass `put`, but the bytes still have to be
        // readable: synthesize a body of the declared length so download
        // paths over a directly uploaded key behave.
        self.objects.lock().entry(storage_key.to_string()).or_insert_with(|| StoredObject {
            bytes: Bytes::from(vec![0u8; size.max(0) as usize]),
            content_type: content_type.to_string(),
        });
        *self.counters.lock().uploads.entry(storage_key.to_string()).or_insert(0) += 1;
        self.record_row(&account, storage_key, file_id, content_type, size).await
    }

    async fn delete_object_by_id(&self, binding: &StorageBinding, file_id: &str) -> HttpResult<()> {
        if let Ok(account) = Self::account_of(binding) {
            self.unregister_app_file(&account, file_id);
        }
        self.deleted_file_ids.lock().push(file_id.to_string());
        Ok(())
    }
}
