//! Google Drive blob store (`storage/drive.ts`): appDataFolder objects named
//! `sharedworld-<base64url(storageKey)>`, indexed authoritatively by the
//! `storage_objects` table. Bodies always stream — a GB-scale pack must never
//! be buffered — and every Drive call runs through the retry ladder plus the
//! per-account upload-start pacer.

use std::sync::Arc;
use std::time::{Duration, Instant};

use async_trait::async_trait;
use base64::Engine;
use bytes::Bytes;
use dashmap::DashMap;
use futures::StreamExt;
use parking_lot::Mutex;
use rand::RngExt;
use reqwest::{Method, Response};
use sw_contracts::StorageProviderType;
use sw_db::repo::{StorageAccountRecord, StorageObjectRecord};
use sw_db::Repository;

use super::{
    BlobRange, PutBody, ResumableProbe, ResumableUploadCapable, StorageBinding, StorageProvider,
    StorageQuota, StoredBlob,
};
use crate::config::Config;
use crate::http_error::{HttpError, HttpResult};
use crate::time;

const DEFAULT_API_BASE: &str = "https://www.googleapis.com/drive/v3";
const DEFAULT_TOKEN_URL: &str = "https://oauth2.googleapis.com/token";
const DEFAULT_RETRY_BASE_DELAY_MS: i64 = 750;
const DEFAULT_RETRY_MAX_DELAY_MS: i64 = 8_000;
const DEFAULT_MAX_UPLOAD_STARTS_PER_SECOND: i64 = 3;

/// The terminal, actionable answer for a full Drive: shipped clients never
/// retry a 403 and render this message verbatim.
pub fn drive_storage_full_error() -> HttpError {
    HttpError::new(
        403,
        "drive_storage_full",
        "Your Google Drive is full. Free up space in Drive or delete old SharedWorld backups, then try again.",
    )
}

/// A Drive failure carrying the upstream facts the classifier needs
/// (`HttpError.upstreamStatus` / `upstreamBody` in the TS port).
#[derive(Debug, Clone)]
struct DriveError {
    http: HttpError,
    upstream_status: Option<u16>,
    upstream_body: Option<String>,
}

impl DriveError {
    fn transport(code: &'static str, label: &str, cause: impl std::fmt::Display) -> Self {
        Self {
            http: HttpError::new(502, code, format!("{label} {cause}")),
            upstream_status: None,
            upstream_body: None,
        }
    }
    fn plain(status: u16, code: &'static str, message: impl Into<String>) -> Self {
        Self { http: HttpError::new(status, code, message), upstream_status: None, upstream_body: None }
    }
}

impl From<HttpError> for DriveError {
    fn from(http: HttpError) -> Self {
        Self { http, upstream_status: None, upstream_body: None }
    }
}

impl From<sw_db::DbError> for DriveError {
    fn from(e: sw_db::DbError) -> Self {
        Self { http: HttpError::from(e), upstream_status: None, upstream_body: None }
    }
}

impl From<DriveError> for HttpError {
    fn from(e: DriveError) -> Self {
        e.http
    }
}

type DriveResult<T> = Result<T, DriveError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DriveOp {
    Upload,
    Download,
    Delete,
}

impl DriveOp {
    fn as_str(self) -> &'static str {
        match self {
            Self::Upload => "upload",
            Self::Download => "download",
            Self::Delete => "delete",
        }
    }
    fn max_attempts(self) -> u32 {
        if self == Self::Upload {
            5
        } else {
            4
        }
    }
}

/// A rebuildable authenticated Drive request (the retry ladder replays it).
#[derive(Debug, Clone)]
struct DriveReq {
    method: Method,
    url: String,
    headers: Vec<(&'static str, String)>,
    body: Option<Bytes>,
}

impl DriveReq {
    fn get(url: String) -> Self {
        Self { method: Method::GET, url, headers: Vec::new(), body: None }
    }
    fn new(method: Method, url: String) -> Self {
        Self { method, url, headers: Vec::new(), body: None }
    }
    fn header(mut self, name: &'static str, value: impl Into<String>) -> Self {
        self.headers.push((name, value.into()));
        self
    }
    fn body(mut self, bytes: Bytes) -> Self {
        self.body = Some(bytes);
        self
    }
}

pub struct GoogleDriveStorageProvider {
    repository: Repository,
    config: Arc<Config>,
    http: reqwest::Client,
    /// Per-account upload-start pacer. One process, one map — what the
    /// worker's static per-isolate map could never be.
    limiters: DashMap<String, Arc<Mutex<AccountRequestLimiter>>>,
}

impl GoogleDriveStorageProvider {
    pub fn new(repository: Repository, config: Arc<Config>, http: reqwest::Client) -> Self {
        Self { repository, config, http, limiters: DashMap::new() }
    }

    fn api_base(&self) -> &str {
        self.config.google_drive_api_base.as_deref().unwrap_or(DEFAULT_API_BASE)
    }

    fn upload_base(&self) -> String {
        self.api_base().replacen("/drive/v3", "/upload/drive/v3", 1)
    }

    fn token_url(&self) -> &str {
        self.config.google_oauth_token_url.as_deref().unwrap_or(DEFAULT_TOKEN_URL)
    }

