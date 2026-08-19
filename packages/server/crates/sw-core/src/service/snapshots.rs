//! Snapshots domain (`service/snapshots.ts`): list/latest/restore/delete,
//! finalize (validation, chain accounting, self-contained chain recipes, the
//! 0027 manifest document write lane), retention, and the blob GC sweeps.

use std::collections::{HashMap, HashSet, VecDeque};

use sw_contracts::sync::{
    is_region_bundle_id, DELTA_V2_FORMAT_VERSION, DELTA_V2_MAX_CHAIN_DEPTH, PACK_DELTA_TRANSFER_MODE,
    PACK_FULL_TRANSFER_MODE, REGION_DELTA_TRANSFER_MODE, REGION_FULL_TRANSFER_MODE, WHOLE_GZIP_TRANSFER_MODE,
};
use sw_contracts::{
    DeleteSnapshotsResult, FileTransferMode, FinalizeSnapshotRequest, ManifestFile, PackChainStep,
    RealtimeEventKind, SnapshotActionResult, SnapshotManifest, SnapshotPack, WorldRuntimePhase,
    WorldSnapshotSummary,
};
use sw_db::repo::{
    PendingBlobDeleteRecord, SnapshotRecord, StorageReferenceScope, WorldStorageBinding,
    SNAPSHOT_CREATED_AT_SLACK_MS,
};

use super::runtime_access::*;
use super::{ServiceContext, Svc};
use crate::http_error::{HttpError, HttpResult};
use crate::request::RequestContext;
use crate::storage::manifest_doc::build_manifest_document;
use crate::storage::PutBody;
use crate::time::{self, Instant};

/// Age policy (0.4.5 schedule): every snapshot for the last hour, then one per
/// hour up to two days, one per day up to 30 days, one per month beyond.
/// Before 0.4.5 the keep-all window was 24h: with a 5-10 min autosave that
/// pinned a day of near-duplicate history and was the main reason free Google
/// Drives filled up; the fine-grained backups only matter for the last hour
/// or so of a session (rollback recovery), which the schedule keeps.
const SNAPSHOT_RETENTION_ALL_RECENT_MS: i64 = 60 * 60_000;
const SNAPSHOT_RETENTION_HOURLY_MS: i64 = 48 * 60 * 60_000;
const SNAPSHOT_RETENTION_DAILY_MS: i64 = 30 * 24 * 60 * 60_000;
const SNAPSHOT_RETENTION_INTERVAL_MS: i64 = 60 * 60_000;

/// Post-response work gets ~30s before the runtime reclaims the isolate; blob
/// deletes stop well inside that and hand the rest to the queue.
pub const DEFERRED_BLOB_DELETE_BUDGET_MS: i64 = 15_000;

const PENDING_BLOB_DELETE_SWEEP_LIMIT: i64 = 3;
/// Per cron tick. Every key costs a handful of provider/row round-trips (the
/// reference check is one query for the whole tick), so the tick stays small
/// and relies on running often.
pub const PENDING_BLOB_DELETE_CRON_LIMIT: i64 = 8;

/// Base-snapshot headers shared by validation, chain accounting and recipe
/// stamping. A cached `None` is "the repository does not know this id".
pub type SnapshotHeadersCache = HashMap<String, Option<SnapshotManifest>>;

/// Existence fallback when there is no object metadata to check.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WhenUnverifiable {
    AssumePresent,
    AskProvider,
}

pub fn is_delta_pack_transfer_mode(mode: FileTransferMode) -> bool {
    matches!(mode, FileTransferMode::RegionDelta | FileTransferMode::PackDelta)
}

pub fn normalize_file_transfer_mode(mode: Option<FileTransferMode>) -> FileTransferMode {
    mode.unwrap_or(WHOLE_GZIP_TRANSFER_MODE)
}

fn snapshot_not_found_error() -> HttpError {
    HttpError::new(404, "snapshot_not_found", "SharedWorld backup not found.")
}

fn deferred_budget_of(ctx: &RequestContext) -> Option<i64> {
    if ctx.defer.is_some() {
        Some(DEFERRED_BLOB_DELETE_BUDGET_MS)
    } else {
        None
    }
}

// ---------------------------------------------------------------------------
// Reads
// ---------------------------------------------------------------------------

pub async fn list_snapshots(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
) -> HttpResult<Vec<WorldSnapshotSummary>> {
    require_membership(svc, ctx, world_id).await?;
    Ok(svc.repository.list_snapshot_summaries(world_id).await?)
}

pub async fn latest_manifest(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
) -> HttpResult<Option<SnapshotManifest>> {
    require_active_membership(svc, ctx, world_id).await?;
    Ok(svc.repository.get_latest_snapshot(world_id).await?.map(|m| (*m).clone()))
}

// ---------------------------------------------------------------------------
// Restore / delete
// ---------------------------------------------------------------------------

/// Restoring a backup republishes it as the newest snapshot rather than
/// rewriting history; the restored manifest keeps pointing at the already
/// stored artifacts. The republished snapshot carries the original's
/// game-version stamps so the cross-version guardrail keeps working on
/// restored worlds, and restore is refused while any host runtime is active:
/// changing the latest snapshot under a live host would invalidate its
/// in-flight delta bases.
pub async fn restore_snapshot(
    svc: &Svc,
    ctx: &RequestContext,
    world_id: &str,
    snapshot_id: &str,
    now: Instant,
) -> HttpResult<SnapshotActionResult> {
    let world = require_world_details(svc, world_id, &ctx.player_uuid).await?;
    require_owner(&world, ctx, "restore backups")?;
    let actor = session_actor_of(svc, ctx, world_id).await?;
    let runtime = svc
        .realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.runtime_status(&actor, now).await }))
        .await?;
    if matches!(
        runtime.phase,
        WorldRuntimePhase::HostStarting | WorldRuntimePhase::HostLive | WorldRuntimePhase::HostFinalizing
    ) {
        return Err(HttpError::new(
            409,
            "world_busy",
            "SharedWorld backups cannot be restored while the world is being hosted.",
        ));
    }
    let snapshot =
        svc.repository.get_snapshot(world_id, snapshot_id).await?.ok_or_else(snapshot_not_found_error)?;
    let game_versions = svc.repository.get_snapshot_game_versions(world_id, snapshot_id).await?;
    let request = FinalizeSnapshotRequest {
        runtime_epoch: None,
        host_token: None,
        base_snapshot_id: Some(snapshot.snapshot_id.clone()),
        data_version: game_versions.as_ref().and_then(|v| v.data_version),
        minecraft_version: game_versions.as_ref().and_then(|v| v.minecraft_version.clone()),
        files: snapshot.files.clone(),
        packs: Some(snapshot.packs.clone()),
    };
    persist_snapshot(svc, world_id, ctx, &request, now, &mut SnapshotHeadersCache::new()).await?;
    let budget = deferred_budget_of(ctx);
    let (svc2, wid) = (svc.clone(), world_id.to_string());
    ctx.run_after_response(async move {
        if let Err(error) = apply_snapshot_retention(&svc2, &wid, now, budget).await {
            tracing::warn!(cause = %error, "SharedWorld snapshot retention after restore failed");
        }
    })
    .await;
    Ok(SnapshotActionResult { world_id: world_id.to_string(), snapshot_id: snapshot_id.to_string() })
}

pub async fn delete_snapshot(
    svc: &Svc,
    ctx: &RequestContext,
    world_id: &str,
    snapshot_id: &str,
) -> HttpResult<SnapshotActionResult> {
    delete_snapshots(svc, ctx, world_id, &[snapshot_id.to_string()]).await?;
    Ok(SnapshotActionResult { world_id: world_id.to_string(), snapshot_id: snapshot_id.to_string() })
}

