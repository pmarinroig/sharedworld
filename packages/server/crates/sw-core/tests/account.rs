//! Account-scoped flows: storage link ownership, unlink, delete-account.

use sw_contracts::*;
use sw_core::time;
use sw_core::RequestContext;
use sw_testkit::*;

/// Runs the full dev-mode (mock) OAuth link flow for `ctx` against the Google
/// account `mock_email` (the mock path uses the email as the OIDC `sub`).
async fn link_google_account(
    env: &TestEnv,
    ctx: &RequestContext,
    mock_email: &str,
) -> Result<StorageLinkSession, sw_core::HttpError> {
    let now = time::now();
    let created =
        env.svc.storage_links.create_storage_link(ctx, &CreateStorageLinkRequest::default(), now).await?;
    let record = env.repo.get_storage_link_session(&created.id).await?.expect("link session");
    env.svc
        .storage_links
        .complete_storage_link(
            &created.id,
            &StorageLinkCompleteRequest {
                session_id: created.id.clone(),
                code: None,
                state: Some(format!("{}:{}", record.id, record.state)),
                mock_email: Some(mock_email.into()),
            },
            now,
        )
        .await
}

#[tokio::test]
async fn linking_someone_elses_google_account_is_rejected() {
    let env = TestEnv::with_fake_drive().await;

    // Owner links a Google account.
    let linked = link_google_account(&env, &owner(), "shared@example.com").await.unwrap();
    assert_eq!(linked.status, StorageLinkStatus::Linked);
    let accounts =
        env.repo.find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, OWNER_UUID).await.unwrap();
    assert_eq!(accounts.len(), 1);
    let original_id = accounts[0].id.clone();

    // A different player linking the same Google account is rejected...
    let err = link_google_account(&env, &guest(), "shared@example.com").await.unwrap_err();
    assert_eq!(err.code, "storage_account_already_linked");

    // ...the failure is persisted on the guest's link session (the wizard
    // poller reads it from there)...
    let guest_sessions =
        env.repo.find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, GUEST_UUID).await.unwrap();
    assert!(guest_sessions.is_empty());

    // ...and the original owner's row is untouched.
    let accounts =
        env.repo.find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, OWNER_UUID).await.unwrap();
    assert_eq!(accounts.len(), 1);
    assert_eq!(accounts[0].id, original_id);
    assert_eq!(accounts[0].owner_player_uuid, OWNER_UUID);

    // Re-linking by the owner stays idempotent: same row, no duplicates.
    let relinked = link_google_account(&env, &owner(), "shared@example.com").await.unwrap();
    assert_eq!(relinked.status, StorageLinkStatus::Linked);
    let accounts =
        env.repo.find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, OWNER_UUID).await.unwrap();
    assert_eq!(accounts.len(), 1);
    assert_eq!(accounts[0].id, original_id);
}

