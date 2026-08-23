//! `IntegrationDriveProvider`: port of the Bun harness's
//! `FakeGoogleDriveStorageProvider`; a resumable-capable stand-in for Google
//! Drive whose session URLs point back at the backend's own
//! `/__fake-drive/upload/:id` endpoint (faithful 308/Range/`bytes */N`
//! semantics), with memory or disk-backed blob bytes and the
//! `/__test/storage` snapshot. Real 0.4.0+ mod clients exercise the
//! direct-to-Drive path against it.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use async_trait::async_trait;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use bytes::Bytes;
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use sw_contracts::StorageProviderType;
use sw_core::http_error::{HttpError, HttpResult};
use sw_core::storage::{
    BlobRange, PutBody, ResumableProbe, ResumableUploadCapable, StorageBinding, StorageProvider,
    StorageQuota, StoredBlob,
};
use sw_db::repo::StorageObjectRecord;
use sw_db::{time, Repository};

#[derive(Debug, Clone, Serialize, Deserialize)]
struct StoredEntry {
    content_type: String,
    object_id: String,
    size: i64,
    /// Memory mode.
    #[serde(skip)]
    bytes: Option<Bytes>,
    /// Disk mode: file under blob_dir.
    file_name: Option<String>,
}

struct FakeSession {
    file_id: String,
    storage_key: String,
    expected_size: i64,
    received: i64,
    chunk_puts: u32,
    completed: bool,
    temp_file: Option<PathBuf>,
    parts: Vec<Bytes>,
}

/// E2E failure injection (`POST /__test/drive-mode`): which Drive failure
/// every storage *operation* (put, session create, register, chunk PUT)
/// reports. Deliberately NOT wired into `quota()`; the quota preflight is
/// 15-min cached server-side, which would make injected failures racy.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DriveFailKind {
    StorageFull,
    ReauthRequired,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct DriveFailMode {
    pub kind: Option<DriveFailKind>,
    /// Fail this many operations then self-clear; `None` = sticky until
    /// switched off (the release coordinator's auto-retry ladder heals
    /// one-shot failures silently, so sticky is the default).
    pub remaining: Option<u32>,
}

pub struct IntegrationDriveProvider {
    repo: Repository,
    public_base_url: String,
    blob_dir: Option<PathBuf>,
    entries: Mutex<HashMap<String, StoredEntry>>,
    sessions: Mutex<HashMap<String, FakeSession>>,
    download_counts: Mutex<HashMap<String, u64>>,
    session_counter: AtomicU64,
    fail_mode: Mutex<DriveFailMode>,
}

impl IntegrationDriveProvider {
    pub fn new(repo: Repository, public_base_url: impl Into<String>, blob_dir: Option<PathBuf>) -> Self {
        let mut entries = HashMap::new();
        if let Some(dir) = &blob_dir {
            std::fs::create_dir_all(dir).ok();
            if let Ok(text) = std::fs::read_to_string(dir.join("index.json")) {
                if let Ok(index) = serde_json::from_str::<Vec<(String, StoredEntry)>>(&text) {
                    entries = index.into_iter().collect();
                }
            }
        }
        Self {
            repo,
            public_base_url: public_base_url.into(),
            blob_dir,
            entries: Mutex::new(entries),
            sessions: Mutex::new(HashMap::new()),
            download_counts: Mutex::new(HashMap::new()),
            session_counter: AtomicU64::new(0),
            fail_mode: Mutex::new(DriveFailMode::default()),
        }
    }

    pub fn set_fail_mode(&self, mode: DriveFailMode) {
        *self.fail_mode.lock() = mode;
    }

    /// The failure the next storage operation must report, decrementing a
    /// bounded mode's counter.
    fn take_failure(&self) -> Option<DriveFailKind> {
        let mut mode = self.fail_mode.lock();
        let kind = mode.kind?;
        match &mut mode.remaining {
            Some(0) => {
                mode.kind = None;
                mode.remaining = None;
                None
            }
            Some(n) => {
                *n -= 1;
                if *n == 0 {
                    mode.kind = None;
                    mode.remaining = None;
                }
                Some(kind)
            }
            None => Some(kind),
        }
    }