    fn positive(value: Option<i64>, fallback: i64) -> i64 {
        value.filter(|v| *v > 0).unwrap_or(fallback)
    }

    async fn require_account(&self, binding: &StorageBinding) -> DriveResult<StorageAccountRecord> {
        let account_id = require_account_id(binding)?;
        self.repository.get_storage_account(&account_id).await?.ok_or_else(|| {
            DriveError::plain(400, "storage_account_not_found", "Linked Google Drive account not found.")
        })
    }

    // -- OAuth ---------------------------------------------------------------

    /// Reuse the stored access token until a minute before expiry, else
    /// refresh. An `invalid_grant` nulls the refresh token so the account
    /// reports unhealthy and the client asks for a fresh connection.
    async fn ensure_access_token(
        &self,
        account: &StorageAccountRecord,
        force_refresh: bool,
    ) -> DriveResult<String> {
        if !force_refresh {
            if let Some(token) = account.access_token.as_deref() {
                let fresh = match account.token_expires_at.as_deref() {
                    None => true,
                    Some(at) => time::parse_iso(at)
                        .is_some_and(|t| time::to_millis(t) > time::to_millis(time::now()) + 60_000),
                };
                if fresh {
                    return Ok(token.to_string());
                }
            }
        }
        let Some(refresh_token) = account.refresh_token.as_deref() else {
            return Err(DriveError::plain(
                401,
                "drive_reauth_required",
                "Google Drive authorization needs to be refreshed.",
            ));
        };

        let form = form_encode(&[
            ("client_id", self.config.google_oauth_client_id.as_deref().unwrap_or("")),
            ("client_secret", self.config.google_oauth_client_secret.as_deref().unwrap_or("")),
            ("refresh_token", refresh_token),
            ("grant_type", "refresh_token"),
        ]);
        let started = Instant::now();
        let response = self
            .http
            .post(self.token_url())
            .header("content-type", "application/x-www-form-urlencoded")
            .body(form)
            .send()
            .await;
        record_drive_request("token_refresh", response.as_ref().map(|r| r.status().as_u16()).ok(), started);
        let reauth = || {
            DriveError::plain(
                401,
                "drive_reauth_required",
                "Google Drive access needs to be renewed. Connect Google Drive again from Minecraft, then retry.",
            )
        };
        let response = match response {
            Ok(r) => r,
            Err(_) => return Err(reauth()),
        };
        if !response.status().is_success() {
            let detail = response.json::<serde_json::Value>().await.ok();
            let invalid_grant =
                detail.as_ref().and_then(|d| d.get("error")?.as_str()).is_some_and(|e| e == "invalid_grant");
            if invalid_grant {
                self.repository
                    .create_or_update_storage_account(StorageAccountRecord {
                        refresh_token: None,
                        updated_at: time::now_iso(),
                        ..account.clone()
                    })
                    .await?;
            }
            return Err(reauth());
        }
        #[derive(serde::Deserialize)]
        struct TokenPayload {
            access_token: String,
            #[serde(default)]
            expires_in: f64,
        }
        let Ok(payload) = response.json::<TokenPayload>().await else { return Err(reauth()) };
        let updated = self
            .repository
            .create_or_update_storage_account(StorageAccountRecord {
                access_token: Some(payload.access_token.clone()),
                token_expires_at: Some(time::plus_ms_iso(time::now(), (payload.expires_in * 1000.0) as i64)),
                updated_at: time::now_iso(),
                ..account.clone()
            })
            .await?;
        Ok(updated.access_token.unwrap_or(payload.access_token))
    }

    // -- request plumbing ----------------------------------------------------

    async fn send_once(
        &self,
        account: &StorageAccountRecord,
        req: &DriveReq,
        retried: bool,
    ) -> DriveResult<Response> {
        let token = self.ensure_access_token(account, retried).await?;
        let mut builder = self.http.request(req.method.clone(), &req.url);
        for (name, value) in &req.headers {
            builder = builder.header(*name, value.as_str());
        }
        builder = builder.header("authorization", format!("Bearer {token}"));
        if let Some(body) = &req.body {
            builder = builder.body(body.clone());
        }
        let op = drive_op_label(&req.method, &req.url);
        let started = Instant::now();
        let result = builder.send().await;
        record_drive_request(op, result.as_ref().map(|r| r.status().as_u16()).ok(), started);
        result.map_err(|e| DriveError::transport("drive_request_failed", "Google Drive request failed.", e))
    }

    /// `driveRequest`: one transparent forced-refresh retry on a 401.
    async fn drive_request(&self, account: &StorageAccountRecord, req: &DriveReq) -> DriveResult<Response> {
        let response = self.send_once(account, req, false).await?;
        if response.status().as_u16() == 401 && account.refresh_token.is_some() {
            return self.send_once(account, req, true).await;
        }
        Ok(response)
    }

