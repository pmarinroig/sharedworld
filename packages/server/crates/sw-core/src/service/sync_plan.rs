//! Sync planning and blob transfer (`service/sync-plan.ts`).
//!
//! Plans which artifacts the current host must upload for its next snapshot
//! (reusing what is already stored, offering delta slots where the chain
//! budget allows), plans the downloads that bring a member's cache up to the
//! latest snapshot, and carries the relayed/direct blob transfer paths.
//!
//! Stale-work rule: every write path is epoch/token gated — a stale host
//! cannot obtain signed upload URLs, open a session, or PUT bytes.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use sw_contracts::*;
use sw_db::repo::{StorageUploadSessionRecord, WorldStorageBinding};

use super::runtime_access::{
    require_host_authority, require_membership, require_session_access_allowing_revoked_host,
    require_world_storage_binding,
};
use super::signer::{sign_download_for_world, sign_upload_for_world};
use super::snapshots::{is_delta_pack_transfer_mode, storage_keys_exist, WhenUnverifiable};
use super::worlds::cached_quota;
use super::ServiceContext;
use crate::http_error::{HttpError, HttpResult};
use crate::ids::random_id;
use crate::request::RequestContext;
use crate::stamp::{verify_blob_stamp, verify_download_stamp};
use crate::storage::drive::drive_storage_full_error;
use crate::storage::{
    parse_single_byte_range, BodyStream, PutBody, ResumableProbe, ResumableUploadCapable, StoredBlob,
};
use crate::time::{self, Instant};

/// 16 MiB: a multiple of Drive's 256 KiB resumable chunk quantum.
const DIRECT_UPLOAD_CHUNK_BYTES: i64 = 16 * 1024 * 1024;
const UPLOAD_SESSION_TTL_MS: i64 = 7 * 24 * 60 * 60_000;
const UPLOAD_SESSION_SWEEP_AFTER_MS: i64 = 8 * 24 * 60 * 60_000;
const UPLOAD_SESSION_SWEEP_LIMIT: i64 = 3;
const CONFIRMED_SESSION_RETAIN_MS: i64 = 24 * 60 * 60_000;
const DEFAULT_MAX_UPLOAD_BODY_BYTES: i64 = 95_000_000;

const UPLOAD_PHASES: &[WorldRuntimePhase] =
    &[WorldRuntimePhase::HostStarting, WorldRuntimePhase::HostLive, WorldRuntimePhase::HostFinalizing];

/// Relayed blob PUT, with the transport facts the HTTP layer extracted
/// (`x-sharedworld-runtime-epoch` / `-host-token` / `-blob-stamp`).
pub struct RelayUploadInput {
    pub content_length: Option<i64>,
    pub content_type: Option<String>,
    pub runtime_epoch: Option<i64>,
    pub host_token: Option<String>,
    pub blob_stamp: Option<String>,
    pub body: BodyStream,
}

/// Relayed blob GET.
#[derive(Debug, Clone, Default)]
pub struct RelayDownloadInput {
    pub range: Option<String>,
    pub blob_stamp: Option<String>,
}

// ---------------------------------------------------------------------------
// Upload planning
// ---------------------------------------------------------------------------

