//! Storage providers (`storage.ts`): the blob store behind content-addressed
//! keys. Google Drive in production, a local filesystem provider for tests
//! and the R2-parity mode.

pub mod drive;
pub mod fs;
pub mod link_service;
pub mod manifest_doc;

use std::pin::Pin;
use std::sync::Arc;

use async_trait::async_trait;
use bytes::Bytes;
use futures::{Stream, StreamExt};
use sw_contracts::StorageProviderType;
use sw_db::Repository;

use crate::config::Config;
use crate::http_error::{HttpError, HttpResult};

pub use sw_db::repo::WorldStorageBinding as StorageBinding;

/// The provider `ACTIVE_STORAGE_PROVIDER` selects (`createStorageProvider`).
pub fn create_storage_provider(
    config: &Arc<Config>,
    repo: &Repository,
    http: reqwest::Client,
) -> Arc<dyn StorageProvider> {
    match config.active_storage_provider {
        StorageProviderType::GoogleDrive => {
            Arc::new(drive::GoogleDriveStorageProvider::new(repo.clone(), config.clone(), http))
        }
        StorageProviderType::R2 => Arc::new(fs::FsStorageProvider::new(
            config.fs_blob_root.clone().unwrap_or_else(|| std::path::PathBuf::from("./blobs")),
        )),
    }
}

pub type BodyStream = Pin<Box<dyn Stream<Item = Result<Bytes, std::io::Error>> + Send + 'static>>;

pub struct StoredBlob {
    pub body: BodyStream,
    pub content_type: String,
    /// Byte length of `body` (the partial length for a 206), `None` when unknown.
    pub size: Option<i64>,
    /// 206 when body is the requested partial range, 200 for the whole blob.
    pub status: u16,
    /// Verbatim Content-Range header value for a 206 response.
    pub content_range: Option<String>,
}

impl StoredBlob {
    pub fn from_bytes(bytes: Bytes, content_type: impl Into<String>) -> Self {
        let len = bytes.len() as i64;
        Self {
            body: Box::pin(futures::stream::once(async move { Ok(bytes) })),
            content_type: content_type.into(),
            size: Some(len),
            status: 200,
            content_range: None,
        }
    }

    /// Buffer the whole body (manifest documents, icons — never world blobs).
    pub async fn into_bytes(self) -> HttpResult<Bytes> {
        let mut out = Vec::with_capacity(self.size.unwrap_or(0).max(0) as usize);
        let mut body = self.body;
        while let Some(chunk) = body.next().await {
            let chunk = chunk
                .map_err(|e| HttpError::new(502, "storage_read_failed", format!("Blob read failed: {e}")))?;
            out.extend_from_slice(&chunk);
        }
        Ok(Bytes::from(out))
    }
}

/// Upload body: in-memory bytes or a stream with a known length (relayed
/// uploads pass the request's Content-Length so providers can stream).
pub enum PutBody {
    Bytes(Bytes),
    Stream { stream: BodyStream, len: Option<i64> },
}