    fn failure_error(kind: DriveFailKind) -> HttpError {
        match kind {
            DriveFailKind::StorageFull => sw_core::storage::drive::drive_storage_full_error(),
            DriveFailKind::ReauthRequired => HttpError::new(
                401,
                "drive_reauth_required",
                "Google Drive authorization needs to be refreshed.",
            ),
        }
    }

    fn persist_index(&self) {
        if let Some(dir) = &self.blob_dir {
            let index: Vec<(String, StoredEntry)> =
                self.entries.lock().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
            let _ = std::fs::write(dir.join("index.json"), serde_json::to_string(&index).unwrap_or_default());
        }
    }

    fn blob_path(&self, file_name: &str) -> PathBuf {
        self.blob_dir.as_ref().expect("disk mode").join(file_name)
    }

    fn file_name_for(storage_key: &str) -> String {
        format!("blob-{}", sw_core::service::signer::url_encode(storage_key))
    }

    async fn upsert_row(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        object_id: &str,
        content_type: &str,
        size: i64,
    ) -> HttpResult<()> {
        if let Some(account) = &binding.storage_account_id {
            let now = time::now_iso();
            self.repo
                .upsert_storage_object(StorageObjectRecord {
                    provider: StorageProviderType::GoogleDrive,
                    storage_account_id: account.clone(),
                    storage_key: storage_key.to_string(),
                    object_id: object_id.to_string(),
                    content_type: content_type.to_string(),
                    size,
                    created_at: now.clone(),
                    updated_at: now,
                })
                .await?;
        }
        Ok(())
    }

    /// `/__test/storage` body.
    pub fn snapshot(&self) -> serde_json::Value {
        let mut objects: Vec<serde_json::Value> = self
            .entries
            .lock()
            .iter()
            .map(|(k, e)| serde_json::json!({"storageKey": k, "contentType": e.content_type, "size": e.size}))
            .collect();
        objects.sort_by(|a, b| a["storageKey"].as_str().cmp(&b["storageKey"].as_str()));
        let mut uploads: Vec<serde_json::Value> = self
            .sessions
            .lock()
            .values()
            .map(|s| serde_json::json!({"storageKey": s.storage_key, "expectedSize": s.expected_size, "received": s.received, "chunkPuts": s.chunk_puts, "completed": s.completed}))
            .collect();
        uploads.sort_by(|a, b| a["storageKey"].as_str().cmp(&b["storageKey"].as_str()));
        let mut downloads: Vec<serde_json::Value> = self
            .download_counts
            .lock()
            .iter()
            .map(|(k, c)| serde_json::json!({"storageKey": k, "count": c}))
            .collect();
        downloads.sort_by(|a, b| a["storageKey"].as_str().cmp(&b["storageKey"].as_str()));
        serde_json::json!({"provider": "google-drive", "objects": objects, "uploads": uploads, "downloads": downloads})
    }

    fn session_status_response(session: &FakeSession) -> Response {
        if session.completed {
            return (
                StatusCode::OK,
                [("content-type", "application/json")],
                serde_json::json!({"id": session.file_id, "size": session.received.to_string()}).to_string(),
            )
                .into_response();
        }
        let mut resp = StatusCode::PERMANENT_REDIRECT.into_response();
        if session.received > 0 {
            resp.headers_mut().insert("range", format!("bytes=0-{}", session.received - 1).parse().unwrap());
        }
        resp
    }

