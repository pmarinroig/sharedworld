//! User-supplied S3-compatible buckets (R2/B2/MinIO...) as a storage
//! provider (0.5.0). The box holds the account's access key pair (encrypted
//! at rest like Drive tokens) and signs every call itself; clients get
//! presigned URLs for direct PUT/GET so world bytes never relay through the
//! box on modern clients. `storage_objects` rows stay the authoritative
//! key index, healed lazily by HEAD when a presigned upload skipped the row.

use async_trait::async_trait;
use sw_contracts::{SignedBlobMethod, SignedBlobUrl, StorageProviderType};
use sw_db::repo::{StorageAccountRecord, StorageObjectRecord};
use sw_db::Repository;

use super::sigv4::{self, S3Creds, S3Target};
use super::{
    AccountCleanupCapable, BlobRange, PresignCapable, PutBody, StorageBinding, StorageProvider, StorageQuota,
    StoredBlob,
};
use crate::http_error::{HttpError, HttpResult};
use crate::time;

pub const DEFAULT_KEY_PREFIX: &str = "sharedworld/";

pub struct S3StorageProvider {
    repository: Repository,
    http: reqwest::Client,
    presign_ttl_seconds: i64,
}

/// Everything needed to address and sign one bucket call.
struct S3Connection {
    endpoint: String,
    region: String,
    bucket: String,
    key_prefix: String,
    access_key_id: String,
    secret_access_key: String,
    account_id: String,
}

impl S3Connection {
    fn target<'a>(&'a self, object_key: &'a str) -> S3Target<'a> {
        S3Target { endpoint: &self.endpoint, region: &self.region, bucket: &self.bucket, key: object_key }
    }

    fn creds(&self) -> S3Creds<'_> {
        S3Creds { access_key_id: &self.access_key_id, secret_access_key: &self.secret_access_key }
    }

    fn object_key(&self, storage_key: &str) -> String {
        format!("{}{}", self.key_prefix, storage_key)
    }
}

fn misconfigured(detail: &str) -> HttpError {
    HttpError::new(
        502,
        "s3_account_misconfigured",
        format!("The linked S3 bucket is misconfigured: {detail}"),
    )
}

fn transport(op: &str, cause: impl std::fmt::Display) -> HttpError {
    HttpError::new(502, "s3_request_failed", format!("S3 {op} failed: {cause}"))
}

fn unexpected(op: &str, status: u16) -> HttpError {
    if status == 403 || status == 401 {
        // Deliberately 502 + a distinct code, never 401: shipped clients
        // treat backend 401s as session expiry.
        return HttpError::new(
            502,
            "s3_unauthorized",
            "The bucket rejected SharedWorld's credentials. Re-link the S3 bucket from the account screen.",
        );
    }
    HttpError::new(502, "s3_request_failed", format!("S3 {op} failed (HTTP {status})."))
}

/// Normalized key prefix: no leading slash, single trailing slash, "" allowed.
pub fn normalize_key_prefix(raw: Option<&str>) -> String {
    let trimmed = raw.unwrap_or(DEFAULT_KEY_PREFIX).trim().trim_start_matches('/');
    if trimmed.is_empty() {
        return String::new();
    }
    format!("{}/", trimmed.trim_end_matches('/'))
}

impl S3StorageProvider {
    pub fn new(repository: Repository, http: reqwest::Client, presign_ttl_seconds: i64) -> Self {
        Self { repository, http, presign_ttl_seconds }
    }

    async fn require_connection(&self, binding: &StorageBinding) -> HttpResult<S3Connection> {
        let Some(account_id) = binding.storage_account_id.as_deref() else {
            return Err(misconfigured("the world has no linked storage account"));
        };
        let account = self
            .repository
            .get_storage_account(account_id)
            .await?
            .ok_or_else(|| misconfigured("the linked storage account no longer exists"))?;
        Self::connection_from_account(&account)
    }