/// Deletes a set of backups in one pass: one write batch, one unreferenced-key
/// computation for the whole set (keys shared between the deleted backups are
/// resolved once), and the response goes out as soon as the rows are gone. The
/// rows are the source of truth — a deleted backup can no longer be restored
/// and no longer counts as used storage — so the provider deletes run after
/// the response under a time budget, with the remainder queued for the cron
/// sweep. Before 0.4.5 every Drive delete ran inline, which put a big backup
/// past the mod's 20s request timeout even though the rows were already gone.
pub async fn delete_snapshots(
    svc: &Svc,
    ctx: &RequestContext,
    world_id: &str,
    snapshot_ids: &[String],
) -> HttpResult<DeleteSnapshotsResult> {
    let world = require_world_details(svc, world_id, &ctx.player_uuid).await?;
    require_owner(&world, ctx, "delete backups")?;
    let binding = require_world_storage_binding(svc, world_id).await?;
    let mut requested: Vec<String> = Vec::new();
    let mut seen: HashSet<&str> = HashSet::new();
    for snapshot_id in snapshot_ids {
        if !snapshot_id.is_empty() && seen.insert(snapshot_id.as_str()) {
            requested.push(snapshot_id.clone());
        }
    }
    if requested.is_empty() {
        return Err(HttpError::new(400, "invalid_request", "snapshotIds must name at least one backup."));
    }
    if world.summary.last_snapshot_id.as_ref().is_some_and(|id| requested.contains(id)) {
        return Err(HttpError::new(
            409,
            "cannot_delete_latest_snapshot",
            "The latest backup cannot be deleted.",
        ));
    }
    let existing = svc.repository.existing_snapshot_ids(world_id, &requested).await?;
    let delete_ids: Vec<String> =
        requested.into_iter().filter(|snapshot_id| existing.contains(snapshot_id)).collect();
    if delete_ids.is_empty() {
        return Err(snapshot_not_found_error());
    }
    // S1: edges come only from non-self-contained referrers, so stamped
    // snapshots pin nothing and old backups become individually deletable.
    // The residual 409 covers only legacy snapshots that still resolve their
    // chains by walking base snapshot rows — and only when the dependant is
    // not itself part of this deletion.
    let deleting: HashSet<&String> = delete_ids.iter().collect();
    let delta_bases = svc.repository.list_snapshot_delta_bases(world_id).await?;
    if delta_bases.iter().any(|edge| {
        deleting.contains(&edge.base_snapshot_id)
            && edge.snapshot_id != edge.base_snapshot_id
            && !deleting.contains(&edge.snapshot_id)
    }) {
        return Err(HttpError::new(
            409,
            "snapshot_base_in_use",
            "A newer backup still needs this one to stay restorable. It will become deletable automatically as backups refresh.",
        ));
    }
    let deletion = svc.repository.delete_snapshots(world_id, &delete_ids).await?;
    let budget = deferred_budget_of(ctx);
    let (svc2, keys) = (svc.clone(), deletion.unreferenced_storage_keys.clone());
    ctx.run_after_response(async move {
        if let Err(error) = delete_unreferenced_blobs(&svc2, &binding, &keys, budget).await {
            tracing::warn!(cause = %error, "SharedWorld blob cleanup after backup delete failed");
        }
    })
    .await;
    Ok(DeleteSnapshotsResult { world_id: world_id.to_string(), deleted_snapshot_ids: delete_ids })
}

// ---------------------------------------------------------------------------
// Finalize / persist
// ---------------------------------------------------------------------------

pub async fn finalize_snapshot(
    svc: &Svc,
    ctx: &RequestContext,
    world_id: &str,
    request: &FinalizeSnapshotRequest,
    now: Instant,
) -> HttpResult<SnapshotManifest> {
    require_host_authority(
        svc,
        ctx,
        world_id,
        request.runtime_epoch,
        request.host_token.as_deref(),
        &[WorldRuntimePhase::HostStarting, WorldRuntimePhase::HostLive, WorldRuntimePhase::HostFinalizing],
        now,
    )
    .await?;
    // One header cache for validation, chain accounting and recipe stamping,
    // primed with every base snapshot the request references in a fixed
    // number of queries: the three passes used to each load the same bases
    // one at a time (three sequential round-trips per base per pass — ~18s of
    // finalize wall time on delta-heavy worlds).
    let mut request = request.clone();
    let mut headers_cache = prefetch_base_snapshot_headers(svc, world_id, &request).await?;
    validate_finalize_snapshot_request(svc, world_id, &request, &mut headers_cache).await?;
    compute_chain_delta_bytes(svc, world_id, &mut request, &mut headers_cache).await?;
    let manifest = persist_snapshot(svc, world_id, ctx, &request, now, &mut headers_cache).await?;
    publish_world_event(svc, world_id, RealtimeEventKind::SnapshotChanged).await?;
    // Retention runs at most hourly per world (CAS claim): it only ever
    // deletes old snapshots, so per-finalize cadence bought nothing but
    // delete/promotion writes on every autosave. Manual delete/restore keep
    // their immediate retention passes.
    //
    // It runs AFTER the response whenever the runtime allows: the snapshot is
    // durable the moment persist_snapshot returns, while the retention pass —
    // provider deletes with retry ladders, legacy-chain upgrades — was
    // measured at 19-46s inline, past the mod's 20s request timeout. The pass
    // is cutoff-safe: blob deletes run under a time budget and the remainder
    // is queued for the bounded sweeps.
    //
    // 0.4.6: an owner cap (maxBackups) is a hard limit, not a schedule — with
    // the hourly slot alone a "None" world accumulated one backup per save for
    // up to an hour. A capped world runs the pass on every finalize (cheap when
    // nothing exceeds the cap); uncapped worlds keep the hourly cadence.
    let capped = svc
        .repository
        .get_world_settings(world_id)
        .await?
        .and_then(|row| row.settings)
        .and_then(|settings| settings.max_backups.flatten())
        .is_some();
    if capped || svc.repository.claim_retention_slot(world_id, now, SNAPSHOT_RETENTION_INTERVAL_MS).await? {
        let budget = deferred_budget_of(ctx);
        let (svc2, wid) = (svc.clone(), world_id.to_string());
        ctx.run_after_response(async move {
            if let Err(error) = apply_snapshot_retention(&svc2, &wid, now, budget).await {
                tracing::warn!(world_id = %wid, cause = %error, "SharedWorld snapshot retention failed");
            }
        })
        .await;
    }
    Ok(manifest)
}

/// 0027 write path: persist the snapshot with its pack member lists as one
/// content-addressed manifest document in the world's storage instead of
/// per-file rows, falling back to legacy rows when the document cannot be
/// written (autosave availability beats format purity — the provider was
/// necessarily reachable seconds earlier for the artifact uploads, but a flake
/// here must not fail the snapshot). The document upload strictly precedes the
/// row batch: an orphaned doc left by a failed batch is inert
/// content-addressed garbage that the retried finalize adopts via the
/// existence check, whereas the reverse order could commit a snapshot whose
/// manifest can never load.
pub async fn persist_snapshot(
    svc: &ServiceContext,
    world_id: &str,
    ctx: &RequestContext,
    request: &FinalizeSnapshotRequest,
    now: Instant,
    headers_cache: &mut SnapshotHeadersCache,
) -> HttpResult<SnapshotManifest> {
    // Stamped here so BOTH producers (finalize and restore) emit
    // self-contained snapshots — restore republishes packs whose recipes
    // inherit from the restored-from snapshot's directory.
    let mut request = request.clone();
    stamp_chain_steps(svc, world_id, &mut request, headers_cache).await?;
    let mut manifest_storage_key: Option<String> = None;
    if !request.packs.as_deref().unwrap_or(&[]).is_empty() {
        let binding = require_world_storage_binding(svc, world_id).await?;
        if svc.storage_provider.manifest_doc_capable(&binding) {
            match write_manifest_document(svc, &binding, request.packs.as_deref().unwrap_or(&[])).await {
                Ok(storage_key) => manifest_storage_key = Some(storage_key),
                Err(error) => tracing::warn!(
                    world_id,
                    cause = %error,
                    "SharedWorld manifest document write failed; falling back to row manifest"
                ),
            }
        }
    }
    // Row-batch failures propagate as always — only the doc write falls back.
    let manifest =
        svc.repository.finalize_snapshot(world_id, &ctx.actor(), &request, now, manifest_storage_key).await?;
    Ok((*manifest).clone())
}

async fn write_manifest_document(
    svc: &ServiceContext,
    binding: &WorldStorageBinding,
    packs: &[SnapshotPack],
) -> HttpResult<String> {
    let built = build_manifest_document(packs);
    if !storage_key_exists(svc, binding, &built.storage_key).await? {
        svc.storage_provider
            .put(
                binding,
                &built.storage_key,
                PutBody::Bytes(bytes::Bytes::from(built.bytes)),
                "application/json",
            )
            .await?;
    }
    Ok(built.storage_key)
}

// ---------------------------------------------------------------------------
// Retention
// ---------------------------------------------------------------------------

/// Retention keeps every snapshot from the last hour, one per hour for two
/// days, one per day for a month and one per month beyond that; the newest
/// snapshot is always kept. Cleanup failures are logged, never propagated:
/// retention must not fail a successful snapshot.
pub async fn apply_snapshot_retention(
    svc: &ServiceContext,
    world_id: &str,
    now: Instant,
    blob_delete_budget_ms: Option<i64>,
) -> HttpResult<()> {
    let snapshots = svc.repository.list_snapshots_for_world(world_id).await?;
    let max_backups = svc
        .repository
        .get_world_settings(world_id)
        .await?
        .and_then(|row| row.settings)
        .and_then(|settings| settings.max_backups.flatten());
    let mut keep = select_snapshots_to_keep(&snapshots, now, max_backups);
    // S1 lazy upgrade: make the KEPT snapshots self-contained first, so the
    // closure below stops protecting their whole ancestry and the rest of the
    // history becomes deletable. One-time per legacy snapshot; no-op after.
    if let Err(error) = upgrade_kept_snapshots_to_self_contained(svc, world_id, &keep).await {
        tracing::warn!(
            world_id,
            cause = %error,
            "SharedWorld chain-steps upgrade failed; retention stays conservative"
        );
    }
    expand_keep_set_with_delta_bases(svc, world_id, &mut keep).await?;
    let delete_ids: Vec<String> = snapshots
        .iter()
        .map(|snapshot| snapshot.snapshot_id.clone())
        .filter(|snapshot_id| !keep.contains(snapshot_id))
        .collect();

    if let Err(error) = retention_cleanup(svc, world_id, &delete_ids, now, blob_delete_budget_ms).await {
        tracing::warn!(world_id, cause = %error, "SharedWorld snapshot retention cleanup failed");
    }
    Ok(())
}