    /// The chunk-PUT endpoint behind the session URLs (the stand-in for
    /// Google's upload host). A leaked bearer here is a client bug → 401.
    pub async fn handle_upload_request(&self, upload_id: &str, headers: &HeaderMap, body: Bytes) -> Response {
        fn json_err(status: StatusCode, error: &str, message: String) -> Response {
            (
                status,
                [("content-type", "application/json")],
                serde_json::json!({"error": error, "message": message}).to_string(),
            )
                .into_response()
        }
        if headers.contains_key("authorization") {
            return json_err(
                StatusCode::UNAUTHORIZED,
                "bearer_leak",
                "Client sent an Authorization header to the fake Drive upload host.".into(),
            );
        }
        // Injected failures: the shape the client's DriveStorageFullException
        // classifier needs is a 403 whose body mentions storageQuotaExceeded.
        if let Some(kind) = self.take_failure() {
            return match kind {
                DriveFailKind::StorageFull => json_err(
                    StatusCode::FORBIDDEN,
                    "storageQuotaExceeded",
                    "The user's Drive storage quota has been exceeded (storageQuotaExceeded).".into(),
                ),
                DriveFailKind::ReauthRequired => json_err(
                    StatusCode::UNAUTHORIZED,
                    "drive_reauth_required",
                    "Google Drive authorization needs to be refreshed.".into(),
                ),
            };
        }
        let mut sessions = self.sessions.lock();
        let Some(session) = sessions.get_mut(upload_id) else {
            return json_err(StatusCode::NOT_FOUND, "not_found", "Unknown or expired upload session.".into());
        };
        let content_range =
            headers.get("content-range").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
        if let Some(rest) = content_range.strip_prefix("bytes */") {
            if rest.chars().all(|c| c.is_ascii_digit()) && !rest.is_empty() {
                return Self::session_status_response(session);
            }
        }
        let parsed = content_range.strip_prefix("bytes ").and_then(|r| {
            let (range, total) = r.split_once('/')?;
            let (start, end) = range.split_once('-')?;
            Some((start.parse::<i64>().ok()?, end.parse::<i64>().ok()?, total.parse::<i64>().ok()?))
        });
        let Some((start, end_inclusive, total)) = parsed else {
            return json_err(
                StatusCode::BAD_REQUEST,
                "bad_request",
                format!("Unparseable Content-Range: {content_range}"),
            );
        };
        if total != session.expected_size {
            return json_err(
                StatusCode::BAD_REQUEST,
                "bad_request",
                format!("Content-Range total {total} does not match session size {}.", session.expected_size),
            );
        }
        session.chunk_puts += 1;
        if start > session.received {
            return Self::session_status_response(session);
        }
        if body.len() as i64 != end_inclusive - start + 1 {
            return json_err(
                StatusCode::BAD_REQUEST,
                "bad_request",
                format!("Body length {} does not match Content-Range {content_range}.", body.len()),
            );
        }
        if end_inclusive + 1 > session.received {
            let fresh = body.slice((session.received - start) as usize..);
            match &session.temp_file {
                Some(path) => {
                    use std::io::Write;
                    let mut f = std::fs::OpenOptions::new()
                        .append(true)
                        .create(true)
                        .open(path)
                        .expect("session temp file");
                    f.write_all(&fresh).expect("append chunk");
                }
                None => session.parts.push(fresh),
            }
            session.received = end_inclusive + 1;
        }
        if session.received == session.expected_size {
            session.completed = true;
        }
        Self::session_status_response(session)
    }

    fn io(e: std::io::Error) -> HttpError {
        HttpError::new(502, "storage_io_failed", format!("fake drive I/O failed: {e}"))
    }
}

fn concat(parts: &[Bytes]) -> Bytes {
    let mut v = Vec::with_capacity(parts.iter().map(|p| p.len()).sum());
    for p in parts {
        v.extend_from_slice(p);
    }
    Bytes::from(v)
}

#[async_trait]
impl StorageProvider for IntegrationDriveProvider {
    fn provider(&self) -> StorageProviderType {
        StorageProviderType::GoogleDrive
    }

    async fn exists(&self, _binding: &StorageBinding, storage_key: &str) -> HttpResult<bool> {
        Ok(self.entries.lock().contains_key(storage_key))
    }

