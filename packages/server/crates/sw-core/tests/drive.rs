//! Characterization tests for the Google Drive HTTP client against a local
//! fake Drive API (port of `test/external/google-drive-storage.test.ts`). The
//! fake speaks just enough of the files/upload/about/token surface for the
//! provider's request shapes and retry behavior to be pinned.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

use axum::body::Body;
use axum::extract::{Request, State};
use axum::response::Response;
use axum::Router;
use bytes::Bytes;
use futures::StreamExt;
use parking_lot::Mutex;
use sw_contracts::StorageProviderType;
use sw_core::config::Config;
use sw_core::storage::drive::GoogleDriveStorageProvider;
use sw_core::storage::{
    BlobRange, PutBody, ResumableProbe, ResumableUploadCapable, StorageBinding, StorageProvider,
};
use sw_db::repo::StorageAccountRecord;
use sw_db::{migrate, time, Db, Repository};

// ---------------------------------------------------------------------------
// fake Drive
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Default)]
struct Reply {
    status: u16,
    body: Option<String>,
    headers: Vec<(String, String)>,
    /// Emit the body in two chunks, releasing the tail only on the gate.
    gated: bool,
}

impl Reply {
    fn status(status: u16) -> Self {
        Self { status, ..Default::default() }
    }
    fn json(status: u16, body: serde_json::Value) -> Self {
        Self { status, body: Some(body.to_string()), ..Default::default() }
    }
    fn text(status: u16, body: &str) -> Self {
        Self { status, body: Some(body.to_string()), ..Default::default() }
    }
    fn header(mut self, name: &str, value: String) -> Self {
        self.headers.push((name.to_string(), value));
        self
    }
}

#[derive(Debug, Clone)]
struct Recorded {
    method: String,
    path: String,
    auth: Option<String>,
    content_type: Option<String>,
    range: Option<String>,
    content_range: Option<String>,
    body_text: String,
}

struct FakeDrive {
    requests: Mutex<Vec<Recorded>>,
    script: Mutex<VecDeque<Reply>>,
    default: Mutex<Reply>,
    token_reply: Mutex<Reply>,
    token_calls: AtomicUsize,
    gate: Arc<tokio::sync::Semaphore>,
}

impl FakeDrive {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            requests: Mutex::new(Vec::new()),
            script: Mutex::new(VecDeque::new()),
            default: Mutex::new(Reply::json(200, serde_json::json!({ "id": "drive-object-1" }))),
            token_reply: Mutex::new(Reply::json(
                200,
                serde_json::json!({ "access_token": "refreshed-token", "expires_in": 3600 }),
            )),
            token_calls: AtomicUsize::new(0),
            gate: Arc::new(tokio::sync::Semaphore::new(0)),
        })
    }

    fn script(&self, replies: Vec<Reply>) {
        *self.script.lock() = replies.into();
    }
    fn set_default(&self, reply: Reply) {
        *self.default.lock() = reply;
    }
    fn clear_requests(&self) {
        self.requests.lock().clear();
    }
    fn requests(&self) -> Vec<Recorded> {
        self.requests.lock().clone()
    }
    fn methods(&self) -> Vec<String> {
        self.requests.lock().iter().map(|r| r.method.clone()).collect()
    }
}

