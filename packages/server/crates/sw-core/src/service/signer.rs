//! Blob URL signing (`WorkerSignedUrlSigner` + `signUploadForWorld` /
//! `signDownloadForWorld` from `service/context.ts`). Upload URLs point at
//! the caller's entry origin (the CF forwarder's `x-sw-entry-origin`, else
//! the box's own origin); download URLs point at `relay_base_url` when
//! configured so the relay serves the bytes.

use std::collections::BTreeMap;

use sw_contracts::{SignedBlobMethod, SignedBlobUrl};

use super::ServiceContext;
use crate::config::Config;
use crate::stamp::{mint_blob_stamp, mint_download_stamp};
use crate::time;

pub const RUNTIME_EPOCH_HEADER: &str = "x-sharedworld-runtime-epoch";
pub const HOST_TOKEN_HEADER: &str = "x-sharedworld-host-token";
pub const BLOB_STAMP_HEADER: &str = "x-sharedworld-blob-stamp";
/// Lane D relay download token (Ed25519 + encrypted Drive token), minted
/// alongside the HMAC stamp when relay keys are configured.
pub const RELAY_TOKEN_HEADER: &str = "x-sharedworld-relay-token";

pub type SignedBlobRequest = SignedBlobUrl;

pub trait BlobUrlSigner: Send + Sync {
    fn sign_upload(
        &self,
        world_id: &str,
        storage_key: &str,
        request_origin: Option<&str>,
    ) -> SignedBlobRequest;
    fn sign_download(
        &self,
        world_id: &str,
        storage_key: &str,
        request_origin: Option<&str>,
    ) -> SignedBlobRequest;
}

pub struct ServerSignedUrlSigner {
    public_base_url: Option<String>,
    relay_base_url: Option<String>,
    ttl_seconds: i64,
}

impl ServerSignedUrlSigner {
    pub fn new(config: &Config) -> Self {
        Self {
            public_base_url: config
                .public_base_url
                .clone()
                .filter(|s| !s.contains("sharedworld.example.workers.dev")),
            relay_base_url: config.relay_base_url.clone(),
            ttl_seconds: config.signed_url_ttl_seconds,
        }
    }

    fn sign(
        &self,
        method: SignedBlobMethod,
        world_id: &str,
        storage_key: &str,
        base: &str,
    ) -> SignedBlobRequest {
        SignedBlobUrl {
            method,
            url: format!(
                "{}/worlds/{}/storage/blob/{}",
                base.trim_end_matches('/'),
                url_encode(world_id),
                url_encode(storage_key)
            ),
            headers: BTreeMap::new(),
            expires_at: time::plus_ms_iso(time::now(), self.ttl_seconds * 1000),
        }
    }

    fn upload_base<'a>(&'a self, request_origin: Option<&'a str>) -> &'a str {
        // The caller's own entry origin wins (forwarded legacy clients keep
        // their bearer-attaching origin); else the configured public base.
        request_origin.or(self.public_base_url.as_deref()).unwrap_or("http://127.0.0.1:8787")
    }
}

impl BlobUrlSigner for ServerSignedUrlSigner {
    fn sign_upload(
        &self,
        world_id: &str,
        storage_key: &str,
        request_origin: Option<&str>,
    ) -> SignedBlobRequest {
        self.sign(SignedBlobMethod::PUT, world_id, storage_key, self.upload_base(request_origin))
    }
    fn sign_download(
        &self,
        world_id: &str,
        storage_key: &str,
        request_origin: Option<&str>,
    ) -> SignedBlobRequest {
        let base = self.relay_base_url.as_deref().unwrap_or_else(|| self.upload_base(request_origin));
        self.sign(SignedBlobMethod::GET, world_id, storage_key, base)
    }
}

/// `encodeURIComponent`.
pub fn url_encode(s: &str) -> String {
    const KEEP: &[u8] = b"-_.!~*'()";
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || KEEP.contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Upload URLs carry epoch/token headers plus the HMAC blob stamp.
pub fn sign_upload_for_world(
    svc: &ServiceContext,
    world_id: &str,
    storage_key: &str,
    runtime_epoch: i64,
    runtime_token: Option<&str>,
    request_origin: Option<&str>,
) -> SignedBlobRequest {
    let mut signed = svc.blob_signer.sign_upload(world_id, storage_key, request_origin);
    signed.headers.insert(RUNTIME_EPOCH_HEADER.into(), runtime_epoch.to_string());
    signed.headers.insert(HOST_TOKEN_HEADER.into(), runtime_token.unwrap_or("").to_string());
    if let Some(stamp) = mint_blob_stamp(&svc.stamp_keys, world_id, runtime_epoch, storage_key, time::now()) {
        signed.headers.insert(BLOB_STAMP_HEADER.into(), stamp);
    }
    signed
}

/// Download URLs carry a download stamp bound to (world, key, viewer).
pub fn sign_download_for_world(
    svc: &ServiceContext,
    world_id: &str,
    storage_key: &str,
    player_uuid: &str,
    request_origin: Option<&str>,
) -> SignedBlobRequest {
    let mut signed = svc.blob_signer.sign_download(world_id, storage_key, request_origin);
    if let Some(stamp) = mint_download_stamp(&svc.stamp_keys, world_id, storage_key, player_uuid, time::now())
    {
        signed.headers.insert(BLOB_STAMP_HEADER.into(), stamp);
    }
    signed
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_like_encode_uri_component() {
        assert_eq!(url_encode("packs/full/ab/abc.pack"), "packs%2Ffull%2Fab%2Fabc.pack");
        assert_eq!(url_encode("a b+c"), "a%20b%2Bc");
        assert_eq!(url_encode("world_1"), "world_1");
    }
}
