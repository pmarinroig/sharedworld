//! Port of `test/realtime/{coordinator,coordinator-efficiency,socket-presence,legacy-presence-prune}.test.ts`.

use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use sw_contracts::*;
use sw_core::realtime::*;
use sw_core::time::{self, Instant};
use sw_core::HttpError;

#[derive(Default)]
pub struct RecordingEffects {
    pub memberships: Mutex<Vec<RuntimeMembership>>,
    pub published: Mutex<Vec<(RealtimeEvent, Option<Vec<String>>)>>,
    pub mirrored_runtimes: Mutex<Vec<WorldRuntimeStatus>>,
    pub mirrored_presence: Mutex<Vec<Vec<RoomPlayer>>>,
    pub alarm_at: Mutex<Option<Instant>>,
    pub host_watches: Mutex<Vec<(String, bool)>>,
    pub host_socket_connected: Mutex<bool>,
    pub last_keepalive_at: Mutex<Option<Instant>>,
    pub list_memberships_calls: Mutex<usize>,
}

impl RecordingEffects {
    fn set_members(&self, m: Vec<RuntimeMembership>) {
        *self.memberships.lock().unwrap() = m;
    }
    fn events_of_kind(&self, kind: RealtimeEventKind) -> Vec<RealtimeEvent> {
        self.published
            .lock()
            .unwrap()
            .iter()
            .filter(|(e, _)| e.kind == kind)
            .map(|(e, _)| e.clone())
            .collect()
    }
    fn set_keepalive(&self, at: Instant) {
        *self.last_keepalive_at.lock().unwrap() = Some(at);
    }
}

#[async_trait]
impl CoordinatorEffects for RecordingEffects {
    async fn list_memberships(&self, _world_id: &str) -> Result<Vec<RuntimeMembership>, HttpError> {
        *self.list_memberships_calls.lock().unwrap() += 1;
        Ok(self.memberships.lock().unwrap().clone())
    }
    async fn mirror_runtime(&self, _world_id: &str, status: &WorldRuntimeStatus) {
        self.mirrored_runtimes.lock().unwrap().push(status.clone());
    }
    async fn mirror_presence(&self, _world_id: &str, players: &[RoomPlayer]) {
        self.mirrored_presence.lock().unwrap().push(players.to_vec());
    }
    async fn publish(&self, event: RealtimeEvent, recipients: Option<Vec<String>>) {
        self.published.lock().unwrap().push((event, recipients));
    }
    async fn schedule_alarm(&self, _world_id: &str, at: Option<Instant>) {
        *self.alarm_at.lock().unwrap() = at;
    }
    async fn set_host_watch(&self, _world_id: &str, host_uuid: &str, watching: bool) -> bool {
        self.host_watches.lock().unwrap().push((host_uuid.to_string(), watching));
        if watching {
            *self.host_socket_connected.lock().unwrap()
        } else {
            false
        }
    }
    async fn probe_host_reachability(&self, _host_uuid: &str) -> Result<Option<Instant>, HttpError> {
        Ok(*self.last_keepalive_at.lock().unwrap())
    }
}

struct H {
    coordinator: WorldCoordinator,
    effects: Arc<RecordingEffects>,
}

fn make() -> H {
    let effects = Arc::new(RecordingEffects::default());
    let coordinator = WorldCoordinator::new("world-1", Box::new(KvStore::default()), effects.clone());
    H { coordinator, effects }
}

fn member(uuid: &str, name: &str, role: MembershipRole, joined_at: &str) -> RuntimeMembership {
    RuntimeMembership {
        player_uuid: uuid.into(),
        player_name: name.into(),
        role,
        joined_at: joined_at.into(),
        deleted_at: None,
    }
}

fn actor(uuid: &str, name: &str) -> SessionActor {
    SessionActor {
        player_uuid: uuid.into(),
        player_name: name.into(),
        membership_active: true,
        ever_member: true,
    }
}

fn t0() -> Instant {
    time::parse_iso("2026-01-03T00:00:00.000Z").unwrap()
}
fn at(seconds: i64) -> Instant {
    t0() + chrono::Duration::seconds(seconds)
}

fn owner() -> SessionActor {
    actor("owner-uuid", "Owner")
}
fn guest() -> SessionActor {
    actor("guest-uuid", "Guest")
}
fn third() -> SessionActor {
    actor("third-uuid", "Third")
}

fn seed_members(h: &H) {
    h.effects.set_members(vec![
        member("owner-uuid", "Owner", MembershipRole::Owner, "2026-01-01T00:00:00.000Z"),
        member("guest-uuid", "Guest", MembershipRole::Member, "2026-01-02T00:00:00.000Z"),
        member("third-uuid", "Third", MembershipRole::Member, "2026-01-02T12:00:00.000Z"),
    ]);
}

