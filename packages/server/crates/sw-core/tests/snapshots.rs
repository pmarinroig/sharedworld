//! Snapshots service: finalize/validate/chain recipes, restore, bulk delete,
//! retention (age schedule + maxBackups) and the blob GC sweeps. Ported from
//! `backend/test/service/snapshots-retention.test.ts`,
//! `backup-delete-0-4-5.test.ts`, `finalize-deferred-retention.test.ts`,
//! `finalize-header-batching.test.ts`, `carried-forward-packs.test.ts` and the
//! persistSnapshot lane of `test/repository/manifest-doc.test.ts`.

use bytes::Bytes;
use sw_contracts::sync::{DELTA_V2_FORMAT_VERSION, NON_REGION_PACK_ID};
use sw_contracts::*;
use sw_core::request::RequestContext;
use sw_core::service::{snapshots, worlds};
use sw_core::storage::manifest_doc::build_manifest_document;
use sw_core::storage::{PutBody, StorageProvider};
use sw_core::time::{self, Instant};
use sw_db::repo::WorldStorageBinding;
use sw_testkit::*;

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

struct World {
    id: String,
    epoch: i64,
    token: String,
}

fn r2_binding() -> WorldStorageBinding {
    WorldStorageBinding { provider: StorageProviderType::R2, storage_account_id: None }
}

fn at(stamp: &str) -> Instant {
    time::parse_iso(stamp).expect("iso stamp")
}

/// A world whose owner holds the initial (host-starting) runtime assignment,
/// the Rust equivalent of `claimHostForTest`.
async fn seed_world(env: &TestEnv, name: &str) -> World {
    let created = worlds::create_world(
        &env.svc,
        &owner(),
        &CreateWorldRequest { name: Some(serde_json::json!(name)), ..Default::default() },
        time::now(),
    )
    .await
    .expect("create world");
    World {
        id: created.world.summary.id,
        epoch: created.initial_upload_assignment.runtime_epoch,
        token: created.initial_upload_assignment.host_token,
    }
}

fn file(hash: &str, storage_key: &str, path: &str) -> ManifestFile {
    ManifestFile {
        path: path.into(),
        hash: hash.into(),
        size: 10,
        compressed_size: 5,
        storage_key: storage_key.into(),
        content_type: "application/octet-stream".into(),
        transfer_mode: None,
        base_snapshot_id: None,
        base_hash: None,
        chain_depth: None,
    }
}

fn member(path: &str, hash: &str) -> PackedManifestFile {
    PackedManifestFile {
        path: path.into(),
        hash: hash.into(),
        size: 10,
        content_type: "application/octet-stream".into(),
    }
}

fn full_pack(pack_id: &str, hash: &str, storage_key: &str) -> SnapshotPack {
    SnapshotPack {
        pack_id: pack_id.into(),
        hash: hash.into(),
        size: 100,
        storage_key: storage_key.into(),
        transfer_mode: FileTransferMode::PackFull,
        base_snapshot_id: None,
        base_hash: None,
        chain_depth: Some(0),
        delta_format_version: None,
        delta_blob_size: None,
        chain_delta_bytes: None,
        chain_steps: None,
        files: vec![member(&format!("{pack_id}.dat"), &format!("{hash}-m"))],
    }
}

/// v1 delta pack (no `deltaFormatVersion`); the legacy chain shape.
fn delta_pack_v1(
    pack_id: &str,
    hash: &str,
    storage_key: &str,
    base: &SnapshotPack,
    base_snapshot_id: &str,
) -> SnapshotPack {
    SnapshotPack {
        hash: hash.into(),
        storage_key: storage_key.into(),
        transfer_mode: FileTransferMode::PackDelta,
        base_snapshot_id: Some(base_snapshot_id.into()),
        base_hash: Some(base.hash.clone()),
        chain_depth: Some(base.chain_depth.unwrap_or(0) + 1),
        files: vec![member(&format!("{pack_id}.dat"), &format!("{hash}-m"))],
        ..full_pack(pack_id, hash, storage_key)
    }
}

fn delta_pack_v2(pack_id: &str, hash: &str, base: &SnapshotPack, base_snapshot_id: &str) -> SnapshotPack {
    SnapshotPack {
        delta_format_version: Some(DELTA_V2_FORMAT_VERSION),
        delta_blob_size: Some(40),
        ..delta_pack_v1(pack_id, hash, &format!("packs/delta2/{hash}.bin"), base, base_snapshot_id)
    }
}

fn request(
    world: &World,
    base_snapshot_id: Option<&str>,
    files: Vec<ManifestFile>,
    packs: Vec<SnapshotPack>,
) -> FinalizeSnapshotRequest {
    FinalizeSnapshotRequest {
        runtime_epoch: Some(world.epoch),
        host_token: Some(world.token.clone()),
        base_snapshot_id: base_snapshot_id.map(str::to_string),
        data_version: None,
        minecraft_version: None,
        files,
        packs: Some(packs),
    }
}

/// Uploads every artifact the request names (the fs provider is the R2-mode
/// blob store finalize validates against).
async fn upload_artifacts(env: &TestEnv, request: &FinalizeSnapshotRequest) {
    let keys = request
        .files
        .iter()
        .map(|f| f.storage_key.clone())
        .chain(request.packs.iter().flatten().map(|p| p.storage_key.clone()));
    for key in keys {
        put_blob(env, &key).await;
    }
}

async fn put_blob(env: &TestEnv, storage_key: &str) {
    env.fs
        .put(
            &r2_binding(),
            storage_key,
            PutBody::Bytes(Bytes::from_static(b"blob")),
            "application/octet-stream",
        )
        .await
        .expect("put blob");
}

async fn blob_exists(env: &TestEnv, storage_key: &str) -> bool {
    env.fs.read_all(storage_key).await.expect("read blob").is_some()
}

async fn finalize(
    env: &TestEnv,
    ctx: &RequestContext,
    world: &World,
    request: FinalizeSnapshotRequest,
    now: &str,
) -> SnapshotManifest {
    upload_artifacts(env, &request).await;
    snapshots::finalize_snapshot(&env.svc, ctx, &world.id, &request, at(now)).await.expect("finalize")
}

async fn created_at_of(env: &TestEnv, world: &World) -> Vec<String> {
    env.repo
        .list_snapshots_for_world(&world.id)
        .await
        .expect("list")
        .into_iter()
        .map(|s| s.created_at)
        .collect()
}

async fn snapshot_ids_of(env: &TestEnv, world: &World) -> Vec<String> {
    env.repo
        .list_snapshots_for_world(&world.id)
        .await
        .expect("list")
        .into_iter()
        .map(|s| s.snapshot_id)
        .collect()
}

/// Rewinds a snapshot's directory to a pre-stamping worker's representation.
async fn strip_chain_steps(env: &TestEnv, snapshot_id: &str) {
    let id = snapshot_id.to_string();
    env.repo
        .db()
        .write(move |c| {
            c.execute(
                "test.strip_chain_steps",
                "UPDATE snapshots SET packs_json = (
                   SELECT json_group_array(json_remove(pack.value, '$.chainSteps'))
                   FROM json_each(packs_json) AS pack
                 ) WHERE id = ?",
                [id.as_str()],
            )?;
            Ok(())
        })
        .await
        .expect("strip chain steps");
}

