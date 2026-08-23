//! Service-level sync planning and blob transfer (ports of
//! `test/service/storage-sync.test.ts`, `delta-v2-gating.test.ts`,
//! `blob-range.test.ts`, `blob-stamp.test.ts`, `blob-upload-session.test.ts`
//! and the sync-policy half of `client-pacing.test.ts`).

use bytes::Bytes;
use sw_contracts::*;
use sw_core::service::sync_plan::{self, RelayDownloadInput, RelayUploadInput};
use sw_core::storage::{BodyStream, PutBody, ResumableUploadCapable, StorageProvider};
use sw_core::{time, HttpError, RequestContext};
use sw_db::repo::Actor;
use sw_testkit::*;

const BUNDLE_ID: &str = "region-bundle:region:0:0";

struct Fixture {
    env: TestEnv,
    world_id: String,
    epoch: i64,
    token: String,
}

impl Fixture {
    async fn r2() -> Fixture {
        Self::build(TestEnv::new().await, false).await
    }

    /// A world bound to a linked (fake) Google Drive account.
    async fn drive() -> Fixture {
        Self::build(TestEnv::with_fake_drive().await, true).await
    }

    async fn build(env: TestEnv, linked: bool) -> Fixture {
        let request = if linked {
            env.link_drive_account(OWNER_UUID).await;
            CreateWorldRequest {
                name: Some(serde_json::json!("Friends SMP")),
                use_linked_storage_account: Some(true),
                import_source: Some(
                    serde_json::json!({ "type": "local-save", "id": "save-1", "name": "Save 1" }),
                ),
                ..Default::default()
            }
        } else {
            CreateWorldRequest { name: Some(serde_json::json!("Friends SMP")), ..Default::default() }
        };
        let created = sw_core::service::worlds::create_world(&env.svc, &owner(), &request, time::now())
            .await
            .expect("create world");
        Fixture {
            world_id: created.world.summary.id.clone(),
            epoch: created.initial_upload_assignment.runtime_epoch,
            token: created.initial_upload_assignment.host_token.clone(),
            env,
        }
    }

    fn authority(&self) -> (Option<i64>, Option<String>) {
        (Some(self.epoch), Some(self.token.clone()))
    }

    fn upload_request(
        &self,
        pack: Option<LocalPackDescriptor>,
        bundles: Vec<LocalPackDescriptor>,
    ) -> UploadPlanRequest {
        UploadPlanRequest {
            runtime_epoch: Some(self.epoch),
            host_token: Some(self.token.clone()),
            files: Vec::new(),
            non_region_pack: pack,
            region_bundles: Some(bundles),
        }
    }

    async fn prepare(
        &self,
        ctx: &RequestContext,
        request: &UploadPlanRequest,
    ) -> Result<UploadPlan, HttpError> {
        sync_plan::prepare_uploads(&self.env.svc, ctx, &self.world_id, request, time::now()).await
    }

    async fn download(
        &self,
        ctx: &RequestContext,
        request: &UploadPlanRequest,
    ) -> Result<DownloadPlan, HttpError> {
        sync_plan::download_plan(&self.env.svc, ctx, &self.world_id, request).await
    }

    /// Writes bytes straight into the world's provider (validation-free setup).
    async fn seed_blob(&self, key: &str, text: &str) {
        let binding = self.env.repo.get_world_storage_binding(&self.world_id).await.unwrap().unwrap();
        self.env
            .svc
            .storage_provider
            .put(&binding, key, PutBody::Bytes(Bytes::from(text.to_string())), "application/octet-stream")
            .await
            .expect("seed blob");
    }

    /// Records a snapshot through the repository: sync planning reads
    /// headers, so the finalize service path is not part of what is tested.
    async fn finalize(&self, base: Option<&str>, packs: Vec<SnapshotPack>, at_ms: i64) -> String {
        let request = FinalizeSnapshotRequest {
            base_snapshot_id: base.map(|s| s.to_string()),
            files: Vec::new(),
            packs: Some(packs),
            ..Default::default()
        };
        let actor = Actor { player_uuid: OWNER_UUID.into(), player_name: "Owner".into() };
        self.env
            .repo
            .finalize_snapshot(&self.world_id, &actor, &request, time::from_millis(at_ms), None)
            .await
            .expect("finalize")
            .snapshot_id
            .clone()
    }
}

fn local_pack(pack_id: &str, hash: &str, size: i64, files: &[(&str, &str)]) -> LocalPackDescriptor {
    LocalPackDescriptor {
        pack_id: pack_id.into(),
        hash: hash.into(),
        size,
        file_count: files.len() as i64,
        files: files
            .iter()
            .map(|(path, hash)| PackedManifestFile {
                path: (*path).into(),
                hash: (*hash).into(),
                size: 10,
                content_type: "application/octet-stream".into(),
            })
            .collect(),
    }
}

fn pack(
    pack_id: &str,
    hash: &str,
    size: i64,
    storage_key: &str,
    mode: FileTransferMode,
    files: &[(&str, &str)],
) -> SnapshotPack {
    SnapshotPack {
        pack_id: pack_id.into(),
        hash: hash.into(),
        size,
        storage_key: storage_key.into(),
        transfer_mode: mode,
        base_snapshot_id: None,
        base_hash: None,
        chain_depth: Some(0),
        delta_format_version: None,
        delta_blob_size: None,
        chain_delta_bytes: None,
        chain_steps: None,
        files: files
            .iter()
            .map(|(path, hash)| PackedManifestFile {
                path: (*path).into(),
                hash: (*hash).into(),
                size: 10,
                content_type: "application/octet-stream".into(),
            })
            .collect(),
    }
}

fn local_file(path: &str, hash: &str) -> LocalFileDescriptor {
    LocalFileDescriptor {
        path: path.into(),
        hash: hash.into(),
        size: 10,
        compressed_size: 5,
        content_type: None,
        delta_capable: false,
    }
}

fn stream(text: &str) -> BodyStream {
    let bytes = Bytes::from(text.to_string());
    Box::pin(futures::stream::once(async move { Ok(bytes) }))
}

fn v2(uuid: &str, name: &str) -> RequestContext {
    ctx_v(uuid, name, "0.4.0")
}

// ---------------------------------------------------------------------------
// Sync policy
// ---------------------------------------------------------------------------