#[tokio::test]
async fn unlink_blocks_on_bound_worlds_then_cleans_every_account_row() {
    let env = TestEnv::with_fake_drive().await;
    let now = time::now();

    // Owner ends up with two storage accounts (linked a second Google account).
    let first = link_google_account(&env, &owner(), "first@example.com").await.unwrap();
    link_google_account(&env, &owner(), "second@example.com").await.unwrap();
    let accounts =
        env.repo.find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, OWNER_UUID).await.unwrap();
    assert_eq!(accounts.len(), 2);
    let account_ids: Vec<String> = accounts.iter().map(|a| a.id.clone()).collect();

    let created = sw_core::service::worlds::create_world(
        &env.svc,
        &owner(),
        &CreateWorldRequest {
            name: Some(serde_json::json!("Blocked World")),
            use_linked_storage_account: Some(true),
            import_source: Some(
                serde_json::json!({ "type": "local-save", "id": "save-1", "name": "Save 1" }),
            ),
            ..Default::default()
        },
        now,
    )
    .await
    .unwrap();
    let wid = created.world.summary.id.clone();

    // Blocked while a world is bound to one of the owner's accounts.
    let err = sw_core::service::account::unlink_storage_account(&env.svc, &owner(), now).await.unwrap_err();
    assert_eq!(err.code, "storage_unlink_blocked");

    // A guest membership in that world does NOT block the guest's own unlink.
    let invite = sw_core::service::members::create_invite(&env.svc, &owner(), &wid, now).await.unwrap();
    sw_core::service::members::redeem_invite(
        &env.svc,
        &guest(),
        &RedeemInviteRequest { code: Some(serde_json::json!(invite.code)) },
        now,
    )
    .await
    .unwrap();
    link_google_account(&env, &guest(), "guest@example.com").await.unwrap();
    sw_core::service::account::unlink_storage_account(&env.svc, &guest(), now).await.unwrap();
    assert!(env
        .repo
        .find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, GUEST_UUID)
        .await
        .unwrap()
        .is_empty());

    // After the world is gone, the owner's unlink succeeds and removes both
    // rows, revokes both grants, and wipes the link-session history.
    sw_core::service::worlds::delete_world(&env.svc, &owner(), &wid, now).await.unwrap();
    sw_core::service::account::unlink_storage_account(&env.svc, &owner(), now).await.unwrap();
    assert!(env
        .repo
        .find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, OWNER_UUID)
        .await
        .unwrap()
        .is_empty());
    let revoked = env.drive().revoked_accounts();
    for id in &account_ids {
        assert!(revoked.contains(id), "account {id} not revoked: {revoked:?}");
    }
    assert!(env.repo.get_storage_link_session(&first.id).await.unwrap().is_none());

    // Unlinked owner shows as not linked.
    let summary = env.svc.storage_links.get_storage_account_summary(&owner()).await.unwrap();
    assert!(!summary.linked);
}