pub async fn prepare_uploads(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &UploadPlanRequest,
    now: Instant,
) -> HttpResult<UploadPlan> {
    require_host_authority(
        svc,
        ctx,
        world_id,
        request.runtime_epoch,
        request.host_token.as_deref(),
        UPLOAD_PHASES,
        now,
    )
    .await?;
    // Validation passed, so the request's own epoch/token are the current
    // authority tuple: they stamp the signed upload URLs.
    let runtime_epoch = request.runtime_epoch.unwrap_or(0);
    let runtime_token = request.host_token.as_deref();
    // Headers-only: planning consumes pack headers and ids, never member
    // lists — no member rows, no 0027 manifest-document fetch. A missing or
    // corrupt manifest doc can therefore never block the upload pipeline.
    let latest = svc.repository.get_latest_snapshot_headers(world_id).await?;
    // Packs whose latest header can no longer be honoured (unstamped delta
    // whose base snapshot row is gone) are planned as if the world had never
    // stored them: the host re-uploads the full artifact and the next
    // snapshot is whole again.
    let unreconstructable = unreconstructable_pack_ids(svc, world_id, latest.as_ref()).await?;
    let latest_packs: &[SnapshotPack] = latest.as_ref().map_or(&[], |m| m.packs.as_slice());
    let latest_pack = latest_packs
        .iter()
        .find(|p| p.pack_id == NON_REGION_PACK_ID && !unreconstructable.contains(&p.pack_id));
    let latest_bundle_by_id: HashMap<&str, &SnapshotPack> = latest_packs
        .iter()
        .filter(|p| is_region_bundle_id(&p.pack_id) && !unreconstructable.contains(&p.pack_id))
        .map(|p| (p.pack_id.as_str(), p))
        .collect();
    let latest_snapshot_id = latest.as_ref().map(|m| m.snapshot_id.clone());

    let binding = require_world_storage_binding(svc, world_id).await?;
    // Quota preflight: 0.4.x direct uploads PUT straight to Google, so a full
    // Drive would otherwise fail client-side with an unclassifiable 403 the
    // autosave loop retries forever.
    fail_if_drive_full(svc, &binding).await?;
    let signer = resolve_plan_signer(svc, ctx, &binding).await?;

    let supports_delta_v2 = ctx.client_at_least(0, 4, 0);
    let bundles: &[LocalPackDescriptor] = request.region_bundles.as_deref().unwrap_or(&[]);
    let bundle_keys: Vec<Option<CandidateKeys>> = bundles
        .iter()
        .map(|bundle| {
            grouped_artifact_candidate_keys(
                Some(bundle),
                latest_bundle_by_id.get(bundle.pack_id.as_str()).copied(),
                MAX_REGION_DELTA_CHAIN_DEPTH,
                storage_key_for_region_bundle_full,
                if supports_delta_v2 {
                    storage_key_for_region_bundle_delta_v2
                } else {
                    storage_key_for_region_bundle_delta
                },
                REGION_FULL_TRANSFER_MODE,
                REGION_DELTA_TRANSFER_MODE,
                supports_delta_v2,
            )
        })
        .collect();
    let pack_keys = grouped_artifact_candidate_keys(
        request.non_region_pack.as_ref(),
        latest_pack,
        MAX_PACK_DELTA_CHAIN_DEPTH,
        storage_key_for_pack_full,
        if supports_delta_v2 { storage_key_for_pack_delta_v2 } else { storage_key_for_pack_delta },
        PACK_FULL_TRANSFER_MODE,
        PACK_DELTA_TRANSFER_MODE,
        supports_delta_v2,
    );

    // One batched existence lookup for every candidate full/delta key: large
    // worlds carry hundreds of packs, and a per-pack query put upload prepare
    // past the client's request timeout.
    let mut candidate_keys: Vec<String> = Vec::new();
    for keys in bundle_keys.iter().chain(std::iter::once(&pack_keys)).flatten() {
        candidate_keys.push(keys.full_storage_key.clone());
        if let Some(delta) = &keys.delta_storage_key {
            candidate_keys.push(delta.clone());
        }
    }
    let existing = storage_keys_exist(svc, &binding, &candidate_keys, WhenUnverifiable::AskProvider).await?;

    let mut region_bundle_uploads: Vec<UploadPackPlan> = Vec::new();
    for (index, bundle) in bundles.iter().enumerate() {
        if let Some(plan) = prepare_grouped_artifact_upload(
            svc,
            ctx,
            world_id,
            Some(bundle),
            latest_snapshot_id.as_deref(),
            latest_bundle_by_id.get(bundle.pack_id.as_str()).copied(),
            runtime_epoch,
            runtime_token,
            bundle_keys[index].as_ref(),
            &existing,
            &signer,
        ) {
            region_bundle_uploads.push(plan);
        }
    }
    let non_region_pack_upload = prepare_grouped_artifact_upload(
        svc,
        ctx,
        world_id,
        request.non_region_pack.as_ref(),
        latest_snapshot_id.as_deref(),
        latest_pack,
        runtime_epoch,
        runtime_token,
        pack_keys.as_ref(),
        &existing,
        &signer,
    );

    let direct_upload_available =
        svc.storage_provider.resumable(&binding).is_some() && binding.storage_account_id.is_some();
    // Presigned uploads (S3) bypass the relay just like resumable sessions,
    // so the relay body ceiling does not apply to them either.
    fail_on_oversized_full_upload(
        svc,
        ctx,
        non_region_pack_upload.iter().chain(region_bundle_uploads.iter()),
        direct_upload_available || signer.is_presigned(),
    )?;
    Ok(UploadPlan {
        world_id: world_id.to_string(),
        snapshot_base_id: latest_snapshot_id,
        uploads: Vec::new(),
        non_region_pack_upload: Some(non_region_pack_upload),
        region_bundle_uploads: Some(region_bundle_uploads),
        sync_policy: sync_policy_for_provider(svc, binding.provider),
        latest_pack_ids: Some(latest_packs.iter().map(|p| p.pack_id.clone()).collect()),
        direct_upload: Some(direct_upload_available.then_some(DirectUploadPolicy {
            chunk_size_bytes: DIRECT_UPLOAD_CHUNK_BYTES,
            max_upload_bytes: None,
        })),
    })
}

/// Latest-snapshot packs that are delta artifacts with NO chainSteps recipe
/// AND whose base snapshot row no longer exists: nothing can rebuild them.
/// Bases became deletable by design in S1, so this state is reachable through
/// a manual backup delete or retention on a legacy (pre-stamping) chain.
async fn unreconstructable_pack_ids(
    svc: &ServiceContext,
    world_id: &str,
    latest: Option<&SnapshotManifest>,
) -> HttpResult<HashSet<String>> {
    let candidates: Vec<&SnapshotPack> = latest
        .map_or(&[][..], |m| m.packs.as_slice())
        .iter()
        .filter(|p| {
            is_delta_pack_transfer_mode(p.transfer_mode)
                && p.base_snapshot_id.is_some()
                && p.chain_steps.as_ref().is_none_or(|s| s.is_empty())
        })
        .collect();
    if candidates.is_empty() {
        return Ok(HashSet::new());
    }
    let bases: Vec<String> = candidates.iter().filter_map(|p| p.base_snapshot_id.clone()).collect();
    let existing = svc.repository.existing_snapshot_ids(world_id, &bases).await?;
    let broken: HashSet<String> = candidates
        .iter()
        .filter(|p| !existing.contains(p.base_snapshot_id.as_deref().unwrap_or("")))
        .map(|p| p.pack_id.clone())
        .collect();
    if !broken.is_empty() {
        let packs: Vec<&str> = broken.iter().map(|s| s.as_str()).collect();
        tracing::warn!(
            world_id,
            ?packs,
            "SharedWorld upload plan forcing full re-upload of unreconstructable packs"
        );
    }
    Ok(broken)
}

/// Bodies over the relay's limit die as unexplained 413s at the edge before
/// any server code runs, so a plan that would force such a full upload fails
/// here with the explanation attached. Fires only when no delta slot exists.
/// How this plan's transfer URLs get signed: the backend HMAC relay signer,
/// or store-native presigned URLs (S3, clients >= 0.4.0 — older clients
/// attach the bearer to every URL, which S3 rejects alongside query auth, so
/// they stay on the relay).
enum PlanSigner {
    Backend,
    Presigned(Box<dyn crate::storage::TransferPresigner>),
}

impl PlanSigner {
    fn is_presigned(&self) -> bool {
        matches!(self, PlanSigner::Presigned(_))
    }