#[tokio::test]
async fn non_drive_worlds_advertise_the_permissive_sync_policy() {
    let f = Fixture::r2().await;
    let plan = f.prepare(&owner(), &f.upload_request(None, vec![])).await.unwrap();
    assert_eq!(plan.sync_policy.max_parallel_downloads, 16);
    assert_eq!(plan.sync_policy.max_concurrent_upload_preparations, 4);
    assert_eq!(plan.sync_policy.max_concurrent_uploads, 4);
    assert_eq!(plan.sync_policy.max_upload_starts_per_second, 8);
    assert_eq!(plan.sync_policy.retry_base_delay_ms, 250);
    assert_eq!(plan.sync_policy.retry_max_delay_ms, 4_000);
    assert_eq!(plan.sync_policy.max_upload_body_bytes, 95_000_000);
}

#[tokio::test]
async fn google_drive_worlds_advertise_a_conservative_sync_policy() {
    let f = Fixture::drive().await;
    let plan = f.prepare(&owner(), &f.upload_request(None, vec![])).await.unwrap();
    assert_eq!(plan.sync_policy.max_concurrent_uploads, 3);
    assert_eq!(plan.sync_policy.max_concurrent_upload_preparations, 2);
    assert_eq!(plan.sync_policy.max_upload_starts_per_second, 3);
    assert_eq!(plan.sync_policy.retry_base_delay_ms, 750);
    assert_eq!(plan.sync_policy.retry_max_delay_ms, 8_000);
    assert_eq!(plan.sync_policy.max_upload_body_bytes, 95_000_000);

    let download = f.download(&owner(), &UploadPlanRequest::default()).await.unwrap();
    assert_eq!(download.sync_policy.max_parallel_downloads, 8);
    assert_eq!(download.sync_policy.max_upload_body_bytes, 95_000_000);
}

#[tokio::test]
async fn config_overrides_the_drive_pacing_knobs() {
    let env = TestEnv::with_fake_drive_config(sw_core::Config {
        drive_max_parallel_downloads: Some(2),
        drive_max_concurrent_uploads: Some(1),
        upload_max_body_bytes: Some(1_000_000),
        // Garbage (non-positive) values behave as unset.
        drive_max_upload_starts_per_second: Some(0),
        ..sw_core::Config::dev()
    })
    .await;
    let policy = sync_plan::sync_policy_for_provider(&env.svc, env.svc.storage_provider.provider());
    assert_eq!(policy.max_parallel_downloads, 2);
    assert_eq!(policy.max_concurrent_uploads, 1);
    assert_eq!(policy.max_upload_starts_per_second, 3);
    assert_eq!(policy.max_upload_body_bytes, 1_000_000);
    assert_eq!(sync_plan::max_upload_body_bytes(&env.svc), 1_000_000);
}

// ---------------------------------------------------------------------------
// Upload / download planning
// ---------------------------------------------------------------------------

#[tokio::test]
async fn download_and_upload_planning_skip_unchanged_files() {
    let f = Fixture::r2().await;
    f.finalize(
        None,
        vec![
            pack(
                NON_REGION_PACK_ID,
                "same-pack",
                32,
                "packs/full/sa/same-pack.pack",
                FileTransferMode::PackFull,
                &[("level.dat", "same")],
            ),
            pack(
                BUNDLE_ID,
                "region",
                100,
                "region-bundles/full/re/region.bundle",
                FileTransferMode::RegionFull,
                &[("region/r.0.0.mca", "region")],
            ),
        ],
        1_000,
    )
    .await;

    let files = vec![local_file("level.dat", "same"), local_file("region/r.0.0.mca", "changed")];
    let mut request = f.upload_request(
        Some(local_pack(NON_REGION_PACK_ID, "same-pack", 32, &[("level.dat", "same")])),
        vec![local_pack(BUNDLE_ID, "changed-bundle", 104, &[("region/r.0.0.mca", "changed")])],
    );
    request.files.clone_from(&files);

    let upload = f.prepare(&owner(), &request).await.unwrap();
    let non_region = upload.non_region_pack_upload.clone().flatten().unwrap();
    assert!(non_region.already_present);
    assert_eq!(non_region.storage_key.as_deref(), Some("packs/full/sa/same-pack.pack"));
    let bundles = upload.region_bundle_uploads.clone().unwrap();
    assert_eq!(bundles.len(), 1);
    assert!(!bundles[0].already_present);
    assert_eq!(upload.latest_pack_ids.as_ref().unwrap().len(), 2);

    let download = f.download(&owner(), &request).await.unwrap();
    assert_eq!(download.retained_paths, vec!["level.dat".to_string()]);
    assert!(download.downloads.is_empty());
    assert!(download.non_region_pack_download.clone().flatten().is_none());
    let bundle_downloads = download.region_bundle_downloads.clone().unwrap();
    assert_eq!(bundle_downloads.len(), 1);
    assert_eq!(bundle_downloads[0].pack_id, BUNDLE_ID);
}

#[tokio::test]
async fn a_world_without_snapshots_retains_every_local_path() {
    let f = Fixture::r2().await;
    let request = UploadPlanRequest {
        files: vec![local_file("level.dat", "a"), local_file("region/r.0.0.mca", "b")],
        ..Default::default()
    };
    let plan = f.download(&owner(), &request).await.unwrap();
    assert_eq!(plan.snapshot_id, None);
    assert_eq!(plan.retained_paths, vec!["level.dat".to_string(), "region/r.0.0.mca".to_string()]);
    assert!(plan.non_region_pack_download.clone().flatten().is_none());
}