#[tokio::test]
async fn delete_account_sweeps_drive_purges_rows_and_scrubs_shared_worlds() {
    let env = TestEnv::with_fake_drive().await;
    let now = time::now();

    // Owner: linked Drive + an owned world.
    link_google_account(&env, &owner(), "owner@example.com").await.unwrap();
    let owner_accounts =
        env.repo.find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, OWNER_UUID).await.unwrap();
    let owner_account_id = owner_accounts[0].id.clone();
    let owned = sw_core::service::worlds::create_world(
        &env.svc,
        &owner(),
        &CreateWorldRequest {
            name: Some(serde_json::json!("Owner World")),
            use_linked_storage_account: Some(true),
            import_source: Some(
                serde_json::json!({ "type": "local-save", "id": "save-1", "name": "Save 1" }),
            ),
            ..Default::default()
        },
        now,
    )
    .await
    .unwrap()
    .world
    .summary
    .id
    .clone();

    // Guest owns a world too; the deleting player is a member there and has
    // hosted a backup in it (created_by must survive as the sentinel).
    link_google_account(&env, &guest(), "guest@example.com").await.unwrap();
    let guest_world = sw_core::service::worlds::create_world(
        &env.svc,
        &guest(),
        &CreateWorldRequest {
            name: Some(serde_json::json!("Guest World")),
            use_linked_storage_account: Some(true),
            import_source: Some(
                serde_json::json!({ "type": "local-save", "id": "save-2", "name": "Save 2" }),
            ),
            ..Default::default()
        },
        now,
    )
    .await
    .unwrap()
    .world
    .summary
    .id
    .clone();
    let invite =
        sw_core::service::members::create_invite(&env.svc, &guest(), &guest_world, now).await.unwrap();
    sw_core::service::members::redeem_invite(
        &env.svc,
        &owner(),
        &RedeemInviteRequest { code: Some(serde_json::json!(invite.code)) },
        now,
    )
    .await
    .unwrap();
    env.repo
        .finalize_snapshot(
            &guest_world,
            &owner().actor(),
            &FinalizeSnapshotRequest { files: vec![], packs: None, ..Default::default() },
            now,
            None,
        )
        .await
        .unwrap();

    // A live session and more Drive files than one step's budget (orphans
    // without index rows included).
    env.repo
        .create_session(SessionToken {
            token: "tok-owner".into(),
            player_uuid: OWNER_UUID.into(),
            player_name: "Owner".into(),
            expires_at: time::plus_ms_iso(now, 3_600_000),
        })
        .await
        .unwrap();
    for i in 0..30 {
        env.drive().add_orphan_app_file(&owner_account_id, &format!("orphan-{i}"));
    }

    // Guarded until the owned world is deleted (the client's phase A).
    let err = sw_core::service::account::delete_account_step(&env.svc, &owner(), now).await.unwrap_err();
    assert_eq!(err.code, "account_delete_blocked");
    sw_core::service::worlds::delete_world(&env.svc, &owner(), &owned, now).await.unwrap();

    // Loop the bounded step to completion; 30 files > the 25/call budget.
    let first = sw_core::service::account::delete_account_step(&env.svc, &owner(), now).await.unwrap();
    assert!(!first.response.done);
    assert_eq!(first.response.phase, "drive_sweep");
    assert!(first.response.remaining > 0);
    let mut done = false;
    let mut tokens = Vec::new();
    for _ in 0..10 {
        let step = sw_core::service::account::delete_account_step(&env.svc, &owner(), now).await.unwrap();
        if step.response.done {
            done = true;
            tokens = step.invalidated_tokens;
            break;
        }
    }
    assert!(done, "delete_account_step never finished");
    assert_eq!(tokens, vec!["tok-owner".to_string()]);

    // Storage: rows gone, Drive empty, grant revoked.
    assert!(env
        .repo
        .find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, OWNER_UUID)
        .await
        .unwrap()
        .is_empty());
    assert!(env.drive().app_file_ids(&owner_account_id).is_empty());
    assert!(env.drive().revoked_accounts().contains(&owner_account_id));

    // The player's worlds are hard-deleted, tombstones included.
    assert!(env.repo.list_world_ids_for_owner(OWNER_UUID).await.unwrap().is_empty());

    // The guest's world survives: guest still sees it, the deleted player is
    // no longer a member, and their backup is credited to the sentinel.
    let guest_view = env.repo.list_worlds_for_player(GUEST_UUID).await.unwrap();
    assert_eq!(guest_view.len(), 1);
    let details = env.repo.get_world_details(&guest_world, GUEST_UUID).await.unwrap().unwrap();
    assert_eq!(details.memberships.len(), 1);
    let snapshots = env.repo.list_snapshots_for_world(&guest_world).await.unwrap();
    assert!(!snapshots.is_empty());
    assert!(snapshots.iter().all(|s| s.created_by_uuid == sw_core::service::account::SENTINEL_PLAYER_UUID));

    // Sessions and the users row are gone (the FK insert now fails).
    assert!(env.repo.get_session("tok-owner").await.unwrap().is_none());
    assert!(env
        .repo
        .create_session(SessionToken {
            token: "tok-after".into(),
            player_uuid: OWNER_UUID.into(),
            player_name: "Owner".into(),
            expires_at: time::plus_ms_iso(now, 3_600_000),
        })
        .await
        .is_err());
}

#[tokio::test]
async fn rejected_link_marks_the_session_failed_with_a_message() {
    let env = TestEnv::with_fake_drive().await;
    link_google_account(&env, &owner(), "shared@example.com").await.unwrap();

    let now = time::now();
    let created = env
        .svc
        .storage_links
        .create_storage_link(&guest(), &CreateStorageLinkRequest::default(), now)
        .await
        .unwrap();
    let record = env.repo.get_storage_link_session(&created.id).await.unwrap().expect("session");
    let err = env
        .svc
        .storage_links
        .complete_storage_link(
            &created.id,
            &StorageLinkCompleteRequest {
                session_id: created.id.clone(),
                code: None,
                state: Some(format!("{}:{}", record.id, record.state)),
                mock_email: Some("shared@example.com".into()),
            },
            now,
        )
        .await
        .unwrap_err();
    assert_eq!(err.code, "storage_account_already_linked");

    let session = env.repo.get_storage_link_session(&created.id).await.unwrap().expect("session");
    assert_eq!(session.status, StorageLinkStatus::Failed);
    assert!(session.error_message.as_deref().unwrap_or("").contains("already linked"));
}