async fn retention_cleanup(
    svc: &ServiceContext,
    world_id: &str,
    delete_ids: &[String],
    now: Instant,
    blob_delete_budget_ms: Option<i64>,
) -> HttpResult<()> {
    let binding = require_world_storage_binding(svc, world_id).await?;
    if !delete_ids.is_empty() {
        let deletion = svc.repository.delete_snapshots(world_id, delete_ids).await?;
        delete_unreferenced_blobs(svc, &binding, &deletion.unreferenced_storage_keys, blob_delete_budget_ms)
            .await?;
    }
    // Piggybacked 0028 retry sweep: rides the same hourly retention slot.
    sweep_pending_blob_deletes(svc, &binding, now).await
}

pub async fn purge_world_snapshots(
    svc: &ServiceContext,
    binding: &WorldStorageBinding,
    world_id: &str,
) -> HttpResult<()> {
    if let Err(error) = purge_world_snapshots_inner(svc, binding, world_id).await {
        tracing::warn!(world_id, cause = %error, "SharedWorld world storage cleanup failed");
    }
    Ok(())
}

async fn purge_world_snapshots_inner(
    svc: &ServiceContext,
    binding: &WorldStorageBinding,
    world_id: &str,
) -> HttpResult<()> {
    let snapshots = svc.repository.list_snapshots_for_world(world_id).await?;
    let ids: Vec<String> = snapshots.into_iter().map(|snapshot| snapshot.snapshot_id).collect();
    let deletion = svc.repository.delete_snapshots(world_id, &ids).await?;
    delete_unreferenced_blobs(svc, binding, &deletion.unreferenced_storage_keys, None).await
}

/// Retention buckets purely by age, but a delta snapshot is only
/// reconstructable while every base in its chain still exists: pruning a base
/// would let `delete_snapshots` reclaim the base's blobs and leave the
/// surviving delta permanently unreconstructable. Keep the transitive closure
/// of delta bases reachable from every kept snapshot. (Inherited pack MEMBER
/// rows need no such protection: `delete_snapshots` promotes them to a
/// surviving heir, so member donors are freely prunable — keeping them here
/// would transitively retain nearly every autosave and defeat retention.)
async fn expand_keep_set_with_delta_bases(
    svc: &ServiceContext,
    world_id: &str,
    keep: &mut HashSet<String>,
) -> HttpResult<()> {
    let edges = svc.repository.list_snapshot_delta_bases(world_id).await?;
    let mut bases_by_referrer: HashMap<String, Vec<String>> = HashMap::new();
    for edge in edges {
        bases_by_referrer.entry(edge.snapshot_id).or_default().push(edge.base_snapshot_id);
    }
    let mut pending: Vec<String> = keep.iter().cloned().collect();
    while let Some(snapshot_id) = pending.pop() {
        for base_snapshot_id in bases_by_referrer.get(&snapshot_id).into_iter().flatten() {
            if keep.insert(base_snapshot_id.clone()) {
                pending.push(base_snapshot_id.clone());
            }
        }
    }
    Ok(())
}

fn select_snapshots_to_keep(
    snapshots: &[SnapshotRecord],
    now: Instant,
    max_backups: Option<i64>,
) -> HashSet<String> {
    let keep = select_snapshots_to_keep_by_age(snapshots, now);
    let Some(max_backups) = max_backups else { return keep };
    if keep.len() as i64 <= max_backups {
        return keep;
    }
    // Owner cap (0.4.2 maxBackups): drop the OLDEST age-kept snapshots beyond
    // the cap. `snapshots` is newest-first, so taking the first N kept ids
    // always retains the latest (and a cap below 1 still keeps the latest).
    let mut capped = HashSet::new();
    for snapshot in snapshots {
        if keep.contains(&snapshot.snapshot_id) {
            capped.insert(snapshot.snapshot_id.clone());
            if capped.len() as i64 >= max_backups {
                break;
            }
        }
    }
    capped
}

fn select_snapshots_to_keep_by_age(snapshots: &[SnapshotRecord], now: Instant) -> HashSet<String> {
    let mut keep: HashSet<String> = HashSet::new();
    let now_time = time::to_millis(now);
    let mut hourly_buckets: HashSet<&str> = HashSet::new();
    let mut daily_buckets: HashSet<&str> = HashSet::new();
    let mut monthly_buckets: HashSet<&str> = HashSet::new();

    for snapshot in snapshots {
        let Some(created) = time::parse_iso(&snapshot.created_at) else {
            keep.insert(snapshot.snapshot_id.clone());
            continue;
        };
        let age_ms = (now_time - time::to_millis(created)).max(0);
        if keep.is_empty() || age_ms <= SNAPSHOT_RETENTION_ALL_RECENT_MS {
            keep.insert(snapshot.snapshot_id.clone());
            continue;
        }
        if age_ms <= SNAPSHOT_RETENTION_HOURLY_MS {
            if hourly_buckets.insert(iso_prefix(&snapshot.created_at, 13)) {
                keep.insert(snapshot.snapshot_id.clone());
            }
            continue;
        }
        if age_ms <= SNAPSHOT_RETENTION_DAILY_MS {
            if daily_buckets.insert(iso_prefix(&snapshot.created_at, 10)) {
                keep.insert(snapshot.snapshot_id.clone());
            }
            continue;
        }
        if monthly_buckets.insert(iso_prefix(&snapshot.created_at, 7)) {
            keep.insert(snapshot.snapshot_id.clone());
        }
    }
    keep
}

/// `createdAt.slice(0, n)` on the ASCII ISO stamps the backend stores.
fn iso_prefix(created_at: &str, n: usize) -> &str {
    let end = created_at.char_indices().nth(n).map(|(i, _)| i).unwrap_or(created_at.len());
    &created_at[..end]
}

// ---------------------------------------------------------------------------
// Blob GC
// ---------------------------------------------------------------------------

/// Deletes blobs whose last referencing rows are already gone. With a
/// `budget_ms`, deletes stop once the budget elapses and every remaining key
/// is queued for the bounded sweeps instead — the caller is running after the
/// response and may be reclaimed by the runtime at any moment past that.
pub async fn delete_unreferenced_blobs(
    svc: &ServiceContext,
    binding: &WorldStorageBinding,
    storage_keys: &[String],
    budget_ms: Option<i64>,
) -> HttpResult<()> {
    let deadline = budget_ms.map(|ms| time::to_millis(time::now()) + ms);
    for (index, storage_key) in storage_keys.iter().enumerate() {
        if deadline.is_some_and(|deadline| time::to_millis(time::now()) >= deadline) {
            let remaining = &storage_keys[index..];
            if let Some(account) = &binding.storage_account_id {
                svc.repository
                    .enqueue_pending_blob_deletes(binding.provider, account, remaining, &time::now_iso())
                    .await?;
            }
            tracing::warn!(
                remaining = remaining.len(),
                budget_ms,
                "SharedWorld blob cleanup deferred to the sweep queue"
            );
            return Ok(());
        }
        if let Err(error) = svc.storage_provider.delete(binding, storage_key).await {
            tracing::warn!(storage_key, cause = %error, "SharedWorld blob cleanup failed for");
            // 0028: the unreferenced-key computation runs exactly once
            // (candidates come from rows that are already deleted), so a
            // dropped delete used to orphan the bytes permanently. Enqueue
            // for the bounded sweep.
            if let Some(account) = &binding.storage_account_id {
                if let Err(error) = svc
                    .repository
                    .enqueue_pending_blob_delete(binding.provider, account, storage_key, &time::now_iso())
                    .await
                {
                    tracing::warn!(
                        storage_key,
                        cause = %error,
                        "SharedWorld pending-delete enqueue failed"
                    );
                }
            }
        }
    }
    Ok(())
}

/// Bounded retry of previously-failed blob deletes (0028), request-driven from
/// the hourly retention slot (the cron drain below is the unattended one).
/// Re-referenced keys are dropped without deleting: content-addressed dedupe
/// can legitimately resurrect a key between enqueue and sweep.
pub async fn sweep_pending_blob_deletes(
    svc: &ServiceContext,
    binding: &WorldStorageBinding,
    now: Instant,
) -> HttpResult<()> {
    let Some(account) = binding.storage_account_id.clone() else { return Ok(()) };
    if let Err(error) = sweep_pending_blob_deletes_inner(svc, binding, &account, now).await {
        tracing::warn!(cause = %error, "SharedWorld pending blob delete sweep failed");
    }
    Ok(())
}

async fn sweep_pending_blob_deletes_inner(
    svc: &ServiceContext,
    binding: &WorldStorageBinding,
    account: &str,
    now: Instant,
) -> HttpResult<()> {
    let pending = svc
        .repository
        .list_pending_blob_deletes(binding.provider, account, PENDING_BLOB_DELETE_SWEEP_LIMIT)
        .await?;
    let entries: Vec<PendingBlobDeleteRecord> = pending
        .into_iter()
        .map(|entry| PendingBlobDeleteRecord {
            provider: binding.provider,
            storage_account_id: account.to_string(),
            storage_key: entry.storage_key,
            attempts: entry.attempts,
            enqueued_at: entry.enqueued_at,
        })
        .collect();
    retry_pending_blob_deletes(svc, &entries, now).await
}