    /// `driveRequestChecked`: a non-OK response becomes a thrown error so the
    /// retry ladder can see it. `allow_not_found` turns 404 into `None`.
    async fn drive_request_checked(
        &self,
        account: &StorageAccountRecord,
        req: &DriveReq,
        code: &'static str,
        label: &'static str,
        allow_not_found: bool,
    ) -> DriveResult<Option<Response>> {
        let response = self.drive_request(account, req).await?;
        if allow_not_found && response.status().as_u16() == 404 {
            return Ok(None);
        }
        if !response.status().is_success() {
            return Err(drive_error(response, code, label).await);
        }
        Ok(Some(response))
    }

    fn account_limiter(&self, account_id: &str) -> Arc<Mutex<AccountRequestLimiter>> {
        if let Some(existing) = self.limiters.get(account_id) {
            return existing.clone();
        }
        let starts = Self::positive(
            self.config.drive_max_upload_starts_per_second,
            DEFAULT_MAX_UPLOAD_STARTS_PER_SECOND,
        )
        .max(1);
        self.limiters
            .entry(account_id.to_string())
            .or_insert_with(|| Arc::new(Mutex::new(AccountRequestLimiter::new(starts))))
            .clone()
    }

    async fn schedule_upload_start(&self, account_id: &str) {
        let wait_ms = self.account_limiter(account_id).lock().schedule_upload_start();
        if wait_ms > 0 {
            tokio::time::sleep(Duration::from_millis(wait_ms as u64)).await;
        }
    }

    async fn with_drive_retries<T, F, Fut>(
        &self,
        account: &StorageAccountRecord,
        operation: DriveOp,
        mut task: F,
    ) -> DriveResult<T>
    where
        F: FnMut() -> Fut,
        Fut: std::future::Future<Output = DriveResult<T>>,
    {
        let base_delay_ms =
            Self::positive(self.config.drive_retry_base_delay_ms, DEFAULT_RETRY_BASE_DELAY_MS).max(1);
        let max_delay_ms = Self::positive(self.config.drive_retry_max_delay_ms, DEFAULT_RETRY_MAX_DELAY_MS)
            .max(base_delay_ms);
        let max_attempts = operation.max_attempts();
        let mut attempt = 0u32;

        loop {
            attempt += 1;
            let error = match task().await {
                Ok(value) => return Ok(value),
                Err(e) => e,
            };
            if !is_retryable_drive_failure(&error) || attempt >= max_attempts {
                return Err(self.final_drive_failure(error, operation, account, attempt).await);
            }
            metrics::counter!("drive_retry_total", "op" => operation.as_str()).increment(1);
            let backoff = base_delay_ms.saturating_mul(1i64 << (attempt - 1).min(30)).min(max_delay_ms);
            let jitter_span = std::cmp::max(50, base_delay_ms / 2);
            let delay_ms = backoff + rand::rng().random_range(0..jitter_span);
            tracing::warn!(
                operation = operation.as_str(),
                account_id = %account.id,
                attempt,
                status = ?error.upstream_status,
                delay_ms,
                "SharedWorld retrying Google Drive request"
            );
            tokio::time::sleep(Duration::from_millis(delay_ms as u64)).await;
        }
    }

    /// Terminal handling: log it (a 4xx never reaches the >=500 response
    /// logging, so this is the only record), and turn a missing-consent 403
    /// into the re-link path — Google's granular consent lets a user finish
    /// OAuth without granting Drive access, which is invisible until here.
    async fn final_drive_failure(
        &self,
        error: DriveError,
        operation: DriveOp,
        account: &StorageAccountRecord,
        attempt: u32,
    ) -> DriveError {
        let status = error.upstream_status;
        let body_head = error.upstream_body.clone();
        tracing::warn!(
            operation = operation.as_str(),
            account_id = %account.id,
            attempt,
            status = ?status,
            body_head = ?body_head,
            "SharedWorld Google Drive request failed"
        );
        if status == Some(403) && is_insufficient_scope_body(body_head.as_deref()) {
            let _ = self
                .repository
                .create_or_update_storage_account(StorageAccountRecord {
                    refresh_token: None,
                    updated_at: time::now_iso(),
                    ..account.clone()
                })
                .await;
            return DriveError {
                http: HttpError::new(
                    401,
                    "drive_reauth_required",
                    "Google Drive was connected without the Drive access permission. Reconnect Google Drive from Minecraft and tick the Drive access checkbox on the Google screen.",
                ),
                upstream_status: Some(403),
                upstream_body: body_head,
            };
        }
        if status == Some(403) && is_storage_quota_exceeded_body(body_head.as_deref()) {
            return DriveError {
                http: drive_storage_full_error(),
                upstream_status: Some(403),
                upstream_body: body_head,
            };
        }
        error
    }

    // -- upload variants -----------------------------------------------------

    async fn create_file(
        &self,
        account: &StorageAccountRecord,
        storage_key: &str,
        bytes: &Bytes,
        content_type: &str,
    ) -> DriveResult<String> {
        let boundary = format!("sharedworld-{}", uuid::Uuid::new_v4());
        let metadata = serde_json::json!({
            "name": drive_object_name(storage_key),
            "parents": ["appDataFolder"],
        })
        .to_string();
        let mut body = Vec::with_capacity(bytes.len() + metadata.len() + 256);
        body.extend_from_slice(
            format!("--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{metadata}\r\n")
                .as_bytes(),
        );
        body.extend_from_slice(format!("--{boundary}\r\nContent-Type: {content_type}\r\n\r\n").as_bytes());
        body.extend_from_slice(bytes);
        body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());