async fn handler(State(state): State<Arc<FakeDrive>>, req: Request) -> Response {
    let method = req.method().to_string();
    let path =
        req.uri().path_and_query().map(|p| p.to_string()).unwrap_or_else(|| req.uri().path().to_string());
    let [auth, content_type, range, content_range] =
        ["authorization", "content-type", "range", "content-range"]
            .map(|name| req.headers().get(name).and_then(|v| v.to_str().ok()).map(|s| s.to_string()));
    let body = axum::body::to_bytes(req.into_body(), usize::MAX).await.unwrap_or_default();

    // The OAuth endpoint is its own lane: the TS test stubs global fetch for
    // it, so it neither records a request nor consumes the Drive script.
    let reply = if path.starts_with("/token") {
        state.token_calls.fetch_add(1, Ordering::SeqCst);
        state.token_reply.lock().clone()
    } else {
        state.requests.lock().push(Recorded {
            method,
            path,
            auth,
            content_type,
            range,
            content_range,
            body_text: String::from_utf8_lossy(&body).to_string(),
        });
        let scripted = state.script.lock().pop_front();
        scripted.unwrap_or_else(|| state.default.lock().clone())
    };

    let mut builder = Response::builder().status(reply.status);
    for (name, value) in &reply.headers {
        builder = builder.header(name, value);
    }
    if reply.gated {
        let gate = state.gate.clone();
        let text = reply.body.unwrap_or_default();
        let (head, tail) = text.split_at(text.len() / 2);
        let (head, tail) = (head.to_string(), tail.to_string());
        let stream = futures::stream::once(async move { Ok::<_, std::io::Error>(Bytes::from(head)) }).chain(
            futures::stream::once(async move {
                let _permit = gate.acquire().await.expect("gate");
                Ok::<_, std::io::Error>(Bytes::from(tail))
            }),
        );
        return builder.body(Body::from_stream(stream)).unwrap();
    }
    builder.body(reply.body.map(Body::from).unwrap_or_else(Body::empty)).unwrap()
}

struct Fixture {
    repo: Repository,
    provider: GoogleDriveStorageProvider,
    binding: StorageBinding,
    account_id: String,
    fake: Arc<FakeDrive>,
    base: String,
}

impl Fixture {
    async fn new() -> Fixture {
        Self::with_config(|_| {}).await
    }

    async fn with_config(tweak: impl FnOnce(&mut Config)) -> Fixture {
        let fake = FakeDrive::new();
        let app = Router::new().fallback(handler).with_state(fake.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let addr = listener.local_addr().expect("addr");
        tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });
        let base = format!("http://127.0.0.1:{}", addr.port());

        let db = Db::open_memory().expect("db");
        migrate::migrate(&db).expect("migrate");
        let repo = Repository::new(db, None);
        let mut config = Config::dev();
        config.google_drive_api_base = Some(format!("{base}/drive/v3"));
        config.google_oauth_token_url = Some(format!("{base}/token"));
        // Keep exponential backoff effectively instant so retry tests stay fast.
        config.drive_retry_base_delay_ms = Some(1);
        config.drive_retry_max_delay_ms = Some(2);
        config.drive_max_upload_starts_per_second = Some(10_000);
        tweak(&mut config);
        let http = reqwest::Client::builder().build().expect("client");
        let provider = GoogleDriveStorageProvider::new(repo.clone(), Arc::new(config), http);
        let account_id = "storage-test-1".to_string();
        Fixture {
            binding: StorageBinding {
                provider: StorageProviderType::GoogleDrive,
                storage_account_id: Some(account_id.clone()),
            },
            repo,
            provider,
            account_id,
            fake,
            base,
        }
    }

    async fn seed_account(&self) {
        self.seed_account_expiring(time::plus_ms_iso(time::now(), 60 * 60_000)).await;
    }

    async fn seed_account_expiring(&self, token_expires_at: String) {
        self.repo
            .create_or_update_storage_account(StorageAccountRecord {
                id: self.account_id.clone(),
                provider: StorageProviderType::GoogleDrive,
                owner_player_uuid: "player-owner".into(),
                external_account_id: format!("external-{}", self.account_id),
                email: Some("owner@example.com".into()),
                display_name: Some("Owner".into()),
                access_token: Some("valid-access-token".into()),
                refresh_token: Some("refresh-token-1".into()),
                token_expires_at: Some(token_expires_at),
                s3_endpoint: None,
                s3_region: None,
                s3_bucket: None,
                s3_key_prefix: None,
                created_at: "2000-01-01T00:00:00.000Z".into(),
                updated_at: "2000-01-01T00:00:00.000Z".into(),
            })
            .await
            .expect("seed account");
    }

    async fn object(&self, key: &str) -> Option<sw_db::repo::StorageObjectRecord> {
        self.repo
            .get_storage_object(StorageProviderType::GoogleDrive, &self.account_id, key)
            .await
            .expect("object")
    }

    async fn account(&self) -> StorageAccountRecord {
        self.repo.get_storage_account(&self.account_id).await.expect("account").expect("present")
    }

    async fn put_bytes(&self, key: &str, text: &str) -> Result<(), sw_core::HttpError> {
        self.provider
            .put(
                &self.binding,
                key,
                PutBody::Bytes(Bytes::from(text.to_string())),
                "application/octet-stream",
            )
            .await
    }
}