#[tokio::test]
async fn region_uploads_expose_delta_candidates_and_cold_downloads_get_a_chain() {
    let f = Fixture::r2().await;
    let snap1 = f
        .finalize(
            None,
            vec![pack(
                BUNDLE_ID,
                "basehash",
                128,
                "region-bundles/full/ba/basehash.bundle",
                FileTransferMode::RegionFull,
                &[("region/r.0.0.mca", "basehash")],
            )],
            1_000,
        )
        .await;
    let mut delta = pack(
        BUNDLE_ID,
        "newhash",
        130,
        "region-bundles/delta/ba/basehash-newhash.bin",
        FileTransferMode::RegionDelta,
        &[("region/r.0.0.mca", "newhash")],
    );
    delta.base_snapshot_id = Some(snap1.clone());
    delta.base_hash = Some("basehash".into());
    delta.chain_depth = Some(1);
    f.finalize(Some(&snap1), vec![delta], 2_000).await;

    let upload = f
        .prepare(
            &owner(),
            &f.upload_request(
                None,
                vec![local_pack(BUNDLE_ID, "thirdhash", 132, &[("region/r.0.0.mca", "thirdhash")])],
            ),
        )
        .await
        .unwrap();
    let bundle = &upload.region_bundle_uploads.clone().unwrap()[0];
    assert!(bundle.full_storage_key.as_deref().unwrap().contains("region-bundles/full/"));
    assert!(bundle.delta_storage_key.as_deref().unwrap().contains("region-bundles/delta/"));
    assert_eq!(bundle.base_hash.as_deref(), Some("newhash"));

    // Warm: the client already has the base, so only the delta ships.
    let warm = f
        .download(
            &owner(),
            &UploadPlanRequest {
                files: vec![local_file("region/r.0.0.mca", "basehash")],
                region_bundles: Some(vec![local_pack(BUNDLE_ID, "basehash", 128, &[])]),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let warm_steps = &warm.region_bundle_downloads.clone().unwrap()[0].steps;
    assert_eq!(warm_steps.len(), 1);
    assert_eq!(warm_steps[0].transfer_mode, FileTransferMode::RegionDelta);

    // Cold: full anchor then delta, oldest first.
    let cold = f.download(&owner(), &UploadPlanRequest::default()).await.unwrap();
    let cold_steps = &cold.region_bundle_downloads.clone().unwrap()[0].steps;
    assert_eq!(cold_steps.len(), 2);
    assert_eq!(cold_steps[0].transfer_mode, FileTransferMode::RegionFull);
    assert_eq!(cold_steps[1].transfer_mode, FileTransferMode::RegionDelta);
    assert!(cold_steps[0].download.headers.contains_key("x-sharedworld-blob-stamp"));
}

#[tokio::test]
async fn non_region_packs_plan_delta_uploads_and_warm_download_tails() {
    let f = Fixture::r2().await;
    let members = [("level.dat", "level-base"), ("data/foo.dat", "foo-base")];
    let snap1 = f
        .finalize(
            None,
            vec![pack(
                NON_REGION_PACK_ID,
                "pack-base",
                256,
                "packs/full/pa/pack-base.pack",
                FileTransferMode::PackFull,
                &members,
            )],
            1_000,
        )
        .await;
    let mut delta = pack(
        NON_REGION_PACK_ID,
        "pack-next",
        64,
        "packs/delta/pa/pack-base-pack-next.bin",
        FileTransferMode::PackDelta,
        &[("level.dat", "level-next"), ("data/foo.dat", "foo-next")],
    );
    delta.base_snapshot_id = Some(snap1.clone());
    delta.base_hash = Some("pack-base".into());
    delta.chain_depth = Some(1);
    f.finalize(Some(&snap1), vec![delta], 2_000).await;

    let upload = f
        .prepare(
            &owner(),
            &f.upload_request(
                Some(local_pack(
                    NON_REGION_PACK_ID,
                    "pack-third",
                    260,
                    &[("level.dat", "level-third"), ("data/foo.dat", "foo-third")],
                )),
                vec![],
            ),
        )
        .await
        .unwrap();
    let plan = upload.non_region_pack_upload.clone().flatten().unwrap();
    assert!(plan.full_storage_key.as_deref().unwrap().contains("packs/full/"));
    assert!(plan.delta_storage_key.as_deref().unwrap().contains("packs/delta/"));
    assert_eq!(plan.base_hash.as_deref(), Some("pack-next"));
    assert_eq!(plan.base_chain_depth, Some(1));
    assert_eq!(plan.transfer_mode, Some(FileTransferMode::PackFull));
    // Both slots are unsigned-if-present; nothing is stored here yet.
    assert!(plan.full_upload.is_some() && plan.delta_upload.is_some());

    let warm = f
        .download(
            &owner(),
            &UploadPlanRequest {
                files: vec![local_file("level.dat", "level-base"), local_file("data/foo.dat", "foo-base")],
                non_region_pack: Some(local_pack(NON_REGION_PACK_ID, "pack-base", 256, &[])),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let warm_steps = &warm.non_region_pack_download.clone().flatten().unwrap().steps;
    assert_eq!(warm_steps.len(), 1);
    assert_eq!(warm_steps[0].transfer_mode, FileTransferMode::PackDelta);

    let cold = f.download(&owner(), &UploadPlanRequest::default()).await.unwrap();
    let cold_steps = &cold.non_region_pack_download.clone().flatten().unwrap().steps;
    assert_eq!(cold_steps.len(), 2);
    assert_eq!(cold_steps[0].transfer_mode, FileTransferMode::PackFull);
    assert_eq!(cold_steps[1].transfer_mode, FileTransferMode::PackDelta);
}

#[tokio::test]
async fn an_already_stored_full_key_is_not_signed_again() {
    let f = Fixture::r2().await;
    f.seed_blob("packs/full/ab/abc.pack", "stored").await;
    let plan = f
        .prepare(
            &owner(),
            &f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "abc", 6, &[("level.dat", "l")])), vec![]),
        )
        .await
        .unwrap()
        .non_region_pack_upload
        .clone()
        .flatten()
        .unwrap();
    assert_eq!(plan.full_storage_key.as_deref(), Some("packs/full/ab/abc.pack"));
    assert!(plan.full_upload.is_none(), "already present keys get no signed slot");
}

#[tokio::test]
async fn a_plan_forcing_an_oversized_full_upload_fails_with_blob_too_large() {
    // What a pre-sharding client with an oversized superpack receives at plan
    // time, instead of a bare 413 from the edge mid-upload.
    let f = Fixture::r2().await;
    let err = f
        .prepare(
            &owner(),
            &f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "huge-pack", 120_000_000, &[])), vec![]),
        )
        .await
        .unwrap_err();
    assert_eq!(err.status, 413);
    assert_eq!(err.code, "blob_too_large");
    assert!(err.message.contains("limited to 95 MB per blob"));
    assert!(err.message.contains("Update the SharedWorld mod"));

    let err = f
        .prepare(
            &owner(),
            &f.upload_request(
                None,
                vec![local_pack("region-bundle:superpack:entities", "huge-shard", 120_000_000, &[])],
            ),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code, "blob_too_large");

    // Under the limit the plan goes through untouched.
    let plan = f
        .prepare(
            &owner(),
            &f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "ok-pack", 10_000_000, &[])), vec![]),
        )
        .await
        .unwrap();
    assert!(plan
        .non_region_pack_upload
        .clone()
        .flatten()
        .unwrap()
        .full_storage_key
        .as_deref()
        .unwrap()
        .contains("packs/full/"));
}