fn auth_of(a: &HostAssignment) -> HostAuthorityArgs {
    HostAuthorityArgs { runtime_epoch: Some(a.runtime_epoch), host_token: Some(a.host_token.clone()) }
}

fn hb(a: &HostAuthorityArgs, join_target: Option<&str>) -> HeartbeatArgs {
    HeartbeatArgs {
        runtime_epoch: a.runtime_epoch,
        host_token: a.host_token.clone(),
        join_target: join_target.map(|s| s.into()),
        minecraft_version: None,
    }
}

async fn become_live_host(h: &mut H, who: &SessionActor, now: Instant) -> HostAuthorityArgs {
    let entry = h.coordinator.enter_session(who, None, false, now).await.unwrap();
    assert_eq!(entry.action, EnterSessionAction::Host);
    let a = entry.assignment.expect("assignment");
    let auth = auth_of(&a);
    let status = h
        .coordinator
        .heartbeat(who, &hb(&auth, Some("join.example:25565")), now + chrono::Duration::seconds(1))
        .await
        .unwrap();
    assert_eq!(status.phase, WorldRuntimePhase::HostLive);
    auth
}

fn expect_err<T: std::fmt::Debug>(r: Result<T, HttpError>, code: &str, reason: Option<&str>) {
    let e = r.expect_err(&format!("expected {code}"));
    assert_eq!(e.code, code);
    if let Some(reason) = reason {
        assert_eq!(e.reason, Some(reason));
    }
}

fn runtime(h: &H) -> Option<WorldRuntimeRecord> {
    h.coordinator.store().get_runtime()
}

// ------------------------------------------------------------ entry/election

#[tokio::test]
async fn p1_entering_idle_world_assigns_one_host_and_later_entrants_wait() {
    let mut h = make();
    seed_members(&h);
    let first = h.coordinator.enter_session(&owner(), None, false, t0()).await.unwrap();
    assert_eq!(first.action, EnterSessionAction::Host);
    assert_eq!(first.assignment.as_ref().unwrap().runtime_epoch, 1);
    let second = h.coordinator.enter_session(&guest(), None, false, at(1)).await.unwrap();
    assert_eq!(second.action, EnterSessionAction::Wait);
    assert!(second.assignment.is_none());
    assert_eq!(runtime(&h).unwrap().host_uuid.as_deref(), Some("owner-uuid"));
}

#[tokio::test]
async fn p1_reentry_by_starting_host_replays_same_assignment() {
    let mut h = make();
    seed_members(&h);
    let first = h.coordinator.enter_session(&owner(), None, false, t0()).await.unwrap();
    let again = h.coordinator.enter_session(&owner(), None, false, at(2)).await.unwrap();
    assert_eq!(again.action, EnterSessionAction::Host);
    assert_eq!(
        again.assignment.as_ref().unwrap().runtime_epoch,
        first.assignment.as_ref().unwrap().runtime_epoch
    );
    assert_eq!(again.assignment.as_ref().unwrap().host_token, first.assignment.as_ref().unwrap().host_token);
}

