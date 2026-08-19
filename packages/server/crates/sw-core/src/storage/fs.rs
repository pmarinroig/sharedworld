//! Local filesystem blob store: the R2 stand-in for tests and self-hosters.
//! Keys map to paths under the root (`..` rejected). No resumable capability.

use std::path::{Path, PathBuf};

use async_trait::async_trait;
use bytes::Bytes;
use sw_contracts::StorageProviderType;
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio_util::io::ReaderStream;

use super::{BlobRange, PutBody, StorageBinding, StorageProvider, StorageQuota, StoredBlob};
use crate::http_error::{HttpError, HttpResult};

pub struct FsStorageProvider {
    root: PathBuf,
}

impl FsStorageProvider {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    fn path_for(&self, storage_key: &str) -> HttpResult<PathBuf> {
        if storage_key.is_empty()
            || storage_key.split('/').any(|seg| seg == ".." || seg.is_empty())
            || storage_key.starts_with('/')
        {
            return Err(HttpError::new(400, "invalid_storage_key", "Storage key is not valid."));
        }
        Ok(self.root.join(storage_key))
    }

    fn meta_path(path: &Path) -> PathBuf {
        let mut p = path.as_os_str().to_owned();
        p.push(".ctype");
        PathBuf::from(p)
    }

    fn io(e: std::io::Error) -> HttpError {
        HttpError::new(502, "storage_io_failed", format!("Blob storage I/O failed: {e}"))
    }
}

#[async_trait]
impl StorageProvider for FsStorageProvider {
    fn provider(&self) -> StorageProviderType {
        StorageProviderType::R2
    }

    async fn exists(&self, _binding: &StorageBinding, storage_key: &str) -> HttpResult<bool> {
        let path = self.path_for(storage_key)?;
        Ok(tokio::fs::metadata(&path).await.is_ok())
    }

    async fn put(
        &self,
        _binding: &StorageBinding,
        storage_key: &str,
        body: PutBody,
        content_type: &str,
    ) -> HttpResult<()> {
        let path = self.path_for(storage_key)?;
        if let Some(parent) = path.parent() {
            tokio::fs::create_dir_all(parent).await.map_err(Self::io)?;
        }
        let tmp = path.with_extension("swtmp");
        let mut file = tokio::fs::File::create(&tmp).await.map_err(Self::io)?;
        match body {
            PutBody::Bytes(b) => file.write_all(&b).await.map_err(Self::io)?,
            PutBody::Stream { mut stream, .. } => {
                use futures::StreamExt;
                while let Some(chunk) = stream.next().await {
                    file.write_all(&chunk.map_err(Self::io)?).await.map_err(Self::io)?;
                }
            }
        }
        file.flush().await.map_err(Self::io)?;
        drop(file);
        tokio::fs::rename(&tmp, &path).await.map_err(Self::io)?;
        tokio::fs::write(Self::meta_path(&path), content_type).await.map_err(Self::io)?;
        Ok(())
    }

    async fn get(
        &self,
        _binding: &StorageBinding,
        storage_key: &str,
        range: Option<&BlobRange>,
    ) -> HttpResult<Option<StoredBlob>> {
        let path = self.path_for(storage_key)?;
        let Ok(meta) = tokio::fs::metadata(&path).await else { return Ok(None) };
        let total = meta.len() as i64;
        let content_type = tokio::fs::read_to_string(Self::meta_path(&path))
            .await
            .unwrap_or_else(|_| "application/octet-stream".into());
        let mut file = tokio::fs::File::open(&path).await.map_err(Self::io)?;
        match range {
            Some(r) => {
                if r.offset >= total {
                    return Err(HttpError::new(
                        416,
                        "range_not_satisfiable",
                        "Requested range is beyond the end of the stored blob.",
                    ));
                }
                let end = r.end_inclusive.unwrap_or(total - 1).min(total - 1);
                let len = end - r.offset + 1;
                file.seek(std::io::SeekFrom::Start(r.offset as u64)).await.map_err(Self::io)?;
                let limited = file.take(len as u64);
                Ok(Some(StoredBlob {
                    body: Box::pin(ReaderStream::with_capacity(limited, 64 * 1024)),
                    content_type,
                    size: Some(len),
                    status: 206,
                    content_range: Some(format!("bytes {}-{}/{}", r.offset, end, total)),
                }))
            }
            None => Ok(Some(StoredBlob {
                body: Box::pin(ReaderStream::with_capacity(file, 64 * 1024)),
                content_type,
                size: Some(total),
                status: 200,
                content_range: None,
            })),
        }
    }

    async fn delete(&self, _binding: &StorageBinding, storage_key: &str) -> HttpResult<()> {
        let path = self.path_for(storage_key)?;
        match tokio::fs::remove_file(&path).await {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(Self::io(e)),
        }
        let _ = tokio::fs::remove_file(Self::meta_path(&path)).await;
        Ok(())
    }

    async fn quota(&self, _binding: &StorageBinding) -> HttpResult<StorageQuota> {
        Ok(StorageQuota::default())
    }
}

impl FsStorageProvider {
    /// Read a whole blob (tests/tools).
    pub async fn read_all(&self, key: &str) -> HttpResult<Option<Bytes>> {
        let binding = StorageBinding { provider: StorageProviderType::R2, storage_account_id: None };
        match self.get(&binding, key, None).await? {
            Some(blob) => Ok(Some(blob.into_bytes().await?)),
            None => Ok(None),
        }
    }
}