    async fn put(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        body: PutBody,
        content_type: &str,
    ) -> HttpResult<()> {
        if let Some(kind) = self.take_failure() {
            return Err(Self::failure_error(kind));
        }
        let bytes = body.into_bytes().await?;
        let entry = if self.blob_dir.is_some() {
            let file_name = Self::file_name_for(storage_key);
            tokio::fs::write(self.blob_path(&file_name), &bytes).await.map_err(Self::io)?;
            StoredEntry {
                content_type: content_type.into(),
                object_id: format!("fake-{storage_key}"),
                size: bytes.len() as i64,
                bytes: None,
                file_name: Some(file_name),
            }
        } else {
            StoredEntry {
                content_type: content_type.into(),
                object_id: format!("fake-{storage_key}"),
                size: bytes.len() as i64,
                bytes: Some(bytes),
                file_name: None,
            }
        };
        let (object_id, size) = (entry.object_id.clone(), entry.size);
        self.entries.lock().insert(storage_key.to_string(), entry);
        self.persist_index();
        self.upsert_row(binding, storage_key, &object_id, content_type, size).await
    }

    async fn get(
        &self,
        _binding: &StorageBinding,
        storage_key: &str,
        range: Option<&BlobRange>,
    ) -> HttpResult<Option<StoredBlob>> {
        let Some(entry) = self.entries.lock().get(storage_key).cloned() else { return Ok(None) };
        *self.download_counts.lock().entry(storage_key.to_string()).or_insert(0) += 1;
        let total = entry.size;
        if let Some(r) = range {
            if r.offset >= total {
                return Err(HttpError::new(
                    416,
                    "range_not_satisfiable",
                    "Requested range is beyond the end of the stored blob.",
                ));
            }
        }
        let offset = range.map(|r| r.offset).unwrap_or(0);
        let end_inclusive = match range {
            Some(r) => r.end_inclusive.unwrap_or(total - 1).min(total - 1),
            None => total - 1,
        };
        let len = (end_inclusive - offset + 1).max(0);
        let content_range = range.map(|_| format!("bytes {offset}-{end_inclusive}/{total}"));
        let status = if range.is_some() { 206 } else { 200 };
        let body: sw_core::storage::BodyStream = match (&entry.file_name, &entry.bytes) {
            (Some(name), _) => {
                use tokio::io::{AsyncReadExt, AsyncSeekExt};
                let mut f = tokio::fs::File::open(self.blob_path(name)).await.map_err(Self::io)?;
                f.seek(std::io::SeekFrom::Start(offset as u64)).await.map_err(Self::io)?;
                Box::pin(tokio_util::io::ReaderStream::with_capacity(f.take(len as u64), 1024 * 1024))
            }
            (None, Some(bytes)) => {
                let slice = bytes.slice(offset as usize..(end_inclusive + 1) as usize);
                Box::pin(futures::stream::once(async move { Ok(slice) }))
            }
            (None, None) => Box::pin(futures::stream::empty()),
        };
        Ok(Some(StoredBlob {
            body,
            content_type: entry.content_type,
            size: Some(len),
            status,
            content_range,
        }))
    }

    async fn delete(&self, binding: &StorageBinding, storage_key: &str) -> HttpResult<()> {
        if let Some(entry) = self.entries.lock().remove(storage_key) {
            if let Some(name) = entry.file_name {
                let _ = std::fs::remove_file(self.blob_path(&name));
            }
        }
        self.persist_index();
        if let Some(account) = &binding.storage_account_id {
            self.repo.delete_storage_object(StorageProviderType::GoogleDrive, account, storage_key).await?;
        }
        Ok(())
    }

    async fn quota(&self, _binding: &StorageBinding) -> HttpResult<StorageQuota> {
        let used: i64 = self.entries.lock().values().map(|e| e.size).sum();
        Ok(StorageQuota { used_bytes: Some(used), total_bytes: None })
    }

    fn resumable(&self, _binding: &StorageBinding) -> Option<&dyn ResumableUploadCapable> {
        Some(self)
    }
}