#[tokio::test]
async fn a_direct_capable_world_lifts_the_relay_ceiling_for_v2_clients() {
    let f = Fixture::drive().await;
    let ctx = v2(OWNER_UUID, "Owner");
    let plan = f
        .prepare(
            &ctx,
            &f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "huge-pack", 120_000_000, &[])), vec![]),
        )
        .await
        .unwrap();
    let direct = plan.direct_upload.clone().flatten().unwrap();
    assert_eq!(direct.chunk_size_bytes, 16 * 1024 * 1024);
    assert_eq!(direct.chunk_size_bytes % (256 * 1024), 0);
    assert_eq!(direct.max_upload_bytes, None);

    // A pre-0.4.0 client on the same world still hits the relay ceiling.
    let err = f
        .prepare(
            &owner(),
            &f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "huge-pack", 120_000_000, &[])), vec![]),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code, "blob_too_large");
    assert!(err.message.contains("Update the SharedWorld mod"));
}

#[tokio::test]
async fn relay_only_worlds_advertise_no_direct_upload_policy() {
    let f = Fixture::r2().await;
    let plan = f.prepare(&v2(OWNER_UUID, "Owner"), &f.upload_request(None, vec![])).await.unwrap();
    assert_eq!(plan.direct_upload, Some(None));
}

#[tokio::test]
async fn an_unreconstructable_delta_pack_is_planned_as_a_fresh_full() {
    let f = Fixture::r2().await;
    let snap1 = f
        .finalize(
            None,
            vec![pack(
                NON_REGION_PACK_ID,
                "pack-a",
                100,
                "packs/full/pa/pack-a.pack",
                FileTransferMode::PackFull,
                &[("level.dat", "a")],
            )],
            1_000,
        )
        .await;
    let mut delta = pack(
        NON_REGION_PACK_ID,
        "pack-b",
        20,
        "packs/delta/pa/pack-a-pack-b.bin",
        FileTransferMode::PackDelta,
        &[("level.dat", "b")],
    );
    delta.base_snapshot_id = Some(snap1.clone());
    delta.base_hash = Some("pack-a".into());
    delta.chain_depth = Some(1);
    f.finalize(Some(&snap1), vec![delta], 2_000).await;
    // The base snapshot row disappears (manual delete / legacy retention).
    f.env.repo.delete_snapshots(&f.world_id, std::slice::from_ref(&snap1)).await.unwrap();

    let plan = f
        .prepare(
            &owner(),
            &f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "pack-c", 100, &[])), vec![]),
        )
        .await
        .unwrap()
        .non_region_pack_upload
        .clone()
        .flatten()
        .unwrap();
    assert_eq!(plan.delta_storage_key, None, "no chainable base survives");
    assert_eq!(plan.base_hash, None);
    assert!(plan.full_storage_key.is_some());
}

#[tokio::test]
async fn a_broken_chain_refuses_the_download_plan() {
    let f = Fixture::r2().await;
    let snap1 = f
        .finalize(
            None,
            vec![pack(
                NON_REGION_PACK_ID,
                "pack-a",
                100,
                "packs/full/pa/pack-a.pack",
                FileTransferMode::PackFull,
                &[("level.dat", "a")],
            )],
            1_000,
        )
        .await;
    let mut delta = pack(
        NON_REGION_PACK_ID,
        "pack-b",
        20,
        "packs/delta/pa/pack-a-pack-b.bin",
        FileTransferMode::PackDelta,
        &[("level.dat", "b")],
    );
    delta.base_snapshot_id = Some(snap1.clone());
    delta.base_hash = Some("pack-a".into());
    delta.chain_depth = Some(1);
    f.finalize(Some(&snap1), vec![delta], 2_000).await;
    f.env.repo.delete_snapshots(&f.world_id, &[snap1]).await.unwrap();

    let err = f.download(&owner(), &UploadPlanRequest::default()).await.unwrap_err();
    assert_eq!(err.status, 409);
    assert_eq!(err.code, "snapshot_chain_broken");
}

#[tokio::test]
async fn a_stamped_chain_survives_its_base_snapshots_deletion() {
    let f = Fixture::r2().await;
    let snap_a = f
        .finalize(
            None,
            vec![pack(
                NON_REGION_PACK_ID,
                "pack-a",
                100,
                "packs/full/a.pack",
                FileTransferMode::PackFull,
                &[("level.dat", "level-a")],
            )],
            1_000,
        )
        .await;
    let mut delta = pack(
        NON_REGION_PACK_ID,
        "pack-b",
        20,
        "packs/delta/a-b.bin",
        FileTransferMode::PackDelta,
        &[("level.dat", "level-b")],
    );
    delta.base_snapshot_id = Some(snap_a.clone());
    delta.base_hash = Some("pack-a".into());
    delta.chain_depth = Some(1);
    // The self-contained recipe finalize stamps onto the pack.
    delta.chain_steps = Some(vec![
        PackChainStep {
            storage_key: "packs/full/a.pack".into(),
            hash: "pack-a".into(),
            base_hash: None,
            transfer_mode: FileTransferMode::PackFull,
            size: 100,
            delta_format_version: None,
        },
        PackChainStep {
            storage_key: "packs/delta/a-b.bin".into(),
            hash: "pack-b".into(),
            base_hash: Some("pack-a".into()),
            transfer_mode: FileTransferMode::PackDelta,
            size: 20,
            delta_format_version: None,
        },
    ]);
    f.finalize(Some(&snap_a), vec![delta], 2_000).await;
    f.env.repo.delete_snapshots(&f.world_id, &[snap_a]).await.unwrap();

    // The plan builds from the recipe: no base snapshot rows involved.
    let plan = f.download(&owner(), &UploadPlanRequest::default()).await.unwrap();
    let steps = &plan.non_region_pack_download.clone().flatten().unwrap().steps;
    assert_eq!(
        steps.iter().map(|s| s.storage_key.clone()).collect::<Vec<_>>(),
        vec!["packs/full/a.pack".to_string(), "packs/delta/a-b.bin".to_string()]
    );
    assert_eq!(steps[0].transfer_mode, FileTransferMode::PackFull);
    assert_eq!(steps[1].base_hash.as_deref(), Some("pack-a"));
    assert_eq!(steps[0].base_snapshot_id, None, "recipe steps are snapshot-independent");

    // A client already holding the anchor gets only the tail.
    let warm = f
        .download(
            &owner(),
            &UploadPlanRequest {
                non_region_pack: Some(local_pack(NON_REGION_PACK_ID, "pack-a", 100, &[])),
                files: vec![local_file("level.dat", "level-a")],
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let warm_steps = &warm.non_region_pack_download.clone().flatten().unwrap().steps;
    assert_eq!(warm_steps.len(), 1);
    assert_eq!(warm_steps[0].storage_key, "packs/delta/a-b.bin");
}

// ---------------------------------------------------------------------------
// Delta v2 gating
// ---------------------------------------------------------------------------

async fn v2_fixture() -> (Fixture, String) {
    let f = Fixture::r2().await;
    let snap = f
        .finalize(
            None,
            vec![pack(
                NON_REGION_PACK_ID,
                "full1",
                1000,
                "packs/full/fu/full1.pack",
                FileTransferMode::PackFull,
                &[("level.dat", "member")],
            )],
            1_000,
        )
        .await;
    (f, snap)
}

#[tokio::test]
async fn capable_clients_get_delta2_slots_and_old_clients_keep_v1() {
    let (f, _) = v2_fixture().await;
    let request = f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "next", 1000, &[])), vec![]);

    let plan = f.prepare(&v2(OWNER_UUID, "Owner"), &request).await.unwrap();
    let v2_plan = plan.non_region_pack_upload.clone().flatten().unwrap();
    assert!(v2_plan.delta_storage_key.as_deref().unwrap().contains("packs/delta2/"));
    assert_eq!(v2_plan.delta_format_version, Some(2));

    let legacy =
        f.prepare(&owner(), &request).await.unwrap().non_region_pack_upload.clone().flatten().unwrap();
    assert!(legacy.delta_storage_key.as_deref().unwrap().contains("packs/delta/"));
    assert_eq!(legacy.delta_format_version, None);
}