        let req = DriveReq::new(Method::POST, format!("{}/files?uploadType=multipart", self.upload_base()))
            .header("content-type", format!("multipart/related; boundary={boundary}"))
            .body(Bytes::from(body));
        let response = self.drive_request(account, &req).await?;
        if !response.status().is_success() {
            return Err(drive_error(response, "drive_upload_failed", "Google Drive upload failed.").await);
        }
        uploaded_file_id(response).await
    }

    async fn update_file(
        &self,
        account: &StorageAccountRecord,
        object_id: &str,
        bytes: &Bytes,
        content_type: &str,
    ) -> DriveResult<String> {
        let req = DriveReq::new(
            Method::PATCH,
            format!("{}/files/{}?uploadType=media", self.upload_base(), url_path_encode(object_id)),
        )
        .header("content-type", content_type.to_string())
        .body(bytes.clone());
        let response = self.drive_request(account, &req).await?;
        if !response.status().is_success() {
            return Err(drive_error(response, "drive_upload_failed", "Google Drive upload failed.").await);
        }
        uploaded_file_id(response).await
    }

    /// Relayed upload as a pass-through: a resumable session (already paced,
    /// retried and id-reusing) plus ONE streaming PUT of the whole body. The
    /// stream cannot be replayed, so there is no mid-transfer retry — the
    /// client's own relay retry re-sends the blob.
    async fn put_streaming(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        stream: super::BodyStream,
        content_type: &str,
        content_length: i64,
    ) -> HttpResult<()> {
        let session_url =
            self.create_resumable_session(binding, storage_key, content_type, content_length).await?;
        let started = Instant::now();
        let response = self
            .http
            .put(&session_url)
            .header("content-range", format!("bytes 0-{}/{}", content_length - 1, content_length))
            .header("content-length", content_length.to_string())
            .body(reqwest::Body::wrap_stream(stream))
            .send()
            .await;
        record_drive_request("upload_put", response.as_ref().map(|r| r.status().as_u16()).ok(), started);
        let response = response.map_err(|e| {
            HttpError::new(502, "drive_upload_failed", format!("Google Drive upload failed. {e}"))
        })?;
        let status = response.status().as_u16();
        if status != 200 && status != 201 {
            let text = response.text().await.unwrap_or_default();
            if status == 403 && is_storage_quota_exceeded_body(Some(&text)) {
                // Terminal user condition — a 502 here made shipped clients
                // burn their transport retries against a full Drive.
                return Err(drive_storage_full_error());
            }
            let detail = if text.is_empty() { String::new() } else { format!(" {}", head(&text, 200)) };
            return Err(HttpError::new(
                502,
                "drive_upload_failed",
                format!("Google Drive upload failed (HTTP {status}).{detail}"),
            ));
        }
        let payload = response.json::<serde_json::Value>().await.unwrap_or(serde_json::Value::Null);
        let Some(id) = payload.get("id").and_then(|v| v.as_str()).map(|s| s.to_string()) else {
            return Err(HttpError::new(
                502,
                "drive_upload_failed",
                "Google Drive completed the upload without reporting a file id.",
            ));
        };
        let reported = payload.get("size").and_then(json_number);
        self.register_uploaded_object(
            binding,
            storage_key,
            &id,
            reported.unwrap_or(content_length),
            content_type,
        )
        .await
    }

    async fn fetch_object_size(&self, binding: &StorageBinding, file_id: &str) -> DriveResult<i64> {
        let account = self.require_account(binding).await?;
        let req =
            DriveReq::get(format!("{}/files/{}?fields=id,size", self.api_base(), url_path_encode(file_id)));
        let response = self
            .with_drive_retries(&account, DriveOp::Download, || {
                self.drive_request_checked(
                    &account,
                    &req,
                    "drive_upload_failed",
                    "Google Drive file metadata read failed.",
                    false,
                )
            })
            .await?;
        let payload = response
            .expect("checked request without allow_not_found returns a response")
            .json::<serde_json::Value>()
            .await
            .unwrap_or(serde_json::Value::Null);
        payload.get("size").and_then(json_number).ok_or_else(|| {
            DriveError::plain(
                502,
                "drive_upload_failed",
                "Google Drive did not report a size for the uploaded file.",
            )
        })
    }
}

#[async_trait]
impl StorageProvider for GoogleDriveStorageProvider {
    fn provider(&self) -> StorageProviderType {
        StorageProviderType::GoogleDrive
    }