impl PutBody {
    pub fn len(&self) -> Option<i64> {
        match self {
            PutBody::Bytes(b) => Some(b.len() as i64),
            PutBody::Stream { len, .. } => *len,
        }
    }
    pub fn is_empty(&self) -> bool {
        self.len() == Some(0)
    }
    pub async fn into_bytes(self) -> HttpResult<Bytes> {
        match self {
            PutBody::Bytes(b) => Ok(b),
            PutBody::Stream { stream, .. } => {
                StoredBlob {
                    body: stream,
                    content_type: String::new(),
                    size: None,
                    status: 200,
                    content_range: None,
                }
                .into_bytes()
                .await
            }
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BlobRange {
    pub offset: i64,
    /// Inclusive end byte, or `None` for "to the end of the blob".
    pub end_inclusive: Option<i64>,
}

/// Single ascending byte range only (`bytes=N-` / `bytes=N-M`).
pub fn parse_single_byte_range(header: Option<&str>) -> Option<BlobRange> {
    let h = header?.trim();
    let rest = h.strip_prefix("bytes=")?;
    let (start, end) = rest.split_once('-')?;
    if start.is_empty()
        || !start.chars().all(|c| c.is_ascii_digit())
        || !end.chars().all(|c| c.is_ascii_digit())
    {
        return None;
    }
    let offset: i64 = start.parse().ok()?;
    let end_inclusive = if end.is_empty() { None } else { Some(end.parse::<i64>().ok()?) };
    if end_inclusive.is_some_and(|e| e < offset) {
        return None;
    }
    Some(BlobRange { offset, end_inclusive })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct StorageQuota {
    pub used_bytes: Option<i64>,
    pub total_bytes: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResumableProbe {
    Incomplete { received_up_to: i64 },
    Complete { file_id: String, size: i64 },
    Expired,
}

/// Optional provider capability behind direct-to-provider resumable uploads.
#[async_trait]
pub trait ResumableUploadCapable: Send + Sync {
    async fn create_resumable_session(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        content_type: &str,
        expected_size: i64,
    ) -> HttpResult<String>;
    async fn probe_resumable_session(
        &self,
        binding: &StorageBinding,
        session_url: &str,
        expected_size: i64,
    ) -> HttpResult<ResumableProbe>;
    /// Records the storage_objects row from provider-reported facts; deletes a superseded old object.
    async fn register_uploaded_object(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        file_id: &str,
        size: i64,
        content_type: &str,
    ) -> HttpResult<()>;
    /// Best-effort delete of a provider object by its provider id.
    async fn delete_object_by_id(&self, binding: &StorageBinding, file_id: &str) -> HttpResult<()>;
}

/// Optional provider capability behind account unlink / account deletion:
/// enumerate everything the app holds for an account — including files that
/// lost their `storage_objects` row — and revoke the app's OAuth access.
#[async_trait]
pub trait AccountCleanupCapable: Send + Sync {
    /// One page of provider file ids for the bound account's app data.
    async fn list_account_object_ids(
        &self,
        binding: &StorageBinding,
        page_token: Option<&str>,
    ) -> HttpResult<(Vec<String>, Option<String>)>;
    /// Delete by provider file id (an already-gone file is success).
    async fn delete_account_object(&self, binding: &StorageBinding, file_id: &str) -> HttpResult<()>;
    /// Best-effort OAuth revocation for the bound account's tokens.
    async fn revoke_account_access(&self, binding: &StorageBinding) -> HttpResult<()>;
}

#[async_trait]
pub trait StorageProvider: Send + Sync {
    fn provider(&self) -> StorageProviderType;
    async fn exists(&self, binding: &StorageBinding, storage_key: &str) -> HttpResult<bool>;
    async fn put(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        body: PutBody,
        content_type: &str,
    ) -> HttpResult<()>;
    /// A range beyond the end of the blob fails with 416 `range_not_satisfiable`.
    async fn get(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        range: Option<&BlobRange>,
    ) -> HttpResult<Option<StoredBlob>>;
    async fn delete(&self, binding: &StorageBinding, storage_key: &str) -> HttpResult<()>;
    async fn quota(&self, binding: &StorageBinding) -> HttpResult<StorageQuota>;
    fn resumable(&self) -> Option<&dyn ResumableUploadCapable> {
        None
    }
    /// Lane-D relay grants (direct reads by the CF relay); Drive only.
    fn relay(&self) -> Option<&dyn crate::relay::RelayCapable> {
        None
    }
    /// Account unlink / delete-account cleanup; Drive only.
    fn account_cleanup(&self) -> Option<&dyn AccountCleanupCapable> {
        None
    }
    /// True when 0027 manifest documents can be written/read for this binding
    /// (`manifestDocCapable`): Drive needs a linked account; fs/R2 always can.
    fn manifest_doc_capable(&self, binding: &StorageBinding) -> bool {
        match self.provider() {
            StorageProviderType::GoogleDrive => {
                binding.storage_account_id.is_some() && binding.provider == StorageProviderType::GoogleDrive
            }
            StorageProviderType::R2 => true,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn range_parsing() {
        assert_eq!(
            parse_single_byte_range(Some("bytes=10-")),
            Some(BlobRange { offset: 10, end_inclusive: None })
        );
        assert_eq!(
            parse_single_byte_range(Some(" bytes=0-99 ")),
            Some(BlobRange { offset: 0, end_inclusive: Some(99) })
        );
        assert_eq!(parse_single_byte_range(Some("bytes=5-3")), None);
        assert_eq!(parse_single_byte_range(Some("bytes=-5")), None);
        assert_eq!(parse_single_byte_range(Some("bytes=0-1,3-4")), None);
        assert_eq!(parse_single_byte_range(None), None);
    }
}
