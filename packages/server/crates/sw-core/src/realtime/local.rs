//! Box wiring: the coordinator effects and persistence over the repository
//! and the in-process gateway (`DoCoordinatorEffects` + `LocalRealtimeService`
//! rolled into one — there is no remote coordinator anymore).

use std::sync::Arc;

use async_trait::async_trait;
use sw_contracts::{RealtimeEvent, RealtimeServerFrame, RoomPlayer, WorldRuntimeStatus};
use sw_db::Repository;

use super::gateway::Gateway;
use super::registry::{AlarmQueue, CoordinatorPersistence, CoordinatorRegistry};
use super::runtime_protocol::RuntimeMembership;
use super::store::CoordinatorEffects;
use crate::http_error::HttpResult;
use crate::time::Instant;

pub struct RepoPersistence(pub Repository);

#[async_trait]
impl CoordinatorPersistence for RepoPersistence {
    async fn load(&self, world_id: &str) -> HttpResult<Vec<(String, String)>> {
        Ok(self.0.coordinator_kv_load(world_id).await?)
    }
    async fn flush(
        &self,
        world_id: &str,
        cleared: bool,
        dirty: Vec<(String, Option<String>)>,
        alarm: Option<Option<String>>,
    ) -> HttpResult<()> {
        Ok(self.0.coordinator_flush(world_id, cleared, dirty, alarm).await?)
    }
    async fn all_alarms(&self) -> HttpResult<Vec<(String, String)>> {
        Ok(self.0.coordinator_alarms_all().await?)
    }
}

pub fn runtime_memberships(list: Vec<sw_contracts::WorldMembership>) -> Vec<RuntimeMembership> {
    list.into_iter()
        .map(|m| RuntimeMembership {
            player_uuid: m.player_uuid,
            player_name: m.player_name,
            role: m.role,
            joined_at: m.joined_at,
            deleted_at: m.deleted_at,
        })
        .collect()
}

pub struct BoxEffects {
    pub repo: Repository,
    pub gateway: Arc<Gateway>,
    pub alarms: Arc<AlarmQueue>,
}

#[async_trait]
impl CoordinatorEffects for BoxEffects {
    async fn list_memberships(&self, world_id: &str) -> HttpResult<Vec<RuntimeMembership>> {
        Ok(runtime_memberships(self.repo.list_memberships(world_id).await?))
    }
    async fn mirror_runtime(&self, world_id: &str, status: &WorldRuntimeStatus) {
        if let Err(e) = self
            .repo
            .upsert_runtime_mirror(world_id, Some(serde_json::to_string(status).expect("json")), None)
            .await
        {
            tracing::warn!(world_id, error = %e, "SharedWorld runtime mirror write failed");
        }
    }
    async fn mirror_presence(&self, world_id: &str, players: &[RoomPlayer]) {
        if let Err(e) = self
            .repo
            .upsert_runtime_mirror(world_id, None, Some(serde_json::to_string(players).expect("json")))
            .await
        {
            tracing::warn!(world_id, error = %e, "SharedWorld presence mirror write failed");
        }
    }
    async fn publish(&self, event: RealtimeEvent, recipients: Option<Vec<String>>) {
        let recipients = match recipients {
            Some(r) => r,
            None => match self.repo.list_memberships(&event.world_id).await {
                Ok(list) => {
                    list.into_iter().filter(|m| m.deleted_at.is_none()).map(|m| m.player_uuid).collect()
                }
                Err(_) => return,
            },
        };
        let text = serde_json::to_string(&RealtimeServerFrame::event(event)).expect("json");
        for p in recipients {
            self.gateway.notify(&p, &text);
        }
    }
    async fn schedule_alarm(&self, world_id: &str, at: Option<Instant>) {
        self.alarms.set(world_id, at);
    }
    async fn set_host_watch(&self, world_id: &str, host_uuid: &str, watching: bool) -> bool {
        self.gateway.set_watch(host_uuid, world_id, watching)
    }
    async fn probe_host_reachability(&self, host_uuid: &str) -> HttpResult<Option<Instant>> {
        Ok(self.gateway.last_seen(host_uuid))
    }
}

/// The fully wired realtime stack for one process.
pub struct Realtime {
    pub registry: Arc<CoordinatorRegistry>,
    pub gateway: Arc<Gateway>,
}

impl Realtime {
    /// Build and start (alarms + eviction loops).
    pub async fn start(repo: Repository) -> HttpResult<Arc<Realtime>> {
        let (registry, wake) = CoordinatorRegistry::new(Arc::new(RepoPersistence(repo.clone())));
        let gateway = Gateway::new(registry.clone());
        registry.set_effects(Arc::new(BoxEffects {
            repo,
            gateway: gateway.clone(),
            alarms: registry.alarms.clone(),
        }));
        registry.start(wake).await?;
        Ok(Arc::new(Realtime { registry, gateway }))
    }

    /// Build without background loops (tests drive alarms by hand).
    pub fn new_manual(repo: Repository) -> (Arc<Realtime>, tokio::sync::mpsc::UnboundedReceiver<()>) {
        let (registry, wake) = CoordinatorRegistry::new(Arc::new(RepoPersistence(repo.clone())));
        let gateway = Gateway::new(registry.clone());
        registry.set_effects(Arc::new(BoxEffects {
            repo,
            gateway: gateway.clone(),
            alarms: registry.alarms.clone(),
        }));
        (Arc::new(Realtime { registry, gateway }), wake)
    }

    /// Publish a durable-data event to explicit recipients (`notifyUsers`).
    pub fn notify_users(&self, event: RealtimeEvent, recipients: &[String]) {
        let text = serde_json::to_string(&RealtimeServerFrame::event(event)).expect("json");
        for p in recipients {
            self.gateway.notify(p, &text);
        }
    }
}
