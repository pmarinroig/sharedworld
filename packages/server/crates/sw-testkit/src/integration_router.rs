//! Per-binding storage router for the integration server: Drive-bound
//! worlds go to the fake `IntegrationDriveProvider`, S3-bound worlds to the
//! REAL `S3StorageProvider` (which the e2e points at the in-process fake S3
//! service). Mirrors the production `RoutingStorageProvider`, whose concrete
//! Drive slot cannot hold a fake.

use std::sync::Arc;

use async_trait::async_trait;
use sw_contracts::{SignedBlobUrl, StorageProviderType};
use sw_core::http_error::HttpResult;
use sw_core::storage::s3::S3StorageProvider;
use sw_core::storage::{
    AccountCleanupCapable, BlobRange, PresignCapable, PutBody, ResumableUploadCapable, StorageBinding,
    StorageProvider, StorageQuota, StoredBlob, TransferPresigner,
};

use crate::integration_drive::IntegrationDriveProvider;

pub struct IntegrationStorageRouter {
    pub drive: Arc<IntegrationDriveProvider>,
    pub s3: S3StorageProvider,
}

impl IntegrationStorageRouter {
    fn route(&self, binding: &StorageBinding) -> &dyn StorageProvider {
        match binding.provider {
            StorageProviderType::S3 => &self.s3,
            _ => self.drive.as_ref(),
        }
    }
}

#[async_trait]
impl StorageProvider for IntegrationStorageRouter {
    fn provider(&self) -> StorageProviderType {
        StorageProviderType::GoogleDrive
    }

    async fn exists(&self, binding: &StorageBinding, storage_key: &str) -> HttpResult<bool> {
        self.route(binding).exists(binding, storage_key).await
    }

    async fn put(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        body: PutBody,
        content_type: &str,
    ) -> HttpResult<()> {
        self.route(binding).put(binding, storage_key, body, content_type).await
    }

    async fn get(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        range: Option<&BlobRange>,
    ) -> HttpResult<Option<StoredBlob>> {
        self.route(binding).get(binding, storage_key, range).await
    }

    async fn delete(&self, binding: &StorageBinding, storage_key: &str) -> HttpResult<()> {
        self.route(binding).delete(binding, storage_key).await
    }

    async fn quota(&self, binding: &StorageBinding) -> HttpResult<StorageQuota> {
        self.route(binding).quota(binding).await
    }

    fn resumable(&self, binding: &StorageBinding) -> Option<&dyn ResumableUploadCapable> {
        self.route(binding).resumable(binding)
    }

    fn relay(&self, binding: &StorageBinding) -> Option<&dyn sw_core::relay::RelayCapable> {
        self.route(binding).relay(binding)
    }

    fn account_cleanup(&self, binding: &StorageBinding) -> Option<&dyn AccountCleanupCapable> {
        self.route(binding).account_cleanup(binding)
    }

    fn presign(&self, binding: &StorageBinding) -> Option<&dyn PresignCapable> {
        self.route(binding).presign(binding)
    }

    fn manifest_doc_capable(&self, binding: &StorageBinding) -> bool {
        self.route(binding).manifest_doc_capable(binding)
    }
}

/// Keep the imports honest (SignedBlobUrl/TransferPresigner appear in the
/// re-exported capability signatures).
#[allow(dead_code)]
fn _capability_types(_: &dyn TransferPresigner, _: &SignedBlobUrl) {}