#[async_trait]
impl ResumableUploadCapable for IntegrationDriveProvider {
    async fn create_resumable_session(
        &self,
        _binding: &StorageBinding,
        storage_key: &str,
        _content_type: &str,
        expected_size: i64,
    ) -> HttpResult<String> {
        if let Some(kind) = self.take_failure() {
            return Err(Self::failure_error(kind));
        }
        let n = self.session_counter.fetch_add(1, Ordering::SeqCst) + 1;
        let session_id = format!("s{n}-{}", &uuid::Uuid::new_v4().simple().to_string()[..8]);
        let temp_file = self.blob_dir.as_ref().map(|_| self.blob_path(&format!("session-{session_id}.part")));
        if let Some(p) = &temp_file {
            std::fs::write(p, b"").map_err(Self::io)?;
        }
        self.sessions.lock().insert(
            session_id.clone(),
            FakeSession {
                file_id: format!("fake-upload-{session_id}"),
                storage_key: storage_key.to_string(),
                expected_size,
                received: 0,
                chunk_puts: 0,
                completed: false,
                temp_file,
                parts: Vec::new(),
            },
        );
        Ok(format!("{}/__fake-drive/upload/{session_id}", self.public_base_url))
    }

    async fn probe_resumable_session(
        &self,
        _binding: &StorageBinding,
        session_url: &str,
        _expected_size: i64,
    ) -> HttpResult<ResumableProbe> {
        let id = session_url.rsplit('/').next().unwrap_or("");
        let sessions = self.sessions.lock();
        let Some(s) = sessions.get(id) else { return Ok(ResumableProbe::Expired) };
        Ok(if s.completed {
            ResumableProbe::Complete { file_id: s.file_id.clone(), size: s.received }
        } else {
            ResumableProbe::Incomplete { received_up_to: s.received }
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
        if let Some(kind) = self.take_failure() {
            return Err(Self::failure_error(kind));
        }
        let entry = {
            let sessions = self.sessions.lock();
            let Some(session) = sessions.values().find(|s| s.file_id == file_id && s.completed) else {
                return Err(HttpError::new(
                    500,
                    "internal_error",
                    format!("Fake Drive has no completed session for file {file_id}."),
                ));
            };
            match &session.temp_file {
                Some(temp) => {
                    let file_name = Self::file_name_for(storage_key);
                    std::fs::rename(temp, self.blob_path(&file_name)).map_err(Self::io)?;
                    StoredEntry {
                        content_type: content_type.into(),
                        object_id: file_id.into(),
                        size,
                        bytes: None,
                        file_name: Some(file_name),
                    }
                }
                None => StoredEntry {
                    content_type: content_type.into(),
                    object_id: file_id.into(),
                    size,
                    bytes: Some(concat(&session.parts)),
                    file_name: None,
                },
            }
        };
        self.entries.lock().insert(storage_key.to_string(), entry);
        self.persist_index();
        self.upsert_row(binding, storage_key, file_id, content_type, size).await
    }

    async fn delete_object_by_id(&self, _binding: &StorageBinding, file_id: &str) -> HttpResult<()> {
        let mut entries = self.entries.lock();
        let doomed: Vec<String> =
            entries.iter().filter(|(_, e)| e.object_id == file_id).map(|(k, _)| k.clone()).collect();
        for k in doomed {
            if let Some(e) = entries.remove(&k) {
                if let Some(name) = e.file_name {
                    let _ = std::fs::remove_file(self.blob_path(&name));
                }
            }
        }
        drop(entries);
        self.persist_index();
        Ok(())
    }
}

impl sw_http::routes::testkit::TestStorageInspector for IntegrationDriveProvider {
    fn snapshot(&self) -> serde_json::Value {
        IntegrationDriveProvider::snapshot(self)
    }
}

#[async_trait]
impl sw_http::routes::testkit::FakeDriveUploads for IntegrationDriveProvider {
    async fn handle_upload_request(
        &self,
        _method: &str,
        upload_id: &str,
        headers: &HeaderMap,
        body: Bytes,
    ) -> Response {
        IntegrationDriveProvider::handle_upload_request(self, upload_id, headers, body).await
    }
}

/// Keep `Path` in scope for disk helpers.
#[allow(dead_code)]
fn _p(_: &Path) {}
