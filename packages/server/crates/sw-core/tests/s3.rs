//! S3 provider integration tests against an in-process MinIO-lite fake:
//! the REAL `S3StorageProvider` (SigV4 header auth, streaming PUT, Range
//! GET, ListObjectsV2, presigned URLs, the link-time probe) over HTTP.

use std::sync::Arc;

use bytes::Bytes;
use sw_contracts::StorageProviderType;
use sw_core::storage::s3::{probe_bucket, S3ConnectionParams, S3StorageProvider};
use sw_core::storage::{BlobRange, PutBody, StorageBinding, StorageProvider};
use sw_db::repo::StorageAccountRecord;
use sw_db::{migrate, time, Db, Repository};
use sw_testkit::fake_s3::FakeS3;

struct Fixture {
    repo: Repository,
    provider: S3StorageProvider,
    binding: StorageBinding,
    fake: FakeS3,
    endpoint: String,
}

impl Fixture {
    async fn new() -> Fixture {
        let fake = FakeS3::default();
        let endpoint = fake.spawn().await;

        let db = Db::open_memory().expect("db");
        migrate::migrate(&db).expect("migrate");
        let repo = Repository::new(db, None);
        let account_id = "s3-account-1".to_string();
        let now = time::now_iso();
        repo.create_or_update_storage_account(StorageAccountRecord {
            id: account_id.clone(),
            provider: StorageProviderType::S3,
            owner_player_uuid: "player-owner".into(),
            external_account_id: "AKTEST".into(),
            email: Some("bucket @ test".into()),
            display_name: None,
            access_token: Some("secret-key".into()),
            refresh_token: None,
            token_expires_at: None,
            s3_endpoint: Some(endpoint.clone()),
            s3_region: Some("auto".into()),
            s3_bucket: Some("test-bucket".into()),
            s3_key_prefix: Some("sharedworld/".into()),
            created_at: now.clone(),
            updated_at: now,
        })
        .await
        .expect("seed s3 account");
        let http = reqwest::Client::builder().build().expect("client");
        let provider = S3StorageProvider::new(repo.clone(), http, 900);
        Fixture {
            binding: StorageBinding {
                provider: StorageProviderType::S3,
                storage_account_id: Some(account_id),
            },
            repo,
            provider,
            fake,
            endpoint,
        }
    }
}

#[tokio::test]
async fn put_get_delete_roundtrip_with_index_rows() {
    let f = Fixture::new().await;
    let key = "packs/full/ab/abc.pack";
    f.provider
        .put(&f.binding, key, PutBody::Bytes(Bytes::from_static(b"hello world")), "application/octet-stream")
        .await
        .unwrap();
    // The object landed under the account's prefix, and the index row exists.
    assert!(f.fake.objects.lock().contains_key("test-bucket/sharedworld/packs/full/ab/abc.pack"));
    assert!(f.provider.exists(&f.binding, key).await.unwrap());
    let row = f
        .repo
        .get_storage_object(StorageProviderType::S3, "s3-account-1", key)
        .await
        .unwrap()
        .expect("index row");
    assert_eq!(row.size, 11);

    let blob = f.provider.get(&f.binding, key, None).await.unwrap().expect("blob");
    assert_eq!(blob.status, 200);
    assert_eq!(blob.into_bytes().await.unwrap().as_ref(), b"hello world");

    let range = BlobRange { offset: 6, end_inclusive: None };
    let partial = f.provider.get(&f.binding, key, Some(&range)).await.unwrap().expect("partial");
    assert_eq!(partial.status, 206);
    assert_eq!(partial.content_range.as_deref(), Some("bytes 6-10/11"));
    assert_eq!(partial.into_bytes().await.unwrap().as_ref(), b"world");

    f.provider.delete(&f.binding, key).await.unwrap();
    assert!(!f.provider.exists(&f.binding, key).await.unwrap());
    assert!(f.repo.get_storage_object(StorageProviderType::S3, "s3-account-1", key).await.unwrap().is_none());
    assert_eq!(*f.fake.unsigned_requests.lock(), 0, "every request must carry SigV4 material");
}