/// Repository-level finalize: the setup lane for snapshots that predate (or
/// bypass) host authority, like the TS fixtures' direct `repository.finalizeSnapshot`.
async fn repo_finalize(
    env: &TestEnv,
    world: &World,
    base_snapshot_id: Option<&str>,
    packs: Vec<SnapshotPack>,
    now: &str,
) -> SnapshotManifest {
    let request = FinalizeSnapshotRequest {
        runtime_epoch: None,
        host_token: None,
        base_snapshot_id: base_snapshot_id.map(str::to_string),
        data_version: None,
        minecraft_version: None,
        files: vec![],
        packs: Some(packs),
    };
    upload_artifacts(env, &request).await;
    (*env
        .repo
        .finalize_snapshot(&world.id, &owner().actor(), &request, at(now), None)
        .await
        .expect("repo finalize"))
    .clone()
}

// ---------------------------------------------------------------------------
// Finalize / persist (0027 document lane)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn finalize_writes_one_content_addressed_manifest_document_and_dedupes_it() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Doc SMP").await;
    let pack = full_pack(NON_REGION_PACK_ID, "pack-hash-1", "packs/full/one.pack");

    let first = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![], vec![pack.clone()]),
        "2026-01-01T01:00:00.000Z",
    )
    .await;
    // Doc mode: member lists live in the document, never in member rows.
    assert_eq!(member_row_count(&env, &first.snapshot_id).await, 0);
    let key = manifest_storage_key(&env, &first.snapshot_id).await.expect("doc pointer");
    assert!(key.starts_with("manifests/"), "{key}");
    assert!(blob_exists(&env, &key).await);
    // The served manifest carries the members resolved from the document.
    assert_eq!(first.packs[0].files.len(), 1);

    // Identical members → identical (content-addressed) doc key, no new object.
    let second = finalize(
        &env,
        &owner(),
        &world,
        request(&world, Some(&first.snapshot_id), vec![], vec![pack]),
        "2026-01-01T02:00:00.000Z",
    )
    .await;
    assert_eq!(manifest_storage_key(&env, &second.snapshot_id).await.as_deref(), Some(key.as_str()));
    assert_eq!(member_row_count(&env, &second.snapshot_id).await, 0);

    // Reads through the service lane.
    let summaries = snapshots::list_snapshots(&env.svc, &owner(), &world.id).await.unwrap();
    assert_eq!(summaries.len(), 2);
    assert!(summaries[0].is_latest && !summaries[1].is_latest);
    let latest = snapshots::latest_manifest(&env.svc, &owner(), &world.id).await.unwrap().unwrap();
    assert_eq!(latest.snapshot_id, second.snapshot_id);
    assert_eq!(latest.packs[0].files[0].path, "non-region.dat");
    // A non-member cannot read either.
    assert_eq!(snapshots::list_snapshots(&env.svc, &guest(), &world.id).await.unwrap_err().code, "forbidden");
}

async fn member_row_count(env: &TestEnv, snapshot_id: &str) -> i64 {
    let id = snapshot_id.to_string();
    env.repo
        .db()
        .read(move |c| {
            Ok(c.query_one(
                "test.member_rows",
                "SELECT COUNT(*) FROM snapshot_files WHERE snapshot_id = ? AND pack_id IS NOT NULL",
                [id.as_str()],
                |r| r.get::<_, i64>(0),
            )?
            .unwrap_or(0))
        })
        .await
        .expect("count member rows")
}

async fn manifest_storage_key(env: &TestEnv, snapshot_id: &str) -> Option<String> {
    let id = snapshot_id.to_string();
    env.repo
        .db()
        .read(move |c| {
            Ok(c.query_one(
                "test.manifest_key",
                "SELECT manifest_storage_key FROM snapshots WHERE id = ?",
                [id.as_str()],
                |r| r.get::<_, Option<String>>(0),
            )?
            .flatten())
        })
        .await
        .expect("manifest key")
}

#[tokio::test]
async fn a_failed_document_write_falls_back_to_row_manifests() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Flaky Drive").await;
    let pack = SnapshotPack {
        files: vec![member("level.dat", "hash-level"), member("session.lock", "hash-lock")],
        ..full_pack(NON_REGION_PACK_ID, "pack-hash-1", "packs/full/one.pack")
    };
    // Block the document write: the provider stages through `<key>.swtmp`, so
    // a directory in its place makes the put fail like an unreachable Drive.
    let built = build_manifest_document(std::slice::from_ref(&pack));
    let staged = env.dir.path().join("blobs").join(built.storage_key.replace(".json", ".swtmp"));
    std::fs::create_dir_all(&staged).unwrap();

    let manifest = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![], vec![pack]),
        "2026-01-01T01:00:00.000Z",
    )
    .await;
    // The snapshot still landed; as legacy member rows.
    assert_eq!(manifest_storage_key(&env, &manifest.snapshot_id).await, None);
    assert_eq!(member_row_count(&env, &manifest.snapshot_id).await, 2);
    let served = snapshots::latest_manifest(&env.svc, &owner(), &world.id).await.unwrap().unwrap();
    assert_eq!(
        served.packs[0].files.iter().map(|f| f.path.as_str()).collect::<Vec<_>>(),
        vec!["level.dat", "session.lock"]
    );
}

#[tokio::test]
async fn a_missing_document_fails_loud_instead_of_serving_empty_members() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Lost Doc").await;
    let manifest = finalize(
        &env,
        &owner(),
        &world,
        request(
            &world,
            None,
            vec![],
            vec![full_pack(NON_REGION_PACK_ID, "pack-hash-1", "packs/full/one.pack")],
        ),
        "2026-01-01T01:00:00.000Z",
    )
    .await;
    let key = manifest_storage_key(&env, &manifest.snapshot_id).await.unwrap();
    env.fs.delete(&r2_binding(), &key).await.unwrap();

    // Headers-only reads (upload planning, finalize validation) still work…
    let headers = env.repo.get_snapshot_headers(&world.id, &manifest.snapshot_id).await.unwrap().unwrap();
    assert_eq!(headers.packs[0].hash, "pack-hash-1");
    assert!(headers.packs[0].files.is_empty());
    // …while serving the manifest fails loud.
    let error = snapshots::latest_manifest(&env.svc, &owner(), &world.id).await.unwrap_err();
    assert_eq!((error.status, error.code), (502, "snapshot_manifest_unavailable"));

    // The world heals by snapshotting again.
    let healed = finalize(
        &env,
        &owner(),
        &world,
        request(
            &world,
            Some(&manifest.snapshot_id),
            vec![],
            vec![full_pack(NON_REGION_PACK_ID, "pack-hash-2", "packs/full/two.pack")],
        ),
        "2026-01-01T02:00:00.000Z",
    )
    .await;
    let served = snapshots::latest_manifest(&env.svc, &owner(), &world.id).await.unwrap().unwrap();
    assert_eq!(served.snapshot_id, healed.snapshot_id);
    assert_eq!(served.packs[0].files.len(), 1);
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