#[tokio::test]
async fn live_host_with_join_target_lets_members_connect() {
    let mut h = make();
    seed_members(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    let entry = h.coordinator.enter_session(&guest(), None, false, at(5)).await.unwrap();
    assert_eq!(entry.action, EnterSessionAction::Connect);
    assert_eq!(entry.runtime.join_target.as_deref(), Some("join.example:25565"));
}

#[tokio::test]
async fn p3_cancelled_preferred_candidate_never_strands_world() {
    let mut h = make();
    seed_members(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    let guest_wait = h.coordinator.enter_session(&guest(), None, false, at(5)).await.unwrap();
    assert_eq!(guest_wait.action, EnterSessionAction::Connect);
    h.coordinator.begin_finalization(&owner(), &auth, at(10)).await.unwrap();
    let guest_queued = h.coordinator.enter_session(&guest(), None, false, at(11)).await.unwrap();
    assert_eq!(guest_queued.action, EnterSessionAction::Wait);
    let third_queued = h.coordinator.enter_session(&third(), None, false, at(12)).await.unwrap();
    assert_eq!(third_queued.action, EnterSessionAction::Wait);
    h.coordinator.complete_finalization(&owner(), &auth, at(13)).await.unwrap();
    h.coordinator
        .cancel_waiting(&guest(), guest_queued.waiter_session_id.as_deref().unwrap_or(""), at(14))
        .await
        .unwrap();
    let observed = h
        .coordinator
        .observe_waiting(&third(), third_queued.waiter_session_id.as_deref(), at(15))
        .await
        .unwrap();
    assert_eq!(observed.action, ObserveWaitingAction::Restart);
    assert_eq!(runtime(&h).unwrap().host_uuid.as_deref(), Some("third-uuid"));
}

#[tokio::test]
async fn p4_unrefreshed_waiter_expires_out_of_candidacy() {
    let mut h = make();
    seed_members(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.begin_finalization(&owner(), &auth, at(5)).await.unwrap();
    let queued = h.coordinator.enter_session(&guest(), None, false, at(6)).await.unwrap();
    assert_eq!(queued.action, EnterSessionAction::Wait);
    h.coordinator.complete_finalization(&owner(), &auth, at(7)).await.unwrap();
    let entry = h.coordinator.enter_session(&third(), None, false, at(7 + 121)).await.unwrap();
    assert_eq!(entry.action, EnterSessionAction::Host);
}

// -------------------------------------------------------------------- fencing

#[tokio::test]
async fn p2_deposed_host_cannot_mutate_new_runtime() {
    let mut h = make();
    seed_members(&h);
    let old = become_live_host(&mut h, &owner(), t0()).await;
    let reentry = h.coordinator.enter_session(&guest(), None, true, at(155)).await.unwrap();
    assert_eq!(reentry.action, EnterSessionAction::Host);
    assert_eq!(reentry.assignment.as_ref().unwrap().runtime_epoch, 2);
    expect_err(
        h.coordinator.heartbeat(&owner(), &hb(&old, None), at(158)).await,
        "host_not_active",
        Some("replaced"),
    );
    expect_err(
        h.coordinator.begin_finalization(&owner(), &old, at(158)).await,
        "host_not_active",
        Some("replaced"),
    );
    expect_err(
        h.coordinator
            .validate_host_authority(
                &owner(),
                old.runtime_epoch,
                old.host_token.as_deref(),
                &[WorldRuntimePhase::HostLive],
                at(158),
            )
            .await,
        "host_not_active",
        Some("replaced"),
    );
    assert_eq!(runtime(&h).unwrap().runtime_epoch, 2);
}

#[tokio::test]
async fn expired_live_lease_records_warning_and_reentry_warns() {
    let mut h = make();
    seed_members(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    let entry = h.coordinator.enter_session(&guest(), None, false, at(155)).await.unwrap();
    assert_eq!(entry.action, EnterSessionAction::WarnHost);
    assert_eq!(entry.runtime.unclean_shutdown_warning.as_ref().unwrap().host_uuid, "owner-uuid");
    let ack = h.coordinator.enter_session(&guest(), None, true, at(156)).await.unwrap();
    assert_eq!(ack.action, EnterSessionAction::Host);
    assert_eq!(ack.assignment.as_ref().unwrap().runtime_epoch, 2);
}

#[tokio::test]
async fn release_replay_succeeds_without_minting_authority() {
    let mut h = make();
    seed_members(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    let released = h.coordinator.release_host(&owner(), &auth, true, at(10)).await.unwrap();
    assert!(released.graceful);
    let replay = h.coordinator.release_host(&owner(), &auth, true, at(11)).await.unwrap();
    assert_eq!(replay.released_at, time::to_iso(at(11)));
    assert!(runtime(&h).is_none());
}

#[tokio::test]
async fn lease_expired_epoch_is_real_authority_loss() {
    let mut h = make();
    seed_members(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.runtime_status(&owner(), at(155)).await.unwrap();
    expect_err(
        h.coordinator.release_host(&owner(), &auth, true, at(156)).await,
        "host_not_active",
        Some("lease_expired"),
    );
    expect_err(h.coordinator.complete_finalization(&owner(), &auth, at(156)).await, "not_finalizing", None);
}

// --------------------------------------------------- finalization / revocation

#[tokio::test]
async fn p6_revoked_host_cannot_heartbeat_but_can_finalize() {
    let mut h = make();
    seed_members(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.member_revoked("owner-uuid", at(5)).await.unwrap();
    let revoked = SessionActor { membership_active: false, ever_member: true, ..owner() };
    expect_err(h.coordinator.heartbeat(&revoked, &hb(&auth, None), at(6)).await, "membership_revoked", None);
    let begun = h.coordinator.begin_finalization(&revoked, &auth, at(7)).await.unwrap();
    assert_eq!(begun.status, WorldStatus::Finalizing);
    let completed = h.coordinator.complete_finalization(&revoked, &auth, at(8)).await.unwrap();
    assert_eq!(completed.status, WorldStatus::Idle);
}

#[tokio::test]
async fn completing_finalization_hands_off_to_preferred_waiter() {
    let mut h = make();
    seed_members(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.begin_finalization(&owner(), &auth, at(5)).await.unwrap();
    let queued = h.coordinator.enter_session(&guest(), None, false, at(6)).await.unwrap();
    assert_eq!(queued.action, EnterSessionAction::Wait);
    let completed = h.coordinator.complete_finalization(&owner(), &auth, at(7)).await.unwrap();
    assert_eq!(completed.status, WorldStatus::Handoff);
    assert_eq!(completed.next_host_uuid.as_deref(), Some("guest-uuid"));
}

#[tokio::test]
async fn p5_destroy_world_clears_state_and_notifies() {
    let mut h = make();
    seed_members(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.destroy_world(vec!["owner-uuid".into(), "guest-uuid".into()]).await.unwrap();
    assert!(runtime(&h).is_none());
    assert!(h.coordinator.store().list_waiters().is_empty());
    assert!(h.effects.alarm_at.lock().unwrap().is_none());
    let published = h.effects.published.lock().unwrap();
    let (ev, recipients) = published.last().unwrap();
    assert_eq!(ev.kind, RealtimeEventKind::WorldDeleted);
    assert_eq!(recipients.as_ref().unwrap(), &vec!["owner-uuid".to_string(), "guest-uuid".to_string()]);
}

// ------------------------------------------------------ connection liveness

#[tokio::test]
async fn host_socket_loss_forfeits_after_grace() {
    let mut h = make();
    seed_members(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.host_socket_closed("owner-uuid", at(10)).await.unwrap();
    let grace = h.coordinator.store().get_host_link().grace_deadline_at;
    assert_eq!(grace.as_deref(), Some(time::plus_ms_iso(at(10), HOST_DISCONNECT_GRACE_MS).as_str()));
    h.coordinator.on_alarm(at(20)).await.unwrap();
    assert_eq!(runtime(&h).unwrap().phase, WorldRuntimePhase::HostLive);
    h.coordinator.on_alarm(at(41)).await.unwrap();
    assert!(runtime(&h).is_none());
    assert_eq!(h.coordinator.store().get_warning().unwrap().host_uuid, "owner-uuid");
}

#[tokio::test]
async fn reconnect_inside_grace_cancels_forfeiture() {
    let mut h = make();
    seed_members(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.host_socket_closed("owner-uuid", at(10)).await.unwrap();
    h.coordinator.host_socket_connected("owner-uuid", at(15)).await.unwrap();
    h.coordinator.on_alarm(at(41)).await.unwrap();
    assert_eq!(runtime(&h).unwrap().phase, WorldRuntimePhase::HostLive);
}

#[tokio::test]
async fn due_grace_deadline_verified_against_keepalive() {
    let mut h = make();
    seed_members(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.host_socket_closed("owner-uuid", at(10)).await.unwrap();
    h.effects.set_keepalive(at(39));
    h.coordinator.on_alarm(at(41)).await.unwrap();
    assert_eq!(runtime(&h).unwrap().phase, WorldRuntimePhase::HostLive);
    assert_eq!(h.coordinator.store().get_host_link(), HostLink { connected: true, grace_deadline_at: None });
    h.coordinator.on_alarm(at(41 + 155)).await.unwrap();
    assert!(runtime(&h).is_none());
}

#[tokio::test]
async fn lease_deadline_probes_keepalive_without_connected_signal() {
    let mut h = make();
    seed_members(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.effects.set_keepalive(at(145));
    h.coordinator.on_alarm(at(155)).await.unwrap();
    assert_eq!(runtime(&h).unwrap().phase, WorldRuntimePhase::HostLive);
    assert!(h.coordinator.store().get_host_link().connected);
}

#[tokio::test]
async fn reachable_host_lease_extends_from_keepalive() {
    let mut h = make();
    seed_members(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.host_socket_connected("owner-uuid", at(2)).await.unwrap();
    h.effects.set_keepalive(at(145));
    h.coordinator.on_alarm(at(155)).await.unwrap();
    assert_eq!(runtime(&h).unwrap().phase, WorldRuntimePhase::HostLive);
    h.coordinator.on_alarm(at(155 + 155)).await.unwrap();
    assert!(runtime(&h).is_none());
}

#[tokio::test]
async fn over_deadline_lease_hit_by_request_probes_keepalive() {
    let mut h = make();
    seed_members(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    h.effects.set_keepalive(at(145));
    let status = h.coordinator.runtime_status(&owner(), at(155)).await.unwrap();
    assert_eq!(status.phase, WorldRuntimePhase::HostLive);
    assert!(h.coordinator.store().get_warning().is_none());
    h.coordinator
        .validate_host_authority(
            &owner(),
            auth.runtime_epoch,
            auth.host_token.as_deref(),
            &[WorldRuntimePhase::HostLive],
            at(156),
        )
        .await
        .unwrap();
    h.coordinator.runtime_status(&owner(), at(156 + 155)).await.unwrap();
    assert!(runtime(&h).is_none());
    assert_eq!(h.coordinator.store().get_warning().unwrap().host_uuid, "owner-uuid");
}

#[tokio::test]
async fn successful_heartbeat_clears_armed_grace() {
    let mut h = make();
    seed_members(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.host_socket_closed("owner-uuid", at(10)).await.unwrap();
    assert!(h.coordinator.store().get_host_link().grace_deadline_at.is_some());
    h.coordinator.heartbeat(&owner(), &hb(&auth, None), at(15)).await.unwrap();
    assert!(h.coordinator.store().get_host_link().grace_deadline_at.is_none());
    assert!(!h.coordinator.store().get_host_link().connected);
    h.coordinator.on_alarm(at(41)).await.unwrap();
    assert_eq!(runtime(&h).unwrap().phase, WorldRuntimePhase::HostLive);
    assert!(h.coordinator.store().get_warning().is_none());
}

#[tokio::test]
async fn p7_lease_expiry_publishes_runtime_change() {
    let mut h = make();
    seed_members(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.effects.published.lock().unwrap().clear();
    h.coordinator.on_alarm(at(155)).await.unwrap();
    let events = h.effects.events_of_kind(RealtimeEventKind::RuntimeChanged);
    assert!(!events.is_empty());
    assert_eq!(events.last().unwrap().runtime.as_ref().unwrap().phase, WorldRuntimePhase::Idle);
}

// ------------------------------------------------------------ room presence

fn rp(uuid: &str, name: &str) -> RoomPlayer {
    RoomPlayer { player_uuid: uuid.into(), player_name: name.into() }
}

#[tokio::test]
async fn host_roster_mirrors_publishes_and_wins() {
    let mut h = make();
    seed_members(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator
        .report_legacy_presence(
            &third(),
            &LegacyPresenceArgs { present: true, guest_session_epoch: 1, presence_sequence: 1 },
            at(3),
        )
        .await
        .unwrap();
    h.coordinator
        .report_host_players(
            "owner-uuid",
            auth.runtime_epoch.unwrap(),
            vec![rp("owner-uuid", "Owner")],
            at(5),
        )
        .await
        .unwrap();
    assert_eq!(h.coordinator.room_players(at(6)), vec![rp("owner-uuid", "Owner")]);
    let presence = h.effects.events_of_kind(RealtimeEventKind::PresenceChanged);
    assert_eq!(presence.last().unwrap().room_players, Some(vec![rp("owner-uuid", "Owner")]));
    assert_eq!(h.effects.mirrored_presence.lock().unwrap().last().unwrap(), &vec![rp("owner-uuid", "Owner")]);
}

#[tokio::test]
async fn roster_report_with_stale_epoch_dropped() {
    let mut h = make();
    seed_members(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.report_host_players("owner-uuid", 99, vec![rp("x", "X")], at(5)).await.unwrap();
    assert!(h.coordinator.store().get_room_players().is_none());
}

#[tokio::test]
async fn legacy_presence_expires_on_alarm() {
    let mut h = make();
    seed_members(&h);
    h.coordinator
        .report_legacy_presence(
            &guest(),
            &LegacyPresenceArgs { present: true, guest_session_epoch: 1, presence_sequence: 1 },
            t0(),
        )
        .await
        .unwrap();
    assert_eq!(h.coordinator.room_players(at(1)).len(), 1);
    h.coordinator.on_alarm(at(46)).await.unwrap();
    assert!(h.coordinator.room_players(at(46)).is_empty());
    assert!(h.effects.mirrored_presence.lock().unwrap().last().unwrap().is_empty());
}

#[tokio::test]
async fn retiring_runtime_clears_room_and_publishes_empty() {
    let mut h = make();
    seed_members(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator
        .report_host_players(
            "owner-uuid",
            auth.runtime_epoch.unwrap(),
            vec![rp("owner-uuid", "Owner")],
            at(5),
        )
        .await
        .unwrap();
    h.coordinator.release_host(&owner(), &auth, true, at(10)).await.unwrap();
    assert!(h.effects.mirrored_presence.lock().unwrap().last().unwrap().is_empty());
}

// ------------------------------------------------------------- push hygiene

#[tokio::test]
async fn steady_heartbeats_do_not_republish() {
    let mut h = make();
    seed_members(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    h.effects.published.lock().unwrap().clear();
    h.coordinator.heartbeat(&owner(), &hb(&auth, None), at(30)).await.unwrap();
    h.coordinator.heartbeat(&owner(), &hb(&auth, None), at(60)).await.unwrap();
    assert!(h.effects.events_of_kind(RealtimeEventKind::RuntimeChanged).is_empty());
}

#[tokio::test]
async fn alarm_always_armed_while_runtime_has_deadline() {
    let mut h = make();
    seed_members(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    assert!(h.effects.alarm_at.lock().unwrap().is_some());
}

#[tokio::test]
async fn access_control_codes() {
    let mut h = make();
    seed_members(&h);
    let stranger = SessionActor {
        player_uuid: "stranger".into(),
        player_name: "S".into(),
        membership_active: false,
        ever_member: false,
    };
    expect_err(h.coordinator.enter_session(&stranger, None, false, t0()).await, "forbidden", None);
    let kicked = SessionActor {
        player_uuid: "kicked".into(),
        player_name: "K".into(),
        membership_active: false,
        ever_member: true,
    };
    expect_err(h.coordinator.enter_session(&kicked, None, false, t0()).await, "membership_revoked", None);
}

// ------------------------------------------------------------ efficiency

fn owner_member() -> RuntimeMembership {
    member("player-owner", "Owner", MembershipRole::Owner, "2026-01-01T00:00:00.000Z")
}

#[tokio::test]
async fn one_membership_read_per_call_and_cache_serves_next() {
    let mut h = make();
    h.effects.set_members(vec![owner_member()]);
    h.coordinator
        .runtime_status(&actor("player-owner", "Owner"), time::parse_iso("2026-01-01T10:00:00.000Z").unwrap())
        .await
        .unwrap();
    assert_eq!(*h.effects.list_memberships_calls.lock().unwrap(), 1);
    h.coordinator
        .runtime_status(&actor("player-owner", "Owner"), time::parse_iso("2026-01-01T10:00:30.000Z").unwrap())
        .await
        .unwrap();
    assert_eq!(*h.effects.list_memberships_calls.lock().unwrap(), 1);
}

#[tokio::test]
async fn ttl_expires_cached_list() {
    let mut h = make();
    h.effects.set_members(vec![owner_member()]);
    h.coordinator
        .runtime_status(&actor("player-owner", "Owner"), time::parse_iso("2026-01-01T10:00:00.000Z").unwrap())
        .await
        .unwrap();
    h.coordinator
        .runtime_status(&actor("player-owner", "Owner"), time::parse_iso("2026-01-01T10:01:30.000Z").unwrap())
        .await
        .unwrap();
    assert_eq!(*h.effects.list_memberships_calls.lock().unwrap(), 2);
}

#[tokio::test]
async fn membership_pokes_invalidate_immediately() {
    let mut h = make();
    h.effects.set_members(vec![owner_member()]);
    let now = time::parse_iso("2026-01-01T10:00:00.000Z").unwrap();
    h.coordinator.runtime_status(&actor("player-owner", "Owner"), now).await.unwrap();
    assert_eq!(*h.effects.list_memberships_calls.lock().unwrap(), 1);
    h.effects.set_members(vec![
        owner_member(),
        member("player-guest", "Guest", MembershipRole::Member, "2026-01-01T09:00:00.000Z"),
    ]);
    h.coordinator.memberships_changed(now + chrono::Duration::seconds(5)).await.unwrap();
    assert_eq!(*h.effects.list_memberships_calls.lock().unwrap(), 2);
    h.coordinator.member_revoked("player-guest", now + chrono::Duration::seconds(10)).await.unwrap();
    assert_eq!(*h.effects.list_memberships_calls.lock().unwrap(), 3);
}

#[tokio::test]
async fn publishes_carry_explicit_recipients() {
    let mut h = make();
    let mut kicked = member("player-kicked", "Kicked", MembershipRole::Member, "2026-01-01T09:00:00.000Z");
    kicked.deleted_at = Some("2026-01-01T09:30:00.000Z".into());
    h.effects.set_members(vec![owner_member(), kicked]);
    h.coordinator
        .runtime_status(&actor("player-owner", "Owner"), time::parse_iso("2026-01-01T10:00:00.000Z").unwrap())
        .await
        .unwrap();
    let published = h.effects.published.lock().unwrap();
    assert!(!published.is_empty());
    for (_, recipients) in published.iter() {
        assert_eq!(recipients.as_ref().unwrap(), &vec!["player-owner".to_string()]);
    }
}

#[tokio::test]
async fn cold_start_over_same_store_does_not_rewrite_mirror() {
    let effects = Arc::new(RecordingEffects::default());
    effects.set_members(vec![owner_member()]);
    let mut store = KvStore::default();
    {
        let mut coordinator = WorldCoordinator::new("world-1", Box::new(store.clone()), effects.clone());
        coordinator
            .runtime_status(
                &actor("player-owner", "Owner"),
                time::parse_iso("2026-01-01T10:00:00.000Z").unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(effects.mirrored_runtimes.lock().unwrap().len(), 1);
        // Persist: carry the rows over like a DO eviction/restart would.
        let kv = coordinator.store_mut().as_any_mut().downcast_mut::<KvStore>().unwrap();
        store = KvStore::from_rows(kv.rows().map(|(k, v)| (k.to_string(), v.to_string())));
    }
    let reborn_effects = Arc::new(RecordingEffects::default());
    reborn_effects.set_members(vec![owner_member()]);
    let mut reborn = WorldCoordinator::new("world-1", Box::new(store), reborn_effects.clone());
    reborn
        .runtime_status(&actor("player-owner", "Owner"), time::parse_iso("2026-01-01T10:02:00.000Z").unwrap())
        .await
        .unwrap();
    assert_eq!(reborn_effects.mirrored_runtimes.lock().unwrap().len(), 0);
    assert_eq!(reborn_effects.published.lock().unwrap().len(), 0);
}

// --------------------------------------------------------- socket presence

fn sp_seed(h: &H) {
    h.effects.set_members(vec![
        member("owner-uuid", "Owner", MembershipRole::Owner, "2026-01-01T00:00:00.000Z"),
        member("guest-uuid", "Guest", MembershipRole::Member, "2026-01-01T01:00:00.000Z"),
    ]);
}

fn presence_events(h: &H) -> usize {
    h.effects.events_of_kind(RealtimeEventKind::PresenceChanged).len()
}

fn uuids(players: &[RoomPlayer]) -> Vec<String> {
    players.iter().map(|p| p.player_uuid.clone()).collect()
}

#[tokio::test]
async fn member_announce_joins_roster_once_non_member_dropped() {
    let mut h = make();
    sp_seed(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    let before = presence_events(&h);
    h.coordinator.report_socket_presence("guest-uuid", true, at(5)).await.unwrap();
    assert!(uuids(&h.coordinator.room_players(at(6))).contains(&"guest-uuid".to_string()));
    assert_eq!(
        h.coordinator.room_players(at(6)).iter().find(|p| p.player_uuid == "guest-uuid").unwrap().player_name,
        "Guest"
    );
    assert_eq!(presence_events(&h), before + 1);
    h.coordinator.report_socket_presence("outsider-uuid", true, at(7)).await.unwrap();
    assert!(!uuids(&h.coordinator.room_players(at(8))).contains(&"outsider-uuid".to_string()));
    assert_eq!(presence_events(&h), before + 1);
}

#[tokio::test]
async fn socket_blip_inside_grace_zero_fanout() {
    let mut h = make();
    sp_seed(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.report_socket_presence("guest-uuid", true, at(5)).await.unwrap();
    let publishes = presence_events(&h);
    let mirrors = h.effects.mirrored_presence.lock().unwrap().len();
    h.coordinator.presence_socket_closed("guest-uuid", at(10)).await.unwrap();
    assert!(uuids(&h.coordinator.room_players(at(12))).contains(&"guest-uuid".to_string()));
    h.coordinator.report_socket_presence("guest-uuid", true, at(13)).await.unwrap();
    assert_eq!(presence_events(&h), publishes);
    assert_eq!(h.effects.mirrored_presence.lock().unwrap().len(), mirrors);
    assert!(uuids(&h.coordinator.room_players(at(14))).contains(&"guest-uuid".to_string()));
}

#[tokio::test]
async fn unreturned_socket_pruned_at_grace_alarm() {
    let mut h = make();
    sp_seed(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.report_socket_presence("guest-uuid", true, at(5)).await.unwrap();
    h.coordinator.presence_socket_closed("guest-uuid", at(10)).await.unwrap();
    let publishes = presence_events(&h);
    assert!(!uuids(&h.coordinator.room_players(at(26))).contains(&"guest-uuid".to_string()));
    h.effects.set_keepalive(at(24));
    h.coordinator.on_alarm(at(26)).await.unwrap();
    assert!(h.coordinator.store().list_socket_presence().is_empty());
    assert_eq!(presence_events(&h), publishes + 1);
}

#[tokio::test]
async fn host_roster_wins_socket_and_legacy_merge() {
    let mut h = make();
    sp_seed(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.report_socket_presence("guest-uuid", true, at(5)).await.unwrap();
    h.coordinator
        .report_legacy_presence(
            &guest(),
            &LegacyPresenceArgs { present: true, guest_session_epoch: 1, presence_sequence: 1 },
            at(6),
        )
        .await
        .unwrap();
    assert_eq!(h.coordinator.room_players(at(7)).iter().filter(|p| p.player_uuid == "guest-uuid").count(), 1);
    h.coordinator
        .report_host_players(
            "owner-uuid",
            auth.runtime_epoch.unwrap(),
            vec![rp("owner-uuid", "Owner")],
            at(8),
        )
        .await
        .unwrap();
    assert_eq!(uuids(&h.coordinator.room_players(at(9))), vec!["owner-uuid".to_string()]);
}

#[tokio::test]
async fn kick_removes_entry_and_reannounce_inert() {
    let mut h = make();
    sp_seed(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.report_socket_presence("guest-uuid", true, at(5)).await.unwrap();
    h.effects.set_members(vec![member(
        "owner-uuid",
        "Owner",
        MembershipRole::Owner,
        "2026-01-01T00:00:00.000Z",
    )]);
    h.coordinator.member_revoked("guest-uuid", at(10)).await.unwrap();
    assert!(!uuids(&h.coordinator.room_players(at(11))).contains(&"guest-uuid".to_string()));
    h.coordinator.report_socket_presence("guest-uuid", true, at(12)).await.unwrap();
    assert!(!uuids(&h.coordinator.room_players(at(13))).contains(&"guest-uuid".to_string()));
}

#[tokio::test]
async fn connected_entries_arm_no_alarms_only_grace_does() {
    let mut h = make();
    sp_seed(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    let lease_alarm = h.effects.alarm_at.lock().unwrap().unwrap();
    h.coordinator.report_socket_presence("guest-uuid", true, at(5)).await.unwrap();
    assert_eq!(h.effects.alarm_at.lock().unwrap().unwrap(), lease_alarm);
    h.coordinator.presence_socket_closed("guest-uuid", at(10)).await.unwrap();
    assert_eq!(h.effects.alarm_at.lock().unwrap().unwrap(), at(25));
}

#[tokio::test]
async fn retiring_runtime_clears_socket_presence() {
    let mut h = make();
    sp_seed(&h);
    let auth = become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator.report_socket_presence("guest-uuid", true, at(5)).await.unwrap();
    h.coordinator.release_host(&owner(), &auth, true, at(20)).await.unwrap();
    assert!(h.coordinator.store().list_socket_presence().is_empty());
}

// --------------------------------------------------- legacy presence prune

#[tokio::test]
async fn expired_legacy_entry_never_arms_subsecond_loop() {
    let mut h = make();
    sp_seed(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator
        .report_legacy_presence(
            &guest(),
            &LegacyPresenceArgs { present: true, guest_session_epoch: 1, presence_sequence: 1 },
            at(2),
        )
        .await
        .unwrap();
    h.effects.set_keepalive(at(90));
    h.coordinator.on_alarm(at(95)).await.unwrap();
    let first = h.effects.alarm_at.lock().unwrap().unwrap();
    assert!(time::to_millis(first) - time::to_millis(at(95)) > 30_000);
    h.effects.set_keepalive(at(180));
    h.coordinator.on_alarm(at(185)).await.unwrap();
    let second = h.effects.alarm_at.lock().unwrap().unwrap();
    assert!(time::to_millis(second) - time::to_millis(at(185)) > 30_000);
}

#[tokio::test]
async fn present_entry_leaves_roster_at_expiry_alarm() {
    let mut h = make();
    sp_seed(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator
        .report_legacy_presence(
            &guest(),
            &LegacyPresenceArgs { present: true, guest_session_epoch: 1, presence_sequence: 1 },
            at(2),
        )
        .await
        .unwrap();
    assert!(uuids(&h.coordinator.room_players(at(10))).contains(&"guest-uuid".to_string()));
    h.effects.set_keepalive(at(46));
    h.coordinator.on_alarm(at(48)).await.unwrap();
    assert!(!uuids(&h.coordinator.room_players(at(48))).contains(&"guest-uuid".to_string()));
}

#[tokio::test]
async fn tombstone_fences_stale_resurrect_after_expiry() {
    let mut h = make();
    sp_seed(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator
        .report_legacy_presence(
            &guest(),
            &LegacyPresenceArgs { present: true, guest_session_epoch: 2, presence_sequence: 1 },
            at(2),
        )
        .await
        .unwrap();
    h.coordinator
        .report_legacy_presence(
            &guest(),
            &LegacyPresenceArgs { present: false, guest_session_epoch: 2, presence_sequence: 2 },
            at(5),
        )
        .await
        .unwrap();
    h.effects.set_keepalive(at(88));
    h.coordinator.on_alarm(at(90)).await.unwrap();
    h.coordinator
        .report_legacy_presence(
            &guest(),
            &LegacyPresenceArgs { present: true, guest_session_epoch: 1, presence_sequence: 9 },
            at(95),
        )
        .await
        .unwrap();
    assert!(!uuids(&h.coordinator.room_players(at(96))).contains(&"guest-uuid".to_string()));
}

#[tokio::test]
async fn tombstones_pruned_past_retention() {
    let mut h = make();
    sp_seed(&h);
    become_live_host(&mut h, &owner(), t0()).await;
    h.coordinator
        .report_legacy_presence(
            &guest(),
            &LegacyPresenceArgs { present: false, guest_session_epoch: 2, presence_sequence: 1 },
            at(2),
        )
        .await
        .unwrap();
    assert_eq!(h.coordinator.store().list_legacy_presence().len(), 1);
    h.effects.set_keepalive(at(700));
    h.coordinator.on_alarm(at(710)).await.unwrap();
    assert!(h.coordinator.store().list_legacy_presence().is_empty());
}