#[tokio::test]
async fn the_delta_byte_budget_forces_a_re_full() {
    let (f, snap) = v2_fixture().await;
    let mut delta = pack(
        NON_REGION_PACK_ID,
        "d1",
        1000,
        "packs/delta2/fu/full1-d1.bin",
        FileTransferMode::PackDelta,
        &[("level.dat", "member-2")],
    );
    delta.base_snapshot_id = Some(snap.clone());
    delta.base_hash = Some("full1".into());
    delta.chain_depth = Some(1);
    delta.delta_format_version = Some(2);
    delta.delta_blob_size = Some(350);
    // 350 <= 0.4 × 1000: still under budget.
    delta.chain_delta_bytes = Some(350);
    let second = f.finalize(Some(&snap), vec![delta.clone()], 2_000).await;

    let request = f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "d3", 1000, &[])), vec![]);
    let under = f.prepare(&v2(OWNER_UUID, "Owner"), &request).await.unwrap();
    assert!(under
        .non_region_pack_upload
        .clone()
        .flatten()
        .unwrap()
        .delta_storage_key
        .as_deref()
        .unwrap()
        .contains("delta2/"));

    // A further delta blows the budget (450 > 400): the slot disappears.
    let mut over_delta = delta.clone();
    over_delta.hash = "d2".into();
    over_delta.storage_key = "packs/delta2/d1/d1-d2.bin".into();
    over_delta.base_snapshot_id = Some(second.clone());
    over_delta.base_hash = Some("d1".into());
    over_delta.chain_depth = Some(2);
    over_delta.chain_delta_bytes = Some(450);
    f.finalize(Some(&second), vec![over_delta], 3_000).await;

    let over = f.prepare(&v2(OWNER_UUID, "Owner"), &request).await.unwrap();
    let plan = over.non_region_pack_upload.clone().flatten().unwrap();
    assert_eq!(plan.delta_storage_key, None);
    assert!(plan.full_storage_key.as_deref().unwrap().contains("packs/full/"));
}

#[tokio::test]
async fn a_legacy_v1_chain_with_a_null_accumulator_forces_a_capable_client_to_full() {
    let (f, snap) = v2_fixture().await;
    let mut delta = pack(
        NON_REGION_PACK_ID,
        "v1d",
        1000,
        "packs/delta/fu/full1-v1d.bin",
        FileTransferMode::PackDelta,
        &[("level.dat", "member-2")],
    );
    delta.base_snapshot_id = Some(snap.clone());
    delta.base_hash = Some("full1".into());
    delta.chain_depth = Some(1);
    // NULL accumulator: a legacy/v1 base carries no byte accounting.
    delta.chain_delta_bytes = None;
    f.finalize(Some(&snap), vec![delta], 2_000).await;

    let plan = f
        .prepare(
            &v2(OWNER_UUID, "Owner"),
            &f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "next", 1000, &[])), vec![]),
        )
        .await
        .unwrap()
        .non_region_pack_upload
        .clone()
        .flatten()
        .unwrap();
    assert_eq!(plan.delta_storage_key, None);
}

#[tokio::test]
async fn a_v1_client_meeting_a_v2_step_gets_client_update_required() {
    let (f, snap) = v2_fixture().await;
    let mut delta = pack(
        NON_REGION_PACK_ID,
        "d1",
        1000,
        "packs/delta2/fu/full1-d1.bin",
        FileTransferMode::PackDelta,
        &[("level.dat", "member-2")],
    );
    delta.base_snapshot_id = Some(snap.clone());
    delta.base_hash = Some("full1".into());
    delta.chain_depth = Some(1);
    delta.delta_format_version = Some(2);
    delta.chain_delta_bytes = Some(400);
    f.finalize(Some(&snap), vec![delta], 2_000).await;

    let err = f.download(&owner(), &UploadPlanRequest::default()).await.unwrap_err();
    assert_eq!(err.status, 409);
    assert_eq!(err.code, "client_update_required");

    let capable = f.download(&v2(OWNER_UUID, "Owner"), &UploadPlanRequest::default()).await.unwrap();
    let steps = &capable.non_region_pack_download.clone().flatten().unwrap().steps;
    assert_eq!(steps.iter().map(|s| s.delta_format_version).collect::<Vec<_>>(), vec![None, Some(2)]);
}

