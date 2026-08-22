//! Service-level smoke: create world → invite → redeem → enter session →
//! heartbeat → kick → delete (exercises worlds/members/session modules).

use sw_contracts::*;
use sw_core::service::{members, session, worlds};
use sw_core::time;
use sw_testkit::*;

#[tokio::test]
async fn world_lifecycle_through_services() {
    let env = TestEnv::new().await;
    let svc = &env.svc;
    let now = time::now();
    let req = CreateWorldRequest {
        name: Some(serde_json::json!("  Our World ")),
        motd_line1: Some("hi".into()),
        ..Default::default()
    };
    let created = worlds::create_world(svc, &owner(), &req, now).await.unwrap();
    assert_eq!(created.world.summary.name, "Our World");
    assert_eq!(created.world.summary.motd.as_deref(), Some("hi"));
    assert_eq!(created.initial_upload_assignment.runtime_epoch, 1);
    let wid = created.world.summary.id.clone();

    // list + etag
    let list = worlds::list_worlds(svc, &owner()).await.unwrap();
    assert_eq!(list.len(), 1);
    assert_eq!(list[0].status, WorldStatus::Hosting);
    let etag1 = worlds::worlds_etag(svc, &owner()).await.unwrap();
    assert!(etag1.starts_with("W/\""));

    // invalid name
    let bad = worlds::create_world(
        svc,
        &owner(),
        &CreateWorldRequest { name: Some(serde_json::json!("ab")), ..Default::default() },
        now,
    )
    .await;
    assert_eq!(bad.unwrap_err().code, "invalid_world_name");

    // invite + redeem
    let invite = members::create_invite(svc, &owner(), &wid, now).await.unwrap();
    assert_eq!(invite.code.len(), 14);
    let again = members::create_invite(svc, &owner(), &wid, now).await.unwrap();
    assert_eq!(again.code, invite.code, "active invite reused");
    let forbidden = members::create_invite(svc, &guest(), &wid, now).await;
    assert_eq!(forbidden.unwrap_err().code, "world_not_found");
    let redeem = RedeemInviteRequest { code: Some(serde_json::json!(invite.code.to_lowercase())) };
    let details = members::redeem_invite(svc, &guest(), &redeem, now).await.unwrap();
    assert_eq!(details.memberships.len(), 2);
    assert_eq!(details.membership.role, MembershipRole::Member);
    let etag2 = worlds::worlds_etag(svc, &owner()).await.unwrap();
    assert_ne!(etag1, etag2);
    let bad_code = members::redeem_invite(
        svc,
        &host_member(),
        &RedeemInviteRequest { code: Some(serde_json::json!("NOPE-NOPE-NOPE")) },
        now,
    )
    .await;
    assert_eq!(bad_code.unwrap_err().code, "invite_not_found");

    // owner is host-starting: a guest entering waits; heartbeat with join target → live → guest connects
    let entry =
        session::enter_session(svc, &guest(), &wid, &EnterSessionRequest::default(), now).await.unwrap();
    assert_eq!(entry.action, EnterSessionAction::Wait);
    let hb = HeartbeatRequest {
        runtime_epoch: Some(created.initial_upload_assignment.runtime_epoch),
        host_token: Some(created.initial_upload_assignment.host_token.clone()),
        join_target: Some("join.example:25565".into()),
        minecraft_version: Some("1.21.11".into()),
    };
    let hb_res = session::heartbeat_host(svc, &owner(), &wid, &hb, now).await.unwrap();
    assert_eq!(hb_res.runtime.phase, WorldRuntimePhase::HostLive);
    assert_eq!(hb_res.memberships.len(), 2);
    assert_eq!(hb_res.runtime.host_minecraft_version.as_deref(), Some("1.21.11"));
    let entry =
        session::enter_session(svc, &guest(), &wid, &EnterSessionRequest::default(), now).await.unwrap();
    assert_eq!(entry.action, EnterSessionAction::Connect);
    let summary = worlds::get_world(svc, &guest(), &wid, now).await.unwrap();
    assert_eq!(summary.summary.status, WorldStatus::Hosting);
    assert_eq!(summary.summary.active_join_target.as_deref(), Some("join.example:25565"));
    assert!(summary.active_invite_code.is_none(), "guests never see the invite");
    let owner_view = worlds::get_world(svc, &owner(), &wid, now).await.unwrap();
    assert!(owner_view.active_invite_code.is_some());

    // presence beat (flat superset)
    let beat = session::set_player_presence(
        svc,
        &guest(),
        &wid,
        &PresenceHeartbeatRequest {
            present: Some(serde_json::json!(true)),
            guest_session_epoch: Some(serde_json::json!(1)),
            presence_sequence: Some(serde_json::json!(1)),
        },
        now,
    )
    .await
    .unwrap();
    assert_eq!(beat.phase, WorldRuntimePhase::HostLive);
    assert!(beat.last_snapshot_id.is_none());
    let v: serde_json::Value = serde_json::to_value(&beat).unwrap();
    assert!(v.get("updatedAt").is_some() && v.get("presence").is_none(), "flat body");
    let summary = worlds::get_world(svc, &owner(), &wid, now).await.unwrap();
    assert_eq!(summary.summary.online_player_count, 2);

    // settings
    let s = worlds::update_world_settings(
        svc,
        &owner(),
        &wid,
        &UpdateWorldSettingsRequest {
            settings: serde_json::json!({"difficulty":"hard","gamerules":{"pvp":false},"maxBackups":5}),
        },
    )
    .await
    .unwrap();
    assert_eq!(s.summary.settings_revision, 1);
    assert_eq!(s.summary.settings.as_ref().unwrap().max_backups, Some(Some(5)));
    let bad = worlds::update_world_settings(
        svc,
        &owner(),
        &wid,
        &UpdateWorldSettingsRequest { settings: serde_json::json!({"nope":1}) },
    )
    .await;
    assert_eq!(bad.unwrap_err().code, "invalid_world_settings");
    let report = worlds::report_host_game_rules(
        svc,
        &owner(),
        &wid,
        &HostGameRulesReportRequest {
            runtime_epoch: hb.runtime_epoch,
            host_token: hb.host_token.clone(),
            gamerules: Some(serde_json::json!({"keepInventory": true})),
            difficulty: None,
            default_game_mode: None,
        },
        now,
    )
    .await
    .unwrap();
    assert_eq!(report.settings_revision, 2);
    assert_eq!(report.settings.gamerules.as_ref().unwrap().len(), 2);

    // kick guest → invite rotated, guest revoked
    let kicked = members::kick_member(svc, &owner(), &wid, GUEST_UUID, now).await.unwrap();
    assert_eq!(kicked.removed_player_uuid, GUEST_UUID);
    let new_invite = members::create_invite(svc, &owner(), &wid, now).await.unwrap();
    assert_ne!(new_invite.code, invite.code);
    let denied = session::enter_session(svc, &guest(), &wid, &EnterSessionRequest::default(), now).await;
    assert_eq!(denied.unwrap_err().code, "membership_revoked");

    // delete world
    worlds::delete_world(svc, &owner(), &wid, now).await.unwrap();
    assert!(worlds::list_worlds(svc, &owner()).await.unwrap().is_empty());
    let gone = worlds::get_world(svc, &owner(), &wid, now).await;
    assert_eq!(gone.unwrap_err().code, "world_not_found");
}

