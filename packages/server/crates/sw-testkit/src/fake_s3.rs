//! MinIO-lite in-process S3 stand-in: enough of the S3 HTTP surface (PUT /
//! GET with Range / HEAD / DELETE / ListObjectsV2) for the REAL
//! `S3StorageProvider` (SigV4 header auth, streaming PUT, presigned URLs)
//! to run against it. Shared by the sw-core provider tests and the
//! integration server's S3 e2e mode. Signatures are not verified (the SigV4
//! vectors pin that in sw-core unit tests); requests must merely CARRY auth
//! material, so an unauthenticated code path shows up in `unsigned_requests`.

use std::collections::BTreeMap;
use std::net::SocketAddr;
use std::sync::Arc;

use axum::body::Body;
use axum::extract::{Path as AxPath, Query, RawQuery, State};
use axum::http::{HeaderMap, Method, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::any;
use axum::Router;
use bytes::Bytes;
use parking_lot::Mutex;

/// Fixed credentials the integration profile hands to link forms.
pub const FAKE_S3_ACCESS_KEY_ID: &str = "e2e-access-key";
pub const FAKE_S3_SECRET_ACCESS_KEY: &str = "e2e-secret-key";
pub const FAKE_S3_BUCKET: &str = "e2e-bucket";

#[derive(Clone, Default)]
pub struct FakeS3 {
    /// `bucket/objectKey` → (bytes, content-type).
    pub objects: Arc<Mutex<BTreeMap<String, (Bytes, String)>>>,
    /// Requests that arrived without any SigV4 material (header or query).
    pub unsigned_requests: Arc<Mutex<u32>>,
}

impl FakeS3 {
    pub fn clear(&self) {
        self.objects.lock().clear();
        *self.unsigned_requests.lock() = 0;
    }

    pub fn router(&self) -> Router {
        Router::new()
            .route("/{*path}", any(fake_handler))
            // Region bundles run to tens of MB; axum's 2 MiB default body
            // limit would 413 them (same override as /__fake-drive/upload).
            .layer(axum::extract::DefaultBodyLimit::max(4 * 1024 * 1024 * 1024))
            .with_state(self.clone())
    }

    /// Bind on a fresh loopback port and serve forever; returns the origin.
    pub async fn spawn(&self) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("bind fake s3");
        let addr: SocketAddr = listener.local_addr().expect("fake s3 addr");
        let app = self.router();
        tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });
        format!("http://{addr}")
    }

    /// `/__test/s3` body: connection info plus a live object listing.
    pub fn info(&self, endpoint: &str) -> serde_json::Value {
        let objects: Vec<serde_json::Value> = self
            .objects
            .lock()
            .iter()
            .map(|(k, (bytes, content_type))| {
                serde_json::json!({"key": k, "size": bytes.len(), "contentType": content_type})
            })
            .collect();
        serde_json::json!({
            "endpoint": endpoint,
            "bucket": FAKE_S3_BUCKET,
            "accessKeyId": FAKE_S3_ACCESS_KEY_ID,
            "secretAccessKey": FAKE_S3_SECRET_ACCESS_KEY,
            "unsignedRequests": *self.unsigned_requests.lock(),
            "objects": objects,
        })
    }
}

pub async fn fake_handler(
    State(fake): State<FakeS3>,
    method: Method,
    AxPath(path): AxPath<String>,
    Query(q): Query<std::collections::HashMap<String, String>>,
    RawQuery(raw_query): RawQuery,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let signed = headers.get("authorization").is_some()
        || raw_query.as_deref().unwrap_or("").contains("X-Amz-Signature=");
    if !signed {
        *fake.unsigned_requests.lock() += 1;
    }
    let path = path.trim_start_matches('/').to_string();
    // Bucket-level list: GET /{bucket}?list-type=2
    if method == Method::GET && q.get("list-type").map(String::as_str) == Some("2") {
        let bucket_prefix = format!("{path}/");
        let key_prefix = q.get("prefix").cloned().unwrap_or_default();
        let mut xml = String::from("<?xml version=\"1.0\"?><ListBucketResult>");
        for key in fake.objects.lock().keys() {
            if let Some(rel) = key.strip_prefix(&bucket_prefix) {
                if rel.starts_with(&key_prefix) {
                    xml.push_str(&format!("<Contents><Key>{rel}</Key></Contents>"));
                }
            }
        }
        xml.push_str("</ListBucketResult>");
        return (StatusCode::OK, xml).into_response();
    }
    match method {
        Method::PUT => {
            let content_type = headers
                .get("content-type")
                .and_then(|v| v.to_str().ok())
                .unwrap_or("application/octet-stream")
                .to_string();
            fake.objects.lock().insert(path, (body, content_type));
            StatusCode::OK.into_response()
        }
        Method::HEAD => match fake.objects.lock().get(&path) {
            Some((bytes, content_type)) => Response::builder()
                .status(200)
                .header("content-length", bytes.len().to_string())
                .header("content-type", content_type)
                .body(Body::empty())
                .unwrap(),
            None => StatusCode::NOT_FOUND.into_response(),
        },
        Method::GET => match fake.objects.lock().get(&path).cloned() {
            Some((bytes, content_type)) => {
                if let Some(range) = headers.get("range").and_then(|v| v.to_str().ok()) {
                    let spec = range.trim_start_matches("bytes=");
                    let (start, end) = spec.split_once('-').unwrap();
                    let start: usize = start.parse().unwrap();
                    if start >= bytes.len() {
                        return StatusCode::RANGE_NOT_SATISFIABLE.into_response();
                    }
                    let end: usize =
                        end.parse::<usize>().map(|e| e.min(bytes.len() - 1)).unwrap_or(bytes.len() - 1);
                    let slice = bytes.slice(start..=end);
                    return Response::builder()
                        .status(206)
                        .header("content-type", content_type)
                        .header("content-length", slice.len().to_string())
                        .header("content-range", format!("bytes {start}-{end}/{}", bytes.len()))
                        .body(Body::from(slice))
                        .unwrap();
                }
                Response::builder()
                    .status(200)
                    .header("content-type", content_type)
                    .header("content-length", bytes.len().to_string())
                    .body(Body::from(bytes))
                    .unwrap()
            }
            None => StatusCode::NOT_FOUND.into_response(),
        },
        Method::DELETE => {
            fake.objects.lock().remove(&path);
            StatusCode::NO_CONTENT.into_response()
        }
        _ => StatusCode::METHOD_NOT_ALLOWED.into_response(),
    }
}