    async fn exists(&self, binding: &StorageBinding, storage_key: &str) -> HttpResult<bool> {
        let account_id = require_account_id(binding)?;
        Ok(self
            .repository
            .get_storage_object(StorageProviderType::GoogleDrive, &account_id, storage_key)
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
        // The relay path: never buffer a stream of known length. Buffering
        // held several whole-body copies, which is what OOM'd the worker.
        if let PutBody::Stream { stream, len } = body {
            if let Some(len) = len.filter(|n| *n > 0) {
                return self.put_streaming(binding, storage_key, stream, content_type, len).await;
            }
            return self.put_buffered(binding, storage_key, drain(stream).await?, content_type).await;
        }
        let PutBody::Bytes(bytes) = body else { unreachable!("stream handled above") };
        self.put_buffered(binding, storage_key, bytes, content_type).await
    }

    async fn get(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        range: Option<&BlobRange>,
    ) -> HttpResult<Option<StoredBlob>> {
        let account = self.require_account(binding).await?;
        let Some(object) = self
            .repository
            .get_storage_object(StorageProviderType::GoogleDrive, &account.id, storage_key)
            .await?
        else {
            return Ok(None);
        };

        let mut req = DriveReq::get(format!(
            "{}/files/{}?alt=media",
            self.api_base(),
            url_path_encode(&object.object_id)
        ));
        if let Some(r) = range {
            req = req.header(
                "range",
                format!("bytes={}-{}", r.offset, r.end_inclusive.map(|e| e.to_string()).unwrap_or_default()),
            );
        }
        let response = match self
            .with_drive_retries(&account, DriveOp::Download, || {
                self.drive_request_checked(
                    &account,
                    &req,
                    "drive_download_failed",
                    "Google Drive download failed.",
                    true,
                )
            })
            .await
        {
            Ok(r) => r,
            Err(e) if e.upstream_status == Some(416) => {
                return Err(HttpError::new(
                    416,
                    "range_not_satisfiable",
                    "Requested range is beyond the end of the stored blob.",
                ))
            }
            Err(e) => return Err(e.http),
        };
        let Some(response) = response else {
            self.repository
                .delete_storage_object(StorageProviderType::GoogleDrive, &account.id, storage_key)
                .await?;
            return Ok(None);
        };

        // The body streams straight through — GB-scale blobs must never be
        // buffered. Retries above cover response establishment only; a
        // mid-stream break reaches the client, which resumes via Range.
        let status = if response.status().as_u16() == 206 { 206 } else { 200 };
        let header =
            |name: &str| response.headers().get(name).and_then(|v| v.to_str().ok()).map(|s| s.to_string());
        let content_length = header("content-length").and_then(|v| v.parse::<i64>().ok());
        let content_type = header("content-type").unwrap_or_else(|| object.content_type.clone());
        let content_range = header("content-range");
        let size = match content_length {
            Some(n) => Some(n),
            None if status == 200 => Some(object.size),
            None => None,
        };
        Ok(Some(StoredBlob {
            body: Box::pin(response.bytes_stream().map(|c| c.map_err(std::io::Error::other))),
            content_type,
            size,
            status,
            content_range,
        }))
    }

    async fn delete(&self, binding: &StorageBinding, storage_key: &str) -> HttpResult<()> {
        let account = self.require_account(binding).await?;
        let Some(object) = self
            .repository
            .get_storage_object(StorageProviderType::GoogleDrive, &account.id, storage_key)
            .await?
        else {
            return Ok(());
        };

        // A failed Drive delete must keep the local object row: dropping it on
        // error would orphan the Drive file forever, while keeping it lets blob
        // GC retry later. 404 means the file is already gone.
        let req = DriveReq::new(
            Method::DELETE,
            format!("{}/files/{}", self.api_base(), url_path_encode(&object.object_id)),
        );
        self.with_drive_retries(&account, DriveOp::Delete, || {
            self.drive_request_checked(
                &account,
                &req,
                "drive_delete_failed",
                "Google Drive delete failed.",
                true,
            )
        })
        .await?;
        self.repository
            .delete_storage_object(StorageProviderType::GoogleDrive, &account.id, storage_key)
            .await?;
        Ok(())
    }

    async fn quota(&self, binding: &StorageBinding) -> HttpResult<StorageQuota> {
        let account = self.require_account(binding).await?;
        let req = DriveReq::get(format!("{}/about?fields=storageQuota", self.api_base()));
        let response = self.drive_request(&account, &req).await?;
        if !response.status().is_success() {
            return Ok(StorageQuota { used_bytes: None, total_bytes: None });
        }
        let payload = response.json::<serde_json::Value>().await.unwrap_or(serde_json::Value::Null);
        let quota = payload.get("storageQuota");
        Ok(StorageQuota {
            used_bytes: quota.and_then(|q| q.get("usage")).and_then(json_number),
            total_bytes: quota.and_then(|q| q.get("limit")).and_then(json_number),
        })
    }

    fn resumable(&self) -> Option<&dyn ResumableUploadCapable> {
        Some(self)
    }

    fn relay(&self) -> Option<&dyn crate::relay::RelayCapable> {
        Some(self)
    }
}

#[async_trait]
impl crate::relay::RelayCapable for GoogleDriveStorageProvider {
    /// A fresh access token for the account plus the Drive file ids of the
    /// requested keys (rows are the authoritative index).
    async fn relay_grant(
        &self,
        binding: &StorageBinding,
        storage_keys: &[String],
    ) -> HttpResult<crate::relay::RelayGrant> {
        let account = self.require_account(binding).await?;
        let access_token = self.ensure_access_token(&account, false).await?;
        // Re-read for the (possibly refreshed) expiry.
        let refreshed = self.repository.get_storage_account(&account.id).await?.unwrap_or(account);
        let expires_at_ms = refreshed
            .token_expires_at
            .as_deref()
            .and_then(time::parse_iso)
            .map(time::to_millis)
            .unwrap_or_else(|| time::to_millis(time::now()) + 50 * 60_000);
        let rows = self
            .repository
            .get_storage_objects_batch(StorageProviderType::GoogleDrive, &refreshed.id, storage_keys)
            .await?;
        Ok(crate::relay::RelayGrant {
            access_token,
            access_token_expires_at_ms: expires_at_ms,
            file_ids: rows.into_iter().map(|r| (r.storage_key, r.object_id)).collect(),
        })
    }
}

impl GoogleDriveStorageProvider {
    async fn put_buffered(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        bytes: Bytes,
        content_type: &str,
    ) -> HttpResult<()> {
        let account = self.require_account(binding).await?;
        let existing = self
            .repository
            .get_storage_object(StorageProviderType::GoogleDrive, &account.id, storage_key)
            .await?;
        let existing_id = existing.as_ref().map(|o| o.object_id.clone());
        let size = bytes.len() as i64;
        let uploaded_id = self
            .with_drive_retries(&account, DriveOp::Upload, || async {
                self.schedule_upload_start(&account.id).await;
                match &existing_id {
                    Some(id) => self.update_file(&account, id, &bytes, content_type).await,
                    None => self.create_file(&account, storage_key, &bytes, content_type).await,
                }
            })
            .await?;

        let now = time::now_iso();
        self.repository
            .upsert_storage_object(StorageObjectRecord {
                provider: StorageProviderType::GoogleDrive,
                storage_account_id: account.id.clone(),
                storage_key: storage_key.to_string(),
                object_id: uploaded_id,
                content_type: content_type.to_string(),
                size,
                created_at: now.clone(),
                updated_at: now,
            })
            .await?;
        Ok(())
    }
}

#[async_trait]
impl ResumableUploadCapable for GoogleDriveStorageProvider {
    /// Starts a Drive resumable session and returns the Location URI verbatim
    /// (that URI is its own credential; the client PUTs chunks straight to it).
    /// A key that already has an object row re-uses the Drive file id so a
    /// re-upload can never leak a duplicate.
    async fn create_resumable_session(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        content_type: &str,
        expected_size: i64,
    ) -> HttpResult<String> {
        let account = self.require_account(binding).await?;
        let existing = self
            .repository
            .get_storage_object(StorageProviderType::GoogleDrive, &account.id, storage_key)
            .await?;
        let (url, method, metadata) = match existing.as_ref().map(|o| o.object_id.as_str()) {
            Some(id) => (
                format!("{}/files/{}?uploadType=resumable", self.upload_base(), url_path_encode(id)),
                Method::PATCH,
                serde_json::json!({}),
            ),
            None => (
                format!("{}/files?uploadType=resumable", self.upload_base()),
                Method::POST,
                serde_json::json!({ "name": drive_object_name(storage_key), "parents": ["appDataFolder"] }),
            ),
        };
        let req = DriveReq::new(method, url)
            .header("content-type", "application/json; charset=UTF-8")
            .header("x-upload-content-type", content_type.to_string())
            .header("x-upload-content-length", expected_size.to_string())
            .body(Bytes::from(metadata.to_string()));

        let response = self
            .with_drive_retries(&account, DriveOp::Upload, || async {
                self.schedule_upload_start(&account.id).await;
                self.drive_request_checked(
                    &account,
                    &req,
                    "drive_upload_failed",
                    "Google Drive resumable session could not be started.",
                    false,
                )
                .await
            })
            .await?;
        response
            .as_ref()
            .and_then(|r| r.headers().get("location"))
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string())
            .ok_or_else(|| {
                HttpError::new(
                    502,
                    "drive_upload_failed",
                    "Google Drive did not return a resumable session URI.",
                )
            })
    }