#[tokio::test]
async fn finalize_validation_rejects_malformed_manifests_with_the_worker_codes() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Validation SMP").await;

    let missing_object =
        request(&world, None, vec![file("h", "blobs/never-uploaded.bin", "level.dat")], vec![]);
    let error = snapshots::finalize_snapshot(
        &env.svc,
        &owner(),
        &world.id,
        &missing_object,
        at("2026-01-01T00:00:00.000Z"),
    )
    .await
    .unwrap_err();
    assert_eq!((error.status, error.code), (400, "snapshot_storage_missing"));

    let duplicate_path = request(
        &world,
        None,
        vec![file("a", "blobs/a.bin", "level.dat"), file("b", "blobs/b.bin", "level.dat")],
        vec![],
    );
    upload_artifacts(&env, &duplicate_path).await;
    let error = snapshots::finalize_snapshot(
        &env.svc,
        &owner(),
        &world.id,
        &duplicate_path,
        at("2026-01-01T00:00:00.000Z"),
    )
    .await
    .unwrap_err();
    assert_eq!(error.code, "duplicate_snapshot_path");
    assert!(error.message.contains("'level.dat'"));

    let pack = full_pack(NON_REGION_PACK_ID, "p1", "packs/full/p1.pack");
    let duplicate_pack = request(&world, None, vec![], vec![pack.clone(), pack.clone()]);
    upload_artifacts(&env, &duplicate_pack).await;
    let error = snapshots::finalize_snapshot(
        &env.svc,
        &owner(),
        &world.id,
        &duplicate_pack,
        at("2026-01-01T00:00:00.000Z"),
    )
    .await
    .unwrap_err();
    assert_eq!(error.code, "duplicate_snapshot_pack");

    // A region bundle id may not use a pack transfer mode.
    let mut wrong_mode = full_pack("region-bundle:r.0.0", "r1", "region-bundles/full/r1.bundle");
    wrong_mode.transfer_mode = FileTransferMode::PackFull;
    let bad = request(&world, None, vec![], vec![wrong_mode]);
    upload_artifacts(&env, &bad).await;
    let error =
        snapshots::finalize_snapshot(&env.svc, &owner(), &world.id, &bad, at("2026-01-01T00:00:00.000Z"))
            .await
            .unwrap_err();
    assert_eq!(error.code, "invalid_snapshot_transfer_mode");

    // A base snapshot that does not exist.
    let orphan = request(&world, Some("snapshot_gone"), vec![], vec![]);
    let error =
        snapshots::finalize_snapshot(&env.svc, &owner(), &world.id, &orphan, at("2026-01-01T00:00:00.000Z"))
            .await
            .unwrap_err();
    assert_eq!(error.code, "snapshot_base_not_found");
    assert!(error.message.contains("was not found for this world"));

    // A real chain, then the three per-artifact mismatches.
    let base_pack = full_pack(NON_REGION_PACK_ID, "full1", "packs/full/full1.pack");
    let base = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![], vec![base_pack.clone()]),
        "2026-01-01T00:00:00.000Z",
    )
    .await;

    let mut wrong_hash = delta_pack_v2(NON_REGION_PACK_ID, "d1", &base_pack, &base.snapshot_id);
    wrong_hash.base_hash = Some("not-the-base".into());
    let bad = request(&world, Some(&base.snapshot_id), vec![], vec![wrong_hash]);
    upload_artifacts(&env, &bad).await;
    assert_eq!(
        snapshots::finalize_snapshot(&env.svc, &owner(), &world.id, &bad, at("2026-01-01T00:10:00.000Z"))
            .await
            .unwrap_err()
            .code,
        "snapshot_base_hash_mismatch"
    );

    let mut wrong_depth = delta_pack_v2(NON_REGION_PACK_ID, "d1", &base_pack, &base.snapshot_id);
    wrong_depth.chain_depth = Some(7);
    let bad = request(&world, Some(&base.snapshot_id), vec![], vec![wrong_depth]);
    upload_artifacts(&env, &bad).await;
    assert_eq!(
        snapshots::finalize_snapshot(&env.svc, &owner(), &world.id, &bad, at("2026-01-01T00:10:00.000Z"))
            .await
            .unwrap_err()
            .code,
        "snapshot_chain_depth_mismatch"
    );

    let mut no_metadata = delta_pack_v2(NON_REGION_PACK_ID, "d1", &base_pack, &base.snapshot_id);
    no_metadata.base_snapshot_id = None;
    no_metadata.base_hash = None;
    let bad = request(&world, Some(&base.snapshot_id), vec![], vec![no_metadata]);
    upload_artifacts(&env, &bad).await;
    let error =
        snapshots::finalize_snapshot(&env.svc, &owner(), &world.id, &bad, at("2026-01-01T00:10:00.000Z"))
            .await
            .unwrap_err();
    assert_eq!(error.code, "invalid_snapshot_delta");
    assert!(error.message.contains("is missing base metadata"));

    // A full pack may not carry base metadata at all.
    let mut full_with_base = full_pack(NON_REGION_PACK_ID, "full2", "packs/full/full2.pack");
    full_with_base.base_snapshot_id = Some(base.snapshot_id.clone());
    full_with_base.base_hash = Some("full1".into());
    let bad = request(&world, Some(&base.snapshot_id), vec![], vec![full_with_base]);
    upload_artifacts(&env, &bad).await;
    assert_eq!(
        snapshots::finalize_snapshot(&env.svc, &owner(), &world.id, &bad, at("2026-01-01T00:10:00.000Z"))
            .await
            .unwrap_err()
            .code,
        "invalid_snapshot_base"
    );
}

// ---------------------------------------------------------------------------
// Chain accounting + recipes (finalize-header-batching.test.ts)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn chain_accounting_and_recipes_match_the_per_id_path() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Delta World").await;

    let a1 = full_pack("a", "a1", "packs/full/a1.pack");
    let b1 = full_pack("b", "b1", "packs/full/b1.pack");
    let c1 = full_pack("c", "c1", "packs/full/c1.pack");
    let s1 = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![], vec![a1.clone(), b1.clone(), c1.clone()]),
        "2026-01-01T00:00:00.000Z",
    )
    .await;
    let a2 = delta_pack_v2("a", "a2", &a1, &s1.snapshot_id);
    let s2 = finalize(
        &env,
        &owner(),
        &world,
        request(&world, Some(&s1.snapshot_id), vec![], vec![a2.clone(), b1.clone(), c1.clone()]),
        "2026-01-01T00:05:00.000Z",
    )
    .await;
    let b3 = delta_pack_v2("b", "b3", &b1, &s1.snapshot_id);
    let a3 = delta_pack_v2("a", "a3", &a2, &s2.snapshot_id);
    let s3 = finalize(
        &env,
        &owner(),
        &world,
        request(&world, Some(&s2.snapshot_id), vec![], vec![a3.clone(), b3.clone(), c1.clone()]),
        "2026-01-01T00:10:00.000Z",
    )
    .await;
    // The measured shape: every pack is a delta and the bases span several
    // distinct snapshots (a→s3, b→s3, c→s1).
    let s4 = finalize(
        &env,
        &owner(),
        &world,
        request(
            &world,
            Some(&s3.snapshot_id),
            vec![],
            vec![
                delta_pack_v2("a", "a4", &a3, &s3.snapshot_id),
                delta_pack_v2("b", "b4", &b3, &s3.snapshot_id),
                delta_pack_v2("c", "c4", &c1, &s1.snapshot_id),
            ],
        ),
        "2026-01-01T00:15:00.000Z",
    )
    .await;

    let headers = env.repo.get_snapshot_headers(&world.id, &s4.snapshot_id).await.unwrap().unwrap();
    let shape: Vec<(String, Option<i64>, Option<i64>, usize)> = headers
        .packs
        .iter()
        .map(|p| {
            (
                p.pack_id.clone(),
                p.chain_depth,
                p.chain_delta_bytes,
                p.chain_steps.as_ref().map(Vec::len).unwrap_or(0),
            )
        })
        .collect();
    assert_eq!(
        shape,
        vec![
            ("a".into(), Some(3), Some(120), 4),
            ("b".into(), Some(2), Some(80), 3),
            ("c".into(), Some(1), Some(40), 2),
        ]
    );
    // Recipes are self-contained: the anchor full first, then every delta.
    let steps = headers.packs[0].chain_steps.clone().unwrap();
    assert_eq!(
        steps.iter().map(|s| s.storage_key.as_str()).collect::<Vec<_>>(),
        vec!["packs/full/a1.pack", "packs/delta2/a2.bin", "packs/delta2/a3.bin", "packs/delta2/a4.bin",]
    );
    assert_eq!(steps[0].base_hash, None);
    assert_eq!(steps[3].base_hash.as_deref(), Some("a3"));
}