/// 0.4.5 cron drain: the unattended counterpart of the request-driven sweeps.
/// Instant-ack deletes and post-response GC hand their overflow to the queue,
/// so a world that goes quiet must not leave bytes stranded until its next
/// upload. Returns how many entries were attempted (for logging/tests).
pub async fn sweep_due_pending_blob_deletes(svc: &ServiceContext, now: Instant, limit: i64) -> usize {
    match sweep_due_pending_blob_deletes_inner(svc, now, limit).await {
        Ok(attempted) => attempted,
        Err(error) => {
            tracing::warn!(cause = %error, "SharedWorld scheduled blob delete sweep failed");
            0
        }
    }
}

async fn sweep_due_pending_blob_deletes_inner(
    svc: &ServiceContext,
    now: Instant,
    limit: i64,
) -> HttpResult<usize> {
    let due = svc.repository.list_due_pending_blob_deletes(&time::to_iso(now), limit).await?;
    retry_pending_blob_deletes(svc, &due, now).await?;
    Ok(due.len())
}

/// Retries a batch of queued deletes with ONE reference check per storage
/// account. Every queued key was verified unreferenced when it was enqueued
/// (`delete_snapshots` resolves candidates against every surviving snapshot);
/// pack directories only ever gain references from snapshots alive at the time
/// (finalize, restore, S1 chain-step stamping copies from living ancestors),
/// so the only thing that can resurrect a queued key is a snapshot created
/// after the enqueue. The re-check is therefore scoped to the account's
/// snapshots created since the oldest enqueue in the batch (with created_at
/// slack) instead of the whole fleet's directories — the per-key fleet scan
/// cost ~875k rows read each on 2026-08-17.
async fn retry_pending_blob_deletes(
    svc: &ServiceContext,
    entries: &[PendingBlobDeleteRecord],
    now: Instant,
) -> HttpResult<()> {
    let mut groups: Vec<(String, Vec<&PendingBlobDeleteRecord>)> = Vec::new();
    for entry in entries {
        let group_key = format!("{}\u{0}{}", entry.provider.as_str(), entry.storage_account_id);
        match groups.iter_mut().find(|(key, _)| *key == group_key) {
            Some((_, group)) => group.push(entry),
            None => groups.push((group_key, vec![entry])),
        }
    }
    for (_, group) in groups {
        let provider = group[0].provider;
        let storage_account_id = group[0].storage_account_id.clone();
        let binding = WorldStorageBinding { provider, storage_account_id: Some(storage_account_id.clone()) };
        let oldest_enqueue =
            group.iter().filter_map(|entry| time::parse_iso(&entry.enqueued_at).map(time::to_millis)).min();
        let keys: Vec<String> = group.iter().map(|entry| entry.storage_key.clone()).collect();
        let referenced = svc
            .repository
            .filter_referenced_storage_keys(
                &keys,
                Some(StorageReferenceScope {
                    provider,
                    storage_account_id: Some(storage_account_id.clone()),
                    snapshots_created_since: oldest_enqueue
                        .map(|oldest| time::to_iso(time::from_millis(oldest - SNAPSHOT_CREATED_AT_SLACK_MS))),
                }),
            )
            .await?;
        for entry in group {
            if referenced.contains(&entry.storage_key) {
                tracing::info!(
                    storage_key = %entry.storage_key,
                    storage_account_id = %storage_account_id,
                    "SharedWorld pending blob delete dropped: key re-referenced"
                );
                svc.repository
                    .delete_pending_blob_delete(provider, &storage_account_id, &entry.storage_key)
                    .await?;
                continue;
            }
            match svc.storage_provider.delete(&binding, &entry.storage_key).await {
                Ok(()) => {
                    svc.repository
                        .delete_pending_blob_delete(provider, &storage_account_id, &entry.storage_key)
                        .await?;
                }
                Err(error) => {
                    tracing::warn!(
                        storage_key = %entry.storage_key,
                        attempts = entry.attempts,
                        cause = %error,
                        "SharedWorld pending blob delete retry failed"
                    );
                    svc.repository
                        .bump_pending_blob_delete_attempt(
                            provider,
                            &storage_account_id,
                            &entry.storage_key,
                            &time::to_iso(now),
                        )
                        .await?;
                }
            }
        }
    }
    Ok(())
}

pub async fn maybe_delete_unreferenced_blob(
    svc: &ServiceContext,
    binding: &WorldStorageBinding,
    storage_key: Option<&str>,
) -> HttpResult<()> {
    let Some(storage_key) = storage_key.filter(|key| !key.is_empty()) else { return Ok(()) };
    let scope = StorageReferenceScope {
        provider: binding.provider,
        storage_account_id: binding.storage_account_id.clone(),
        snapshots_created_since: None,
    };
    if svc.repository.is_storage_key_referenced(storage_key, Some(scope)).await? {
        return Ok(());
    }
    delete_unreferenced_blobs(svc, binding, &[storage_key.to_string()], None).await
}

// ---------------------------------------------------------------------------
// Storage-object existence
// ---------------------------------------------------------------------------

pub async fn storage_key_exists(
    svc: &ServiceContext,
    binding: &WorldStorageBinding,
    storage_key: &str,
) -> HttpResult<bool> {
    Ok(storage_keys_exist(
        svc,
        binding,
        std::slice::from_ref(&storage_key.to_string()),
        WhenUnverifiable::AssumePresent,
    )
    .await?
    .contains(storage_key))
}

/// Existence for a whole key set at once. Large worlds carry hundreds of
/// packs; checking them one query at a time put upload prepare/finalize past
/// the client's request timeout, so callers with more than one key must use
/// this.
///
/// `when_unverifiable` picks the fallback when there is no object metadata to
/// check (unlinked world): finalize validation assumes keys are present (a
/// missing check must not reject a snapshot), while upload planning asks the
/// provider so fresh worlds still get signed slots.
pub async fn storage_keys_exist(
    svc: &ServiceContext,
    binding: &WorldStorageBinding,
    storage_keys: &[String],
    when_unverifiable: WhenUnverifiable,
) -> HttpResult<HashSet<String>> {
    let mut unique: Vec<String> = Vec::new();
    {
        let mut seen = HashSet::new();
        for key in storage_keys {
            if seen.insert(key.clone()) {
                unique.push(key.clone());
            }
        }
    }
    if unique.is_empty() {
        return Ok(HashSet::new());
    }
    if binding.provider == sw_contracts::StorageProviderType::GoogleDrive {
        // Drive providers record every stored object in the repository; those
        // rows are the authoritative existence check (the real provider's
        // exists() is the same lookup).
        if let Some(account) = &binding.storage_account_id {
            return Ok(svc.repository.list_existing_storage_keys(binding.provider, account, &unique).await?);
        }
        if when_unverifiable == WhenUnverifiable::AssumePresent {
            // Unlinked worlds do not have cheap object metadata to validate against.
            return Ok(unique.into_iter().collect());
        }
    }
    let mut out = HashSet::new();
    for key in unique {
        if svc.storage_provider.exists(binding, &key).await? {
            out.insert(key);
        }
    }
    Ok(out)
}

// ---------------------------------------------------------------------------
// Finalize validation
// ---------------------------------------------------------------------------

/// Batch-loads the headers of every base snapshot a finalize request refers to
/// (the request's own base plus each delta file's/pack's base) into one cache
/// shared by validation, chain accounting and recipe stamping. Ids the
/// repository does not know stay uncached, so the per-id path still produces
/// its precise `snapshot_base_not_found`.
async fn prefetch_base_snapshot_headers(
    svc: &ServiceContext,
    world_id: &str,
    request: &FinalizeSnapshotRequest,
) -> HttpResult<SnapshotHeadersCache> {
    let mut ids: Vec<String> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    let push = |id: &Option<String>, ids: &mut Vec<String>, seen: &mut HashSet<String>| {
        if let Some(id) = id {
            if seen.insert(id.clone()) {
                ids.push(id.clone());
            }
        }
    };
    push(&request.base_snapshot_id, &mut ids, &mut seen);
    for file in &request.files {
        push(&file.base_snapshot_id, &mut ids, &mut seen);
    }
    for pack in request.packs.as_deref().unwrap_or(&[]) {
        push(&pack.base_snapshot_id, &mut ids, &mut seen);
    }
    let mut cache = SnapshotHeadersCache::new();
    if ids.is_empty() {
        return Ok(cache);
    }
    for (snapshot_id, headers) in svc.repository.get_snapshot_headers_batch(world_id, &ids).await? {
        cache.insert(snapshot_id, Some(headers));
    }
    Ok(cache)
}