    /// Asks the session where it stands (`bytes */N`). No auth header: the
    /// session URI is the credential, and this keeps our probe identical to
    /// what the client is allowed to send.
    async fn probe_resumable_session(
        &self,
        binding: &StorageBinding,
        session_url: &str,
        expected_size: i64,
    ) -> HttpResult<ResumableProbe> {
        let started = Instant::now();
        let response = self
            .http
            .put(session_url)
            .header("content-range", format!("bytes */{expected_size}"))
            .send()
            .await;
        record_drive_request("upload_probe", response.as_ref().map(|r| r.status().as_u16()).ok(), started);
        let response = response.map_err(|e| {
            HttpError::new(502, "drive_upload_failed", format!("Google Drive resumable probe failed. {e}"))
        })?;
        let status = response.status().as_u16();
        if status == 308 {
            let received_up_to = response
                .headers()
                .get("range")
                .and_then(|v| v.to_str().ok())
                .and_then(parse_resumable_range)
                .map(|end| end + 1)
                .unwrap_or(0);
            return Ok(ResumableProbe::Incomplete { received_up_to });
        }
        if status == 200 || status == 201 {
            let payload = response.json::<serde_json::Value>().await.unwrap_or(serde_json::Value::Null);
            let Some(file_id) = payload.get("id").and_then(|v| v.as_str()).map(|s| s.to_string()) else {
                return Err(HttpError::new(
                    502,
                    "drive_upload_failed",
                    "Google Drive completed the upload without reporting a file id.",
                ));
            };
            let size = match payload.get("size").and_then(json_number) {
                Some(size) => size,
                None => self.fetch_object_size(binding, &file_id).await?,
            };
            return Ok(ResumableProbe::Complete { file_id, size });
        }
        if status == 404 || status == 410 {
            return Ok(ResumableProbe::Expired);
        }
        Err(HttpError::new(
            502,
            "drive_upload_failed",
            format!("Google Drive resumable probe failed (HTTP {status})."),
        ))
    }