    fn sign_upload(
        &self,
        svc: &ServiceContext,
        world_id: &str,
        storage_key: &str,
        runtime_epoch: i64,
        runtime_token: Option<&str>,
        request_origin: Option<&str>,
    ) -> sw_contracts::SignedBlobUrl {
        match self {
            PlanSigner::Backend => sign_upload_for_world(
                svc,
                world_id,
                storage_key,
                runtime_epoch,
                runtime_token,
                request_origin,
            ),
            PlanSigner::Presigned(p) => p.presign_put(storage_key),
        }
    }

    fn sign_download(
        &self,
        svc: &ServiceContext,
        world_id: &str,
        storage_key: &str,
        player_uuid: &str,
        request_origin: Option<&str>,
    ) -> sw_contracts::SignedBlobUrl {
        match self {
            PlanSigner::Backend => {
                sign_download_for_world(svc, world_id, storage_key, player_uuid, request_origin)
            }
            PlanSigner::Presigned(p) => p.presign_get(storage_key),
        }
    }
}

async fn resolve_plan_signer(
    svc: &ServiceContext,
    ctx: &RequestContext,
    binding: &WorldStorageBinding,
) -> HttpResult<PlanSigner> {
    if binding.provider == StorageProviderType::S3 && ctx.client_at_least(0, 4, 0) {
        if let Some(presign) = svc.storage_provider.presign(binding) {
            return Ok(PlanSigner::Presigned(presign.presign_context(binding).await?));
        }
    }
    Ok(PlanSigner::Backend)
}

fn fail_on_oversized_full_upload<'a>(
    svc: &ServiceContext,
    ctx: &RequestContext,
    plans: impl Iterator<Item = &'a UploadPackPlan>,
    direct_upload_available: bool,
) -> HttpResult<()> {
    if direct_upload_available && ctx.client_at_least(0, 4, 0) {
        // 0.4.0+ clients on a direct-capable world upload any size via
        // resumable sessions; the relay ceiling does not apply to them.
        return Ok(());
    }
    let limit_bytes = max_upload_body_bytes(svc);
    for plan in plans {
        if plan.already_present || plan.delta_storage_key.is_some() || plan.pack.size <= limit_bytes {
            continue;
        }
        let size_mb = megabytes(plan.pack.size);
        let limit_mb = megabytes(limit_bytes);
        return Err(HttpError::new(
            413,
            "blob_too_large",
            format!(
                "This world's \"{}\" data is {size_mb} MB, but relayed SharedWorld uploads are limited to {limit_mb} MB per blob. {}",
                plan.pack.pack_id,
                oversized_advice(ctx)
            ),
        ));
    }
    Ok(())
}

/// "Update the mod" is only honest advice for clients that predate direct
/// uploads; a current client landing here is on a relay-only world.
fn oversized_advice(ctx: &RequestContext) -> &'static str {
    if ctx.client_at_least(0, 4, 0) {
        "This world's storage only supports relayed transfers; shrink the world or re-link its storage."
    } else {
        "Update the SharedWorld mod to the latest version (it uploads large files directly), or shrink the world."
    }
}

fn megabytes(bytes: i64) -> i64 {
    std::cmp::max(1, (bytes as f64 / 1_000_000.0).round() as i64)
}

struct CandidateKeys {
    full_storage_key: String,
    delta_storage_key: Option<String>,
    base_chain_depth: i64,
    full_transfer_mode: FileTransferMode,
    delta_format_version: Option<i64>,
}

/// The storage keys a pack upload could target (full slot, plus a delta slot
/// when the chain budget allows). Computed for every pack up front so their
/// existence resolves in one batched query; `None` when no upload is needed.
fn grouped_artifact_candidate_keys(
    pack: Option<&LocalPackDescriptor>,
    latest_pack: Option<&SnapshotPack>,
    max_chain_depth: i64,
    full_storage_key_for_hash: fn(&str) -> String,
    delta_storage_key_for_hashes: fn(&str, &str) -> String,
    full_transfer_mode: FileTransferMode,
    delta_transfer_mode: FileTransferMode,
    supports_delta_v2: bool,
) -> Option<CandidateKeys> {
    let pack = pack?;
    if latest_pack.is_some_and(|l| l.hash == pack.hash) {
        return None;
    }
    let base_chain_depth = latest_pack
        .filter(|l| l.transfer_mode == delta_transfer_mode)
        .map(|l| l.chain_depth.unwrap_or(0))
        .unwrap_or(0);
    let chainable_base = latest_pack
        .is_some_and(|l| l.transfer_mode == full_transfer_mode || l.transfer_mode == delta_transfer_mode);
    let delta_available = if supports_delta_v2 {
        // Byte-budget policy (O(1), no chain walk): keep offering deltas while
        // the chain's cumulative delta bytes stay under the budget fraction of
        // the full artifact. A NULL accumulator (legacy/v1 base) forces one
        // full upload, which restarts accounting and keeps v2 deltas off
        // unaccounted chains. Base full artifacts have accumulator 0.
        let chain_delta_bytes = match latest_pack.filter(|l| l.transfer_mode == delta_transfer_mode) {
            Some(l) => l.chain_delta_bytes,
            None => Some(0),
        };
        chainable_base
            && base_chain_depth < DELTA_V2_MAX_CHAIN_DEPTH
            && chain_delta_bytes.is_some_and(|bytes| {
                (bytes as f64) <= DELTA_CHAIN_BUDGET_FRACTION * latest_pack.map_or(0, |l| l.size) as f64
            })
    } else {
        chainable_base && base_chain_depth < max_chain_depth
    };
    Some(CandidateKeys {
        full_storage_key: full_storage_key_for_hash(&pack.hash),
        delta_storage_key: delta_available
            .then(|| delta_storage_key_for_hashes(&latest_pack.expect("chainable base").hash, &pack.hash)),
        base_chain_depth,
        full_transfer_mode,
        delta_format_version: (delta_available && supports_delta_v2).then_some(DELTA_V2_FORMAT_VERSION),
    })
}