/// Snapshot finalization validates the whole manifest before any row is
/// written: unique paths/pack ids, storage objects that actually exist, and
/// delta chains whose base snapshot, base hash and chain depth all line up.
async fn validate_finalize_snapshot_request(
    svc: &ServiceContext,
    world_id: &str,
    request: &FinalizeSnapshotRequest,
    snapshot_cache: &mut SnapshotHeadersCache,
) -> HttpResult<()> {
    let binding = require_world_storage_binding(svc, world_id).await?;
    let mut seen_paths: HashSet<String> = HashSet::new();
    let mut seen_pack_ids: HashSet<String> = HashSet::new();
    let mut keys: Vec<String> = request.files.iter().map(|f| f.storage_key.clone()).collect();
    keys.extend(request.packs.as_deref().unwrap_or(&[]).iter().map(|p| p.storage_key.clone()));
    let existing_storage_keys =
        storage_keys_exist(svc, &binding, &keys, WhenUnverifiable::AssumePresent).await?;

    if let Some(base_snapshot_id) = &request.base_snapshot_id {
        require_snapshot_for_validation(svc, world_id, base_snapshot_id, snapshot_cache).await?;
    }

    for file in &request.files {
        validate_manifest_file_shape(file)?;
        if !seen_paths.insert(file.path.clone()) {
            return Err(HttpError::new(
                400,
                "duplicate_snapshot_path",
                format!("Snapshot includes duplicate file path '{}'.", file.path),
            ));
        }
        assert_storage_key_exists(&existing_storage_keys, &file.storage_key)?;
        validate_manifest_file_base(svc, world_id, file, snapshot_cache).await?;
    }

    for pack in request.packs.as_deref().unwrap_or(&[]) {
        validate_snapshot_pack_shape(pack)?;
        if !seen_pack_ids.insert(pack.pack_id.clone()) {
            return Err(HttpError::new(
                400,
                "duplicate_snapshot_pack",
                format!("Snapshot includes duplicate pack id '{}'.", pack.pack_id),
            ));
        }
        assert_storage_key_exists(&existing_storage_keys, &pack.storage_key)?;
        for file in &pack.files {
            if file.path.trim().is_empty() {
                return Err(HttpError::new(
                    400,
                    "invalid_snapshot_path",
                    "Snapshot packed file path is required.",
                ));
            }
            if !seen_paths.insert(file.path.clone()) {
                return Err(HttpError::new(
                    400,
                    "duplicate_snapshot_path",
                    format!("Snapshot includes duplicate file path '{}'.", file.path),
                ));
            }
        }
        validate_snapshot_pack_base(svc, world_id, pack, snapshot_cache, request).await?;
    }
    Ok(())
}

/// Shared delta-base validation for the two artifact families (manifest files
/// and snapshot packs). The rules are identical; only the lookup into the base
/// snapshot and the human-readable labels differ.
struct DeltaBaseArtifact<'a> {
    kind: &'static str,
    reference: String,
    is_delta: bool,
    base_snapshot_id: Option<&'a str>,
    base_hash: Option<&'a str>,
    chain_depth: Option<i64>,
}

/// `(hash, expectedChainDepth)` of the artifact's counterpart in the base.
type FoundBase = Option<(String, i64)>;

async fn validate_delta_artifact_base(
    svc: &ServiceContext,
    world_id: &str,
    artifact: DeltaBaseArtifact<'_>,
    find_base: impl Fn(&SnapshotManifest) -> FoundBase,
    snapshot_cache: &mut SnapshotHeadersCache,
) -> HttpResult<()> {
    let hash_ref = if artifact.kind == "pack" {
        format!("pack {}", artifact.reference)
    } else {
        artifact.reference.clone()
    };
    if artifact.is_delta {
        let (Some(base_snapshot_id), Some(base_hash), Some(chain_depth)) =
            (artifact.base_snapshot_id, artifact.base_hash, artifact.chain_depth)
        else {
            return Err(missing_base_metadata(&artifact));
        };
        if base_snapshot_id.is_empty() || base_hash.is_empty() || chain_depth < 1 {
            return Err(missing_base_metadata(&artifact));
        }
        let base_snapshot =
            require_snapshot_for_validation(svc, world_id, base_snapshot_id, snapshot_cache).await?;
        let Some((hash, expected_chain_depth)) = find_base(&base_snapshot) else {
            return Err(HttpError::new(
                400,
                "snapshot_base_not_found",
                format!(
                    "Snapshot base {} {} was not found in '{}'.",
                    artifact.kind, artifact.reference, base_snapshot_id
                ),
            ));
        };
        if base_hash != hash {
            return Err(HttpError::new(
                400,
                "snapshot_base_hash_mismatch",
                format!("Snapshot base hash for {hash_ref} does not match '{base_snapshot_id}'."),
            ));
        }
        if chain_depth != expected_chain_depth {
            return Err(HttpError::new(
                400,
                "snapshot_chain_depth_mismatch",
                format!("Snapshot chain depth for {hash_ref} does not match its base artifact."),
            ));
        }
        return Ok(());
    }
    if artifact.base_snapshot_id.is_some()
        || artifact.base_hash.is_some()
        || !is_zero_or_null_chain_depth(artifact.chain_depth)
    {
        return Err(HttpError::new(
            400,
            "invalid_snapshot_base",
            format!(
                "Non-delta {} {} cannot declare base snapshot metadata.",
                artifact.kind, artifact.reference
            ),
        ));
    }
    Ok(())
}

fn missing_base_metadata(artifact: &DeltaBaseArtifact<'_>) -> HttpError {
    HttpError::new(
        400,
        "invalid_snapshot_delta",
        format!("Snapshot delta {} {} is missing base metadata.", artifact.kind, artifact.reference),
    )
}

async fn validate_manifest_file_base(
    svc: &ServiceContext,
    world_id: &str,
    file: &ManifestFile,
    snapshot_cache: &mut SnapshotHeadersCache,
) -> HttpResult<()> {
    let path = file.path.clone();
    validate_delta_artifact_base(
        svc,
        world_id,
        DeltaBaseArtifact {
            kind: "file",
            reference: format!("'{}'", file.path),
            is_delta: normalize_file_transfer_mode(file.transfer_mode) == REGION_DELTA_TRANSFER_MODE,
            base_snapshot_id: file.base_snapshot_id.as_deref(),
            base_hash: file.base_hash.as_deref(),
            chain_depth: file.chain_depth,
        },
        move |base| {
            base.files.iter().find(|entry| entry.path == path).map(|base_file| {
                (
                    base_file.hash.clone(),
                    next_chain_depth(
                        normalize_file_transfer_mode(base_file.transfer_mode),
                        base_file.chain_depth,
                    ),
                )
            })
        },
        snapshot_cache,
    )
    .await
}

/// The parent snapshot's copy of a pack the request carries forward unchanged
/// (same id, artifact hash, storage key and transfer mode). The upload plan
/// echoes such packs' headers verbatim from the latest snapshot, base
/// references included — and since S1 those base snapshot ROWS are
/// legitimately deletable (self-contained recipes and the GC legs keep the
/// bytes). So a carried-forward pack must be judged by its parent's already
/// validated header, never by whether its original base row still exists:
/// demanding the row turned any deleted base into a world that could not
/// finalize again ("Snapshot base ... was not found for this world").
async fn carried_forward_parent_pack(
    svc: &ServiceContext,
    world_id: &str,
    request_base_snapshot_id: Option<&str>,
    pack: &SnapshotPack,
    snapshot_cache: &mut SnapshotHeadersCache,
) -> HttpResult<Option<SnapshotPack>> {
    let Some(base_snapshot_id) = request_base_snapshot_id else { return Ok(None) };
    let parent = snapshot_headers_cached(svc, world_id, base_snapshot_id, snapshot_cache).await?;
    let parent_pack =
        parent.and_then(|parent| parent.packs.into_iter().find(|entry| entry.pack_id == pack.pack_id));
    let Some(parent_pack) = parent_pack else { return Ok(None) };
    if parent_pack.hash != pack.hash
        || parent_pack.storage_key != pack.storage_key
        || parent_pack.transfer_mode != pack.transfer_mode
        || parent_pack.base_snapshot_id != pack.base_snapshot_id
        || parent_pack.base_hash != pack.base_hash
        || parent_pack.chain_depth != pack.chain_depth
    {
        return Ok(None);
    }
    Ok(Some(parent_pack))
}

async fn validate_snapshot_pack_base(
    svc: &ServiceContext,
    world_id: &str,
    pack: &SnapshotPack,
    snapshot_cache: &mut SnapshotHeadersCache,
    request: &FinalizeSnapshotRequest,
) -> HttpResult<()> {
    if is_delta_pack_transfer_mode(pack.transfer_mode)
        && carried_forward_parent_pack(
            svc,
            world_id,
            request.base_snapshot_id.as_deref(),
            pack,
            snapshot_cache,
        )
        .await?
        .is_some()
    {
        // Inherited verbatim from the parent snapshot, whose header already
        // passed this validation when it was written.
        return validate_snapshot_pack_delta_v2_fields(pack);
    }
    let pack_id = pack.pack_id.clone();
    validate_delta_artifact_base(
        svc,
        world_id,
        DeltaBaseArtifact {
            kind: "pack",
            reference: format!("'{}'", pack.pack_id),
            is_delta: is_delta_pack_transfer_mode(pack.transfer_mode),
            base_snapshot_id: pack.base_snapshot_id.as_deref(),
            base_hash: pack.base_hash.as_deref(),
            chain_depth: pack.chain_depth,
        },
        move |base| {
            base.packs.iter().find(|entry| entry.pack_id == pack_id).map(|base_pack| {
                (base_pack.hash.clone(), next_chain_depth(base_pack.transfer_mode, base_pack.chain_depth))
            })
        },
        snapshot_cache,
    )
    .await?;
    validate_snapshot_pack_delta_v2_fields(pack)
}