    fn connection_from_account(account: &StorageAccountRecord) -> HttpResult<S3Connection> {
        if account.provider != StorageProviderType::S3 {
            return Err(misconfigured("the linked account is not an S3 account"));
        }
        let endpoint = account
            .s3_endpoint
            .as_deref()
            .ok_or_else(|| misconfigured("no endpoint on record"))?
            .trim_end_matches('/')
            .to_string();
        Ok(S3Connection {
            endpoint,
            region: account.s3_region.clone().unwrap_or_else(|| "auto".into()),
            bucket: account.s3_bucket.clone().ok_or_else(|| misconfigured("no bucket on record"))?,
            key_prefix: normalize_key_prefix(account.s3_key_prefix.as_deref()),
            access_key_id: account.external_account_id.clone(),
            secret_access_key: account
                .access_token
                .clone()
                .ok_or_else(|| misconfigured("no secret key on record"))?,
            account_id: account.id.clone(),
        })
    }

    async fn head(&self, conn: &S3Connection, storage_key: &str) -> HttpResult<Option<(i64, String)>> {
        let object_key = conn.object_key(storage_key);
        let url = format!("{}/{}/{}", conn.endpoint, conn.bucket, object_key);
        let mut req = self.http.head(&url);
        for (name, value) in
            sigv4::sign_headers("HEAD", &conn.target(&object_key), &conn.creds(), &[], time::now())
        {
            req = req.header(&name, &value);
        }
        let response = req.send().await.map_err(|e| transport("head", e))?;
        match response.status().as_u16() {
            200 => {
                let size = response
                    .headers()
                    .get("content-length")
                    .and_then(|v| v.to_str().ok())
                    .and_then(|v| v.parse::<i64>().ok())
                    .unwrap_or(0);
                let content_type = response
                    .headers()
                    .get("content-type")
                    .and_then(|v| v.to_str().ok())
                    .unwrap_or("application/octet-stream")
                    .to_string();
                Ok(Some((size, content_type)))
            }
            404 => Ok(None),
            status => Err(unexpected("head", status)),
        }
    }

