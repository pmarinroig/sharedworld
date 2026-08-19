//! Registry persistence (flush/reload), gateway attach/detach pokes, alarms.

use std::sync::{Arc, Mutex};

use sw_contracts::*;
use sw_core::realtime::gateway::ConnSink;
use sw_core::realtime::local::Realtime;
use sw_core::realtime::*;
use sw_core::time;
use sw_db::repo::{Actor, UserRecord, WorldStorageBinding};
use sw_db::{migrate, Db, Repository};

#[derive(Default)]
struct BufSink {
    frames: Mutex<Vec<String>>,
    closed: Mutex<bool>,
}
impl ConnSink for BufSink {
    fn send_text(&self, text: String) {
        self.frames.lock().unwrap().push(text);
    }
    fn close(&self) {
        *self.closed.lock().unwrap() = true;
    }
}

async fn seed() -> (Repository, String) {
    let db = Db::open_memory().unwrap();
    migrate::migrate(&db).unwrap();
    let repo = Repository::new(db, None);
    let now = time::now_iso();
    for (u, n) in [("owner", "Owner"), ("guest", "Guest")] {
        repo.upsert_user(UserRecord {
            player_uuid: u.into(),
            player_name: n.into(),
            created_at: now.clone(),
        })
        .await
        .unwrap();
    }
    let actor = Actor { player_uuid: "owner".into(), player_name: "Owner".into() };
    let w = repo
        .create_world(
            &actor,
            "W",
            "w",
            WorldStorageBinding { provider: StorageProviderType::GoogleDrive, storage_account_id: None },
            None,
            None,
        )
        .await
        .unwrap();
    repo.add_membership(WorldMembership {
        world_id: w.summary.id.clone(),
        player_uuid: "guest".into(),
        player_name: "Guest".into(),
        role: MembershipRole::Member,
        joined_at: now,
        deleted_at: None,
        can_use_commands: false,
    })
    .await
    .unwrap();
    (repo, w.summary.id)
}

fn actor(uuid: &str, name: &str) -> SessionActor {
    SessionActor {
        player_uuid: uuid.into(),
        player_name: name.into(),
        membership_active: true,
        ever_member: true,
    }
}

#[tokio::test]
async fn state_survives_registry_restart_and_gateway_delivers() {
    let (repo, wid) = seed().await;
    let (rt, _wake) = Realtime::new_manual(repo.clone());
    let owner_sink = Arc::new(BufSink::default());
    let guest_sink = Arc::new(BufSink::default());
    rt.gateway.attach("owner", 1, owner_sink.clone(), time::now(), None, true).await;
    rt.gateway.attach("guest", 2, guest_sink.clone(), time::now(), None, true).await;
    assert_eq!(owner_sink.frames.lock().unwrap()[0], r#"{"v":1,"type":"welcome"}"#);

    let now = time::now();
    let o = actor("owner", "Owner");
    let entry = rt
        .registry
        .call(&wid, |c| Box::pin(async move { c.enter_session(&o, None, false, now).await }))
        .await
        .unwrap();
    assert_eq!(entry.action, EnterSessionAction::Host);
    let a = entry.assignment.unwrap();
    // runtime-changed reached both members over their sockets
    let guest_frames = guest_sink.frames.lock().unwrap().clone();
    assert!(guest_frames.iter().any(|f| f.contains("runtime-changed")), "{guest_frames:?}");
    // alarm persisted + kv persisted
    assert_eq!(repo.coordinator_alarms_all().await.unwrap().len(), 1);
    let rows = repo.coordinator_kv_load(&wid).await.unwrap();
    assert!(rows.iter().any(|(k, _)| k == "runtime"));
    // mirror written
    let mirror = repo.get_runtime_mirror(&wid).await.unwrap().unwrap();
    assert!(mirror.status_json.unwrap().contains("host-starting"));

    // "restart": a fresh registry over the same repository sees the runtime
    let (rt2, _wake2) = Realtime::new_manual(repo.clone());
    let o2 = actor("owner", "Owner");
    let again = rt2
        .registry
        .call(&wid, |c| {
            Box::pin(
                async move { c.enter_session(&o2, None, false, now + chrono::Duration::seconds(1)).await },
            )
        })
        .await
        .unwrap();
    assert_eq!(again.action, EnterSessionAction::Host);
    assert_eq!(again.assignment.unwrap().host_token, a.host_token);

    // host socket close → grace armed via gateway watch plumbing
    let owner_sink2 = Arc::new(BufSink::default());
    rt2.gateway.attach("owner", 3, owner_sink2, time::now(), None, true).await;
    let hb = HeartbeatArgs {
        runtime_epoch: Some(a.runtime_epoch),
        host_token: Some(a.host_token.clone()),
        join_target: Some("j:1".into()),
        minecraft_version: None,
    };
    let o3 = actor("owner", "Owner");
    let status = rt2
        .registry
        .call(&wid, |c| {
            Box::pin(async move { c.heartbeat(&o3, &hb, now + chrono::Duration::seconds(2)).await })
        })
        .await
        .unwrap();
    assert_eq!(status.phase, WorldRuntimePhase::HostLive);
    assert!(rt2.gateway.watches_of("owner").contains(&wid));
    rt2.gateway.detach(3).await;
    let kv = rt2.registry.snapshot_kv(&wid).await.unwrap();
    let link = kv.iter().find(|(k, _)| k == "hostLink").map(|(_, v)| v.clone()).unwrap();
    assert!(link.contains("graceDeadlineAt\":\"20"), "{link}");
    assert!(!rt2.registry.alarms.is_empty());
}