#[allow(clippy::too_many_arguments)]
fn prepare_grouped_artifact_upload(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    pack: Option<&LocalPackDescriptor>,
    latest_snapshot_id: Option<&str>,
    latest_pack: Option<&SnapshotPack>,
    runtime_epoch: i64,
    runtime_token: Option<&str>,
    candidate_keys: Option<&CandidateKeys>,
    existing_storage_keys: &HashSet<String>,
    signer: &PlanSigner,
) -> Option<UploadPackPlan> {
    let pack = pack?;
    if let Some(latest) = latest_pack.filter(|l| l.hash == pack.hash) {
        return Some(UploadPackPlan {
            pack: pack.clone(),
            already_present: true,
            storage_key: Some(latest.storage_key.clone()),
            transfer_mode: Some(latest.transfer_mode),
            upload: None,
            full_storage_key: None,
            full_upload: None,
            delta_storage_key: None,
            delta_upload: None,
            base_snapshot_id: latest.base_snapshot_id.clone(),
            base_hash: latest.base_hash.clone(),
            base_chain_depth: latest.chain_depth,
            delta_format_version: None,
        });
    }
    let keys = candidate_keys?;
    let full_exists = existing_storage_keys.contains(&keys.full_storage_key);
    let delta_exists = keys.delta_storage_key.as_ref().is_some_and(|k| existing_storage_keys.contains(k));
    let sign = |key: &str| {
        signer.sign_upload(svc, world_id, key, runtime_epoch, runtime_token, ctx.request_origin.as_deref())
    };
    Some(UploadPackPlan {
        pack: pack.clone(),
        already_present: false,
        transfer_mode: Some(keys.full_transfer_mode),
        storage_key: None,
        upload: None,
        full_storage_key: Some(keys.full_storage_key.clone()),
        full_upload: (!full_exists).then(|| sign(&keys.full_storage_key)),
        delta_storage_key: keys.delta_storage_key.clone(),
        delta_upload: match &keys.delta_storage_key {
            Some(key) if !delta_exists => Some(sign(key)),
            _ => None,
        },
        base_snapshot_id: latest_snapshot_id.map(|s| s.to_string()),
        base_hash: latest_pack.map(|l| l.hash.clone()),
        base_chain_depth: Some(keys.base_chain_depth),
        delta_format_version: keys.delta_format_version,
    })
}

// ---------------------------------------------------------------------------
// Download planning
// ---------------------------------------------------------------------------

pub async fn download_plan(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &UploadPlanRequest,
) -> HttpResult<DownloadPlan> {
    require_membership(svc, ctx, world_id).await?;
    let binding = svc.repository.get_world_storage_binding(world_id).await?;
    let provider = binding.as_ref().map(|b| b.provider).unwrap_or_else(|| svc.storage_provider.provider());
    let signer = match binding.as_ref() {
        Some(b) => resolve_plan_signer(svc, ctx, b).await?,
        None => PlanSigner::Backend,
    };
    let Some(latest) = svc.repository.get_latest_snapshot(world_id).await? else {
        return Ok(DownloadPlan {
            world_id: world_id.to_string(),
            snapshot_id: None,
            downloads: Vec::new(),
            non_region_pack_download: Some(None),
            region_bundle_downloads: Some(Vec::new()),
            retained_paths: request.files.iter().map(|f| f.path.clone()).collect(),
            sync_policy: sync_policy_for_provider(svc, provider),
        });
    };

    let local_by_path: HashMap<&str, &LocalFileDescriptor> =
        request.files.iter().map(|f| (f.path.as_str(), f)).collect();
    let mut retained_paths: Vec<String> = Vec::new();
    let mut snapshot_cache: HashMap<String, Arc<SnapshotManifest>> = HashMap::new();
    let supports_delta_v2 = ctx.client_at_least(0, 4, 0);
    // Chain recipes live only in the directory (headers path, uncached) —
    // served manifests stay byte-stable while retention lazily upgrades
    // legacy directories in place.
    let latest_headers = svc.repository.get_latest_snapshot_headers(world_id).await?;
    let chain_steps_by_pack_id: HashMap<String, Vec<PackChainStep>> = latest_headers
        .filter(|h| h.snapshot_id == latest.snapshot_id)
        .map(|h| h.packs)
        .unwrap_or_default()
        .into_iter()
        .filter_map(|p| match p.chain_steps {
            Some(steps) if !steps.is_empty() => Some((p.pack_id, steps)),
            _ => None,
        })
        .collect();

    let mut non_region_pack_download: Option<DownloadPackPlan> = None;
    let mut region_bundle_downloads: Vec<DownloadPackPlan> = Vec::new();
    let changed = |pack: &SnapshotPack| {
        pack.files
            .iter()
            .any(|f| local_by_path.get(f.path.as_str()).map(|l| l.hash.as_str()) != Some(f.hash.as_str()))
    };

    if let Some(latest_pack) = latest.packs.iter().find(|p| p.pack_id == NON_REGION_PACK_ID) {
        if changed(latest_pack) {
            non_region_pack_download = Some(DownloadPackPlan {
                pack_id: latest_pack.pack_id.clone(),
                hash: latest_pack.hash.clone(),
                size: latest_pack.size,
                files: latest_pack.files.clone(),
                steps: build_pack_download_steps(
                    svc,
                    ctx,
                    world_id,
                    latest_pack,
                    chain_steps_by_pack_id.get(&latest_pack.pack_id),
                    request.non_region_pack.as_ref().map(|p| p.hash.as_str()),
                    &mut snapshot_cache,
                    PACK_DELTA_TRANSFER_MODE,
                    supports_delta_v2,
                    &signer,
                )
                .await?,
            });
        } else {
            retained_paths.extend(latest_pack.files.iter().map(|f| f.path.clone()));
        }
    }
    for bundle in latest.packs.iter().filter(|p| is_region_bundle_id(&p.pack_id)) {
        if changed(bundle) {
            let local_hash = request
                .region_bundles
                .as_ref()
                .and_then(|b| b.iter().find(|e| e.pack_id == bundle.pack_id))
                .map(|e| e.hash.as_str());
            region_bundle_downloads.push(DownloadPackPlan {
                pack_id: bundle.pack_id.clone(),
                hash: bundle.hash.clone(),
                size: bundle.size,
                files: bundle.files.clone(),
                steps: build_pack_download_steps(
                    svc,
                    ctx,
                    world_id,
                    bundle,
                    chain_steps_by_pack_id.get(&bundle.pack_id),
                    local_hash,
                    &mut snapshot_cache,
                    REGION_DELTA_TRANSFER_MODE,
                    supports_delta_v2,
                    &signer,
                )
                .await?,
            });
        } else {
            retained_paths.extend(bundle.files.iter().map(|f| f.path.clone()));
        }
    }

    let mut plan = DownloadPlan {
        world_id: world_id.to_string(),
        snapshot_id: Some(latest.snapshot_id.clone()),
        downloads: Vec::new(),
        non_region_pack_download: Some(non_region_pack_download),
        region_bundle_downloads: Some(region_bundle_downloads),
        retained_paths,
        sync_policy: sync_policy_for_provider(svc, provider),
    };
    // Lane D: stamp each step with a relay token when the deployment relays
    // downloads through Cloudflare (no-op otherwise).
    if svc.relay_keys.is_some() {
        match binding.as_ref() {
            Some(binding) => {
                crate::relay::attach_relay_tokens(svc, binding, &mut plan, &ctx.player_uuid).await?
            }
            None => tracing::info!(world_id, "relay tokens skipped: no storage binding"),
        }
    } else {
        tracing::info!(world_id, "relay tokens skipped: relay keys not configured");
    }
    Ok(plan)
}