#[tokio::test]
async fn the_v1_refusal_also_covers_stamped_recipes() {
    let (f, snap) = v2_fixture().await;
    let mut delta = pack(
        NON_REGION_PACK_ID,
        "d1",
        1000,
        "packs/delta2/fu/full1-d1.bin",
        FileTransferMode::PackDelta,
        &[("level.dat", "member-2")],
    );
    delta.base_snapshot_id = Some(snap.clone());
    delta.base_hash = Some("full1".into());
    delta.chain_depth = Some(1);
    delta.delta_format_version = Some(2);
    delta.chain_steps = Some(vec![
        PackChainStep {
            storage_key: "packs/full/fu/full1.pack".into(),
            hash: "full1".into(),
            base_hash: None,
            transfer_mode: FileTransferMode::PackFull,
            size: 1000,
            delta_format_version: None,
        },
        PackChainStep {
            storage_key: "packs/delta2/fu/full1-d1.bin".into(),
            hash: "d1".into(),
            base_hash: Some("full1".into()),
            transfer_mode: FileTransferMode::PackDelta,
            size: 400,
            delta_format_version: Some(2),
        },
    ]);
    f.finalize(Some(&snap), vec![delta], 2_000).await;

    let err = f.download(&owner(), &UploadPlanRequest::default()).await.unwrap_err();
    assert_eq!(err.code, "client_update_required");
    let capable = f.download(&v2(OWNER_UUID, "Owner"), &UploadPlanRequest::default()).await.unwrap();
    assert_eq!(capable.non_region_pack_download.clone().flatten().unwrap().steps.len(), 2);
}

// ---------------------------------------------------------------------------
// Relayed blob transfer
// ---------------------------------------------------------------------------

const BLOB_KEY: &str = "packs/full/ab/abcdef.pack";

async fn blob_fixture() -> Fixture {
    let f = Fixture::r2().await;
    f.seed_blob(BLOB_KEY, "0123456789").await;
    f
}

async fn get_blob(
    f: &Fixture,
    ctx: &RequestContext,
    input: RelayDownloadInput,
) -> (u16, Option<String>, String) {
    let blob = sync_plan::download_storage_blob(&f.env.svc, ctx, &f.world_id, BLOB_KEY, &input, time::now())
        .await
        .expect("blob");
    let (status, range) = (blob.status, blob.content_range.clone());
    let bytes = blob.into_bytes().await.unwrap();
    (status, range, String::from_utf8(bytes.to_vec()).unwrap())
}

#[tokio::test]
async fn no_range_serves_the_whole_blob() {
    let f = blob_fixture().await;
    let (status, range, body) = get_blob(&f, &owner(), RelayDownloadInput::default()).await;
    assert_eq!(status, 200);
    assert_eq!(range, None);
    assert_eq!(body, "0123456789");
}

#[tokio::test]
async fn an_open_range_resumes_from_the_offset() {
    let f = blob_fixture().await;
    let (status, range, body) =
        get_blob(&f, &owner(), RelayDownloadInput { range: Some("bytes=4-".into()), ..Default::default() })
            .await;
    assert_eq!(status, 206);
    assert_eq!(range.as_deref(), Some("bytes 4-9/10"));
    assert_eq!(body, "456789");
}

#[tokio::test]
async fn a_bounded_range_serves_exactly_the_requested_slice() {
    let f = blob_fixture().await;
    let (status, range, body) =
        get_blob(&f, &owner(), RelayDownloadInput { range: Some("bytes=2-5".into()), ..Default::default() })
            .await;
    assert_eq!(status, 206);
    assert_eq!(range.as_deref(), Some("bytes 2-5/10"));
    assert_eq!(body, "2345");
}

#[tokio::test]
async fn a_malformed_range_falls_back_to_the_full_200() {
    let f = blob_fixture().await;
    for header in ["bytes=5-2", "bytes=-500", "bytes=0-1,4-5", "items=0-4", "garbage", ""] {
        let (status, _, body) =
            get_blob(&f, &owner(), RelayDownloadInput { range: Some(header.into()), ..Default::default() })
                .await;
        assert_eq!(status, 200, "header {header:?}");
        assert_eq!(body, "0123456789");
    }
}

#[tokio::test]
async fn a_range_past_the_end_is_416() {
    let f = blob_fixture().await;
    let err = sync_plan::download_storage_blob(
        &f.env.svc,
        &owner(),
        &f.world_id,
        BLOB_KEY,
        &RelayDownloadInput { range: Some("bytes=10-".into()), ..Default::default() },
        time::now(),
    )
    .await
    .err()
    .expect("416");
    assert_eq!(err.status, 416);
    assert_eq!(err.code, "range_not_satisfiable");
}

#[tokio::test]
async fn a_missing_blob_is_404_blob_not_found() {
    let f = Fixture::r2().await;
    let err = sync_plan::download_storage_blob(
        &f.env.svc,
        &owner(),
        &f.world_id,
        "packs/full/zz/nope.pack",
        &RelayDownloadInput::default(),
        time::now(),
    )
    .await
    .err()
    .expect("404");
    assert_eq!(err.status, 404);
    assert_eq!(err.code, "blob_not_found");
}

#[tokio::test]
async fn a_download_stamp_alone_serves_the_blob_to_a_non_member() {
    let f = blob_fixture().await;
    let stranger = ctx("third-uuid", "Third");
    let stamp = sw_core::stamp::mint_download_stamp(
        &f.env.svc.stamp_keys,
        &f.world_id,
        BLOB_KEY,
        &stranger.player_uuid,
        time::now(),
    )
    .expect("stamp");

    // The coordinator path refuses a non-member, so success proves the
    // stamped fast path decided alone.
    let (status, _, body) =
        get_blob(&f, &stranger, RelayDownloadInput { blob_stamp: Some(stamp.clone()), ..Default::default() })
            .await;
    assert_eq!(status, 200);
    assert_eq!(body, "0123456789");

    // The same stamp is useless to another player and for another key.
    let other = ctx("player-other", "Other");
    let err = sw_core::stamp::verify_download_stamp(
        &f.env.svc.stamp_keys,
        &stamp,
        &f.world_id,
        BLOB_KEY,
        &other.player_uuid,
        time::now(),
    );
    assert!(!err);
    let refused = sync_plan::download_storage_blob(
        &f.env.svc,
        &other,
        &f.world_id,
        BLOB_KEY,
        &RelayDownloadInput { blob_stamp: Some(stamp), ..Default::default() },
        time::now(),
    )
    .await
    .err()
    .expect("refused");
    assert_eq!(refused.status, 403);
}

async fn upload_input(f: &Fixture, text: &str, stamp: Option<String>, authority: bool) -> RelayUploadInput {
    let (epoch, token) = if authority { f.authority() } else { (None, None) };
    RelayUploadInput {
        content_length: Some(text.len() as i64),
        content_type: Some("application/octet-stream".into()),
        runtime_epoch: epoch,
        host_token: token,
        blob_stamp: stamp,
        body: stream(text),
    }
}