fn err_of<T>(result: Result<T, sw_core::HttpError>) -> sw_core::HttpError {
    match result {
        Ok(_) => panic!("expected an error"),
        Err(e) => e,
    }
}

fn stream_of(text: &str) -> sw_core::storage::BodyStream {
    let bytes = Bytes::from(text.to_string());
    Box::pin(futures::stream::once(async move { Ok(bytes) }))
}

async fn read_all(blob: sw_core::storage::StoredBlob) -> String {
    String::from_utf8(blob.into_bytes().await.expect("body").to_vec()).expect("utf8")
}

// ---------------------------------------------------------------------------
// put
// ---------------------------------------------------------------------------

#[tokio::test]
async fn put_creates_a_new_file_via_multipart_with_bearer_auth_and_records_the_object() {
    let f = Fixture::new().await;
    f.seed_account().await;

    f.put_bytes("worlds/w1/snapshot.bin", "payload").await.unwrap();

    let requests = f.fake.requests();
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].method, "POST");
    assert_eq!(requests[0].path, "/upload/drive/v3/files?uploadType=multipart");
    assert_eq!(requests[0].auth.as_deref(), Some("Bearer valid-access-token"));
    assert!(requests[0].content_type.as_deref().unwrap().starts_with("multipart/related; boundary="));
    // The metadata part names the object from the base64url storage key.
    assert!(requests[0].body_text.contains("sharedworld-"));

    let object = f.object("worlds/w1/snapshot.bin").await.unwrap();
    assert_eq!(object.object_id, "drive-object-1");
    assert!(f.provider.exists(&f.binding, "worlds/w1/snapshot.bin").await.unwrap());
}

#[tokio::test]
async fn put_updates_an_existing_object_in_place_via_media_patch() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/snapshot.bin", "v1").await.unwrap();
    f.fake.clear_requests();

    f.put_bytes("worlds/w1/snapshot.bin", "v2").await.unwrap();

    let requests = f.fake.requests();
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].method, "PATCH");
    assert_eq!(requests[0].path, "/upload/drive/v3/files/drive-object-1?uploadType=media");
}

#[tokio::test]
async fn put_retries_retryable_statuses_with_backoff_and_then_succeeds() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.script(vec![Reply::status(500), Reply::status(429)]);

    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();

    assert_eq!(f.fake.requests().len(), 3);
}

#[tokio::test]
async fn put_gives_up_after_five_attempts_when_the_failure_persists() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.script((0..5).map(|_| Reply::text(503, "overloaded")).collect());

    let err = f.put_bytes("worlds/w1/a.bin", "data").await.unwrap_err();
    assert_eq!(err.code, "drive_upload_failed");
    assert_eq!(f.fake.requests().len(), 5);
}

#[tokio::test]
async fn a_rate_limit_403_is_retried_like_a_429() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.script(vec![Reply::json(
        403,
        serde_json::json!({ "error": { "code": 403, "errors": [{ "domain": "usageLimits", "reason": "userRateLimitExceeded" }] } }),
    )]);

    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();

    assert_eq!(f.fake.requests().len(), 2);
}

#[tokio::test]
async fn a_permanent_403_storage_quota_fails_fast_without_burning_the_retry_ladder() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.script(vec![Reply::json(
        403,
        serde_json::json!({ "error": { "code": 403, "errors": [{ "domain": "usageLimits", "reason": "storageQuotaExceeded" }] } }),
    )]);

    let err = f.put_bytes("worlds/w1/a.bin", "data").await.unwrap_err();
    // Quota exhaustion is its own terminal, actionable code (403 = never
    // retried by any shipped client's transport policy).
    assert_eq!(err.code, "drive_storage_full");
    assert_eq!(err.status, 403);
    assert!(err.message.contains("Google Drive is full"));
    assert_eq!(f.fake.requests().len(), 1);
}