#[allow(clippy::too_many_arguments)]
async fn build_pack_download_steps(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    latest_pack: &SnapshotPack,
    chain_steps: Option<&Vec<PackChainStep>>,
    local_pack_hash: Option<&str>,
    snapshot_cache: &mut HashMap<String, Arc<SnapshotManifest>>,
    delta_transfer_mode: FileTransferMode,
    supports_delta_v2: bool,
    signer: &PlanSigner,
) -> HttpResult<Vec<DownloadPlanStep>> {
    if let Some(steps) = chain_steps.filter(|s| !s.is_empty()) {
        // S1 self-contained chains: the plan builds from the pack's own recipe
        // — no base snapshot rows, no chain walk, no snapshot_chain_broken.
        return build_steps_from_chain_recipe(
            svc,
            ctx,
            world_id,
            steps,
            local_pack_hash,
            supports_delta_v2,
            signer,
        );
    }
    let mut steps: Vec<DownloadPlanStep> = Vec::new();
    let mut cursor: Option<SnapshotPack> = Some(latest_pack.clone());
    while let Some(pack) = cursor.take() {
        if local_pack_hash == Some(pack.hash.as_str()) {
            break;
        }
        if pack.delta_format_version.is_some() && !supports_delta_v2 {
            // No full-root fallback exists: the latest full blob was never
            // uploaded once a delta chain started, and serving the retained
            // chain root alone would reconstruct STALE content whose hash
            // cannot match the manifest.
            return Err(client_update_required());
        }
        steps.push(DownloadPlanStep {
            transfer_mode: pack.transfer_mode,
            storage_key: pack.storage_key.clone(),
            artifact_size: pack.size,
            base_snapshot_id: pack.base_snapshot_id.clone(),
            base_hash: pack.base_hash.clone(),
            delta_format_version: pack.delta_format_version,
            download: signer.sign_download(
                svc,
                world_id,
                &pack.storage_key,
                &ctx.player_uuid,
                ctx.request_origin.as_deref(),
            ),
        });
        if pack.transfer_mode != delta_transfer_mode {
            break;
        }
        let Some(base_snapshot_id) = pack.base_snapshot_id.as_deref() else { break };
        if local_pack_hash.is_some() && local_pack_hash == pack.base_hash.as_deref() {
            break;
        }
        let base = load_snapshot_pack(svc, world_id, base_snapshot_id, &pack.pack_id, snapshot_cache).await?;
        let Some(base) = base else {
            // The chain needs a base artifact whose snapshot row no longer
            // exists. A truncated plan would fail client-side mid-apply with a
            // confusing missing-delta-base error; refuse loudly instead.
            return Err(HttpError::new(
                409,
                "snapshot_chain_broken",
                format!(
                    "SharedWorld backup data for '{}' is missing a delta base artifact.",
                    latest_pack.pack_id
                ),
            ));
        };
        cursor = Some(base);
    }
    steps.reverse();
    Ok(steps)
}

/// Mirror of the legacy walk, driven by the stamped recipe: newest step
/// backwards until the client's local hash matches an intermediate chain state
/// or the anchor full is reached, then served oldest-first. Step
/// `baseSnapshotId` is null on purpose — the recipe is snapshot-independent.
fn build_steps_from_chain_recipe(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    chain_steps: &[PackChainStep],
    local_pack_hash: Option<&str>,
    supports_delta_v2: bool,
    signer: &PlanSigner,
) -> HttpResult<Vec<DownloadPlanStep>> {
    let mut steps: Vec<DownloadPlanStep> = Vec::new();
    for step in chain_steps.iter().rev() {
        if local_pack_hash == Some(step.hash.as_str()) {
            break;
        }
        if step.delta_format_version.is_some() && !supports_delta_v2 {
            return Err(client_update_required());
        }
        steps.push(DownloadPlanStep {
            transfer_mode: step.transfer_mode,
            storage_key: step.storage_key.clone(),
            artifact_size: step.size,
            base_snapshot_id: None,
            base_hash: step.base_hash.clone(),
            delta_format_version: step.delta_format_version,
            download: signer.sign_download(
                svc,
                world_id,
                &step.storage_key,
                &ctx.player_uuid,
                ctx.request_origin.as_deref(),
            ),
        });
        let Some(base_hash) = step.base_hash.as_deref() else { break };
        if local_pack_hash == Some(base_hash) {
            break;
        }
    }
    steps.reverse();
    Ok(steps)
}

