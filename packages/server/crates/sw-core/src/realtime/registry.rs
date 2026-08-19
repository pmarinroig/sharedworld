//! In-process coordinator registry: one [`WorldActor`] per touched world
//! (async mutex = the DO CallSerializer), lazily loaded from
//! `coordinator_kv`, flushed after every call, evicted when idle; plus the
//! single-alarm-per-world timer wheel, persisted in `coordinator_alarms`.

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::Duration;

use dashmap::DashMap;
use metrics::{counter, gauge};
use tokio::sync::{mpsc, Mutex};

use super::coordinator::WorldCoordinator;
use super::store::{CoordinatorEffects, KvStore};
use crate::http_error::{HttpError, HttpResult};
use crate::time::{self, Instant};

/// Persistence seam for coordinator state (implemented over `sw_db::Repository`).
#[async_trait::async_trait]
pub trait CoordinatorPersistence: Send + Sync {
    async fn load(&self, world_id: &str) -> HttpResult<Vec<(String, String)>>;
    async fn flush(
        &self,
        world_id: &str,
        cleared: bool,
        dirty: Vec<(String, Option<String>)>,
        alarm: Option<Option<String>>,
    ) -> HttpResult<()>;
    async fn all_alarms(&self) -> HttpResult<Vec<(String, String)>>;
}

pub struct WorldActor {
    lock: Mutex<Option<WorldCoordinator>>,
    last_used_ms: AtomicI64,
}

type CallFut<'a, R> = Pin<Box<dyn Future<Output = HttpResult<R>> + Send + 'a>>;

/// Alarm wheel: records the wanted deadline per world (dedupe) and fires
/// `on_alarm` through the registry when due.
pub struct AlarmQueue {
    wanted: StdMutex<HashMap<String, i64>>,
    wake: mpsc::UnboundedSender<()>,
}

impl AlarmQueue {
    fn new() -> (Arc<Self>, mpsc::UnboundedReceiver<()>) {
        let (tx, rx) = mpsc::unbounded_channel();
        (Arc::new(Self { wanted: StdMutex::new(HashMap::new()), wake: tx }), rx)
    }

    /// Returns true when the stored deadline changed (callers persist then).
    pub fn set(&self, world_id: &str, at: Option<Instant>) -> bool {
        let mut wanted = self.wanted.lock().unwrap();
        let changed = match at {
            Some(t) => wanted.insert(world_id.to_string(), time::to_millis(t)) != Some(time::to_millis(t)),
            None => wanted.remove(world_id).is_some(),
        };
        if changed {
            let _ = self.wake.send(());
        }
        changed
    }

    fn due(&self, now_ms: i64) -> Vec<String> {
        let mut wanted = self.wanted.lock().unwrap();
        let due: Vec<String> =
            wanted.iter().filter(|(_, at)| **at <= now_ms).map(|(w, _)| w.clone()).collect();
        for w in &due {
            wanted.remove(w);
        }
        due
    }

    fn next_ms(&self) -> Option<i64> {
        self.wanted.lock().unwrap().values().copied().min()
    }