/// v2 delta bookkeeping rules: a v2 delta pack must report its true blob size
/// (the accumulator's input) and stay under the depth ceiling; non-delta packs
/// must not claim a delta format. `chain_delta_bytes` is never accepted from
/// the client — finalize computes it from the base row.
fn validate_snapshot_pack_delta_v2_fields(pack: &SnapshotPack) -> HttpResult<()> {
    let Some(version) = pack.delta_format_version else { return Ok(()) };
    if version != DELTA_V2_FORMAT_VERSION {
        return Err(HttpError::new(
            400,
            "invalid_snapshot_delta",
            format!("Snapshot pack '{}' declares unsupported delta format {version}.", pack.pack_id),
        ));
    }
    if !is_delta_pack_transfer_mode(pack.transfer_mode) {
        return Err(HttpError::new(
            400,
            "invalid_snapshot_delta",
            format!("Snapshot pack '{}' declares a delta format on a non-delta transfer mode.", pack.pack_id),
        ));
    }
    if pack.delta_blob_size.is_none_or(|size| size <= 0) {
        return Err(HttpError::new(
            400,
            "invalid_snapshot_delta",
            format!("Snapshot pack '{}' is missing its delta blob size.", pack.pack_id),
        ));
    }
    if pack.chain_depth.unwrap_or(0) > DELTA_V2_MAX_CHAIN_DEPTH {
        return Err(HttpError::new(
            400,
            "snapshot_chain_depth_mismatch",
            format!("Snapshot pack '{}' exceeds the delta chain ceiling.", pack.pack_id),
        ));
    }
    Ok(())
}

fn assert_storage_key_exists(existing_storage_keys: &HashSet<String>, storage_key: &str) -> HttpResult<()> {
    if !existing_storage_keys.contains(storage_key) {
        return Err(HttpError::new(
            400,
            "snapshot_storage_missing",
            format!("Snapshot storage object '{storage_key}' was not found."),
        ));
    }
    Ok(())
}

fn validate_manifest_file_shape(file: &ManifestFile) -> HttpResult<()> {
    if file.path.trim().is_empty() {
        return Err(HttpError::new(400, "invalid_snapshot_path", "Snapshot file path is required."));
    }
    if file.storage_key.trim().is_empty() {
        return Err(HttpError::new(
            400,
            "invalid_snapshot_storage_key",
            format!("Snapshot file '{}' is missing a storage key.", file.path),
        ));
    }
    let transfer_mode = normalize_file_transfer_mode(file.transfer_mode);
    let allowed = transfer_mode == WHOLE_GZIP_TRANSFER_MODE
        || transfer_mode == REGION_FULL_TRANSFER_MODE
        || transfer_mode == REGION_DELTA_TRANSFER_MODE;
    if !allowed {
        return Err(HttpError::new(
            400,
            "invalid_snapshot_transfer_mode",
            format!(
                "Snapshot file '{}' uses unsupported transfer mode '{}'.",
                file.path,
                file.transfer_mode.map(|mode| mode.as_str()).unwrap_or("undefined")
            ),
        ));
    }
    Ok(())
}

fn validate_snapshot_pack_shape(pack: &SnapshotPack) -> HttpResult<()> {
    if pack.pack_id.trim().is_empty() {
        return Err(HttpError::new(400, "invalid_snapshot_pack", "Snapshot pack id is required."));
    }
    if pack.storage_key.trim().is_empty() {
        return Err(HttpError::new(
            400,
            "invalid_snapshot_storage_key",
            format!("Snapshot pack '{}' is missing a storage key.", pack.pack_id),
        ));
    }
    let allowed = if is_region_bundle_id(&pack.pack_id) {
        pack.transfer_mode == REGION_FULL_TRANSFER_MODE || pack.transfer_mode == REGION_DELTA_TRANSFER_MODE
    } else {
        pack.transfer_mode == PACK_FULL_TRANSFER_MODE || pack.transfer_mode == PACK_DELTA_TRANSFER_MODE
    };
    if !allowed {
        return Err(HttpError::new(
            400,
            "invalid_snapshot_transfer_mode",
            format!(
                "Snapshot pack '{}' uses unsupported transfer mode '{}'.",
                pack.pack_id,
                pack.transfer_mode.as_str()
            ),
        ));
    }
    Ok(())
}

fn next_chain_depth(base_transfer_mode: FileTransferMode, base_chain_depth: Option<i64>) -> i64 {
    if is_delta_pack_transfer_mode(base_transfer_mode) {
        base_chain_depth.unwrap_or(0) + 1
    } else {
        1
    }
}

fn is_zero_or_null_chain_depth(value: Option<i64>) -> bool {
    matches!(value, None | Some(0))
}

// ---------------------------------------------------------------------------
// Chain accounting + self-contained chain recipes (S1)
// ---------------------------------------------------------------------------

/// Server-side accumulator: for every pack in the request, stamp
/// `chainDeltaBytes` before persisting. Full packs restart the chain at 0; v2
/// delta packs extend their base's accumulator by their own blob size; v1
/// delta packs stay NULL (unaccounted — the planner will force a re-full).
/// Never trusts a client-sent accumulator.
async fn compute_chain_delta_bytes(
    svc: &ServiceContext,
    world_id: &str,
    request: &mut FinalizeSnapshotRequest,
    snapshot_cache: &mut SnapshotHeadersCache,
) -> HttpResult<()> {
    let base_snapshot_id = request.base_snapshot_id.clone();
    let pack_count = request.packs.as_ref().map(|packs| packs.len()).unwrap_or(0);
    for index in 0..pack_count {
        let pack = request.packs.as_ref().expect("packs")[index].clone();
        let chain_delta_bytes = match classify_chain_delta(&pack) {
            ChainDeltaAccounting::Fixed(value) => value,
            ChainDeltaAccounting::FromBase => {
                if let Some(parent_pack) = carried_forward_parent_pack(
                    svc,
                    world_id,
                    base_snapshot_id.as_deref(),
                    &pack,
                    snapshot_cache,
                )
                .await?
                {
                    parent_pack.chain_delta_bytes
                } else {
                    let base_snapshot = require_snapshot_for_validation(
                        svc,
                        world_id,
                        pack.base_snapshot_id.as_deref().unwrap_or_default(),
                        snapshot_cache,
                    )
                    .await?;
                    let base_pack = base_snapshot.packs.iter().find(|entry| entry.pack_id == pack.pack_id);
                    // The planner never offers a v2 slot over an unaccounted
                    // chain; a client claiming one anyway is broken or hostile.
                    let Some(base_accumulator) = base_chain_accumulator(base_pack) else {
                        return Err(HttpError::new(
                            400,
                            "invalid_snapshot_delta",
                            format!(
                                "Snapshot pack '{}' chains a v2 delta onto an unaccounted base.",
                                pack.pack_id
                            ),
                        ));
                    };
                    Some(base_accumulator + pack.delta_blob_size.unwrap_or(0))
                }
            }
        };
        request.packs.as_mut().expect("packs")[index].chain_delta_bytes = chain_delta_bytes;
    }
    Ok(())
}

/// What a pack's accumulator is before the base is consulted.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ChainDeltaAccounting {
    /// Full packs restart at 0; v1 delta packs stay unaccounted (`None`).
    Fixed(Option<i64>),
    /// v2 delta packs extend the base pack's accumulator.
    FromBase,
}

fn classify_chain_delta(pack: &SnapshotPack) -> ChainDeltaAccounting {
    if !is_delta_pack_transfer_mode(pack.transfer_mode) {
        ChainDeltaAccounting::Fixed(Some(0))
    } else if pack.delta_format_version != Some(DELTA_V2_FORMAT_VERSION) {
        ChainDeltaAccounting::Fixed(None)
    } else {
        ChainDeltaAccounting::FromBase
    }
}

/// The accumulator a v2 delta inherits from its base pack; `None` means the
/// chain is unaccounted (missing base pack, or a base whose own accumulator
/// was never computed).
fn base_chain_accumulator(base_pack: Option<&SnapshotPack>) -> Option<i64> {
    match base_pack {
        None => None,
        Some(base_pack) if is_delta_pack_transfer_mode(base_pack.transfer_mode) => {
            base_pack.chain_delta_bytes
        }
        Some(_) => Some(0),
    }
}

/// Server-stamped self-contained chains (S1): every pack in the request gets a
/// `chainSteps` recipe — full packs anchor a fresh chain, delta packs extend
/// their base pack's steps. Client-sent values are always overwritten (same
/// trust model as `chainDeltaBytes`). When the base is a legacy snapshot with
/// no steps of its own, the chain is synthesized once by walking the legacy
/// base headers here, so the FIRST stamped snapshot is already independent of
/// every older snapshot row. A broken/unresolvable legacy chain leaves
/// `chainSteps` null — that pack keeps the walk-based download path.
async fn stamp_chain_steps(
    svc: &ServiceContext,
    world_id: &str,
    request: &mut FinalizeSnapshotRequest,
    headers_cache: &mut SnapshotHeadersCache,
) -> HttpResult<()> {
    let base_snapshot_id = request.base_snapshot_id.clone();
    let pack_count = request.packs.as_ref().map(|packs| packs.len()).unwrap_or(0);
    for index in 0..pack_count {
        let pack = request.packs.as_ref().expect("packs")[index].clone();
        let chain_steps = if !is_delta_pack_transfer_mode(pack.transfer_mode) {
            Some(vec![self_chain_step(&pack, None)])
        } else {
            let parent_pack =
                carried_forward_parent_pack(svc, world_id, base_snapshot_id.as_deref(), &pack, headers_cache)
                    .await?;
            match parent_pack.and_then(|parent| parent.chain_steps).filter(|steps| !steps.is_empty()) {
                // Same artifact, same chain: the parent's recipe IS this pack's.
                Some(steps) => Some(steps),
                None => {
                    chain_steps_of_base_pack(svc, world_id, &pack, headers_cache).await?.map(|mut steps| {
                        steps.push(self_chain_step(&pack, pack.base_hash.clone()));
                        steps
                    })
                }
            }
        };
        request.packs.as_mut().expect("packs")[index].chain_steps = chain_steps;
    }
    Ok(())
}

