use std::collections::HashMap;

use sw_contracts::*;
use sw_db::repo::*;
use sw_db::{migrate, time, Db, Repository};

fn pack(id: &str, hash: &str, key: &str, files: Vec<(&str, &str, i64)>) -> SnapshotPack {
    SnapshotPack {
        pack_id: id.into(),
        hash: hash.into(),
        size: files.iter().map(|f| f.2).sum(),
        storage_key: key.into(),
        transfer_mode: FileTransferMode::PackFull,
        base_snapshot_id: None,
        base_hash: None,
        chain_depth: None,
        delta_format_version: None,
        delta_blob_size: None,
        chain_delta_bytes: None,
        chain_steps: None,
        files: files
            .into_iter()
            .map(|(p, h, s)| PackedManifestFile {
                path: p.into(),
                hash: h.into(),
                size: s,
                content_type: "application/octet-stream".into(),
            })
            .collect(),
    }
}

#[tokio::test]
async fn world_snapshot_lifecycle() {
    let db = Db::open_memory().unwrap();
    migrate::migrate(&db).unwrap();
    let repo = Repository::new(db.clone(), None);
    let now = time::now();
    repo.upsert_user(UserRecord {
        player_uuid: "owner".into(),
        player_name: "Owner".into(),
        created_at: time::to_iso(now),
    })
    .await
    .unwrap();
    repo.upsert_user(UserRecord {
        player_uuid: "guest".into(),
        player_name: "Guest".into(),
        created_at: time::to_iso(now),
    })
    .await
    .unwrap();
    let actor = Actor { player_uuid: "owner".into(), player_name: "Owner".into() };
    let binding =
        WorldStorageBinding { provider: StorageProviderType::GoogleDrive, storage_account_id: None };
    let world =
        repo.create_world(&actor, "My World", "my-world", binding, Some("hello".into()), None).await.unwrap();
    assert_eq!(world.summary.name, "My World");
    assert!(world.summary.slug.starts_with("my-world-"));
    assert_eq!(world.membership.role, MembershipRole::Owner);
    let wid = world.summary.id.clone();

    // memberships + invites
    repo.add_membership(WorldMembership {
        world_id: wid.clone(),
        player_uuid: "guest".into(),
        player_name: "Guest".into(),
        role: MembershipRole::Member,
        joined_at: time::to_iso(now),
        deleted_at: None,
        can_use_commands: false,
    })
    .await
    .unwrap();
    assert!(repo.is_world_member(&wid, "guest").await.unwrap());
    let facts = repo.session_actor_facts(&wid, "guest").await.unwrap().unwrap();
    assert!(facts.membership_active && facts.ever_member);
    assert!(repo.session_actor_facts("nope", "guest").await.unwrap().is_none());
    let list = repo.list_worlds_for_player("guest").await.unwrap();
    assert_eq!(list.len(), 1);
    assert_eq!(list[0].member_count, 2);
    let facts1 = repo.worlds_change_facts("guest").await.unwrap();
    assert!(facts1["worlds"].as_array().unwrap().len() == 1);

    // finalize two snapshots (row mode), second inherits an unchanged pack
    let req1 = FinalizeSnapshotRequest {
        files: vec![ManifestFile {
            path: "level.dat".into(),
            hash: "h-level".into(),
            size: 10,
            compressed_size: 8,
            storage_key: "files/h-level".into(),
            content_type: "application/octet-stream".into(),
            transfer_mode: Some(FileTransferMode::WholeGzip),
            base_snapshot_id: None,
            base_hash: None,
            chain_depth: None,
        }],
        packs: Some(vec![
            pack("non-region", "p1", "packs/full/p1/p1.pack", vec![("a.txt", "ha", 5), ("b.txt", "hb", 7)]),
            pack(
                "region-bundle:region:1:1",
                "r1",
                "region-bundles/full/r1/r1.bundle",
                vec![("r.1.1.mca", "hr", 100)],
            ),
        ]),
        ..Default::default()
    };
    let m1 = repo.finalize_snapshot(&wid, &actor, &req1, now, None).await.unwrap();
    assert_eq!(m1.packs.len(), 2);
    assert_eq!(m1.packs[0].pack_id, "non-region");
    assert_eq!(m1.packs[0].files.len(), 2);
    assert_eq!(m1.files.len(), 1);

    let mut req2 = req1.clone();
    req2.base_snapshot_id = Some(m1.snapshot_id.clone());
    req2.packs.as_mut().unwrap()[1] = pack(
        "region-bundle:region:1:1",
        "r2",
        "region-bundles/full/r2/r2.bundle",
        vec![("r.1.1.mca", "hr2", 120)],
    );
    let m2 =
        repo.finalize_snapshot(&wid, &actor, &req2, now + chrono::Duration::seconds(1), None).await.unwrap();
    assert_eq!(m2.packs[0].files.len(), 2, "inherited members resolve from donor");
    assert_eq!(m2.packs[1].hash, "r2");
    let latest = repo.get_latest_snapshot(&wid).await.unwrap().unwrap();
    assert_eq!(latest.snapshot_id, m2.snapshot_id);
    let headers = repo.get_latest_snapshot_headers(&wid).await.unwrap().unwrap();
    assert!(headers.packs.iter().all(|p| p.files.is_empty()));
    let batch =
        repo.get_snapshot_headers_batch(&wid, &[m1.snapshot_id.clone(), "missing".into()]).await.unwrap();
    assert_eq!(batch.len(), 1);

    let summaries = repo.list_snapshot_summaries(&wid).await.unwrap();
    assert_eq!(summaries.len(), 2);
    assert!(summaries[0].is_latest);
    assert_eq!(summaries[0].file_count, 1 + 2 + 1);
    assert_eq!(summaries[0].total_size, 10 + 12 + 120);

    // storage objects + usage + gc
    for (k, s) in [
        ("files/h-level", 8),
        ("packs/full/p1/p1.pack", 12),
        ("region-bundles/full/r1/r1.bundle", 100),
        ("region-bundles/full/r2/r2.bundle", 120),
    ] {
        repo.upsert_storage_object(StorageObjectRecord {
            provider: StorageProviderType::GoogleDrive,
            storage_account_id: "acct".into(),
            storage_key: k.into(),
            object_id: format!("obj-{k}"),
            content_type: "application/octet-stream".into(),
            size: s,
            created_at: time::to_iso(now),
            updated_at: time::to_iso(now),
        })
        .await
        .unwrap();
    }
    let existing = repo
        .list_existing_storage_keys(
            StorageProviderType::GoogleDrive,
            "acct",
            &["files/h-level".into(), "nope".into()],
        )
        .await
        .unwrap();
    assert_eq!(existing.len(), 1);
    let referenced = repo
        .filter_referenced_storage_keys(&["region-bundles/full/r1/r1.bundle".into(), "zzz".into()], None)
        .await
        .unwrap();
    assert!(referenced.contains("region-bundles/full/r1/r1.bundle") && !referenced.contains("zzz"));

    // delete the older snapshot: r1 becomes unreferenced, shared keys stay
    let del = repo.delete_snapshots(&wid, std::slice::from_ref(&m1.snapshot_id)).await.unwrap();
    assert_eq!(del.deleted_snapshot_ids, vec![m1.snapshot_id.clone()]);
    assert_eq!(del.unreferenced_storage_keys, vec!["region-bundles/full/r1/r1.bundle".to_string()]);
    assert!(repo.get_snapshot(&wid, &m1.snapshot_id).await.unwrap().is_none());
    // the heir still loads its promoted member rows
    let m2_again = repo.get_snapshot(&wid, &m2.snapshot_id).await.unwrap().unwrap();
    assert_eq!(m2_again.packs[0].files.len(), 2);

    // chain step stamping
    let mut steps = HashMap::new();
    steps.insert(
        "non-region".to_string(),
        vec![PackChainStep {
            storage_key: "packs/full/p1/p1.pack".into(),
            hash: "p1".into(),
            base_hash: None,
            transfer_mode: FileTransferMode::PackFull,
            size: 12,
            delta_format_version: None,
        }],
    );
    repo.stamp_snapshot_chain_steps(&m2.snapshot_id, steps).await.unwrap();
    let h = repo.get_snapshot_headers(&wid, &m2.snapshot_id).await.unwrap().unwrap();
    assert_eq!(h.packs[0].chain_steps.as_ref().unwrap().len(), 1);
    let edges = repo.list_snapshot_delta_bases(&wid).await.unwrap();
    assert!(edges.is_empty());

    // retention slot CAS
    assert!(repo.claim_retention_slot(&wid, now, 3_600_000).await.unwrap());
    assert!(!repo.claim_retention_slot(&wid, now, 3_600_000).await.unwrap());

    // settings
    assert!(repo.update_world_settings(&wid, r#"{"difficulty":"hard"}"#).await.unwrap());
    let s = repo.get_world_settings(&wid).await.unwrap().unwrap();
    assert_eq!(s.settings_revision, 1);
    assert_eq!(s.settings.unwrap().difficulty, Some(WorldDifficulty::Hard));
    assert!(!repo.update_world_settings_if_revision(&wid, "{}", 0).await.unwrap());
    assert!(repo.update_world_settings_if_revision(&wid, "{}", 1).await.unwrap());

    // delete world by owner
    let res = repo.delete_world_for_player(&actor, &wid, now).await.unwrap();
    assert!(res.world_deleted);
    assert!(repo.list_worlds_for_player("guest").await.unwrap().is_empty());
}

#[tokio::test]
async fn pending_deletes_backoff() {
    let db = Db::open_memory().unwrap();
    migrate::migrate(&db).unwrap();
    let repo = Repository::new(db, None);
    let t0 = time::from_millis(1_700_000_000_000);
    repo.enqueue_pending_blob_deletes(
        StorageProviderType::GoogleDrive,
        "a",
        &["k1".into(), "k2".into(), "k1".into()],
        &time::to_iso(t0),
    )
    .await
    .unwrap();
    assert_eq!(repo.count_pending_blob_deletes().await.unwrap(), 2);
    let due = repo.list_due_pending_blob_deletes(&time::to_iso(t0), 10).await.unwrap();
    assert_eq!(due.len(), 2);
    repo.bump_pending_blob_delete_attempt(StorageProviderType::GoogleDrive, "a", "k1", &time::to_iso(t0))
        .await
        .unwrap();
    let due = repo.list_due_pending_blob_deletes(&time::plus_ms_iso(t0, 4 * 60_000), 10).await.unwrap();
    assert_eq!(due.len(), 1);
    let due = repo.list_due_pending_blob_deletes(&time::plus_ms_iso(t0, 5 * 60_000), 10).await.unwrap();
    assert_eq!(due.len(), 2);
    repo.bump_pending_blob_delete_attempt(StorageProviderType::GoogleDrive, "a", "k1", &time::to_iso(t0))
        .await
        .unwrap();
    let due = repo.list_due_pending_blob_deletes(&time::plus_ms_iso(t0, 9 * 60_000), 10).await.unwrap();
    assert_eq!(due.len(), 1);
    let due = repo.list_due_pending_blob_deletes(&time::plus_ms_iso(t0, 10 * 60_000), 10).await.unwrap();
    assert_eq!(due.len(), 2);
}

#[tokio::test]
async fn tokens_are_encrypted_at_rest_when_a_cipher_is_configured() {
    use sw_db::repo::StorageAccountRecord;
    use sw_db::TokenCipher;
    let db = Db::open_memory().unwrap();
    migrate::migrate(&db).unwrap();
    let repo =
        Repository::new(db.clone(), None).with_token_cipher(std::sync::Arc::new(TokenCipher::new([9u8; 32])));
    let now = time::now_iso();
    repo.create_or_update_storage_account(StorageAccountRecord {
        id: "acct".into(),
        provider: StorageProviderType::GoogleDrive,
        owner_player_uuid: "owner".into(),
        external_account_id: "ext".into(),
        email: Some("e@x".into()),
        display_name: None,
        access_token: Some("ya29.secret".into()),
        refresh_token: Some("1//refresh".into()),
        token_expires_at: None,
        s3_endpoint: None,
        s3_region: None,
        s3_bucket: None,
        s3_key_prefix: None,
        created_at: now.clone(),
        updated_at: now,
    })
    .await
    .unwrap();
    let back = repo.get_storage_account("acct").await.unwrap().unwrap();
    assert_eq!(back.refresh_token.as_deref(), Some("1//refresh"));
    assert_eq!(back.access_token.as_deref(), Some("ya29.secret"));
    let raw: String = db
        .read_blocking(|c| {
            Ok(c.raw().query_row(
                "SELECT refresh_token FROM storage_accounts WHERE id = 'acct'",
                [],
                |r| r.get(0),
            )?)
        })
        .unwrap();
    assert!(raw.starts_with("enc:v1:"), "{raw}");
    // A repository without the key cannot read the token (treated as absent).
    let blind = Repository::new(db.clone(), None);
    assert!(blind.get_storage_account("acct").await.unwrap().unwrap().refresh_token.is_none());
    let by_owner =
        repo.find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, "owner").await.unwrap();
    assert_eq!(by_owner[0].refresh_token.as_deref(), Some("1//refresh"));
}

/// PII pass: tokens AND the email are ciphertext at rest — a Repository
/// without the key sees neither, one with the key round-trips them.
#[tokio::test]
async fn storage_account_email_and_tokens_are_encrypted_at_rest() {
    let db = Db::open_memory().expect("db");
    migrate::migrate(&db).expect("migrate");
    let key = {
        use base64::Engine;
        let b64 = sw_db::token_cipher::TokenCipher::generate_key_b64();
        let raw = base64::engine::general_purpose::STANDARD.decode(b64).unwrap();
        <[u8; 32]>::try_from(raw.as_slice()).unwrap()
    };
    let cipher = std::sync::Arc::new(sw_db::token_cipher::TokenCipher::new(key));
    let with_key = Repository::new(db.clone(), None).with_token_cipher(cipher);
    let without_key = Repository::new(db, None);

    let now = time::now_iso();
    with_key
        .create_or_update_storage_account(StorageAccountRecord {
            id: "storage_pii".into(),
            provider: StorageProviderType::GoogleDrive,
            owner_player_uuid: "owner-uuid".into(),
            external_account_id: "sub-1".into(),
            email: Some("pau@example.com".into()),
            display_name: None,
            access_token: Some("at-secret".into()),
            refresh_token: Some("rt-secret".into()),
            token_expires_at: None,
            s3_endpoint: None,
            s3_region: None,
            s3_bucket: None,
            s3_key_prefix: None,
            created_at: now.clone(),
            updated_at: now,
        })
        .await
        .unwrap();

    // Keyed reads round-trip the plaintext.
    let seen = with_key.get_storage_account("storage_pii").await.unwrap().unwrap();
    assert_eq!(seen.email.as_deref(), Some("pau@example.com"));
    assert_eq!(seen.refresh_token.as_deref(), Some("rt-secret"));

    // Keyless reads prove nothing personal is stored in the clear: the raw
    // column values are `enc:v1:` blobs, surfaced as absent.
    let raw = without_key.get_storage_account("storage_pii").await.unwrap().unwrap();
    assert_eq!(raw.email, None);
    assert_eq!(raw.access_token, None);
    assert_eq!(raw.refresh_token, None);
}