fn client_update_required() -> HttpError {
    HttpError::new(
        409,
        "client_update_required",
        "This world was uploaded by a newer SharedWorld version. Update the SharedWorld mod to download it.",
    )
}

async fn load_snapshot_pack(
    svc: &ServiceContext,
    world_id: &str,
    snapshot_id: &str,
    pack_id: &str,
    snapshot_cache: &mut HashMap<String, Arc<SnapshotManifest>>,
) -> HttpResult<Option<SnapshotPack>> {
    let snapshot = match snapshot_cache.get(snapshot_id) {
        Some(cached) => cached.clone(),
        None => {
            let Some(loaded) = svc.repository.get_snapshot(world_id, snapshot_id).await? else {
                return Ok(None);
            };
            snapshot_cache.insert(snapshot_id.to_string(), loaded.clone());
            loaded
        }
    };
    Ok(snapshot.packs.iter().find(|p| p.pack_id == pack_id).cloned())
}

// ---------------------------------------------------------------------------
// Stamped authority fast path
// ---------------------------------------------------------------------------

/// True when a valid blob stamp scoped to (worldId, storageKey) names an epoch
/// that is still the live runtime per the mirror. This replaces the
/// coordinator round-trip on the per-artifact routes: the stamp was minted
/// only after full authority validation at plan time, and the mirror —
/// single-writer, coordinator-maintained — pins the epoch to the present.
/// Mirror `revokedAt` is deliberately ignored, matching host-authority
/// validation (a revoked host may finish its uploads; finalize is the gate).
async fn stamp_authorized(
    svc: &ServiceContext,
    world_id: &str,
    stamp: Option<&str>,
    storage_key: &str,
    now: Instant,
) -> bool {
    let Some(stamp) = stamp.filter(|s| !s.is_empty()) else { return false };
    let Some(epoch) = verify_blob_stamp(&svc.stamp_keys, stamp, world_id, storage_key, now) else {
        return false;
    };
    let Ok(Some(mirror)) = svc.repository.get_runtime_mirror(world_id).await else { return false };
    let Some(status_json) = mirror.status_json else { return false };
    let Ok(status) = serde_json::from_str::<serde_json::Value>(&status_json) else { return false };
    let phase = status.get("phase").and_then(|v| v.as_str()).unwrap_or("");
    let live = matches!(phase, "host-starting" | "host-live" | "host-finalizing");
    live && status.get("runtimeEpoch").and_then(|v| v.as_i64()) == Some(epoch)
}

// ---------------------------------------------------------------------------
// Direct (resumable) uploads
// ---------------------------------------------------------------------------

fn require_resumable<'a>(
    svc: &'a ServiceContext,
    binding: &WorldStorageBinding,
) -> HttpResult<&'a dyn ResumableUploadCapable> {
    match svc.storage_provider.resumable(binding) {
        Some(capable) if binding.storage_account_id.is_some() => Ok(capable),
        _ => Err(HttpError::new(
            409,
            "direct_upload_unsupported",
            "This world's storage does not support direct uploads.",
        )),
    }
}

fn json_str(v: Option<&serde_json::Value>) -> Option<&str> {
    v.and_then(|v| v.as_str())
}

fn json_num(v: Option<&serde_json::Value>) -> Option<f64> {
    v.and_then(|v| v.as_f64()).filter(|n| n.is_finite())
}

/// Starts a direct-to-provider resumable upload for one storage key. Same
/// authority gate as the relay blob PUT; the returned session URL is the
/// provider's own resumable URI, which the client feeds bytes without any
/// SharedWorld credential.
pub async fn create_blob_upload_session(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &CreateBlobSessionRequest,
    now: Instant,
) -> HttpResult<CreateBlobSessionResponse> {
    let storage_key = json_str(request.storage_key.as_ref()).unwrap_or("");
    if !stamp_authorized(svc, world_id, request.blob_stamp.as_deref(), storage_key, now).await {
        require_host_authority(
            svc,
            ctx,
            world_id,
            request.runtime_epoch,
            request.host_token.as_deref(),
            UPLOAD_PHASES,
            now,
        )
        .await?;
    }
    let binding = require_world_storage_binding(svc, world_id).await?;
    let capable = require_resumable(svc, &binding)?;
    if storage_key.trim().is_empty() {
        return Err(HttpError::new(400, "invalid_storage_key", "Storage key is required."));
    }
    let Some(content_length) = json_num(request.content_length.as_ref()).filter(|n| *n > 0.0) else {
        return Err(HttpError::new(400, "invalid_upload_size", "Upload size must be a positive byte count."));
    };
    let content_length = content_length as i64;
    let content_type = json_str(request.content_type.as_ref())
        .filter(|s| !s.is_empty())
        .unwrap_or("application/octet-stream");

    fail_if_drive_full(svc, &binding).await?;
    sweep_expired_upload_sessions(svc, capable, &binding, now).await?;
    // No GC retry sweep here: this runs once per BLOB (a big world opens
    // hundreds of sessions per snapshot), so a queue that filled after a
    // retention prune used to bill its reference checks per upload.
    let session_url =
        capable.create_resumable_session(&binding, storage_key, content_type, content_length).await?;
    let upload_id = random_id("upl");
    svc.repository
        .create_upload_session(StorageUploadSessionRecord {
            upload_id: upload_id.clone(),
            provider: binding.provider,
            storage_account_id: binding.storage_account_id.clone().unwrap_or_default(),
            world_id: world_id.to_string(),
            storage_key: storage_key.to_string(),
            session_url: session_url.clone(),
            content_type: content_type.to_string(),
            expected_size: content_length,
            created_at: time::to_iso(now),
            confirmed_at: None,
        })
        .await?;
    Ok(CreateBlobSessionResponse {
        upload_id,
        session_url,
        chunk_size_bytes: DIRECT_UPLOAD_CHUNK_BYTES,
        expires_at: time::plus_ms_iso(now, UPLOAD_SESSION_TTL_MS),
    })
}