#[tokio::test]
async fn a_missing_consent_403_tombstones_the_refresh_token_and_demands_a_relink() {
    // Granular consent: OAuth completed without the Drive checkbox. The link
    // looks healthy until the first real Drive write lands here.
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.script(vec![Reply::json(
        403,
        serde_json::json!({ "error": { "code": 403, "message": "Request had insufficient authentication scopes.", "errors": [{ "domain": "global", "reason": "insufficientPermissions" }] } }),
    )]);

    let err = f.put_bytes("worlds/w1/a.bin", "data").await.unwrap_err();
    assert_eq!(err.status, 401);
    assert_eq!(err.code, "drive_reauth_required");
    assert!(err.message.contains("checkbox"));
    assert_eq!(f.fake.requests().len(), 1);
    assert_eq!(f.account().await.refresh_token, None);
}

#[tokio::test]
async fn put_does_not_retry_a_non_retryable_client_error() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.script(vec![Reply::text(400, "bad multipart")]);

    let err = f.put_bytes("worlds/w1/a.bin", "data").await.unwrap_err();
    assert_eq!(err.code, "drive_upload_failed");
    assert!(err.message.contains("HTTP 400"));
    assert_eq!(f.fake.requests().len(), 1);
}

#[tokio::test]
async fn an_unlinked_binding_is_rejected_before_any_network_traffic() {
    let f = Fixture::new().await;
    let unlinked = StorageBinding { provider: StorageProviderType::GoogleDrive, storage_account_id: None };
    let err = f
        .provider
        .put(&unlinked, "k", PutBody::Bytes(Bytes::from_static(b"v")), "text/plain")
        .await
        .unwrap_err();
    assert_eq!(err.code, "missing_storage_account");
    assert!(f.fake.requests().is_empty());
}

// ---------------------------------------------------------------------------
// get
// ---------------------------------------------------------------------------

#[tokio::test]
async fn get_returns_the_blob_for_a_known_object() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "stored-bytes").await.unwrap();
    f.fake.clear_requests();
    f.fake.set_default(Reply::text(200, "stored-bytes"));

    let blob = f.provider.get(&f.binding, "worlds/w1/a.bin", None).await.unwrap().unwrap();
    assert_eq!(read_all(blob).await, "stored-bytes");
    let requests = f.fake.requests();
    assert_eq!(requests[0].method, "GET");
    assert_eq!(requests[0].path, "/drive/v3/files/drive-object-1?alt=media");
}

#[tokio::test]
async fn a_ranged_get_forwards_range_and_streams_drives_206_through() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "stored-bytes").await.unwrap();
    f.fake.clear_requests();
    f.fake.script(vec![Reply::text(206, "red-bytes")
        .header("content-range", "bytes 3-11/12".into())
        .header("content-length", "9".into())]);

    let blob = f
        .provider
        .get(&f.binding, "worlds/w1/a.bin", Some(&BlobRange { offset: 3, end_inclusive: None }))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(blob.status, 206);
    assert_eq!(blob.content_range.as_deref(), Some("bytes 3-11/12"));
    assert_eq!(blob.size, Some(9));
    assert_eq!(f.fake.requests()[0].range.as_deref(), Some("bytes=3-"));
    assert_eq!(read_all(blob).await, "red-bytes");
}

#[tokio::test]
async fn a_range_past_the_end_maps_drives_416_to_range_not_satisfiable() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();
    f.fake.script(vec![Reply::status(416)]);

    let err = err_of(
        f.provider
            .get(&f.binding, "worlds/w1/a.bin", Some(&BlobRange { offset: 9999, end_inclusive: None }))
            .await,
    );
    assert_eq!(err.status, 416);
    assert_eq!(err.code, "range_not_satisfiable");
}