/// Synthesizes `chainSteps` recipes for kept snapshots that predate stamping.
/// Reads and rewrites directories only (never cached manifests); a pack whose
/// legacy chain cannot be resolved simply stays unstamped and keeps
/// contributing conservative edges.
async fn upgrade_kept_snapshots_to_self_contained(
    svc: &ServiceContext,
    world_id: &str,
    keep: &HashSet<String>,
) -> HttpResult<()> {
    let mut headers_cache = SnapshotHeadersCache::new();
    for snapshot_id in keep {
        let Some(snapshot) = snapshot_headers_cached(svc, world_id, snapshot_id, &mut headers_cache).await?
        else {
            continue;
        };
        let mut steps_by_pack_id: HashMap<String, Vec<PackChainStep>> = HashMap::new();
        for pack in &snapshot.packs {
            if pack.chain_steps.is_some() {
                continue;
            }
            if !is_delta_pack_transfer_mode(pack.transfer_mode) {
                steps_by_pack_id.insert(pack.pack_id.clone(), vec![self_chain_step(pack, None)]);
                continue;
            }
            if let Some(steps) =
                synthesize_legacy_chain_steps(svc, world_id, pack, &pack.pack_id, &mut headers_cache).await?
            {
                steps_by_pack_id.insert(pack.pack_id.clone(), steps);
            }
        }
        if !steps_by_pack_id.is_empty() {
            svc.repository.stamp_snapshot_chain_steps(snapshot_id, steps_by_pack_id).await?;
        }
    }
    Ok(())
}

fn self_chain_step(pack: &SnapshotPack, base_hash: Option<String>) -> PackChainStep {
    PackChainStep {
        storage_key: pack.storage_key.clone(),
        hash: pack.hash.clone(),
        base_hash,
        transfer_mode: pack.transfer_mode,
        size: pack.size,
        delta_format_version: pack.delta_format_version,
    }
}

async fn chain_steps_of_base_pack(
    svc: &ServiceContext,
    world_id: &str,
    pack: &SnapshotPack,
    headers_cache: &mut SnapshotHeadersCache,
) -> HttpResult<Option<Vec<PackChainStep>>> {
    let Some(base_snapshot_id) = pack.base_snapshot_id.as_deref() else { return Ok(None) };
    let base_snapshot = snapshot_headers_cached(svc, world_id, base_snapshot_id, headers_cache).await?;
    let base_pack = base_snapshot
        .and_then(|snapshot| snapshot.packs.into_iter().find(|entry| entry.pack_id == pack.pack_id));
    let Some(base_pack) = base_pack else { return Ok(None) };
    if base_pack.chain_steps.is_some() {
        return Ok(base_pack.chain_steps);
    }
    synthesize_legacy_chain_steps(svc, world_id, &base_pack, &pack.pack_id, headers_cache).await
}

/// Walks a legacy (pre-stamping) chain once to its anchor full.
async fn synthesize_legacy_chain_steps(
    svc: &ServiceContext,
    world_id: &str,
    legacy_base_pack: &SnapshotPack,
    pack_id: &str,
    headers_cache: &mut SnapshotHeadersCache,
) -> HttpResult<Option<Vec<PackChainStep>>> {
    let mut steps: VecDeque<PackChainStep> = VecDeque::new();
    let mut cursor = legacy_base_pack.clone();
    // Existing depth ceilings bound real chains at 64; the margin guards
    // against malformed cycles.
    for _ in 0..80 {
        if !is_delta_pack_transfer_mode(cursor.transfer_mode) {
            steps.push_front(self_chain_step(&cursor, None));
            return Ok(Some(steps.into()));
        }
        steps.push_front(self_chain_step(&cursor, cursor.base_hash.clone()));
        let Some(base_snapshot_id) = cursor.base_snapshot_id.clone() else { break };
        let base_snapshot = snapshot_headers_cached(svc, world_id, &base_snapshot_id, headers_cache).await?;
        let next = base_snapshot
            .and_then(|snapshot| snapshot.packs.into_iter().find(|entry| entry.pack_id == pack_id));
        let Some(next) = next else { break };
        if let Some(next_steps) = next.chain_steps {
            let mut resolved = next_steps;
            resolved.extend(steps);
            return Ok(Some(resolved));
        }
        cursor = next;
    }
    tracing::warn!(world_id, pack_id, "SharedWorld could not synthesize legacy chain steps");
    Ok(None)
}

async fn snapshot_headers_cached(
    svc: &ServiceContext,
    world_id: &str,
    snapshot_id: &str,
    cache: &mut SnapshotHeadersCache,
) -> HttpResult<Option<SnapshotManifest>> {
    if let Some(hit) = cache.get(snapshot_id) {
        return Ok(hit.clone());
    }
    let snapshot = svc.repository.get_snapshot_headers(world_id, snapshot_id).await?;
    cache.insert(snapshot_id.to_string(), snapshot.clone());
    Ok(snapshot)
}