    pub fn len(&self) -> usize {
        self.wanted.lock().unwrap().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

pub struct CoordinatorRegistry {
    worlds: DashMap<String, Arc<WorldActor>>,
    persistence: Arc<dyn CoordinatorPersistence>,
    effects: StdMutex<Option<Arc<dyn CoordinatorEffects>>>,
    pub alarms: Arc<AlarmQueue>,
    idle_evict_after_ms: i64,
}

impl CoordinatorRegistry {
    /// Build the registry; call [`CoordinatorRegistry::set_effects`] before use
    /// and [`CoordinatorRegistry::start`] to run alarms/eviction.
    pub fn new(persistence: Arc<dyn CoordinatorPersistence>) -> (Arc<Self>, mpsc::UnboundedReceiver<()>) {
        let (alarms, rx) = AlarmQueue::new();
        (
            Arc::new(Self {
                worlds: DashMap::new(),
                persistence,
                effects: StdMutex::new(None),
                alarms,
                idle_evict_after_ms: 30 * 60_000,
            }),
            rx,
        )
    }

    pub fn set_effects(&self, effects: Arc<dyn CoordinatorEffects>) {
        *self.effects.lock().unwrap() = Some(effects);
    }

    fn effects(&self) -> Arc<dyn CoordinatorEffects> {
        self.effects.lock().unwrap().clone().expect("coordinator effects installed")
    }

    /// Load persisted alarms, then run the alarm loop and idle eviction.
    pub async fn start(self: &Arc<Self>, mut wake: mpsc::UnboundedReceiver<()>) -> HttpResult<()> {
        for (world_id, at) in self.persistence.all_alarms().await? {
            if let Some(t) = time::parse_iso(&at) {
                self.alarms.set(&world_id, Some(t));
            }
        }
        let me = Arc::clone(self);
        tokio::spawn(async move {
            loop {
                let now_ms = time::to_millis(time::now());
                let due = me.alarms.due(now_ms);
                for world_id in due {
                    let me2 = Arc::clone(&me);
                    tokio::spawn(async move {
                        counter!("coordinator_alarms_fired_total").increment(1);
                        let now = time::now();
                        if let Err(e) = me2.call(&world_id, move |c| Box::pin(c.on_alarm(now))).await {
                            tracing::warn!(world_id, error = %e, "SharedWorld coordinator alarm failed");
                        }
                    });
                }
                let sleep_ms = me
                    .alarms
                    .next_ms()
                    .map(|n| (n - time::to_millis(time::now())).clamp(1, 60_000))
                    .unwrap_or(60_000);
                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_millis(sleep_ms as u64)) => {}
                    _ = wake.recv() => {}
                }
            }
        });
        let me = Arc::clone(self);
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(60)).await;
                me.evict_idle();
                gauge!("coordinator_worlds_loaded").set(me.worlds.len() as f64);
            }
        });
        Ok(())
    }

    fn evict_idle(&self) {
        let cutoff = time::to_millis(time::now()) - self.idle_evict_after_ms;
        let stale: Vec<String> = self
            .worlds
            .iter()
            .filter(|e| e.value().last_used_ms.load(Ordering::Relaxed) < cutoff)
            .map(|e| e.key().clone())
            .collect();
        for w in stale {
            if let Some((_, actor)) = self.worlds.remove_if(&w, |_, a| a.lock.try_lock().is_ok()) {
                drop(actor);
            }
        }
    }

    pub fn loaded_worlds(&self) -> usize {
        self.worlds.len()
    }

    /// Run one serialized coordinator call for `world_id`, then flush its
    /// dirty state (and any alarm change) before returning.
    pub async fn call<R, F>(&self, world_id: &str, f: F) -> HttpResult<R>
    where
        R: Send + 'static,
        F: for<'a> FnOnce(&'a mut WorldCoordinator) -> CallFut<'a, R> + Send,
    {
        let actor = self
            .worlds
            .entry(world_id.to_string())
            .or_insert_with(|| {
                Arc::new(WorldActor { lock: Mutex::new(None), last_used_ms: AtomicI64::new(0) })
            })
            .clone();
        actor.last_used_ms.store(time::to_millis(time::now()), Ordering::Relaxed);
        let mut guard = actor.lock.lock().await;
        if guard.is_none() {
            let rows = self.persistence.load(world_id).await?;
            let store = KvStore::from_rows(rows);
            let mut coordinator = WorldCoordinator::new(world_id, Box::new(store), self.effects());
            coordinator.on_loaded(time::now()).await;
            *guard = Some(coordinator);
        }
        let coordinator = guard.as_mut().expect("loaded");
        let alarm_before = self.alarms.wanted.lock().unwrap().get(world_id).copied();
        let result = f(coordinator).await;
        // Flush regardless of the call's outcome: state may have moved before
        // the error (e.g. lease expiry inside resolve()).
        let (cleared, dirty) = downcast_kv(coordinator).map(|kv| kv.take_dirty()).unwrap_or((false, vec![]));
        let alarm_after = self.alarms.wanted.lock().unwrap().get(world_id).copied();
        let alarm = if alarm_before != alarm_after {
            Some(alarm_after.map(|ms| time::to_iso(time::from_millis(ms))))
        } else {
            None
        };
        if let Err(e) = self.persistence.flush(world_id, cleared, dirty, alarm).await {
            tracing::error!(world_id, error = %e, "SharedWorld coordinator state flush failed");
            return Err(HttpError::new(
                503,
                "realtime_unavailable",
                "Realtime state could not be persisted.",
            )
            .with_retry_after(2));
        }
        result
    }

    /// Box-only: persisted coordinator kv for tooling/tests.
    pub async fn snapshot_kv(&self, world_id: &str) -> HttpResult<Vec<(String, String)>> {
        self.call(world_id, |c| {
            Box::pin(async move {
                Ok(downcast_kv(c)
                    .map(|kv| kv.rows().map(|(k, v)| (k.to_string(), v.to_string())).collect())
                    .unwrap_or_default())
            })
        })
        .await
    }
}

fn downcast_kv(c: &mut WorldCoordinator) -> Option<&mut KvStore> {
    c.store_mut().as_any_mut().downcast_mut::<KvStore>()
}