    /// Content-addressed keys never change bytes, so an upsert here is a
    /// pure index heal (presigned uploads bypass the box entirely).
    async fn record_object(
        &self,
        conn: &S3Connection,
        storage_key: &str,
        size: i64,
        content_type: &str,
    ) -> HttpResult<()> {
        let now = time::now_iso();
        self.repository
            .upsert_storage_object(StorageObjectRecord {
                provider: StorageProviderType::S3,
                storage_account_id: conn.account_id.clone(),
                storage_key: storage_key.to_string(),
                object_id: conn.object_key(storage_key),
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
impl StorageProvider for S3StorageProvider {
    fn provider(&self) -> StorageProviderType {
        StorageProviderType::S3
    }

    async fn exists(&self, binding: &StorageBinding, storage_key: &str) -> HttpResult<bool> {
        let Some(account_id) = binding.storage_account_id.as_deref() else {
            return Err(misconfigured("the world has no linked storage account"));
        };
        if self
            .repository
            .get_storage_object(StorageProviderType::S3, account_id, storage_key)
            .await?
            .is_some()
        {
            return Ok(true);
        }
        // Row miss: the object may exist from a presigned upload that never
        // registered. HEAD the bucket and heal the row when found.
        let conn = self.require_connection(binding).await?;
        match self.head(&conn, storage_key).await? {
            Some((size, content_type)) => {
                self.record_object(&conn, storage_key, size, &content_type).await?;
                Ok(true)
            }
            None => Ok(false),
        }
    }

    async fn put(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        body: PutBody,
        content_type: &str,
    ) -> HttpResult<()> {
        let conn = self.require_connection(binding).await?;
        let object_key = conn.object_key(storage_key);
        let url = format!("{}/{}/{}", conn.endpoint, conn.bucket, object_key);
        let len = body.len();
        let mut req = self.http.put(&url);
        for (name, value) in sigv4::sign_headers(
            "PUT",
            &conn.target(&object_key),
            &conn.creds(),
            &[("content-type".into(), content_type.to_string())],
            time::now(),
        ) {
            req = req.header(&name, &value);
        }
        // Same streaming discipline as the Drive relay path: a known length
        // is never buffered (an explicit content-length keeps hyper off
        // chunked framing, which S3 rejects).
        let req = match body {
            PutBody::Bytes(bytes) => req.body(bytes),
            PutBody::Stream { stream, len: Some(len) } => {
                req.header("content-length", len.to_string()).body(reqwest::Body::wrap_stream(stream))
            }
            PutBody::Stream { stream, len: None } => {
                let bytes = PutBody::Stream { stream, len: None }.into_bytes().await?;
                req.body(bytes)
            }
        };
        let response = req.send().await.map_err(|e| transport("put", e))?;
        let status = response.status().as_u16();
        if status != 200 && status != 201 && status != 204 {
            return Err(unexpected("put", status));
        }
        let size = match len {
            Some(n) => n,
            None => self.head(&conn, storage_key).await?.map(|(s, _)| s).unwrap_or(0),
        };
        self.record_object(&conn, storage_key, size, content_type).await
    }

    async fn get(
        &self,
        binding: &StorageBinding,
        storage_key: &str,
        range: Option<&BlobRange>,
    ) -> HttpResult<Option<StoredBlob>> {
        let conn = self.require_connection(binding).await?;
        let object_key = conn.object_key(storage_key);
        let url = format!("{}/{}/{}", conn.endpoint, conn.bucket, object_key);
        let mut req = self.http.get(&url);
        for (name, value) in
            sigv4::sign_headers("GET", &conn.target(&object_key), &conn.creds(), &[], time::now())
        {
            req = req.header(&name, &value);
        }
        if let Some(r) = range {
            let end = r.end_inclusive.map(|e| e.to_string()).unwrap_or_default();
            req = req.header("range", format!("bytes={}-{end}", r.offset));
        }
        let response = req.send().await.map_err(|e| transport("get", e))?;
        let status = response.status().as_u16();
        match status {
            200 | 206 => {}
            404 => return Ok(None),
            416 => {
                return Err(HttpError::new(
                    416,
                    "range_not_satisfiable",
                    "Requested range is beyond the end of the stored blob.",
                ))
            }
            other => return Err(unexpected("get", other)),
        }
        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("application/octet-stream")
            .to_string();
        let size = response
            .headers()
            .get("content-length")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<i64>().ok());
        let content_range =
            response.headers().get("content-range").and_then(|v| v.to_str().ok()).map(|v| v.to_string());
        let body = response.bytes_stream();
        Ok(Some(StoredBlob {
            body: Box::pin(futures::StreamExt::map(body, |chunk| {
                chunk.map_err(|e| std::io::Error::other(format!("S3 body read failed: {e}")))
            })),
            content_type,
            size,
            status,
            content_range,
        }))
    }

    async fn delete(&self, binding: &StorageBinding, storage_key: &str) -> HttpResult<()> {
        let conn = self.require_connection(binding).await?;
        let object_key = conn.object_key(storage_key);
        let url = format!("{}/{}/{}", conn.endpoint, conn.bucket, object_key);
        let mut req = self.http.delete(&url);
        for (name, value) in
            sigv4::sign_headers("DELETE", &conn.target(&object_key), &conn.creds(), &[], time::now())
        {
            req = req.header(&name, &value);
        }
        let response = req.send().await.map_err(|e| transport("delete", e))?;
        let status = response.status().as_u16();
        // S3 answers 204 even for a missing key; 404 from odd stores is
        // equally "already gone".
        if status != 200 && status != 204 && status != 404 {
            return Err(unexpected("delete", status));
        }
        self.repository.delete_storage_object(StorageProviderType::S3, &conn.account_id, storage_key).await?;
        Ok(())
    }

    /// S3 has no quota API; unknown/unlimited (the Drive-full preflight is
    /// Drive-only, and the storage tab simply shows no usage bar).
    async fn quota(&self, _binding: &StorageBinding) -> HttpResult<StorageQuota> {
        Ok(StorageQuota::default())
    }

    fn account_cleanup(&self, _binding: &StorageBinding) -> Option<&dyn AccountCleanupCapable> {
        Some(self)
    }

    fn presign(&self, _binding: &StorageBinding) -> Option<&dyn PresignCapable> {
        Some(self)
    }
}

#[async_trait]
impl super::PresignCapable for S3StorageProvider {
    async fn presign_context(
        &self,
        binding: &StorageBinding,
    ) -> HttpResult<Box<dyn super::TransferPresigner>> {
        let conn = self.require_connection(binding).await?;
        Ok(Box::new(S3PresignContext { conn, ttl_seconds: self.presign_ttl_seconds }))
    }
}

/// One resolved bucket connection; presigns any number of keys synchronously.
pub struct S3PresignContext {
    conn: S3Connection,
    ttl_seconds: i64,
}

impl S3PresignContext {
    fn signed(&self, method: SignedBlobMethod, verb: &str, storage_key: &str) -> SignedBlobUrl {
        let object_key = self.conn.object_key(storage_key);
        SignedBlobUrl {
            method,
            url: sigv4::presign(
                verb,
                &self.conn.target(&object_key),
                &self.conn.creds(),
                self.ttl_seconds,
                time::now(),
            ),
            headers: Default::default(),
            expires_at: time::plus_ms_iso(time::now(), self.ttl_seconds * 1000),
        }
    }
}

impl super::TransferPresigner for S3PresignContext {
    fn presign_put(&self, storage_key: &str) -> SignedBlobUrl {
        self.signed(SignedBlobMethod::PUT, "PUT", storage_key)
    }

    fn presign_get(&self, storage_key: &str) -> SignedBlobUrl {
        self.signed(SignedBlobMethod::GET, "GET", storage_key)
    }
}

#[async_trait]
impl AccountCleanupCapable for S3StorageProvider {
    /// One ListObjectsV2 page of object keys under the account's prefix.
    /// The returned ids are full bucket object keys.
    async fn list_account_object_ids(
        &self,
        binding: &StorageBinding,
        page_token: Option<&str>,
    ) -> HttpResult<(Vec<String>, Option<String>)> {
        let conn = self.require_connection(binding).await?;
        // Query params must be part of the SigV4 canonical request; reuse the
        // presign-style canonicalization by signing a GET with query via
        // header auth is more involved; ListObjectsV2 rides a presigned URL
        // instead, which signs the query naturally.
        let mut query = format!("list-type=2&max-keys=1000&prefix={}", sigv4_encode(&conn.key_prefix));
        if let Some(token) = page_token {
            query.push_str(&format!("&continuation-token={}", sigv4_encode(token)));
        }
        let url = presign_with_query(&conn, "GET", "", &query, self.presign_ttl_seconds);
        let response = self.http.get(&url).send().await.map_err(|e| transport("list", e))?;
        let status = response.status().as_u16();
        if status != 200 {
            return Err(unexpected("list", status));
        }
        let text = response.text().await.map_err(|e| transport("list", e))?;
        Ok(parse_list_objects(&text))
    }

    /// `file_id` here is the full bucket object key from the listing.
    async fn delete_account_object(&self, binding: &StorageBinding, file_id: &str) -> HttpResult<()> {
        let conn = self.require_connection(binding).await?;
        let url = format!("{}/{}/{}", conn.endpoint, conn.bucket, file_id);
        let mut req = self.http.delete(&url);
        for (name, value) in
            sigv4::sign_headers("DELETE", &conn.target(file_id), &conn.creds(), &[], time::now())
        {
            req = req.header(&name, &value);
        }
        let response = req.send().await.map_err(|e| transport("delete", e))?;
        let status = response.status().as_u16();
        if status != 200 && status != 204 && status != 404 {
            return Err(unexpected("delete", status));
        }
        Ok(())
    }

    /// Nothing to revoke: the user rotates/deletes their own bucket keys.
    async fn revoke_account_access(&self, _binding: &StorageBinding) -> HttpResult<()> {
        Ok(())
    }
}

/// Connection details as entered in the link form, before any account exists.
pub struct S3ConnectionParams {
    pub endpoint: String,
    pub region: String,
    pub bucket: String,
    pub key_prefix: String,
    pub access_key_id: String,
    pub secret_access_key: String,
}

/// Link-time validation: write, read back, and delete a tiny probe object.
/// Returns a user-facing message on failure (re-rendered in the link form).
/// Uses its own no-redirect client: the endpoint is user-supplied (the SSRF
/// surface), so the box must never follow it anywhere else.
pub async fn probe_bucket(params: &S3ConnectionParams, probe_id: &str) -> Result<(), String> {
    let http = reqwest::Client::builder()
        .user_agent("sharedworld-server")
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|e| format!("probe client: {e}"))?;
    let http = &http;
    let conn = S3Connection {
        endpoint: params.endpoint.trim_end_matches('/').to_string(),
        region: params.region.clone(),
        bucket: params.bucket.clone(),
        key_prefix: normalize_key_prefix(Some(&params.key_prefix)),
        access_key_id: params.access_key_id.clone(),
        secret_access_key: params.secret_access_key.clone(),
        account_id: String::new(),
    };
    let object_key = format!("{}.sharedworld-probe-{probe_id}", conn.key_prefix);
    let url = format!("{}/{}/{}", conn.endpoint, conn.bucket, object_key);
    let payload = b"sharedworld-probe".to_vec();

    let mut put = http.put(&url).timeout(std::time::Duration::from_secs(15));
    for (name, value) in sigv4::sign_headers(
        "PUT",
        &conn.target(&object_key),
        &conn.creds(),
        &[("content-type".into(), "text/plain".into())],
        time::now(),
    ) {
        put = put.header(&name, &value);
    }
    let response = put.body(payload.clone()).send().await.map_err(|e| probe_transport_message(&e))?;
    let status = response.status().as_u16();
    if !(status == 200 || status == 201 || status == 204) {
        return Err(probe_status_message("writing a test object", status));
    }

    let mut get = http.get(&url).timeout(std::time::Duration::from_secs(15));
    for (name, value) in
        sigv4::sign_headers("GET", &conn.target(&object_key), &conn.creds(), &[], time::now())
    {
        get = get.header(&name, &value);
    }
    let response = get.send().await.map_err(|e| probe_transport_message(&e))?;
    let status = response.status().as_u16();
    if status != 200 {
        return Err(probe_status_message("reading the test object back", status));
    }
    let body = response.bytes().await.map_err(|e| probe_transport_message(&e))?;
    if body.as_ref() != payload.as_slice() {
        return Err(
            "The bucket returned different bytes than SharedWorld wrote. Check the endpoint URL.".into()
        );
    }

    let mut delete = http.delete(&url).timeout(std::time::Duration::from_secs(15));
    for (name, value) in
        sigv4::sign_headers("DELETE", &conn.target(&object_key), &conn.creds(), &[], time::now())
    {
        delete = delete.header(&name, &value);
    }
    let response = delete.send().await.map_err(|e| probe_transport_message(&e))?;
    let status = response.status().as_u16();
    if !(status == 200 || status == 204 || status == 404) {
        return Err(probe_status_message("deleting the test object", status));
    }
    Ok(())
}

fn probe_transport_message(e: &reqwest::Error) -> String {
    if e.is_timeout() {
        return "The endpoint did not answer in time. Check the endpoint URL.".into();
    }
    if e.is_connect() {
        return "SharedWorld could not connect to the endpoint. Check the endpoint URL (it must be reachable from the internet).".into();
    }
    format!("SharedWorld could not reach the endpoint: {e}")
}

fn probe_status_message(step: &str, status: u16) -> String {
    match status {
        401 | 403 => format!(
            "The bucket refused {step} (HTTP {status}). Check the access key id, secret key, and that the key has read/write permission on this bucket."
        ),
        404 => format!(
            "The bucket was not found while {step} (HTTP 404). Check the bucket name and endpoint URL."
        ),
        301 | 307 => format!(
            "The endpoint redirected while {step} (HTTP {status}); usually a wrong region. Check the region."
        ),
        _ => format!("The bucket refused {step} (HTTP {status})."),
    }
}

fn sigv4_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for b in value.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// Presign an arbitrary bucket-level request with extra query parameters
/// (ListObjectsV2). `object_key` may be "" for bucket-level calls.
fn presign_with_query(
    conn: &S3Connection,
    method: &str,
    object_key: &str,
    extra_query: &str,
    ttl_secs: i64,
) -> String {
    // Reuses sigv4::presign's canonicalization by inlining it with the extra
    // query merged in sorted order.
    sigv4::presign_with_extra_query(
        method,
        &conn.target(object_key),
        &conn.creds(),
        extra_query,
        ttl_secs,
        time::now(),
    )
}

/// Minimal ListObjectsV2 XML extraction: `<Key>` values and the
/// `<NextContinuationToken>`. The XML is machine-generated and flat, so a
/// scan is enough; no XML dependency.
fn parse_list_objects(xml: &str) -> (Vec<String>, Option<String>) {
    let mut keys = Vec::new();
    let mut rest = xml;
    while let Some(start) = rest.find("<Key>") {
        let after = &rest[start + 5..];
        let Some(end) = after.find("</Key>") else { break };
        keys.push(xml_unescape(&after[..end]));
        rest = &after[end..];
    }
    let token = xml
        .find("<NextContinuationToken>")
        .and_then(|s| {
            let after = &xml[s + "<NextContinuationToken>".len()..];
            after.find("</NextContinuationToken>").map(|e| xml_unescape(&after[..e]))
        })
        .filter(|t| !t.is_empty());
    (keys, token)
}

fn xml_unescape(s: &str) -> String {
    s.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn key_prefix_normalization() {
        assert_eq!(normalize_key_prefix(None), "sharedworld/");
        assert_eq!(normalize_key_prefix(Some("")), "");
        assert_eq!(normalize_key_prefix(Some("  ")), "");
        assert_eq!(normalize_key_prefix(Some("worlds")), "worlds/");
        assert_eq!(normalize_key_prefix(Some("/worlds/")), "worlds/");
        assert_eq!(normalize_key_prefix(Some("a/b")), "a/b/");
    }

    #[test]
    fn list_objects_xml_extraction() {
        let xml = r#"<?xml version="1.0"?><ListBucketResult>
            <IsTruncated>true</IsTruncated>
            <Contents><Key>sharedworld/packs/full/ab/x.pack</Key><Size>10</Size></Contents>
            <Contents><Key>sharedworld/a&amp;b.bin</Key></Contents>
            <NextContinuationToken>tok123</NextContinuationToken>
        </ListBucketResult>"#;
        let (keys, token) = parse_list_objects(xml);
        assert_eq!(keys, vec!["sharedworld/packs/full/ab/x.pack".to_string(), "sharedworld/a&b.bin".into()]);
        assert_eq!(token.as_deref(), Some("tok123"));
        assert_eq!(parse_list_objects("<ListBucketResult></ListBucketResult>"), (vec![], None));
    }
}