#[tokio::test]
async fn get_resolves_before_the_body_finishes_streaming() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "seed").await.unwrap();
    f.fake.clear_requests();
    f.fake.set_default(Reply { gated: true, ..Reply::text(200, "head-tail") });

    // An implementation that awaited the whole body would deadlock here: the
    // tail is released only after `get()` has already resolved.
    let blob = f.provider.get(&f.binding, "worlds/w1/a.bin", None).await.unwrap().unwrap();
    f.fake.gate.add_permits(1);
    assert_eq!(read_all(blob).await, "head-tail");
}

#[tokio::test]
async fn get_drops_the_local_object_record_when_drive_reports_404() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();
    f.fake.script(vec![Reply::status(404)]);

    assert!(f.provider.get(&f.binding, "worlds/w1/a.bin", None).await.unwrap().is_none());
    assert!(f.object("worlds/w1/a.bin").await.is_none());
}

#[tokio::test]
async fn get_retries_retryable_statuses_and_then_succeeds() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();
    f.fake.clear_requests();
    f.fake.script(vec![Reply::status(503), Reply::status(429)]);
    f.fake.set_default(Reply::text(200, "payload"));

    let blob = f.provider.get(&f.binding, "worlds/w1/a.bin", None).await.unwrap().unwrap();
    assert_eq!(read_all(blob).await, "payload");
    assert_eq!(f.fake.requests().len(), 3);
}

#[tokio::test]
async fn get_gives_up_after_four_attempts_when_the_failure_persists() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();
    f.fake.clear_requests();
    f.fake.set_default(Reply::status(503));

    let err = err_of(f.provider.get(&f.binding, "worlds/w1/a.bin", None).await);
    assert_eq!(err.status, 503);
    assert_eq!(err.code, "drive_download_failed");
    assert_eq!(f.fake.requests().len(), 4);
}

#[tokio::test]
async fn get_does_not_retry_a_non_retryable_client_error() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();
    f.fake.clear_requests();
    f.fake.script(vec![Reply::status(400)]);

    let err = err_of(f.provider.get(&f.binding, "worlds/w1/a.bin", None).await);
    assert_eq!(err.status, 400);
    assert_eq!(err.code, "drive_download_failed");
    assert_eq!(f.fake.requests().len(), 1);
}

// ---------------------------------------------------------------------------
// delete
// ---------------------------------------------------------------------------

#[tokio::test]
async fn delete_removes_the_drive_file_and_the_local_record() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();
    f.fake.clear_requests();
    f.fake.set_default(Reply::status(204));

    f.provider.delete(&f.binding, "worlds/w1/a.bin").await.unwrap();

    let requests = f.fake.requests();
    assert_eq!(requests[0].method, "DELETE");
    assert_eq!(requests[0].path, "/drive/v3/files/drive-object-1");
    assert!(f.object("worlds/w1/a.bin").await.is_none());
}

#[tokio::test]
async fn delete_retries_retryable_statuses_and_then_removes_the_local_record() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();
    f.fake.clear_requests();
    f.fake.script(vec![Reply::status(503)]);
    f.fake.set_default(Reply::status(204));

    f.provider.delete(&f.binding, "worlds/w1/a.bin").await.unwrap();

    assert_eq!(f.fake.requests().len(), 2);
    assert!(f.object("worlds/w1/a.bin").await.is_none());
}

#[tokio::test]
async fn a_persistently_failing_delete_keeps_the_local_record_for_later_gc() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();
    f.fake.clear_requests();
    f.fake.set_default(Reply::status(500));

    let err = f.provider.delete(&f.binding, "worlds/w1/a.bin").await.unwrap_err();
    assert_eq!(err.status, 500);
    assert_eq!(err.code, "drive_delete_failed");
    assert!(f.object("worlds/w1/a.bin").await.is_some());
}