// ---------------------------------------------------------------------------
// Carried-forward packs over a deleted base (carried-forward-packs.test.ts)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn a_carried_forward_pack_survives_its_base_snapshot_row() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Armut gezegeni").await;
    let full = full_pack(NON_REGION_PACK_ID, "full1", "packs/full/fu/full1.pack");
    let s1 = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![], vec![full.clone()]),
        "2026-01-01T10:00:00.000Z",
    )
    .await;
    let mut delta = delta_pack_v2(NON_REGION_PACK_ID, "d1", &full, &s1.snapshot_id);
    delta.delta_blob_size = Some(150);
    let s2 = finalize(
        &env,
        &owner(),
        &world,
        request(&world, Some(&s1.snapshot_id), vec![], vec![delta.clone()]),
        "2026-01-01T10:05:00.000Z",
    )
    .await;
    // s3 carries the delta pack forward unchanged (what the upload plan's
    // alreadyPresent echo produces): same header, base still s1.
    let s3 = finalize(
        &env,
        &owner(),
        &world,
        request(&world, Some(&s2.snapshot_id), vec![], vec![delta.clone()]),
        "2026-01-01T10:10:00.000Z",
    )
    .await;
    let s3_headers = env.repo.get_snapshot_headers(&world.id, &s3.snapshot_id).await.unwrap().unwrap();
    assert_eq!(s3_headers.packs[0].chain_steps.as_ref().unwrap().len(), 2);

    // The base row goes away (a manual backup delete; allowed for stamped
    // referrers since S1).
    env.repo.delete_snapshots(&world.id, std::slice::from_ref(&s1.snapshot_id)).await.unwrap();
    assert!(env.repo.get_snapshot_headers(&world.id, &s1.snapshot_id).await.unwrap().is_none());

    // Before the fix: 400 snapshot_base_not_found here.
    let s4 = finalize(
        &env,
        &owner(),
        &world,
        request(&world, Some(&s3.snapshot_id), vec![], vec![delta]),
        "2026-01-01T10:15:00.000Z",
    )
    .await;
    let s4_headers = env.repo.get_snapshot_headers(&world.id, &s4.snapshot_id).await.unwrap().unwrap();
    assert_eq!(s4_headers.packs[0].chain_steps, s3_headers.packs[0].chain_steps);
    assert_eq!(s4_headers.packs[0].chain_delta_bytes, Some(150));
}

#[tokio::test]
async fn a_changed_pack_over_a_missing_base_is_still_validated() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Armut gezegeni").await;
    let full = full_pack(NON_REGION_PACK_ID, "full1", "packs/full/fu/full1.pack");
    let s1 = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![], vec![full.clone()]),
        "2026-01-01T10:00:00.000Z",
    )
    .await;
    env.repo.delete_snapshots(&world.id, std::slice::from_ref(&s1.snapshot_id)).await.unwrap();

    // Not carried forward from any parent: a fresh delta claiming s1 as base.
    let fresh = delta_pack_v2(NON_REGION_PACK_ID, "d1", &full, &s1.snapshot_id);
    let req = request(&world, None, vec![], vec![fresh]);
    upload_artifacts(&env, &req).await;
    let error =
        snapshots::finalize_snapshot(&env.svc, &owner(), &world.id, &req, at("2026-01-01T10:05:00.000Z"))
            .await
            .unwrap_err();
    assert_eq!((error.status, error.code), (400, "snapshot_base_not_found"));
}

// ---------------------------------------------------------------------------
// Retention (finalize-deferred-retention.test.ts + snapshots-retention.test.ts)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn finalize_returns_before_retention_and_the_deferred_pass_thins_history() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Retention Test").await;
    let ctx = env.deferring_ctx(owner());

    let stamps = [
        ("jan-old", "blobs/ja/jan-old.bin", "2026-01-01T00:00:00.000Z"),
        ("jan-keep", "blobs/ja/jan-keep.bin", "2026-01-20T12:00:00.000Z"),
        ("march-old", "blobs/ma/march-old.bin", "2026-03-01T10:00:00.000Z"),
        ("march-keep", "blobs/ma/march-keep.bin", "2026-03-01T12:00:00.000Z"),
        ("recent-a", "blobs/re/recent-a.bin", "2026-03-30T10:00:00.000Z"),
        ("recent-b", "blobs/re/recent-b.bin", "2026-03-31T00:00:00.000Z"),
    ];
    for (hash, key, stamp) in stamps {
        let mut files = vec![file(hash, key, "level.dat")];
        if hash.starts_with("march") {
            files.push(file("shared", "blobs/sh/shared.bin", "playerdata/owner.dat"));
        }
        finalize(&env, &ctx, &world, request(&world, None, files, vec![]), stamp).await;
    }
    // Every finalize claimed a fresh hourly slot and handed retention off; the
    // rows are all still there because nothing ran yet.
    assert_eq!(env.deferred.lock().unwrap().len(), 6);
    assert_eq!(created_at_of(&env, &world).await.len(), 6);

    env.run_deferred().await;
    assert_eq!(
        created_at_of(&env, &world).await,
        vec![
            "2026-03-31T00:00:00.000Z",
            "2026-03-30T10:00:00.000Z",
            "2026-03-01T12:00:00.000Z",
            "2026-01-20T12:00:00.000Z",
        ]
    );
    assert!(!blob_exists(&env, "blobs/ja/jan-old.bin").await);
    assert!(!blob_exists(&env, "blobs/ma/march-old.bin").await);
    // The blob both March snapshots share stays: the survivor still needs it.
    assert!(blob_exists(&env, "blobs/sh/shared.bin").await);
    assert!(blob_exists(&env, "blobs/re/recent-b.bin").await);
}

#[tokio::test]
async fn the_age_schedule_keeps_an_hour_then_hourly_then_dailies() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Schedule").await;
    let stamps = [
        ("t0", "2026-06-01T00:00:00.000Z"), // 3.5 days old → daily
        ("t1", "2026-06-03T20:10:00.000Z"), // same hour as t2, older → thinned
        ("t2", "2026-06-03T20:40:00.000Z"), // ~15h old → hourly bucket keeps the newest
        ("t3", "2026-06-04T08:30:00.000Z"), // 3.5h old → own hourly bucket
        ("t4", "2026-06-04T11:15:00.000Z"), // 45 min old → keep-all window
        ("t5", "2026-06-04T12:00:00.000Z"), // latest
    ];
    for (hash, stamp) in stamps {
        let files = vec![file(hash, &format!("blobs/{hash}.bin"), "level.dat")];
        finalize(&env, &owner(), &world, request(&world, None, files, vec![]), stamp).await;
    }
    // Retention rode the last finalize's hourly slot (previous slot at t3).
    assert_eq!(
        created_at_of(&env, &world).await,
        vec![
            "2026-06-04T12:00:00.000Z",
            "2026-06-04T11:15:00.000Z",
            "2026-06-04T08:30:00.000Z",
            "2026-06-03T20:40:00.000Z",
            "2026-06-01T00:00:00.000Z",
        ]
    );
    assert!(!blob_exists(&env, "blobs/t1.bin").await);
    assert!(blob_exists(&env, "blobs/t2.bin").await);
}