#[tokio::test]
async fn streaming_put_with_known_length() {
    let f = Fixture::new().await;
    let chunks: Vec<Result<Bytes, std::io::Error>> =
        vec![Ok(Bytes::from_static(b"abc")), Ok(Bytes::from_static(b"defg"))];
    let stream = Box::pin(futures::stream::iter(chunks));
    f.provider
        .put(
            &f.binding,
            "packs/full/cd/cdef.pack",
            PutBody::Stream { stream, len: Some(7) },
            "application/octet-stream",
        )
        .await
        .unwrap();
    let stored = f.fake.objects.lock().get("test-bucket/sharedworld/packs/full/cd/cdef.pack").cloned();
    assert_eq!(stored.expect("stored").0.as_ref(), b"abcdefg");
}

#[tokio::test]
async fn exists_heals_missing_index_row_for_presign_uploaded_object() {
    let f = Fixture::new().await;
    let key = "packs/full/ee/eeee.pack";
    // Simulate a presigned client upload: bytes exist, no index row.
    f.fake.objects.lock().insert(
        "test-bucket/sharedworld/packs/full/ee/eeee.pack".into(),
        (Bytes::from_static(b"direct"), "application/octet-stream".into()),
    );
    assert!(f.provider.exists(&f.binding, key).await.unwrap());
    let row = f
        .repo
        .get_storage_object(StorageProviderType::S3, "s3-account-1", key)
        .await
        .unwrap()
        .expect("healed row");
    assert_eq!(row.size, 6);
    // And a genuinely absent key stays absent.
    assert!(!f.provider.exists(&f.binding, "packs/full/ff/ffff.pack").await.unwrap());
}

#[tokio::test]
async fn presigned_urls_work_end_to_end_without_backend_auth() {
    let f = Fixture::new().await;
    let presigner = f.provider.presign(&f.binding).expect("presign capable");
    let ctx = presigner.presign_context(&f.binding).await.unwrap();

    // A bare reqwest client (no bearer, no SigV4 headers) PUTs and GETs via
    // the query-auth URLs — exactly what the mod does with SignedBlobUrlDto.
    let client = reqwest::Client::new();
    let put = ctx.presign_put("packs/full/aa/aaaa.pack");
    assert!(put.url.contains("X-Amz-Signature="));
    let response = client
        .put(&put.url)
        .header("content-type", "application/octet-stream")
        .body("presigned bytes")
        .send()
        .await
        .unwrap();
    assert_eq!(response.status().as_u16(), 200);

    let get = ctx.presign_get("packs/full/aa/aaaa.pack");
    let body = client.get(&get.url).send().await.unwrap().bytes().await.unwrap();
    assert_eq!(body.as_ref(), b"presigned bytes");

    // The provider sees it (heal-on-exists) even though the box never PUT it.
    assert!(f.provider.exists(&f.binding, "packs/full/aa/aaaa.pack").await.unwrap());
}

#[tokio::test]
async fn account_cleanup_lists_and_deletes_under_prefix() {
    let f = Fixture::new().await;
    f.provider
        .put(
            &f.binding,
            "packs/full/ab/one.pack",
            PutBody::Bytes(Bytes::from_static(b"1")),
            "application/octet-stream",
        )
        .await
        .unwrap();
    f.provider
        .put(
            &f.binding,
            "manifests/cd/two.json",
            PutBody::Bytes(Bytes::from_static(b"2")),
            "application/json",
        )
        .await
        .unwrap();
    // A foreign object outside the prefix must never be touched.
    f.fake
        .objects
        .lock()
        .insert("test-bucket/unrelated/file.txt".into(), (Bytes::from_static(b"x"), "text/plain".into()));

    let cleanup = f.provider.account_cleanup(&f.binding).expect("cleanup capable");
    let (ids, next) = cleanup.list_account_object_ids(&f.binding, None).await.unwrap();
    assert_eq!(next, None);
    assert_eq!(ids.len(), 2, "{ids:?}");
    assert!(ids.iter().all(|id| id.starts_with("sharedworld/")));
    for id in &ids {
        cleanup.delete_account_object(&f.binding, id).await.unwrap();
    }
    let remaining = f.fake.objects.lock();
    assert_eq!(remaining.len(), 1);
    assert!(remaining.contains_key("test-bucket/unrelated/file.txt"));
}