#[tokio::test]
async fn a_blob_stamp_alone_authorizes_the_relay_put() {
    let f = Fixture::r2().await;
    let plan = f
        .prepare(&owner(), &f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "hash-1", 7, &[])), vec![]))
        .await
        .unwrap()
        .non_region_pack_upload
        .clone()
        .flatten()
        .unwrap();
    let key = plan.full_storage_key.clone().unwrap();
    let stamp = plan.full_upload.unwrap().headers.get("x-sharedworld-blob-stamp").cloned().unwrap();

    // Without epoch/token the coordinator path would refuse outright.
    sync_plan::upload_storage_blob(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &key,
        upload_input(&f, "payload", Some(stamp.clone()), false).await,
        time::now(),
    )
    .await
    .expect("stamped PUT");
    assert_eq!(f.env.fs.read_all(&key).await.unwrap().unwrap(), Bytes::from_static(b"payload"));

    // A stamp minted for a different key falls back and is refused.
    let err = sync_plan::upload_storage_blob(
        &f.env.svc,
        &owner(),
        &f.world_id,
        "packs/full/other-key.pack",
        upload_input(&f, "payload", Some(stamp), false).await,
        time::now(),
    )
    .await
    .unwrap_err();
    assert_eq!(err.status, 409);

    // A stale-epoch stamp falls back too.
    let stale =
        sw_core::stamp::mint_blob_stamp(&f.env.svc.stamp_keys, &f.world_id, 99, &key, time::now()).unwrap();
    let err = sync_plan::upload_storage_blob(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &key,
        upload_input(&f, "payload", Some(stale), false).await,
        time::now(),
    )
    .await
    .unwrap_err();
    assert_eq!(err.status, 409);
}

#[tokio::test]
async fn without_a_signing_secret_plans_carry_no_stamp_and_the_legacy_path_works() {
    let env = TestEnv::with_config(sw_core::Config { signing_secret: None, ..sw_core::Config::dev() }).await;
    let f = Fixture::build(env, false).await;
    let plan = f
        .prepare(&owner(), &f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "hash-1", 7, &[])), vec![]))
        .await
        .unwrap()
        .non_region_pack_upload
        .clone()
        .flatten()
        .unwrap();
    let upload = plan.full_upload.unwrap();
    assert!(!upload.headers.contains_key("x-sharedworld-blob-stamp"));
    assert_eq!(upload.headers.get("x-sharedworld-runtime-epoch"), Some(&f.epoch.to_string()));

    sync_plan::upload_storage_blob(
        &f.env.svc,
        &owner(),
        &f.world_id,
        plan.full_storage_key.as_deref().unwrap(),
        upload_input(&f, "payload", None, true).await,
        time::now(),
    )
    .await
    .expect("epoch/token PUT");
}

#[tokio::test]
async fn a_relay_put_over_the_body_limit_is_413() {
    let f = Fixture::r2().await;
    let mut input = upload_input(&f, "payload", None, true).await;
    input.content_length = Some(120_000_000);
    let err = sync_plan::upload_storage_blob(&f.env.svc, &owner(), &f.world_id, BLOB_KEY, input, time::now())
        .await
        .unwrap_err();
    assert_eq!(err.status, 413);
    assert_eq!(err.code, "blob_too_large");
    assert!(err.message.contains("limited to 95 MB per blob"));
}

#[tokio::test]
async fn a_relay_put_without_a_content_length_buffers_once_and_still_stores() {
    let f = Fixture::r2().await;
    let mut input = upload_input(&f, "chunked-body", None, true).await;
    input.content_length = None;
    sync_plan::upload_storage_blob(&f.env.svc, &owner(), &f.world_id, BLOB_KEY, input, time::now())
        .await
        .expect("chunked PUT");
    assert_eq!(f.env.fs.read_all(BLOB_KEY).await.unwrap().unwrap(), Bytes::from_static(b"chunked-body"));
}

// ---------------------------------------------------------------------------
// Direct upload sessions
// ---------------------------------------------------------------------------

const SESSION_KEY: &str = "packs/full/aa/aaaa.pack";

fn session_request(f: &Fixture, key: &str, len: i64) -> CreateBlobSessionRequest {
    CreateBlobSessionRequest {
        storage_key: Some(serde_json::json!(key)),
        runtime_epoch: Some(f.epoch),
        host_token: Some(f.token.clone()),
        content_type: Some(serde_json::json!("application/octet-stream")),
        content_length: Some(serde_json::json!(len)),
        blob_stamp: None,
    }
}

fn commit_request(f: &Fixture, upload_id: &str) -> CommitBlobSessionRequest {
    CommitBlobSessionRequest {
        upload_id: Some(serde_json::json!(upload_id)),
        runtime_epoch: Some(f.epoch),
        host_token: Some(f.token.clone()),
        blob_stamp: None,
    }
}

#[tokio::test]
async fn session_then_commit_registers_the_object_idempotently() {
    let f = Fixture::drive().await;
    let session = sync_plan::create_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &session_request(&f, SESSION_KEY, 1000),
        time::now(),
    )
    .await
    .unwrap();
    assert!(session.session_url.contains("drive.invalid/session/"));
    assert_eq!(session.chunk_size_bytes, 16 * 1024 * 1024);

    // The client PUTs its chunks straight at the provider session.
    f.env.drive().append_chunk(&session.session_url, &vec![0u8; 1000]);

    let committed = sync_plan::commit_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &commit_request(&f, &session.upload_id),
        time::now(),
    )
    .await
    .unwrap();
    assert_eq!(committed.storage_key, SESSION_KEY);
    assert_eq!(committed.size, 1000);
    let account = f.env.repo.get_world_storage_binding(&f.world_id).await.unwrap().unwrap();
    let row = f
        .env
        .repo
        .get_storage_object(
            StorageProviderType::GoogleDrive,
            account.storage_account_id.as_deref().unwrap(),
            SESSION_KEY,
        )
        .await
        .unwrap()
        .unwrap();
    assert_eq!(row.size, 1000);

    // A lost response is safely retried.
    let again = sync_plan::commit_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &commit_request(&f, &session.upload_id),
        time::now(),
    )
    .await
    .unwrap();
    assert_eq!(again.size, 1000);
}

#[tokio::test]
async fn committing_a_partial_upload_is_a_retryable_409() {
    let f = Fixture::drive().await;
    let session = sync_plan::create_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &session_request(&f, SESSION_KEY, 1000),
        time::now(),
    )
    .await
    .unwrap();
    f.env.drive().append_chunk(&session.session_url, &vec![0u8; 400]);

    let err = sync_plan::commit_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &commit_request(&f, &session.upload_id),
        time::now(),
    )
    .await
    .unwrap_err();
    assert_eq!(err.status, 409);
    assert_eq!(err.code, "upload_incomplete");
    assert!(err.message.contains("only 400 of 1000 bytes"));
}