#[tokio::test]
async fn max_backups_one_keeps_only_the_current_snapshot_and_lowering_prunes_now() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Latest Only").await;
    let mut ids = Vec::new();
    for day in 1..=4 {
        let hash = format!("d{day}");
        let files = vec![file(&hash, &format!("blobs/{hash}.bin"), "level.dat")];
        let manifest = finalize(
            &env,
            &owner(),
            &world,
            request(&world, None, files, vec![]),
            &format!("2026-05-0{day}T10:00:00.000Z"),
        )
        .await;
        ids.push(manifest.snapshot_id);
    }
    assert_eq!(snapshot_ids_of(&env, &world).await.len(), 4);

    // maxBackups 0 is refused by settings validation.
    let error = worlds::update_world_settings(
        &env.svc,
        &owner(),
        &world.id,
        &UpdateWorldSettingsRequest { settings: serde_json::json!({ "maxBackups": 0 }) },
    )
    .await
    .unwrap_err();
    assert_eq!(error.status, 400);

    // No hourly-slot wait: the settings write itself runs retention (deferred).
    let ctx = env.deferring_ctx(owner());
    worlds::update_world_settings(
        &env.svc,
        &ctx,
        &world.id,
        &UpdateWorldSettingsRequest { settings: serde_json::json!({ "maxBackups": 1 }) },
    )
    .await
    .unwrap();
    assert_eq!(env.deferred.lock().unwrap().len(), 1);
    env.run_deferred().await;
    assert_eq!(snapshot_ids_of(&env, &world).await, vec![ids[3].clone()]);

    // Raising the cap does not run retention.
    worlds::update_world_settings(
        &env.svc,
        &ctx,
        &world.id,
        &UpdateWorldSettingsRequest { settings: serde_json::json!({ "maxBackups": 10 }) },
    )
    .await
    .unwrap();
    assert_eq!(env.deferred.lock().unwrap().len(), 0);
}

/// A capped world enforces the cap on every finalize, not just on the hourly
/// retention slot: saves minutes apart under "None" keep exactly one snapshot.
#[tokio::test]
async fn capped_world_enforces_the_cap_on_every_finalize() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Cap Each Save").await;
    worlds::update_world_settings(
        &env.svc,
        &owner(),
        &world.id,
        &UpdateWorldSettingsRequest { settings: serde_json::json!({ "maxBackups": 1 }) },
    )
    .await
    .unwrap();
    for minute in 0..5 {
        let hash = format!("m{minute}");
        let files = vec![file(&hash, &format!("blobs/{hash}.bin"), "level.dat")];
        let manifest = finalize(
            &env,
            &owner(),
            &world,
            request(&world, None, files, vec![]),
            &format!("2026-06-01T10:0{minute}:00.000Z"),
        )
        .await;
        assert_eq!(snapshot_ids_of(&env, &world).await, vec![manifest.snapshot_id.clone()], "save {minute}");
    }
}

#[tokio::test]
async fn max_backups_caps_the_age_kept_set_oldest_first() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Capped SMP").await;
    env.repo.update_world_settings(&world.id, r#"{"maxBackups":3}"#).await.unwrap();
    let mut ids = Vec::new();
    for day in 1..=6 {
        let hash = format!("pack-{day}");
        let pack = full_pack(NON_REGION_PACK_ID, &hash, &format!("packs/full/day-{day}.pack"));
        let manifest = finalize(
            &env,
            &owner(),
            &world,
            request(&world, None, vec![], vec![pack]),
            &format!("2026-01-0{day}T10:00:00.000Z"),
        )
        .await;
        ids.push(manifest.snapshot_id);
    }
    let mut kept = snapshot_ids_of(&env, &world).await;
    kept.sort();
    let mut expected = ids[3..].to_vec();
    expected.sort();
    assert_eq!(kept, expected);
}

#[tokio::test]
async fn retention_keeps_the_delta_bases_a_survivor_still_needs() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Chain Retention").await;
    let pack_a = full_pack(NON_REGION_PACK_ID, "pack-a", "packs/full/a.pack");
    let a = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![], vec![pack_a.clone()]),
        "2026-01-01T10:00:00.000Z",
    )
    .await;
    let pack_b = delta_pack_v1(NON_REGION_PACK_ID, "pack-b", "packs/delta/a-b.bin", &pack_a, &a.snapshot_id);
    let b = finalize(
        &env,
        &owner(),
        &world,
        request(&world, Some(&a.snapshot_id), vec![], vec![pack_b.clone()]),
        "2026-01-01T11:00:00.000Z",
    )
    .await;
    // Two days later a third save extends the chain. Age-based retention alone
    // would prune snapshot A (its day bucket is already represented by B) and
    // delete the full artifact every reconstruction starts from.
    let c = finalize(
        &env,
        &owner(),
        &world,
        request(
            &world,
            Some(&b.snapshot_id),
            vec![],
            vec![delta_pack_v1(NON_REGION_PACK_ID, "pack-c", "packs/delta/b-c.bin", &pack_b, &b.snapshot_id)],
        ),
        "2026-01-03T12:00:00.000Z",
    )
    .await;

    // S1: A's ROW is pruned (its bucket is represented by B), but the chain
    // BLOBS survive because B and C are self-contained and reference them.
    let mut kept = snapshot_ids_of(&env, &world).await;
    kept.sort();
    let mut expected = vec![b.snapshot_id.clone(), c.snapshot_id.clone()];
    expected.sort();
    assert_eq!(kept, expected);
    assert!(blob_exists(&env, "packs/full/a.pack").await);
    assert!(blob_exists(&env, "packs/delta/a-b.bin").await);
}