    async fn register_uploaded_object(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        file_id: &str,
        size: i64,
        content_type: &str,
    ) -> HttpResult<()> {
        let account = self.require_account(binding).await?;
        let existing = self
            .repository
            .get_storage_object(StorageProviderType::GoogleDrive, &account.id, storage_key)
            .await?;
        if let Some(old) = existing.as_ref().filter(|o| o.object_id != file_id) {
            self.delete_object_by_id(binding, &old.object_id.clone()).await?;
        }
        self.repository
            .upsert_storage_object(StorageObjectRecord {
                provider: StorageProviderType::GoogleDrive,
                storage_account_id: account.id.clone(),
                storage_key: storage_key.to_string(),
                object_id: file_id.to_string(),
                content_type: content_type.to_string(),
                size,
                created_at: existing.map(|o| o.created_at).unwrap_or_else(time::now_iso),
                updated_at: time::now_iso(),
            })
            .await?;
        Ok(())
    }

    async fn delete_object_by_id(&self, binding: &StorageBinding, file_id: &str) -> HttpResult<()> {
        let account = self.require_account(binding).await?;
        let req =
            DriveReq::new(Method::DELETE, format!("{}/files/{}", self.api_base(), url_path_encode(file_id)));
        // Cleanup only — an orphaned Drive file must never fail the request
        // that discovered it.
        if let Err(error) = self
            .drive_request_checked(&account, &req, "drive_delete_failed", "Google Drive delete failed.", true)
            .await
        {
            tracing::warn!(file_id, cause = %error.http, "SharedWorld Drive object cleanup failed");
        }
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

fn require_account_id(binding: &StorageBinding) -> Result<String, HttpError> {
    binding.storage_account_id.clone().ok_or_else(|| {
        HttpError::new(400, "missing_storage_account", "World is not linked to a storage account.")
    })
}

async fn drain(mut stream: super::BodyStream) -> HttpResult<Bytes> {
    let mut out = Vec::new();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk
            .map_err(|e| HttpError::new(502, "storage_read_failed", format!("Blob read failed: {e}")))?;
        out.extend_from_slice(&chunk);
    }
    Ok(Bytes::from(out))
}

/// `URLSearchParams` encoding: application/x-www-form-urlencoded (space → `+`).
fn form_encode(pairs: &[(&str, &str)]) -> String {
    let component = |s: &str| {
        let mut out = String::with_capacity(s.len());
        for b in s.bytes() {
            match b {
                b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'*' | b'-' | b'.' | b'_' => out.push(b as char),
                b' ' => out.push('+'),
                _ => out.push_str(&format!("%{b:02X}")),
            }
        }
        out
    };
    pairs.iter().map(|(k, v)| format!("{}={}", component(k), component(v))).collect::<Vec<_>>().join("&")
}

fn drive_object_name(storage_key: &str) -> String {
    format!("sharedworld-{}", base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(storage_key.as_bytes()))
}

/// `encodeURIComponent` over a single path segment.
fn url_path_encode(s: &str) -> String {
    crate::service::signer::url_encode(s)
}

fn head(text: &str, max: usize) -> String {
    text.chars().take(max).collect()
}

/// JSON numbers and Drive's stringified `int64` fields both parse.
fn json_number(v: &serde_json::Value) -> Option<i64> {
    match v {
        serde_json::Value::Number(n) => n.as_f64().filter(|f| f.is_finite()).map(|f| f as i64),
        serde_json::Value::String(s) => {
            s.trim().parse::<f64>().ok().filter(|f| f.is_finite()).map(|f| f as i64)
        }
        _ => None,
    }
}

/// `^bytes=0-(\d+)$`.
fn parse_resumable_range(header: &str) -> Option<i64> {
    let rest = header.trim().strip_prefix("bytes=0-")?;
    if rest.is_empty() || !rest.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    rest.parse::<i64>().ok()
}

async fn uploaded_file_id(response: Response) -> DriveResult<String> {
    let payload = response.json::<serde_json::Value>().await.unwrap_or(serde_json::Value::Null);
    payload.get("id").and_then(|v| v.as_str()).map(|s| s.to_string()).ok_or_else(|| {
        DriveError::plain(
            502,
            "drive_upload_failed",
            "Google Drive completed the upload without reporting a file id.",
        )
    })
}

/// Builds the error for a non-OK Drive response, keeping the body head for
/// reason checks and logs.
async fn drive_error(response: Response, code: &'static str, label: &'static str) -> DriveError {
    let status = response.status().as_u16();
    // Body unavailable is fine; the status alone still identifies the failure.
    let text = response.text().await.unwrap_or_default();
    let message = if text.is_empty() {
        format!("{label} HTTP {status}.")
    } else {
        format!("{label} HTTP {status}: {text}")
    };
    DriveError {
        http: HttpError::new(status, code, message),
        upstream_status: Some(status),
        upstream_body: Some(head(&text, 400)),
    }
}

/// Google answers 403 for both transient rate limiting and permanent
/// conditions (missing consent scope, storage quota, daily caps). Only the
/// rate-limit reasons deserve a retry.
fn is_retryable_drive_failure(error: &DriveError) -> bool {
    let Some(status) = error.upstream_status else { return false };
    if status == 429 || status >= 500 {
        return true;
    }
    if status != 403 {
        return false;
    }
    error.upstream_body.as_deref().unwrap_or("").to_lowercase().contains("ratelimitexceeded")
}

/// Matches ONLY the storage-quota reason: Google also uses
/// `rateLimitExceeded`/`userRateLimitExceeded` (retryable) and a bare
/// `quotaExceeded` for API-call quotas.
fn is_storage_quota_exceeded_body(body_head: Option<&str>) -> bool {
    body_head.unwrap_or("").to_lowercase().contains("storagequotaexceeded")
}

fn is_insufficient_scope_body(body_head: Option<&str>) -> bool {
    let body = body_head.unwrap_or("").to_lowercase();
    body.contains("insufficientpermissions")
        || body.contains("insufficient_scope")
        || body.contains("access_token_scope_insufficient")
        || body.contains("insufficient authentication scopes")
}

/// Paces upload STARTS per account: Drive's constrained resource.
struct AccountRequestLimiter {
    interval_ms: i64,
    next_allowed_at_ms: i64,
}

impl AccountRequestLimiter {
    fn new(max_starts_per_second: i64) -> Self {
        Self {
            interval_ms: (1000f64 / max_starts_per_second as f64).ceil().max(1.0) as i64,
            next_allowed_at_ms: 0,
        }
    }