#[tokio::test]
async fn committing_an_expired_session_is_410_and_forgets_it() {
    let f = Fixture::drive().await;
    let session = sync_plan::create_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &session_request(&f, SESSION_KEY, 1000),
        time::now(),
    )
    .await
    .unwrap();
    f.env.drive().expire_session(&session.session_url);

    let err = sync_plan::commit_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &commit_request(&f, &session.upload_id),
        time::now(),
    )
    .await
    .unwrap_err();
    assert_eq!(err.status, 410);
    assert_eq!(err.code, "upload_session_expired");
    assert!(f.env.repo.get_upload_session(&session.upload_id).await.unwrap().is_none());
}

#[tokio::test]
async fn a_size_mismatch_deletes_the_stored_object_and_fails_the_commit() {
    let f = Fixture::drive().await;
    let session = sync_plan::create_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &session_request(&f, SESSION_KEY, 1000),
        time::now(),
    )
    .await
    .unwrap();
    f.env.drive().complete_session(&session.session_url, "file-bad", 999);

    let err = sync_plan::commit_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &commit_request(&f, &session.upload_id),
        time::now(),
    )
    .await
    .unwrap_err();
    assert_eq!(err.status, 409);
    assert_eq!(err.code, "upload_size_mismatch");
    assert_eq!(f.env.drive().deleted_file_ids(), vec!["file-bad".to_string()]);
    assert!(f.env.repo.get_upload_session(&session.upload_id).await.unwrap().is_none());
}

#[tokio::test]
async fn stale_authority_cannot_open_or_commit_sessions() {
    let f = Fixture::drive().await;
    let mut request = session_request(&f, SESSION_KEY, 10);
    request.runtime_epoch = Some(f.epoch + 1);
    request.host_token = Some("wrong".into());
    let err = sync_plan::create_blob_upload_session(&f.env.svc, &owner(), &f.world_id, &request, time::now())
        .await
        .unwrap_err();
    assert_eq!(err.status, 409);
}

#[tokio::test]
async fn sessions_validate_the_storage_key_and_the_declared_size() {
    let f = Fixture::drive().await;
    let err = sync_plan::create_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &session_request(&f, "   ", 10),
        time::now(),
    )
    .await
    .unwrap_err();
    assert_eq!(err.status, 400);
    assert_eq!(err.code, "invalid_storage_key");

    let err = sync_plan::create_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &session_request(&f, SESSION_KEY, 0),
        time::now(),
    )
    .await
    .unwrap_err();
    assert_eq!(err.status, 400);
    assert_eq!(err.code, "invalid_upload_size");
}

#[tokio::test]
async fn a_relay_only_world_refuses_direct_upload_sessions() {
    let f = Fixture::r2().await;
    let err = sync_plan::create_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &session_request(&f, SESSION_KEY, 10),
        time::now(),
    )
    .await
    .unwrap_err();
    assert_eq!(err.status, 409);
    assert_eq!(err.code, "direct_upload_unsupported");
}

#[tokio::test]
async fn session_init_sweeps_stale_unconfirmed_sessions_for_the_account() {
    let f = Fixture::drive().await;
    let binding = f.env.repo.get_world_storage_binding(&f.world_id).await.unwrap().unwrap();
    let account_id = binding.storage_account_id.clone().unwrap();
    // A completed-but-never-confirmed session left a provider file behind.
    let stale_url = f
        .env
        .drive()
        .create_resumable_session(&binding, "packs/full/zz/old.pack", "application/octet-stream", 50)
        .await
        .unwrap();
    f.env.drive().complete_session(&stale_url, "orphan-file", 50);
    f.env
        .repo
        .create_upload_session(sw_db::repo::StorageUploadSessionRecord {
            upload_id: "upl_stale".into(),
            provider: StorageProviderType::GoogleDrive,
            storage_account_id: account_id,
            world_id: f.world_id.clone(),
            storage_key: "packs/full/zz/old.pack".into(),
            session_url: stale_url,
            content_type: "application/octet-stream".into(),
            expected_size: 50,
            created_at: "2020-01-01T00:00:00.000Z".into(),
            confirmed_at: None,
        })
        .await
        .unwrap();

    sync_plan::create_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &session_request(&f, SESSION_KEY, 10),
        time::now(),
    )
    .await
    .unwrap();

    assert!(f.env.repo.get_upload_session("upl_stale").await.unwrap().is_none());
    assert_eq!(f.env.drive().deleted_file_ids(), vec!["orphan-file".to_string()]);
}

#[tokio::test]
async fn a_full_drive_fails_planning_and_session_creation_with_drive_storage_full() {
    let f = Fixture::drive().await;
    f.env.drive().set_quota(Some(1_000), Some(1_000));

    let err = f.prepare(&owner(), &f.upload_request(None, vec![])).await.unwrap_err();
    assert_eq!(err.status, 403);
    assert_eq!(err.code, "drive_storage_full");
    assert!(err.message.contains("Google Drive is full"));

    let err = sync_plan::create_blob_upload_session(
        &f.env.svc,
        &owner(),
        &f.world_id,
        &session_request(&f, SESSION_KEY, 10),
        time::now(),
    )
    .await
    .unwrap_err();
    assert_eq!(err.code, "drive_storage_full");
}

#[tokio::test]
async fn an_unknown_quota_never_blocks_uploads() {
    let f = Fixture::drive().await;
    f.env.drive().set_quota(None, None);
    let plan = f.prepare(&owner(), &f.upload_request(None, vec![])).await;
    assert!(plan.is_ok());
}

#[tokio::test]
async fn drive_bound_worlds_resolve_existence_from_storage_object_rows() {
    let f = Fixture::drive().await;
    let binding = f.env.repo.get_world_storage_binding(&f.world_id).await.unwrap().unwrap();
    f.env
        .drive()
        .put(
            &binding,
            "packs/full/ab/abc.pack",
            PutBody::Bytes(Bytes::from_static(b"stored")),
            "application/octet-stream",
        )
        .await
        .unwrap();

    let plan = f
        .prepare(&owner(), &f.upload_request(Some(local_pack(NON_REGION_PACK_ID, "abc", 6, &[])), vec![]))
        .await
        .unwrap()
        .non_region_pack_upload
        .clone()
        .flatten()
        .unwrap();
    assert_eq!(plan.full_storage_key.as_deref(), Some("packs/full/ab/abc.pack"));
    assert!(plan.full_upload.is_none(), "the object row is the authoritative index");
}