#[tokio::test]
async fn retention_upgrades_kept_legacy_snapshots_then_prunes_their_ancestry() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Bloated SMP").await;
    // A pre-chainSteps history: full → delta → delta, one per day.
    let pack_a = full_pack(NON_REGION_PACK_ID, "pack-a", "packs/full/a.pack");
    let a = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![], vec![pack_a.clone()]),
        "2026-01-01T10:00:00.000Z",
    )
    .await;
    let pack_b = delta_pack_v1(NON_REGION_PACK_ID, "pack-b", "packs/delta/a-b.bin", &pack_a, &a.snapshot_id);
    let b = finalize(
        &env,
        &owner(),
        &world,
        request(&world, Some(&a.snapshot_id), vec![], vec![pack_b.clone()]),
        "2026-01-02T10:00:00.000Z",
    )
    .await;
    let pack_c = delta_pack_v1(NON_REGION_PACK_ID, "pack-c", "packs/delta/b-c.bin", &pack_b, &b.snapshot_id);
    let c = finalize(
        &env,
        &owner(),
        &world,
        request(&world, Some(&b.snapshot_id), vec![], vec![pack_c.clone()]),
        "2026-01-03T10:00:00.000Z",
    )
    .await;
    for id in [&a.snapshot_id, &b.snapshot_id, &c.snapshot_id] {
        strip_chain_steps(&env, id).await;
    }

    // Months later a new finalize claims the hourly retention slot: kept
    // snapshots get recipes synthesized from the legacy walk, after which the
    // unpinned ancestry is pruned wholesale.
    let d = finalize(
        &env,
        &owner(),
        &world,
        request(
            &world,
            Some(&c.snapshot_id),
            vec![],
            vec![delta_pack_v1(NON_REGION_PACK_ID, "pack-d", "packs/delta/c-d.bin", &pack_c, &c.snapshot_id)],
        ),
        "2026-06-01T10:00:00.000Z",
    )
    .await;

    let mut kept = snapshot_ids_of(&env, &world).await;
    kept.sort();
    let mut expected = vec![c.snapshot_id.clone(), d.snapshot_id.clone()];
    expected.sort();
    assert_eq!(kept, expected, "only the monthly-kept C and the new latest D survive");

    // C was lazily upgraded: its directory now carries a synthesized recipe.
    let c_headers = env.repo.get_snapshot_headers(&world.id, &c.snapshot_id).await.unwrap().unwrap();
    assert_eq!(
        c_headers.packs[0]
            .chain_steps
            .as_ref()
            .unwrap()
            .iter()
            .map(|s| s.storage_key.as_str())
            .collect::<Vec<_>>(),
        vec!["packs/full/a.pack", "packs/delta/a-b.bin", "packs/delta/b-c.bin"]
    );
    // The chain blobs survive (still referenced by the recipes).
    assert!(blob_exists(&env, "packs/full/a.pack").await);
    assert!(blob_exists(&env, "packs/delta/a-b.bin").await);
    // D's own recipe walks the whole chain.
    let d_headers = env.repo.get_snapshot_headers(&world.id, &d.snapshot_id).await.unwrap().unwrap();
    assert_eq!(
        d_headers.packs[0]
            .chain_steps
            .as_ref()
            .unwrap()
            .iter()
            .map(|s| s.storage_key.as_str())
            .collect::<Vec<_>>(),
        vec!["packs/full/a.pack", "packs/delta/a-b.bin", "packs/delta/b-c.bin", "packs/delta/c-d.bin",]
    );
}

// ---------------------------------------------------------------------------
// Restore
// ---------------------------------------------------------------------------

#[tokio::test]
async fn restoring_a_packed_snapshot_republishes_it_as_the_newest() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Packed Restore").await;
    let pack_a = SnapshotPack {
        size: 256,
        files: vec![member("level.dat", "level-a"), member("data/foo.dat", "foo-a")],
        ..full_pack(NON_REGION_PACK_ID, "pack-a", "packs/full/pa/pack-a.pack")
    };
    let region_a = SnapshotPack {
        size: 128,
        transfer_mode: FileTransferMode::RegionFull,
        files: vec![member("region/r.0.0.mca", "region-a")],
        ..full_pack("region-bundle:region:0:0", "region-a", "region-bundles/full/re/region-a.bundle")
    };
    let a =
        repo_finalize(&env, &world, None, vec![pack_a.clone(), region_a.clone()], "2099-01-05T00:00:00.000Z")
            .await;
    let pack_b = SnapshotPack {
        files: vec![member("level.dat", "level-b"), member("data/foo.dat", "foo-b")],
        ..delta_pack_v1(
            NON_REGION_PACK_ID,
            "pack-b",
            "packs/delta/pa/pack-a-pack-b.bin",
            &pack_a,
            &a.snapshot_id,
        )
    };
    let region_b = SnapshotPack {
        transfer_mode: FileTransferMode::RegionDelta,
        files: vec![member("region/r.0.0.mca", "region-b")],
        ..delta_pack_v1(
            "region-bundle:region:0:0",
            "region-b",
            "region-bundles/delta/re/region-a-region-b.bin",
            &region_a,
            &a.snapshot_id,
        )
    };
    repo_finalize(&env, &world, Some(&a.snapshot_id), vec![pack_b, region_b], "2099-01-05T00:01:00.000Z")
        .await;

    // Restore is only legal once the runtime claim has expired (2099 here).
    let restored = snapshots::restore_snapshot(
        &env.svc,
        &owner(),
        &world.id,
        &a.snapshot_id,
        at("2099-01-05T00:02:00.000Z"),
    )
    .await
    .unwrap();
    assert_eq!(restored.snapshot_id, a.snapshot_id, "the restored-from id comes back");

    let summaries = snapshots::list_snapshots(&env.svc, &owner(), &world.id).await.unwrap();
    assert_eq!(summaries.len(), 3, "restore republishes rather than rewriting history");
    assert!(summaries[0].is_latest);
    assert_eq!(summaries[0].file_count, 3);
    assert_eq!(summaries[0].total_size, 30);

    let latest = snapshots::latest_manifest(&env.svc, &owner(), &world.id).await.unwrap().unwrap();
    assert_ne!(latest.snapshot_id, a.snapshot_id);
    assert!(latest.files.is_empty());
    assert_eq!(
        latest.packs.iter().map(|p| p.pack_id.as_str()).collect::<Vec<_>>(),
        vec![NON_REGION_PACK_ID, "region-bundle:region:0:0"]
    );
    assert_eq!(latest.packs.iter().map(|p| p.hash.as_str()).collect::<Vec<_>>(), vec!["pack-a", "region-a"]);
    assert_eq!(
        latest.packs[0].files.iter().map(|f| f.path.as_str()).collect::<Vec<_>>(),
        vec!["data/foo.dat", "level.dat"]
    );
    assert_eq!(latest.packs[0].base_snapshot_id, None);
    assert_eq!(latest.packs[1].base_snapshot_id, None);

    // A guest may not restore, and an unknown backup is a 404.
    assert_eq!(
        snapshots::restore_snapshot(
            &env.svc,
            &guest(),
            &world.id,
            &a.snapshot_id,
            at("2099-01-05T00:03:00.000Z")
        )
        .await
        .unwrap_err()
        .code,
        "world_not_found"
    );
    assert_eq!(
        snapshots::restore_snapshot(
            &env.svc,
            &owner(),
            &world.id,
            "snapshot_missing",
            at("2099-01-05T00:03:00.000Z")
        )
        .await
        .unwrap_err()
        .code,
        "snapshot_not_found"
    );
}

#[tokio::test]
async fn restore_is_refused_while_the_world_is_hosted() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Busy World").await;
    let now = time::now();
    let a = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![file("a", "blobs/a.bin", "level.dat")], vec![]),
        &time::to_iso(now),
    )
    .await;
    let error =
        snapshots::restore_snapshot(&env.svc, &owner(), &world.id, &a.snapshot_id, now).await.unwrap_err();
    assert_eq!((error.status, error.code), (409, "world_busy"));
}