/// Confirms a finished direct upload. The server never trusts the client's
/// word: it probes the provider session itself and records the provider's
/// reported file id and size. Idempotent — a lost response is safely retried.
pub async fn commit_blob_upload_session(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &CommitBlobSessionRequest,
    now: Instant,
) -> HttpResult<CommitBlobSessionResponse> {
    // Stamp scope-check runs against the session's own storage key; error
    // ordering for stampless callers is unchanged (authority before the 410).
    let session = svc
        .repository
        .get_upload_session(json_str(request.upload_id.as_ref()).unwrap_or(""))
        .await?
        .filter(|s| s.world_id == world_id);
    let stamped = match &session {
        Some(s) => stamp_authorized(svc, world_id, request.blob_stamp.as_deref(), &s.storage_key, now).await,
        None => false,
    };
    if !stamped {
        require_host_authority(
            svc,
            ctx,
            world_id,
            request.runtime_epoch,
            request.host_token.as_deref(),
            UPLOAD_PHASES,
            now,
        )
        .await?;
    }
    let binding = require_world_storage_binding(svc, world_id).await?;
    let capable = require_resumable(svc, &binding)?;
    let Some(session) = session else {
        return Err(HttpError::new(
            410,
            "upload_session_expired",
            "This upload session is no longer active. Start the upload again.",
        ));
    };
    if session.confirmed_at.is_some() {
        let object = svc
            .repository
            .get_storage_object(session.provider, &session.storage_account_id, &session.storage_key)
            .await?;
        return Ok(CommitBlobSessionResponse {
            storage_key: session.storage_key,
            size: object.map(|o| o.size).unwrap_or(session.expected_size),
        });
    }
    let probe =
        capable.probe_resumable_session(&binding, &session.session_url, session.expected_size).await?;
    let (file_id, size) = match probe {
        ResumableProbe::Incomplete { received_up_to } => {
            return Err(HttpError::new(
                409,
                "upload_incomplete",
                format!(
                    "The upload has only {received_up_to} of {} bytes. Finish uploading, then commit again.",
                    session.expected_size
                ),
            ))
        }
        ResumableProbe::Expired => {
            svc.repository.delete_upload_session(&session.upload_id).await?;
            return Err(HttpError::new(
                410,
                "upload_session_expired",
                "This upload session expired. Start the upload again.",
            ));
        }
        ResumableProbe::Complete { file_id, size } => (file_id, size),
    };
    if size != session.expected_size {
        capable.delete_object_by_id(&binding, &file_id).await?;
        svc.repository.delete_upload_session(&session.upload_id).await?;
        return Err(HttpError::new(
            409,
            "upload_size_mismatch",
            format!(
                "The stored upload is {size} bytes but {} were expected. Start the upload again.",
                session.expected_size
            ),
        ));
    }
    capable
        .register_uploaded_object(&binding, &session.storage_key, &file_id, size, &session.content_type)
        .await?;
    svc.repository.mark_upload_session_confirmed(&session.upload_id, &time::to_iso(now)).await?;
    Ok(CommitBlobSessionResponse { storage_key: session.storage_key, size })
}