#[tokio::test]
async fn delete_treats_a_drive_404_as_already_gone() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();
    f.fake.clear_requests();
    f.fake.script(vec![Reply::status(404)]);

    f.provider.delete(&f.binding, "worlds/w1/a.bin").await.unwrap();

    assert_eq!(f.fake.requests().len(), 1);
    assert!(f.object("worlds/w1/a.bin").await.is_none());
}

// ---------------------------------------------------------------------------
// streaming put + resumable sessions
// ---------------------------------------------------------------------------

#[tokio::test]
async fn put_streams_a_known_length_body_through_a_resumable_session_without_bearer_on_the_put() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.script(vec![
        Reply::status(200).header("location", format!("{}/resumable/stream-1", f.base)),
        Reply::json(200, serde_json::json!({ "id": "stream-file-1", "size": "7" })),
    ]);

    f.provider
        .put(
            &f.binding,
            "worlds/w1/big.bin",
            PutBody::Stream { stream: stream_of("payload"), len: Some(7) },
            "application/octet-stream",
        )
        .await
        .unwrap();

    let requests = f.fake.requests();
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[0].method, "POST");
    assert_eq!(requests[0].path, "/upload/drive/v3/files?uploadType=resumable");
    assert_eq!(requests[1].method, "PUT");
    assert_eq!(requests[1].path, "/resumable/stream-1");
    assert_eq!(requests[1].content_range.as_deref(), Some("bytes 0-6/7"));
    assert_eq!(requests[1].body_text, "payload");
    // The session URL is the credential — the byte PUT must not carry ours.
    assert_eq!(requests[1].auth, None);

    let object = f.object("worlds/w1/big.bin").await.unwrap();
    assert_eq!(object.object_id, "stream-file-1");
    assert_eq!(object.size, 7);
}

#[tokio::test]
async fn streaming_put_reuses_the_existing_drive_file_id_via_a_patch_session_init() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/big.bin", "old-bytes").await.unwrap();
    f.fake.clear_requests();
    f.fake.script(vec![
        Reply::status(200).header("location", format!("{}/resumable/stream-2", f.base)),
        Reply::json(200, serde_json::json!({ "id": "drive-object-1", "size": "9" })),
    ]);

    f.provider
        .put(
            &f.binding,
            "worlds/w1/big.bin",
            PutBody::Stream { stream: stream_of("new-bytes"), len: Some(9) },
            "application/octet-stream",
        )
        .await
        .unwrap();

    let requests = f.fake.requests();
    assert_eq!(requests[0].method, "PATCH");
    assert_eq!(requests[0].path, "/upload/drive/v3/files/drive-object-1?uploadType=resumable");
    // Same Drive file id: no superseded-object delete should be issued.
    assert_eq!(f.fake.methods(), vec!["PATCH".to_string(), "PUT".to_string()]);
    let object = f.object("worlds/w1/big.bin").await.unwrap();
    assert_eq!(object.object_id, "drive-object-1");
    assert_eq!(object.size, 9);
}

#[tokio::test]
async fn a_failed_streaming_put_surfaces_drive_upload_failed_and_records_no_object() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.script(vec![
        Reply::status(200).header("location", format!("{}/resumable/stream-3", f.base)),
        Reply::text(500, "backend exploded"),
    ]);

    let err = f
        .provider
        .put(
            &f.binding,
            "worlds/w1/big.bin",
            PutBody::Stream { stream: stream_of("doomed"), len: Some(6) },
            "application/octet-stream",
        )
        .await
        .unwrap_err();
    assert_eq!(err.code, "drive_upload_failed");
    assert!(f.object("worlds/w1/big.bin").await.is_none());
}

#[tokio::test]
async fn a_full_drive_on_the_streaming_put_is_classified_as_drive_storage_full() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.script(vec![
        Reply::status(200).header("location", format!("{}/resumable/stream-4", f.base)),
        Reply::json(
            403,
            serde_json::json!({ "error": { "errors": [{ "reason": "storageQuotaExceeded" }] } }),
        ),
    ]);

    let err = f
        .provider
        .put(
            &f.binding,
            "worlds/w1/big.bin",
            PutBody::Stream { stream: stream_of("doomed"), len: Some(6) },
            "application/octet-stream",
        )
        .await
        .unwrap_err();
    assert_eq!(err.status, 403);
    assert_eq!(err.code, "drive_storage_full");
}