async fn require_snapshot_for_validation(
    svc: &ServiceContext,
    world_id: &str,
    snapshot_id: &str,
    snapshot_cache: &mut SnapshotHeadersCache,
) -> HttpResult<SnapshotManifest> {
    // Headers-only on purpose: delta validation and chainDeltaBytes read base
    // HEADERS (hash/transferMode/chainDepth/chainDeltaBytes) plus loose rows,
    // never pack member lists — so finalize stays independent of the 0027
    // manifest document and a missing doc can never block the next snapshot
    // (the world heals by snapshotting again).
    snapshot_headers_cached(svc, world_id, snapshot_id, snapshot_cache).await?.ok_or_else(|| {
        HttpError::new(
            400,
            "snapshot_base_not_found",
            format!("Snapshot base '{snapshot_id}' was not found for this world."),
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record(snapshot_id: &str, created_at: &str) -> SnapshotRecord {
        SnapshotRecord {
            snapshot_id: snapshot_id.to_string(),
            world_id: "world".into(),
            created_at: created_at.to_string(),
            created_by_uuid: "player-owner".into(),
        }
    }

    fn kept(keep: &HashSet<String>, snapshots: &[SnapshotRecord]) -> Vec<String> {
        snapshots.iter().filter(|s| keep.contains(&s.snapshot_id)).map(|s| s.snapshot_id.clone()).collect()
    }

    fn at(stamp: &str) -> Instant {
        time::parse_iso(stamp).expect("iso")
    }

    /// The 0.4.5 schedule from `backup-delete-0-4-5.test.ts`: keep-all for an
    /// hour, then one per hour, then dailies.
    #[test]
    fn age_schedule_keeps_hour_then_hourly_then_daily() {
        let snapshots = vec![
            record("t5", "2026-06-04T12:00:00.000Z"),
            record("t4", "2026-06-04T11:15:00.000Z"),
            record("t3", "2026-06-04T08:30:00.000Z"),
            record("t2", "2026-06-03T20:40:00.000Z"),
            record("t1", "2026-06-03T20:10:00.000Z"),
            record("t0", "2026-06-01T00:00:00.000Z"),
        ];
        let keep = select_snapshots_to_keep_by_age(&snapshots, at("2026-06-04T12:00:00.000Z"));
        assert_eq!(kept(&keep, &snapshots), vec!["t5", "t4", "t3", "t2", "t0"]);
    }

    /// `snapshots-retention.test.ts`: monthlies beyond 30 days, one per day
    /// inside the month, and the newest is always kept.
    #[test]
    fn age_schedule_thins_old_history_to_days_and_months() {
        let snapshots = vec![
            record("recent-b", "2026-03-31T00:00:00.000Z"),
            record("recent-a", "2026-03-30T10:00:00.000Z"),
            record("march-keep", "2026-03-01T12:00:00.000Z"),
            record("march-old", "2026-03-01T10:00:00.000Z"),
            record("jan-keep", "2026-01-20T12:00:00.000Z"),
            record("jan-old", "2026-01-01T00:00:00.000Z"),
        ];
        let keep = select_snapshots_to_keep_by_age(&snapshots, at("2026-03-31T00:00:00.000Z"));
        assert_eq!(kept(&keep, &snapshots), vec!["recent-b", "recent-a", "march-keep", "jan-keep"]);
    }

    #[test]
    fn unparseable_timestamps_are_always_kept() {
        let snapshots = vec![record("newest", "2026-03-31T00:00:00.000Z"), record("broken", "nope")];
        let keep = select_snapshots_to_keep_by_age(&snapshots, at("2026-03-31T00:00:00.000Z"));
        assert_eq!(kept(&keep, &snapshots), vec!["newest", "broken"]);
    }

    #[test]
    fn max_backups_caps_the_age_kept_set_oldest_first() {
        let snapshots: Vec<SnapshotRecord> = (1..=6)
            .rev()
            .map(|day| record(&format!("d{day}"), &format!("2026-01-0{day}T10:00:00.000Z")))
            .collect();
        let now = at("2026-01-06T10:00:00.000Z");
        assert_eq!(kept(&select_snapshots_to_keep(&snapshots, now, None), &snapshots).len(), 6);
        assert_eq!(
            kept(&select_snapshots_to_keep(&snapshots, now, Some(3)), &snapshots),
            vec!["d6", "d5", "d4"]
        );
        // maxBackups 1 (0.4.5): only the current snapshot survives; a cap
        // below one still keeps the latest.
        assert_eq!(kept(&select_snapshots_to_keep(&snapshots, now, Some(1)), &snapshots), vec!["d6"]);
        assert_eq!(kept(&select_snapshots_to_keep(&snapshots, now, Some(0)), &snapshots), vec!["d6"]);
        // A cap above the age-kept count changes nothing.
        assert_eq!(kept(&select_snapshots_to_keep(&snapshots, now, Some(10)), &snapshots).len(), 6);
    }

    fn full_pack(pack_id: &str, hash: &str) -> SnapshotPack {
        SnapshotPack {
            pack_id: pack_id.into(),
            hash: hash.into(),
            size: 100,
            storage_key: format!("packs/full/{hash}.pack"),
            transfer_mode: PACK_FULL_TRANSFER_MODE,
            base_snapshot_id: None,
            base_hash: None,
            chain_depth: Some(0),
            delta_format_version: None,
            delta_blob_size: None,
            chain_delta_bytes: None,
            chain_steps: None,
            files: vec![],
        }
    }

    fn delta_pack(pack_id: &str, hash: &str, base: &SnapshotPack, base_snapshot: &str) -> SnapshotPack {
        SnapshotPack {
            pack_id: pack_id.into(),
            hash: hash.into(),
            size: 100,
            storage_key: format!("packs/delta2/{hash}.bin"),
            transfer_mode: PACK_DELTA_TRANSFER_MODE,
            base_snapshot_id: Some(base_snapshot.into()),
            base_hash: Some(base.hash.clone()),
            chain_depth: Some(base.chain_depth.unwrap_or(0) + 1),
            delta_format_version: Some(DELTA_V2_FORMAT_VERSION),
            delta_blob_size: Some(40),
            chain_delta_bytes: None,
            chain_steps: None,
            files: vec![],
        }
    }

    #[test]
    fn self_chain_steps_anchor_full_packs_and_extend_deltas() {
        let full = full_pack("a", "a1");
        let anchor = self_chain_step(&full, None);
        assert_eq!(anchor.storage_key, "packs/full/a1.pack");
        assert_eq!(anchor.hash, "a1");
        assert_eq!(anchor.base_hash, None);
        assert_eq!(anchor.transfer_mode, PACK_FULL_TRANSFER_MODE);
        assert_eq!(anchor.delta_format_version, None);

        let delta = delta_pack("a", "a2", &full, "s1");
        let step = self_chain_step(&delta, delta.base_hash.clone());
        assert_eq!(step.base_hash.as_deref(), Some("a1"));
        assert_eq!(step.delta_format_version, Some(DELTA_V2_FORMAT_VERSION));
        assert_eq!(step.transfer_mode, PACK_DELTA_TRANSFER_MODE);
    }

    #[test]
    fn next_chain_depth_restarts_on_full_bases() {
        assert_eq!(next_chain_depth(PACK_FULL_TRANSFER_MODE, Some(7)), 1);
        assert_eq!(next_chain_depth(PACK_DELTA_TRANSFER_MODE, Some(2)), 3);
        assert_eq!(next_chain_depth(PACK_DELTA_TRANSFER_MODE, None), 1);
        assert_eq!(next_chain_depth(REGION_DELTA_TRANSFER_MODE, Some(0)), 1);
        assert!(is_zero_or_null_chain_depth(None));
        assert!(is_zero_or_null_chain_depth(Some(0)));
        assert!(!is_zero_or_null_chain_depth(Some(1)));
    }

    #[test]
    fn delta_v2_field_validation_matches_the_worker_codes() {
        let full = full_pack("a", "a1");
        assert!(validate_snapshot_pack_delta_v2_fields(&full).is_ok());

        let mut wrong_version = delta_pack("a", "a2", &full, "s1");
        wrong_version.delta_format_version = Some(3);
        let error = validate_snapshot_pack_delta_v2_fields(&wrong_version).unwrap_err();
        assert_eq!((error.status, error.code), (400, "invalid_snapshot_delta"));
        assert!(error.message.contains("declares unsupported delta format 3"));

        let mut not_delta = full.clone();
        not_delta.delta_format_version = Some(DELTA_V2_FORMAT_VERSION);
        assert!(validate_snapshot_pack_delta_v2_fields(&not_delta)
            .unwrap_err()
            .message
            .contains("non-delta transfer mode"));

        let mut no_size = delta_pack("a", "a2", &full, "s1");
        no_size.delta_blob_size = None;
        assert!(validate_snapshot_pack_delta_v2_fields(&no_size)
            .unwrap_err()
            .message
            .contains("missing its delta blob size"));

        let mut too_deep = delta_pack("a", "a2", &full, "s1");
        too_deep.chain_depth = Some(DELTA_V2_MAX_CHAIN_DEPTH + 1);
        let error = validate_snapshot_pack_delta_v2_fields(&too_deep).unwrap_err();
        assert_eq!(error.code, "snapshot_chain_depth_mismatch");
        assert!(error.message.contains("exceeds the delta chain ceiling"));
    }

    #[test]
    fn shape_validation_matches_the_worker_codes() {
        let mut pack = full_pack("non-region", "a1");
        pack.transfer_mode = REGION_FULL_TRANSFER_MODE;
        assert_eq!(validate_snapshot_pack_shape(&pack).unwrap_err().code, "invalid_snapshot_transfer_mode");
        let mut bundle = full_pack("region-bundle:r.0.0", "a1");
        assert_eq!(validate_snapshot_pack_shape(&bundle).unwrap_err().code, "invalid_snapshot_transfer_mode");
        bundle.transfer_mode = REGION_FULL_TRANSFER_MODE;
        assert!(validate_snapshot_pack_shape(&bundle).is_ok());
        let mut no_key = full_pack("non-region", "a1");
        no_key.storage_key = "  ".into();
        assert_eq!(validate_snapshot_pack_shape(&no_key).unwrap_err().code, "invalid_snapshot_storage_key");

        let file = ManifestFile {
            path: "level.dat".into(),
            hash: "h".into(),
            size: 10,
            compressed_size: 5,
            storage_key: "blobs/ha/h.bin".into(),
            content_type: "application/octet-stream".into(),
            transfer_mode: None,
            base_snapshot_id: None,
            base_hash: None,
            chain_depth: None,
        };
        assert!(validate_manifest_file_shape(&file).is_ok());
        let packed = ManifestFile { transfer_mode: Some(PACK_FULL_TRANSFER_MODE), ..file.clone() };
        assert_eq!(validate_manifest_file_shape(&packed).unwrap_err().code, "invalid_snapshot_transfer_mode");
        let empty_path = ManifestFile { path: "  ".into(), ..file.clone() };
        assert_eq!(validate_manifest_file_shape(&empty_path).unwrap_err().code, "invalid_snapshot_path");
    }
    /// `finalize-header-batching.test.ts` / `carried-forward-packs.test.ts`
    /// accounting rules, on the pure classification helpers.
    #[test]
    fn chain_delta_accounting_classifies_packs_like_the_worker() {
        let a1 = full_pack("a", "a1");
        assert_eq!(classify_chain_delta(&a1), ChainDeltaAccounting::Fixed(Some(0)));

        let a2 = delta_pack("a", "a2", &a1, "s1");
        assert_eq!(classify_chain_delta(&a2), ChainDeltaAccounting::FromBase);

        // v1 deltas stay unaccounted: the planner forces a re-full over them.
        let mut v1 = delta_pack("a", "a2", &a1, "s1");
        v1.delta_format_version = None;
        assert_eq!(classify_chain_delta(&v1), ChainDeltaAccounting::Fixed(None));
    }

    #[test]
    fn base_accumulators_restart_on_full_bases_and_extend_v2_chains() {
        let a1 = full_pack("a", "a1");
        // A full base restarts the accumulator; the delta then contributes
        // only its own blob size (40 in the batching fixture).
        assert_eq!(base_chain_accumulator(Some(&a1)), Some(0));

        let mut a2 = delta_pack("a", "a2", &a1, "s1");
        a2.chain_delta_bytes = Some(40);
        assert_eq!(base_chain_accumulator(Some(&a2)), Some(40));

        // A v1 (unaccounted) base and a missing base both refuse the chain.
        let mut unaccounted = delta_pack("a", "a2", &a1, "s1");
        unaccounted.chain_delta_bytes = None;
        assert_eq!(base_chain_accumulator(Some(&unaccounted)), None);
        assert_eq!(base_chain_accumulator(None), None);
    }
}