// ---------------------------------------------------------------------------
// Delete (backup-delete-0-4-5.test.ts)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn bulk_delete_drops_every_named_row_and_reclaims_blobs_after_the_response() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Bulk").await;
    // Distinct days so retention (which rides finalize) keeps them all.
    let shared = || file("shared", "blobs/shared.bin", "playerdata/o.dat");
    let a = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![file("a", "blobs/a.bin", "level.dat"), shared()], vec![]),
        "2026-05-01T10:00:00.000Z",
    )
    .await;
    let b = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![file("b", "blobs/b.bin", "level.dat"), shared()], vec![]),
        "2026-05-02T10:00:00.000Z",
    )
    .await;
    let c = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![file("c", "blobs/c.bin", "level.dat"), shared()], vec![]),
        "2026-05-03T10:00:00.000Z",
    )
    .await;
    let latest = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![file("d", "blobs/d.bin", "level.dat")], vec![]),
        "2026-05-04T10:00:00.000Z",
    )
    .await;

    let ctx = env.deferring_ctx(owner());
    let result = snapshots::delete_snapshots(
        &env.svc,
        &ctx,
        &world.id,
        &[a.snapshot_id.clone(), b.snapshot_id.clone(), "snapshot_missing".into(), a.snapshot_id.clone()],
    )
    .await
    .unwrap();

    // Rows are gone at response time; missing ids are skipped, duplicates collapse.
    let mut deleted = result.deleted_snapshot_ids.clone();
    deleted.sort();
    let mut expected = vec![a.snapshot_id.clone(), b.snapshot_id.clone()];
    expected.sort();
    assert_eq!(deleted, expected);
    let mut remaining = snapshot_ids_of(&env, &world).await;
    remaining.sort();
    let mut survivors = vec![c.snapshot_id.clone(), latest.snapshot_id.clone()];
    survivors.sort();
    assert_eq!(remaining, survivors);
    // The response did not wait for the provider deletes.
    assert_eq!(env.deferred.lock().unwrap().len(), 1);
    assert!(blob_exists(&env, "blobs/a.bin").await);
    env.run_deferred().await;
    // Only the keys no surviving snapshot references; the shared blob stays.
    assert!(!blob_exists(&env, "blobs/a.bin").await);
    assert!(!blob_exists(&env, "blobs/b.bin").await);
    assert!(blob_exists(&env, "blobs/shared.bin").await);

    // Naming the latest anywhere in the set refuses the whole request.
    let error = snapshots::delete_snapshots(
        &env.svc,
        &owner(),
        &world.id,
        &[c.snapshot_id.clone(), latest.snapshot_id.clone()],
    )
    .await
    .unwrap_err();
    assert_eq!((error.status, error.code), (409, "cannot_delete_latest_snapshot"));
    // Nothing that exists → 404, as the single-id form; an empty set → 400.
    assert_eq!(
        snapshots::delete_snapshots(&env.svc, &owner(), &world.id, &["snapshot_missing".into()])
            .await
            .unwrap_err()
            .status,
        404
    );
    assert_eq!(
        snapshots::delete_snapshots(&env.svc, &owner(), &world.id, &[]).await.unwrap_err().status,
        400
    );
    // Only the owner may delete backups.
    assert_eq!(
        snapshots::delete_snapshot(&env.svc, &guest(), &world.id, &c.snapshot_id).await.unwrap_err().code,
        "world_not_found"
    );
}

#[tokio::test]
async fn single_delete_answers_before_the_provider_deletes_run() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Single").await;
    let old = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![file("a", "blobs/a.bin", "level.dat")], vec![]),
        "2026-05-01T10:00:00.000Z",
    )
    .await;
    finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![file("b", "blobs/b.bin", "level.dat")], vec![]),
        "2026-05-02T10:00:00.000Z",
    )
    .await;
    let ctx = env.deferring_ctx(owner());
    let result = snapshots::delete_snapshot(&env.svc, &ctx, &world.id, &old.snapshot_id).await.unwrap();
    assert_eq!(result.snapshot_id, old.snapshot_id);
    assert!(blob_exists(&env, "blobs/a.bin").await);
    env.run_deferred().await;
    assert!(!blob_exists(&env, "blobs/a.bin").await);
}

#[tokio::test]
async fn a_legacy_dependant_still_blocks_deleting_its_base() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Legacy Guard").await;
    let pack_a = full_pack(NON_REGION_PACK_ID, "pack-a", "packs/full/a.pack");
    let a = finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![], vec![pack_a.clone()]),
        "2026-01-01T10:00:00.000Z",
    )
    .await;
    let b = finalize(
        &env,
        &owner(),
        &world,
        request(
            &world,
            Some(&a.snapshot_id),
            vec![],
            vec![delta_pack_v1(NON_REGION_PACK_ID, "pack-b", "packs/delta/a-b.bin", &pack_a, &a.snapshot_id)],
        ),
        "2026-01-01T11:00:00.000Z",
    )
    .await;
    finalize(
        &env,
        &owner(),
        &world,
        request(
            &world,
            Some(&b.snapshot_id),
            vec![],
            vec![full_pack(NON_REGION_PACK_ID, "pack-c", "packs/full/c.pack")],
        ),
        "2026-01-01T12:00:00.000Z",
    )
    .await;
    // Rewind B to a pre-stamping worker's representation: strip its recipe.
    strip_chain_steps(&env, &b.snapshot_id).await;

    let error = snapshots::delete_snapshot(&env.svc, &owner(), &world.id, &a.snapshot_id).await.unwrap_err();
    assert_eq!((error.status, error.code), (409, "snapshot_base_in_use"));
    assert!(error.message.contains("A newer backup still needs this one"));

    // Deleting the legacy dependant first unblocks the base, as before.
    snapshots::delete_snapshot(&env.svc, &owner(), &world.id, &b.snapshot_id).await.unwrap();
    snapshots::delete_snapshot(&env.svc, &owner(), &world.id, &a.snapshot_id).await.unwrap();
    assert_eq!(snapshot_ids_of(&env, &world).await.len(), 1);
}

#[tokio::test]
async fn the_latest_backup_is_well_defined_when_two_snapshots_share_a_timestamp() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Latest Tie").await;
    // A duplicated finalize (client retry) lands two snapshots with one stamp.
    for suffix in ["one", "two"] {
        let files =
            vec![file(&format!("level-{suffix}"), &format!("blobs/le/level-{suffix}.bin"), "level.dat")];
        finalize(&env, &owner(), &world, request(&world, None, files, vec![]), "2026-01-01T10:00:00.000Z")
            .await;
    }
    let summaries = snapshots::list_snapshots(&env.svc, &owner(), &world.id).await.unwrap();
    assert_eq!(summaries.len(), 2);
    let latest: Vec<&WorldSnapshotSummary> = summaries.iter().filter(|s| s.is_latest).collect();
    assert_eq!(latest.len(), 1);
    let latest_id = latest[0].snapshot_id.clone();
    assert_eq!(
        snapshots::latest_manifest(&env.svc, &owner(), &world.id).await.unwrap().map(|m| m.snapshot_id),
        Some(latest_id.clone())
    );

    // The delete guard protects exactly the snapshot everything calls latest.
    let error = snapshots::delete_snapshot(&env.svc, &owner(), &world.id, &latest_id).await.unwrap_err();
    assert_eq!(error.message, "The latest backup cannot be deleted.");
    let other = summaries.iter().find(|s| !s.is_latest).unwrap();
    snapshots::delete_snapshot(&env.svc, &owner(), &world.id, &other.snapshot_id).await.unwrap();
}

// ---------------------------------------------------------------------------
// Blob GC: budget overflow + the pending-delete sweeps
// ---------------------------------------------------------------------------

fn drive_binding(account: &str) -> WorldStorageBinding {
    WorldStorageBinding {
        provider: StorageProviderType::GoogleDrive,
        storage_account_id: Some(account.into()),
    }
}