#[tokio::test]
async fn storage_email_is_visible_to_the_owner_only() {
    let env = TestEnv::with_fake_drive().await;
    let now = time::now();
    link_google_account(&env, &owner(), "owner@example.com").await.unwrap();
    let created = sw_core::service::worlds::create_world(
        &env.svc,
        &owner(),
        &CreateWorldRequest {
            name: Some(serde_json::json!("Email World")),
            use_linked_storage_account: Some(true),
            import_source: Some(
                serde_json::json!({ "type": "local-save", "id": "save-1", "name": "Save 1" }),
            ),
            ..Default::default()
        },
        now,
    )
    .await
    .unwrap();
    let wid = created.world.summary.id.clone();
    let invite = sw_core::service::members::create_invite(&env.svc, &owner(), &wid, now).await.unwrap();
    sw_core::service::members::redeem_invite(
        &env.svc,
        &guest(),
        &RedeemInviteRequest { code: Some(serde_json::json!(invite.code)) },
        now,
    )
    .await
    .unwrap();

    let owner_list = sw_core::service::worlds::list_worlds(&env.svc, &owner()).await.unwrap();
    assert_eq!(owner_list[0].storage_account_email.as_deref(), Some("owner@example.com"));
    let guest_list = sw_core::service::worlds::list_worlds(&env.svc, &guest()).await.unwrap();
    assert_eq!(guest_list[0].storage_account_email, None, "members must not see the owner's email");

    let guest_details = sw_core::service::worlds::get_world(&env.svc, &guest(), &wid, now).await.unwrap();
    assert_eq!(guest_details.summary.storage_account_email, None);

    let owner_usage = sw_core::service::worlds::get_storage_usage(&env.svc, &owner(), &wid).await.unwrap();
    assert_eq!(owner_usage.account_email.as_deref(), Some("owner@example.com"));
    let guest_usage = sw_core::service::worlds::get_storage_usage(&env.svc, &guest(), &wid).await.unwrap();
    assert_eq!(guest_usage.account_email, None);
}

#[tokio::test]
async fn expired_link_sessions_are_pruned_after_a_day() {
    let env = TestEnv::with_fake_drive().await;
    let now = time::now();
    let created = env
        .svc
        .storage_links
        .create_storage_link(&owner(), &CreateStorageLinkRequest::default(), now)
        .await
        .unwrap();
    // Fresh session: within its TTL, the hourly prune must keep it.
    env.repo.prune_expired_auth_rows(&time::to_iso(now), 2_000).await.unwrap();
    assert!(env.repo.get_storage_link_session(&created.id).await.unwrap().is_some());
    // A day and a bit later the row (and its email, once linked) goes away.
    let later = time::to_iso(now + chrono::Duration::hours(26));
    env.repo.prune_expired_auth_rows(&later, 2_000).await.unwrap();
    assert!(env.repo.get_storage_link_session(&created.id).await.unwrap().is_none());
}

/// A grant revoked at Google (dead refresh token) must not trap the user:
/// deletion skips the unreachable Drive sweep and still removes everything
/// server-side.
#[tokio::test]
async fn delete_account_completes_even_when_drive_auth_is_dead() {
    let env = TestEnv::with_fake_drive().await;
    let now = time::now();
    link_google_account(&env, &owner(), "dead@example.com").await.unwrap();
    let account_id =
        env.repo.find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, OWNER_UUID).await.unwrap()
            [0]
        .id
        .clone();
    env.drive().add_orphan_app_file(&account_id, "unreachable-file");
    env.drive().set_cleanup_auth_dead(&account_id);

    let outcome = sw_core::service::account::delete_account_step(&env.svc, &owner(), now).await.unwrap();
    assert!(outcome.response.done);

    // Server-side data is gone; the unreachable Drive file was skipped, not
    // silently claimed as deleted.
    assert!(env
        .repo
        .find_storage_accounts_by_owner(StorageProviderType::GoogleDrive, OWNER_UUID)
        .await
        .unwrap()
        .is_empty());
    assert_eq!(env.drive().app_file_ids(&account_id), vec!["unreachable-file".to_string()]);
}