/// [P8] Effects whose membership read fails make the initial host claim lose
/// the way a concurrent claimant would — after the world/membership rows
/// already exist. The failed create must compensate, not strand them.
struct SeedLosingEffects;

#[async_trait::async_trait]
impl sw_core::realtime::CoordinatorEffects for SeedLosingEffects {
    async fn list_memberships(
        &self,
        _world_id: &str,
    ) -> Result<Vec<sw_core::realtime::RuntimeMembership>, sw_core::HttpError> {
        Err(sw_core::HttpError::new(409, "world_busy", "injected: lost the claim race"))
    }
    async fn mirror_runtime(&self, _world_id: &str, _status: &WorldRuntimeStatus) {}
    async fn mirror_presence(&self, _world_id: &str, _players: &[RoomPlayer]) {}
    async fn publish(&self, _event: RealtimeEvent, _recipients: Option<Vec<String>>) {}
    async fn schedule_alarm(&self, _world_id: &str, _at: Option<sw_core::time::Instant>) {}
    async fn set_host_watch(&self, _world_id: &str, _host_uuid: &str, _watching: bool) -> bool {
        false
    }
    async fn probe_host_reachability(
        &self,
        _host_uuid: &str,
    ) -> Result<Option<sw_core::time::Instant>, sw_core::HttpError> {
        Ok(None)
    }
}

#[tokio::test]
async fn p8_create_whose_runtime_seed_loses_leaves_no_world_behind() {
    let env = TestEnv::new().await;
    env.realtime.registry.set_effects(std::sync::Arc::new(SeedLosingEffects));
    let doomed = worlds::create_world(
        &env.svc,
        &owner(),
        &CreateWorldRequest { name: Some(serde_json::json!("Doomed World")), ..Default::default() },
        time::now(),
    )
    .await;
    assert!(doomed.is_err(), "seed loss must fail the create");
    assert!(worlds::list_worlds(&env.svc, &owner()).await.unwrap().is_empty());
}