#[tokio::test]
async fn an_exhausted_budget_queues_every_remaining_key_instead_of_deleting() {
    let env = TestEnv::new().await;
    let binding = drive_binding("storage-account-1");
    for key in ["k/one", "k/two", "k/three"] {
        put_blob(&env, key).await;
    }
    snapshots::delete_unreferenced_blobs(
        &env.svc,
        &binding,
        &["k/one".into(), "k/two".into(), "k/three".into()],
        Some(0),
    )
    .await
    .unwrap();
    assert!(blob_exists(&env, "k/one").await, "nothing was deleted");
    let mut queued: Vec<String> = env
        .repo
        .list_pending_blob_deletes(StorageProviderType::GoogleDrive, "storage-account-1", 10)
        .await
        .unwrap()
        .into_iter()
        .map(|entry| entry.storage_key)
        .collect();
    queued.sort();
    assert_eq!(queued, vec!["k/one", "k/three", "k/two"]);
}

#[tokio::test]
async fn no_budget_deletes_everything_and_queues_nothing() {
    let env = TestEnv::new().await;
    let binding = drive_binding("storage-account-1");
    for key in ["k/one", "k/two"] {
        put_blob(&env, key).await;
    }
    snapshots::delete_unreferenced_blobs(&env.svc, &binding, &["k/one".into(), "k/two".into()], None)
        .await
        .unwrap();
    assert!(!blob_exists(&env, "k/one").await);
    assert!(!blob_exists(&env, "k/two").await);
    assert!(env
        .repo
        .list_pending_blob_deletes(StorageProviderType::GoogleDrive, "storage-account-1", 10)
        .await
        .unwrap()
        .is_empty());
}

#[tokio::test]
async fn the_cron_sweep_drops_a_queued_key_a_newer_snapshot_references_again() {
    // Content-addressed dedupe can resurrect a key between enqueue and sweep.
    // The re-check is scoped to snapshots created since the enqueue (with
    // slack): a newer snapshot naming the key must keep the blob, and the
    // queue row is dropped instead of deleted.
    let env = TestEnv::new().await;
    let world = env
        .repo
        .create_world(&owner().actor(), "Resurrect", "resurrect", drive_binding("acct-1"), None, None)
        .await
        .unwrap();
    env.repo
        .enqueue_pending_blob_deletes(
            StorageProviderType::GoogleDrive,
            "acct-1",
            &["blobs/back.bin".into(), "blobs/gone.bin".into()],
            "2026-05-01T00:00:00.000Z",
        )
        .await
        .unwrap();
    for key in ["blobs/back.bin", "blobs/gone.bin"] {
        put_blob(&env, key).await;
    }
    let pack = SnapshotPack { size: 1, files: vec![], ..full_pack("p", "h", "blobs/back.bin") };
    env.repo
        .finalize_snapshot(
            &world.summary.id,
            &owner().actor(),
            &FinalizeSnapshotRequest {
                runtime_epoch: None,
                host_token: None,
                base_snapshot_id: None,
                data_version: None,
                minecraft_version: None,
                files: vec![],
                packs: Some(vec![pack]),
            },
            at("2026-05-01T00:03:00.000Z"),
            None,
        )
        .await
        .unwrap();

    let attempted =
        snapshots::sweep_due_pending_blob_deletes(&env.svc, at("2026-05-01T00:05:00.000Z"), 10).await;
    assert_eq!(attempted, 2);
    assert!(blob_exists(&env, "blobs/back.bin").await, "re-referenced key kept");
    assert!(!blob_exists(&env, "blobs/gone.bin").await);
    assert!(env
        .repo
        .list_pending_blob_deletes(StorageProviderType::GoogleDrive, "acct-1", 10)
        .await
        .unwrap()
        .is_empty());
}

#[tokio::test]
async fn the_cron_sweep_drains_across_accounts_and_backs_off_failing_keys() {
    let env = TestEnv::new().await;
    for key in ["blobs/ok-1.bin", "blobs/ok-2.bin"] {
        put_blob(&env, key).await;
    }
    // A directory where the blob should be: the provider's delete fails with a
    // real I/O error instead of the "already gone" no-op.
    let flaky_path = env.dir.path().join("blobs").join("blobs/flaky.bin");
    std::fs::create_dir_all(&flaky_path).unwrap();

    env.repo
        .enqueue_pending_blob_deletes(
            StorageProviderType::GoogleDrive,
            "acct-1",
            &["blobs/flaky.bin".into(), "blobs/ok-1.bin".into()],
            "2026-05-01T00:00:00.000Z",
        )
        .await
        .unwrap();
    env.repo
        .enqueue_pending_blob_deletes(
            StorageProviderType::GoogleDrive,
            "acct-2",
            &["blobs/ok-2.bin".into()],
            "2026-05-01T00:00:01.000Z",
        )
        .await
        .unwrap();

    let t0 = at("2026-05-01T00:01:00.000Z");
    assert_eq!(snapshots::sweep_due_pending_blob_deletes(&env.svc, t0, 10).await, 3);
    assert!(!blob_exists(&env, "blobs/ok-1.bin").await);
    assert!(!blob_exists(&env, "blobs/ok-2.bin").await);

    // Successful keys leave the queue; the failed one stays with attempts=1
    // and is not due again until its backoff (5 min for the first failure).
    let due_now = env.repo.list_due_pending_blob_deletes(&time::plus_ms_iso(t0, 60_000), 10).await.unwrap();
    assert!(due_now.is_empty());
    let later = at("2026-05-01T00:07:00.000Z");
    let due = env.repo.list_due_pending_blob_deletes(&time::to_iso(later), 10).await.unwrap();
    assert_eq!(due.len(), 1);
    assert_eq!(due[0].storage_key, "blobs/flaky.bin");
    assert_eq!(due[0].storage_account_id, "acct-1");
    assert_eq!(due[0].attempts, 1);

    // Second failure doubles the wait.
    assert_eq!(snapshots::sweep_due_pending_blob_deletes(&env.svc, later, 10).await, 1);
    assert!(env
        .repo
        .list_due_pending_blob_deletes(&time::plus_ms_iso(later, 6 * 60_000), 10)
        .await
        .unwrap()
        .is_empty());
    let attempts: Vec<i64> = env
        .repo
        .list_due_pending_blob_deletes(&time::plus_ms_iso(later, 11 * 60_000), 10)
        .await
        .unwrap()
        .into_iter()
        .map(|entry| entry.attempts)
        .collect();
    assert_eq!(attempts, vec![2]);

    // Once the provider recovers the key drains and the queue is empty.
    std::fs::remove_dir(&flaky_path).unwrap();
    let recovered = at("2026-05-01T00:18:00.000Z");
    assert_eq!(snapshots::sweep_due_pending_blob_deletes(&env.svc, recovered, 10).await, 1);
    assert!(env.repo.list_due_pending_blob_deletes("2027-01-01T00:00:00.000Z", 10).await.unwrap().is_empty());
}

// ---------------------------------------------------------------------------
// World deletion purges the world's snapshots and blobs
// ---------------------------------------------------------------------------

#[tokio::test]
async fn deleting_a_world_purges_its_snapshots_and_blobs() {
    let env = TestEnv::new().await;
    let world = seed_world(&env, "Doomed").await;
    finalize(
        &env,
        &owner(),
        &world,
        request(&world, None, vec![file("a", "blobs/a.bin", "level.dat")], vec![]),
        "2026-05-01T10:00:00.000Z",
    )
    .await;
    worlds::delete_world(&env.svc, &owner(), &world.id, time::now()).await.unwrap();
    assert!(env.repo.list_snapshots_for_world(&world.id).await.unwrap().is_empty());
    assert!(!blob_exists(&env, "blobs/a.bin").await);
}
