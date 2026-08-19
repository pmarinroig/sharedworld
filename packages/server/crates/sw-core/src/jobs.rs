//! Background jobs (the worker's cron + piggybacked sweeps, now plain tokio
//! tasks): pending blob-delete drain, expired auth rows, Mojang key refresh,
//! queue-depth gauge.

use std::sync::Arc;
use std::time::Duration;

use metrics::{counter, gauge, histogram};

use crate::service::snapshots::sweep_due_pending_blob_deletes;
use crate::service::Svc;
use crate::time;

#[derive(Debug, Clone)]
pub struct JobsConfig {
    pub pending_delete_interval: Duration,
    pub pending_delete_batch: i64,
    pub prune_interval: Duration,
    pub mojang_refresh_interval: Duration,
}

impl Default for JobsConfig {
    fn default() -> Self {
        Self {
            pending_delete_interval: Duration::from_secs(60),
            pending_delete_batch: 50,
            prune_interval: Duration::from_secs(3600),
            mojang_refresh_interval: Duration::from_secs(24 * 3600),
        }
    }
}

async fn run_job<F, Fut>(name: &'static str, f: F)
where
    F: FnOnce() -> Fut,
    Fut: std::future::Future<Output = Result<String, String>>,
{
    let started = std::time::Instant::now();
    match f().await {
        Ok(summary) => {
            counter!("jobs_run_total", "job" => name, "outcome" => "ok").increment(1);
            tracing::debug!(
                job = name,
                summary,
                elapsed_ms = started.elapsed().as_millis() as u64,
                "job ran"
            );
        }
        Err(e) => {
            counter!("jobs_run_total", "job" => name, "outcome" => "error").increment(1);
            tracing::warn!(job = name, error = %e, "job failed");
        }
    }
    histogram!("jobs_duration_seconds", "job" => name).record(started.elapsed().as_secs_f64());
}

/// Spawn every background loop; returns immediately.
pub fn start(svc: Svc, cfg: JobsConfig) {
    let cfg = Arc::new(cfg);
    // Blob GC drain (the minute cron).
    {
        let svc = svc.clone();
        let cfg = cfg.clone();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(cfg.pending_delete_interval);
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                tick.tick().await;
                let svc2 = svc.clone();
                let batch = cfg.pending_delete_batch;
                run_job("pending_blob_deletes", || async move {
                    let attempted = sweep_due_pending_blob_deletes(&svc2, time::now(), batch).await;
                    if attempted > 0 {
                        tracing::info!(attempted, "SharedWorld blob GC sweep");
                    }
                    if let Ok(depth) = svc2.repository.count_pending_blob_deletes().await {
                        gauge!("pending_blob_deletes_depth").set(depth as f64);
                    }
                    Ok(format!("attempted={attempted}"))
                })
                .await;
            }
        });
    }
    // Expired sessions / challenges.
    {
        let svc = svc.clone();
        let cfg = cfg.clone();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(cfg.prune_interval);
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                tick.tick().await;
                let svc2 = svc.clone();
                run_job("prune_auth_rows", || async move {
                    let mut total = 0;
                    loop {
                        let n = svc2
                            .repository
                            .prune_expired_auth_rows(&time::now_iso(), 2_000)
                            .await
                            .map_err(|e| e.to_string())?;
                        total += n;
                        if n < 2_000 {
                            break;
                        }
                    }
                    Ok(format!("deleted={total}"))
                })
                .await;
            }
        });
    }
    // Mojang services keys self-refresh.
    if svc.config.mojang_keys_self_refresh && svc.config.mojang_player_certificate_keys.is_none() {
        let svc = svc.clone();
        let cfg = cfg.clone();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(cfg.mojang_refresh_interval);
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                tick.tick().await;
                let svc2 = svc.clone();
                run_job("mojang_keys_refresh", || async move {
                    let keys = svc2.auth.services_keys().refresh().await.map_err(|e| e.to_string())?;
                    Ok(format!("keys={}", keys.len()))
                })
                .await;
            }
        });
    }
}