#[tokio::test]
async fn a_stream_without_a_known_length_falls_back_to_the_buffered_multipart_path() {
    let f = Fixture::new().await;
    f.seed_account().await;

    f.provider
        .put(
            &f.binding,
            "worlds/w1/legacy.bin",
            PutBody::Stream { stream: stream_of("legacy"), len: None },
            "application/octet-stream",
        )
        .await
        .unwrap();

    let requests = f.fake.requests();
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].path, "/upload/drive/v3/files?uploadType=multipart");
}

#[tokio::test]
async fn create_resumable_session_posts_for_a_new_key_and_returns_the_location_verbatim() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.script(vec![Reply::status(200).header("location", format!("{}/resumable/abc", f.base))]);

    let session_url = f
        .provider
        .create_resumable_session(&f.binding, "worlds/w1/new.bin", "application/octet-stream", 12345)
        .await
        .unwrap();

    assert_eq!(session_url, format!("{}/resumable/abc", f.base));
    let requests = f.fake.requests();
    assert_eq!(requests[0].method, "POST");
    assert_eq!(requests[0].path, "/upload/drive/v3/files?uploadType=resumable");
    assert!(requests[0].body_text.contains("appDataFolder"));
}

#[tokio::test]
async fn create_resumable_session_patches_the_existing_drive_file_id_for_a_known_key() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "old-bytes").await.unwrap();
    f.fake.clear_requests();
    f.fake.script(vec![Reply::status(200).header("location", format!("{}/resumable/upd", f.base))]);

    let session_url = f
        .provider
        .create_resumable_session(&f.binding, "worlds/w1/a.bin", "application/octet-stream", 999)
        .await
        .unwrap();

    assert!(session_url.contains("/resumable/upd"));
    let requests = f.fake.requests();
    assert_eq!(requests[0].method, "PATCH");
    assert_eq!(requests[0].path, "/upload/drive/v3/files/drive-object-1?uploadType=resumable");
}

#[tokio::test]
async fn probe_resumable_session_maps_308_complete_and_expired_states() {
    let f = Fixture::new().await;
    f.seed_account().await;
    let url = format!("{}/resumable/x", f.base);

    f.fake.script(vec![Reply::status(308).header("range", "bytes=0-499".into())]);
    assert_eq!(
        f.provider.probe_resumable_session(&f.binding, &url, 1000).await.unwrap(),
        ResumableProbe::Incomplete { received_up_to: 500 }
    );

    f.fake.script(vec![Reply::json(200, serde_json::json!({ "id": "file-1", "size": "1000" }))]);
    assert_eq!(
        f.provider.probe_resumable_session(&f.binding, &url, 1000).await.unwrap(),
        ResumableProbe::Complete { file_id: "file-1".into(), size: 1000 }
    );

    f.fake.script(vec![Reply::status(404)]);
    assert_eq!(
        f.provider.probe_resumable_session(&f.binding, &url, 1000).await.unwrap(),
        ResumableProbe::Expired
    );
}

#[tokio::test]
async fn probe_falls_back_to_a_metadata_read_when_the_completion_reports_no_size() {
    let f = Fixture::new().await;
    f.seed_account().await;
    let url = format!("{}/resumable/x", f.base);
    f.fake.script(vec![
        Reply::json(200, serde_json::json!({ "id": "file-2" })),
        Reply::json(200, serde_json::json!({ "id": "file-2", "size": "512" })),
    ]);

    assert_eq!(
        f.provider.probe_resumable_session(&f.binding, &url, 512).await.unwrap(),
        ResumableProbe::Complete { file_id: "file-2".into(), size: 512 }
    );
    assert_eq!(f.fake.requests()[1].path, "/drive/v3/files/file-2?fields=id,size");
}