    /// Reserves the next slot and returns how long the caller must wait.
    fn schedule_upload_start(&mut self) -> i64 {
        let now = time::to_millis(time::now());
        let scheduled = now.max(self.next_allowed_at_ms);
        self.next_allowed_at_ms = scheduled + self.interval_ms;
        scheduled - now
    }
}

/// Coarse Drive operation label for metrics (no ids in labels).
fn drive_op_label(method: &Method, url: &str) -> &'static str {
    if url.contains("/about") {
        "about"
    } else if url.contains("/upload/") {
        if *method == Method::POST {
            "upload_create"
        } else {
            "upload_patch"
        }
    } else if *method == Method::GET {
        if url.contains("alt=media") {
            "download"
        } else {
            "metadata"
        }
    } else if *method == Method::DELETE {
        "delete"
    } else if *method == Method::PATCH {
        "update"
    } else if *method == Method::POST {
        "create"
    } else {
        "other"
    }
}

/// `drive_requests_total{op,status}` + `drive_request_duration_seconds{op}`;
/// `status` is the class ("2xx", "4xx", "5xx") or "error" for transport failures.
pub(crate) fn record_drive_request(op: &'static str, status: Option<u16>, started: Instant) {
    let class: &'static str = match status {
        Some(s) if s < 300 => "2xx",
        Some(s) if s < 400 => "3xx",
        Some(s) if s < 500 => "4xx",
        Some(_) => "5xx",
        None => "error",
    };
    metrics::counter!("drive_requests_total", "op" => op, "status" => class).increment(1);
    metrics::histogram!("drive_request_duration_seconds", "op" => op).record(started.elapsed().as_secs_f64());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn object_names_and_ranges_match_ts() {
        assert_eq!(drive_object_name("packs/full/ab/abc.pack"), "sharedworld-cGFja3MvZnVsbC9hYi9hYmMucGFjaw");
        assert_eq!(parse_resumable_range("bytes=0-499"), Some(499));
        assert_eq!(parse_resumable_range("bytes=1-499"), None);
        assert_eq!(parse_resumable_range(""), None);
        assert_eq!(json_number(&serde_json::json!("1024")), Some(1024));
        assert_eq!(json_number(&serde_json::json!(2048)), Some(2048));
        assert_eq!(json_number(&serde_json::json!(null)), None);
        assert!(is_storage_quota_exceeded_body(Some("reason: storageQuotaExceeded")));
        assert!(!is_storage_quota_exceeded_body(Some("reason: userRateLimitExceeded")));
        assert!(is_insufficient_scope_body(Some("reason: insufficientPermissions")));
    }

    #[test]
    fn limiter_paces_starts() {
        let mut limiter = AccountRequestLimiter::new(2);
        assert_eq!(limiter.interval_ms, 500);
        assert_eq!(limiter.schedule_upload_start(), 0);
        assert!(limiter.schedule_upload_start() >= 499);
    }
}