#[tokio::test]
async fn s3_link_flow_end_to_end() {
    use sw_core::request::RequestContext;
    use sw_core::storage::link_service::{S3LinkForm, StorageLinkService};
    use sw_core::Config;

    let f = Fixture::new().await;
    let mut config = Config::dev();
    config.allow_insecure_s3_endpoint = true;
    let service = StorageLinkService::new(
        f.repo.clone(),
        Arc::new(config),
        StorageProviderType::GoogleDrive,
        reqwest::Client::new(),
    );
    let ctx = RequestContext { player_uuid: "player-owner".into(), ..RequestContext::default() };
    let request = sw_contracts::CreateStorageLinkRequest {
        provider: Some(serde_json::Value::String("s3".into())),
        force_consent: false,
        import_source: None,
    };
    let session = service.create_storage_link(&ctx, &request, time::now()).await.unwrap();
    assert_eq!(session.provider, StorageProviderType::S3);
    assert!(session.auth_url.contains("/storage/s3/link?session="), "{}", session.auth_url);

    let state_param = session
        .auth_url
        .split("state=")
        .nth(1)
        .map(|s| s.split('&').next().unwrap().replace("%3A", ":"))
        .expect("state in url");
    let form = S3LinkForm {
        endpoint: f.endpoint.clone(),
        region: "auto".into(),
        bucket: "test-bucket".into(),
        access_key_id: "AK-LINK".into(),
        secret_access_key: "SK-LINK".into(),
        key_prefix: String::new(),
    };

    // Wrong state → refused, session stays pending.
    let err = service.complete_s3_link(&session.id, Some("wrong"), &form, time::now()).await.unwrap_err();
    assert_eq!(err.code, "storage_link_state_mismatch");

    // A failed probe (bad endpoint) → form-shaped error, session survives.
    let bad = S3LinkForm { endpoint: "http://127.0.0.1:1".into(), ..form.clone() };
    let err = service
        .complete_s3_link(&session.id, Some(state_param.as_str()), &bad, time::now())
        .await
        .unwrap_err();
    assert_eq!(err.code, "s3_link_form_invalid");
    let polled = service.get_storage_link_session(&ctx, &session.id, time::now()).await.unwrap();
    assert_eq!(polled.status, sw_contracts::StorageLinkStatus::Pending);

    // Happy path.
    let linked =
        service.complete_s3_link(&session.id, Some(state_param.as_str()), &form, time::now()).await.unwrap();
    assert_eq!(linked.status, sw_contracts::StorageLinkStatus::Linked);
    assert!(linked.linked_account_email.as_deref().unwrap_or("").starts_with("test-bucket @ "));
    let completed = service.require_completed_link_session(&ctx, &session.id).await.unwrap();
    let account = f
        .repo
        .get_storage_account(completed.storage_account_id.as_deref().unwrap())
        .await
        .unwrap()
        .expect("account row");
    assert_eq!(account.provider, StorageProviderType::S3);
    assert_eq!(account.external_account_id, "AK-LINK");
    assert_eq!(account.access_token.as_deref(), Some("SK-LINK"));
    // Empty prefix field means the default prefix.
    assert_eq!(account.s3_key_prefix.as_deref(), Some("sharedworld/"));

    // Another player linking the same access key → conflict.
    let other = RequestContext { player_uuid: "player-guest".into(), ..RequestContext::default() };
    let session2 = service.create_storage_link(&other, &request, time::now()).await.unwrap();
    let state2 = session2
        .auth_url
        .split("state=")
        .nth(1)
        .map(|s| s.split('&').next().unwrap().replace("%3A", ":"))
        .unwrap();
    let err =
        service.complete_s3_link(&session2.id, Some(state2.as_str()), &form, time::now()).await.unwrap_err();
    assert_eq!(err.code, "storage_account_already_linked");
}

#[tokio::test]
async fn link_probe_round_trips_and_cleans_up() {
    let f = Fixture::new().await;
    probe_bucket(
        &S3ConnectionParams {
            endpoint: f.endpoint.clone(),
            region: "auto".into(),
            bucket: "test-bucket".into(),
            key_prefix: "sharedworld/".into(),
            access_key_id: "AKTEST".into(),
            secret_access_key: "secret-key".into(),
        },
        "probe-1",
    )
    .await
    .unwrap();
    assert!(f.fake.objects.lock().is_empty(), "the probe object must be deleted");

    // Wrong endpoint: a connection failure comes back as a friendly message.
    let err = probe_bucket(
        &S3ConnectionParams {
            endpoint: "http://127.0.0.1:1".into(),
            region: "auto".into(),
            bucket: "test-bucket".into(),
            key_prefix: "sharedworld/".into(),
            access_key_id: "AKTEST".into(),
            secret_access_key: "secret-key".into(),
        },
        "probe-2",
    )
    .await
    .unwrap_err();
    assert!(err.contains("could not connect"), "{err}");
}