#[tokio::test]
async fn register_uploaded_object_supersedes_a_stale_object_id_and_deletes_the_old_drive_file() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.put_bytes("worlds/w1/a.bin", "old").await.unwrap();
    f.fake.clear_requests();
    f.fake.script(vec![Reply::status(204)]);

    f.provider
        .register_uploaded_object(
            &f.binding,
            "worlds/w1/a.bin",
            "drive-object-2",
            42,
            "application/octet-stream",
        )
        .await
        .unwrap();

    let requests = f.fake.requests();
    assert_eq!(requests[0].method, "DELETE");
    assert_eq!(requests[0].path, "/drive/v3/files/drive-object-1");
    let row = f.object("worlds/w1/a.bin").await.unwrap();
    assert_eq!(row.object_id, "drive-object-2");
    assert_eq!(row.size, 42);
}

// ---------------------------------------------------------------------------
// OAuth
// ---------------------------------------------------------------------------

#[tokio::test]
async fn a_401_triggers_one_token_refresh_and_a_retry() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.script(vec![Reply::status(401)]);

    f.put_bytes("worlds/w1/a.bin", "data").await.unwrap();

    assert_eq!(f.fake.token_calls.load(Ordering::SeqCst), 1);
    let requests = f.fake.requests();
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[1].auth.as_deref(), Some("Bearer refreshed-token"));
    assert_eq!(f.account().await.access_token.as_deref(), Some("refreshed-token"));
}

#[tokio::test]
async fn a_rejected_refresh_with_invalid_grant_drops_the_stored_refresh_token() {
    let f = Fixture::new().await;
    // Expire the access token so the provider must refresh before any Drive call.
    f.seed_account_expiring(time::plus_ms_iso(time::now(), -60_000)).await;
    *f.fake.token_reply.lock() = Reply::json(400, serde_json::json!({ "error": "invalid_grant" }));

    let err = f.put_bytes("worlds/w1/a.bin", "data").await.unwrap_err();
    assert_eq!(err.code, "drive_reauth_required");
    assert_eq!(f.account().await.refresh_token, None);
    // No Drive traffic happened with a dead authorization.
    assert!(f.fake.requests().is_empty());
}

#[tokio::test]
async fn a_rejected_refresh_without_invalid_grant_keeps_the_stored_refresh_token() {
    let f = Fixture::new().await;
    f.seed_account_expiring(time::plus_ms_iso(time::now(), -60_000)).await;
    *f.fake.token_reply.lock() = Reply::status(503);

    let err = f.put_bytes("worlds/w1/a.bin", "data").await.unwrap_err();
    assert_eq!(err.code, "drive_reauth_required");
    assert_eq!(f.account().await.refresh_token.as_deref(), Some("refresh-token-1"));
}

// ---------------------------------------------------------------------------
// quota + pacing
// ---------------------------------------------------------------------------

#[tokio::test]
async fn quota_parses_the_storage_quota_payload() {
    let f = Fixture::new().await;
    f.seed_account().await;
    f.fake.set_default(Reply::json(
        200,
        serde_json::json!({ "storageQuota": { "usage": "1024", "limit": "2048" } }),
    ));

    let quota = f.provider.quota(&f.binding).await.unwrap();
    assert_eq!(quota.used_bytes, Some(1024));
    assert_eq!(quota.total_bytes, Some(2048));
    assert_eq!(f.fake.requests()[0].path, "/drive/v3/about?fields=storageQuota");
}

#[tokio::test]
async fn upload_starts_are_paced_per_account() {
    let f = Fixture::with_config(|c| c.drive_max_upload_starts_per_second = Some(10)).await;
    f.seed_account().await;

    let started = std::time::Instant::now();
    for i in 0..4 {
        f.put_bytes(&format!("worlds/w1/{i}.bin"), "data").await.unwrap();
    }
    // 10 starts/second ⇒ 100 ms apart; the first goes immediately.
    assert!(started.elapsed().as_millis() >= 280, "elapsed {:?}", started.elapsed());
    assert_eq!(f.fake.requests().len(), 4);
}