/// Bounded, opportunistic reclaim of stale unconfirmed sessions for this
/// account. Never-completed resumable sessions leave no provider file behind;
/// completed-but-unconfirmed ones do, so those get deleted unless the object
/// row already adopted the file. Session init is the natural moment because it
/// proves the account is active.
pub async fn sweep_expired_upload_sessions(
    svc: &ServiceContext,
    capable: &dyn ResumableUploadCapable,
    binding: &WorldStorageBinding,
    now: Instant,
) -> HttpResult<()> {
    let Some(account_id) = binding.storage_account_id.as_deref() else { return Ok(()) };
    // Confirmed rows outlive their commit only to serve idempotent commit
    // retries; after a day they are pure growth. Plain bounded DELETE.
    svc.repository
        .delete_confirmed_upload_sessions_before(
            binding.provider,
            account_id,
            &time::plus_ms_iso(now, -CONFIRMED_SESSION_RETAIN_MS),
            20,
        )
        .await?;
    let cutoff = time::plus_ms_iso(now, -UPLOAD_SESSION_SWEEP_AFTER_MS);
    let stale = svc
        .repository
        .list_unconfirmed_upload_sessions_before(
            binding.provider,
            account_id,
            &cutoff,
            UPLOAD_SESSION_SWEEP_LIMIT,
        )
        .await?;
    for session in stale {
        match capable.probe_resumable_session(binding, &session.session_url, session.expected_size).await {
            Ok(ResumableProbe::Complete { file_id, .. }) => {
                let object = svc
                    .repository
                    .get_storage_object(session.provider, &session.storage_account_id, &session.storage_key)
                    .await?;
                if object.map(|o| o.object_id).as_deref() != Some(file_id.as_str()) {
                    let _ = capable.delete_object_by_id(binding, &file_id).await;
                }
            }
            Ok(_) => {}
            Err(error) => {
                tracing::warn!(
                    upload_id = %session.upload_id,
                    cause = %error,
                    "SharedWorld upload-session sweep probe failed"
                );
            }
        }
        svc.repository.delete_upload_session(&session.upload_id).await?;
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Relayed blob transfer
// ---------------------------------------------------------------------------

/// Blob bytes flow through the server; host authority is re-checked from the
/// runtime headers stamped onto the signed upload URL — via the HMAC blob
/// stamp when present and current (no coordinator call), else the coordinator.
pub async fn upload_storage_blob(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    storage_key: &str,
    input: RelayUploadInput,
    now: Instant,
) -> HttpResult<()> {
    if !stamp_authorized(svc, world_id, input.blob_stamp.as_deref(), storage_key, now).await {
        require_host_authority(
            svc,
            ctx,
            world_id,
            input.runtime_epoch,
            input.host_token.as_deref(),
            UPLOAD_PHASES,
            now,
        )
        .await?;
    }
    let content_type = input.content_type.unwrap_or_else(|| "application/octet-stream".into());
    let limit_bytes = max_upload_body_bytes(svc);
    let oversized = |bytes: i64| {
        HttpError::new(
            413,
            "blob_too_large",
            format!(
                "This blob is {} MB, but relayed SharedWorld uploads are limited to {} MB per blob. {}",
                megabytes(bytes),
                megabytes(limit_bytes),
                oversized_advice(ctx)
            ),
        )
    };
    let body = match input.content_length.filter(|n| *n >= 0) {
        Some(declared) => {
            if declared > limit_bytes {
                return Err(oversized(declared));
            }
            PutBody::Stream { stream: input.body, len: Some(declared) }
        }
        None => {
            // Chunked upload with no Content-Length: shipped clients (Java
            // HttpClient with a progress-wrapped InputStream publisher) send
            // these, so a 411 here breaks real relays. Buffer ONCE — a single
            // copy stays small because the relay ceiling does — and stream to
            // the provider with the now-known length.
            let buffered = PutBody::Stream { stream: input.body, len: None }.into_bytes().await?;
            let len = buffered.len() as i64;
            if len > limit_bytes {
                return Err(oversized(len));
            }
            PutBody::Stream {
                stream: Box::pin(futures::stream::once(async move { Ok(buffered) })),
                len: Some(len),
            }
        }
    };
    let binding = require_world_storage_binding(svc, world_id).await?;
    svc.storage_provider.put(&binding, storage_key, body, &content_type).await
}

/// Blob bytes flow through the server; read access is re-checked from the
/// download stamp on the signed URL when present and current (no coordinator
/// call, no membership query), else via the coordinator path with the
/// revoked-host exception.
pub async fn download_storage_blob(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    storage_key: &str,
    input: &RelayDownloadInput,
    now: Instant,
) -> HttpResult<StoredBlob> {
    let stamped = input.blob_stamp.as_deref().filter(|s| !s.is_empty()).is_some_and(|stamp| {
        verify_download_stamp(&svc.stamp_keys, stamp, world_id, storage_key, &ctx.player_uuid, now)
    });
    if !stamped {
        require_session_access_allowing_revoked_host(svc, ctx, world_id).await?;
    }
    let range = parse_single_byte_range(input.range.as_deref());
    let binding = require_world_storage_binding(svc, world_id).await?;
    let Some(mut blob) = svc.storage_provider.get(&binding, storage_key, range.as_ref()).await? else {
        return Err(HttpError::new(404, "blob_not_found", "Blob not found."));
    };
    // A provider that ignored the range (test doubles, future providers) still
    // answers a correct 200 with the whole blob; clients treat 200-after-Range
    // as "restart from scratch". No ETags needed: storage keys are content
    // addressed, so the bytes behind a key can never change between attempts.
    if blob.status != 206 || blob.content_range.is_none() {
        blob.status = 200;
        blob.content_range = None;
    }
    Ok(blob)
}

// ---------------------------------------------------------------------------
// Policy
// ---------------------------------------------------------------------------

fn positive(value: Option<i64>, fallback: i64) -> i64 {
    value.filter(|v| *v > 0).unwrap_or(fallback)
}

/// Google Drive gets the conservative pacing because upload request starts are
/// its constrained resource; other providers can be driven harder.
pub fn sync_policy_for_provider(svc: &ServiceContext, provider: StorageProviderType) -> SyncPolicy {
    let config = &svc.config;
    if provider == StorageProviderType::GoogleDrive {
        return SyncPolicy {
            max_parallel_downloads: positive(config.drive_max_parallel_downloads, 8),
            max_concurrent_upload_preparations: positive(config.drive_max_upload_preparations, 2),
            max_concurrent_uploads: positive(config.drive_max_concurrent_uploads, 3),
            max_upload_starts_per_second: positive(config.drive_max_upload_starts_per_second, 3),
            retry_base_delay_ms: positive(config.drive_retry_base_delay_ms, 750),
            retry_max_delay_ms: positive(config.drive_retry_max_delay_ms, 8_000),
            max_upload_body_bytes: max_upload_body_bytes(svc),
        };
    }
    SyncPolicy {
        max_parallel_downloads: 16,
        max_concurrent_upload_preparations: 4,
        max_concurrent_uploads: 4,
        max_upload_starts_per_second: 8,
        retry_base_delay_ms: 250,
        retry_max_delay_ms: 4_000,
        max_upload_body_bytes: max_upload_body_bytes(svc),
    }
}

pub fn max_upload_body_bytes(svc: &ServiceContext) -> i64 {
    positive(svc.config.upload_max_body_bytes, DEFAULT_MAX_UPLOAD_BODY_BYTES)
}

/// Terminal preflight for a full Drive. Uses the 15-min cached quota — the
/// check only fires when the account is genuinely at capacity, and clears
/// within one cache TTL of the user freeing space. Unlinked worlds and unknown
/// quotas pass (a missing check must not block uploads).
async fn fail_if_drive_full(svc: &ServiceContext, binding: &WorldStorageBinding) -> HttpResult<()> {
    if binding.provider != StorageProviderType::GoogleDrive || binding.storage_account_id.is_none() {
        return Ok(());
    }
    match cached_quota(svc, binding).await {
        Ok(quota) => {
            let full = matches!((quota.used_bytes, quota.total_bytes), (Some(used), Some(total)) if total > 0 && used >= total);
            if full {
                return Err(drive_storage_full_error());
            }
        }
        // Quota lookups are best-effort; an unreachable /about must not block
        // uploads (the classified 403 still catches a truly full Drive).
        Err(error) => {
            tracing::warn!(cause = %error, "SharedWorld quota preflight failed");
        }
    }
    Ok(())
}
