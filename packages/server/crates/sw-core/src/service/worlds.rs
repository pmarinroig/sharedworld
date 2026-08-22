//! Worlds domain (`service/worlds.ts`).

use std::collections::BTreeMap;

use base64::Engine;
use sha2::{Digest, Sha256};
use sw_contracts::*;
use sw_db::repo::{WorldStorageBinding, WorldUpdateRecord};

use super::runtime_access::*;
use super::signer::sign_download_for_world;
use super::snapshots::{
    apply_snapshot_retention, maybe_delete_unreferenced_blob, purge_world_snapshots,
    DEFERRED_BLOB_DELETE_BUDGET_MS,
};
use super::{ServiceContext, Svc};
use crate::http_error::{HttpError, HttpResult};
use crate::ids::slugify;
use crate::request::RequestContext;
use crate::storage::{PutBody, StorageBinding, StorageQuota};
use crate::time::Instant;

pub async fn list_worlds(svc: &ServiceContext, ctx: &RequestContext) -> HttpResult<Vec<WorldSummary>> {
    let worlds = svc.repository.list_worlds_for_player(&ctx.player_uuid).await?;
    Ok(worlds.into_iter().map(|w| hydrate_world_summary(svc, w, ctx)).collect())
}

fn weak_etag_of(material: &serde_json::Value) -> String {
    let bytes = serde_json::to_vec(material).expect("json");
    let digest = Sha256::digest(&bytes);
    format!("W/\"{}\"", hex(&digest))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn etag_material(facts: serde_json::Value, ctx: &RequestContext) -> serde_json::Value {
    serde_json::json!({
        "facts": facts,
        "playerUuid": ctx.player_uuid,
        "origin": ctx.request_origin,
        "clientVersion": ctx.client_version,
    })
}

/// Weak ETag for GET /worlds; always present.
pub async fn worlds_etag(svc: &ServiceContext, ctx: &RequestContext) -> HttpResult<String> {
    let facts = svc.repository.worlds_change_facts(&ctx.player_uuid).await?;
    Ok(weak_etag_of(&etag_material(facts, ctx)))
}

/// Weak ETag for GET /worlds/:id; `None` when the caller has no access.
pub async fn world_etag(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    now: Instant,
) -> HttpResult<Option<String>> {
    let facts = svc.repository.world_change_facts(world_id, &ctx.player_uuid, now).await?;
    Ok(facts.map(|f| weak_etag_of(&etag_material(f, ctx))))
}

pub async fn create_world(
    svc: &Svc,
    ctx: &RequestContext,
    request: &CreateWorldRequest,
    now: Instant,
) -> HttpResult<CreateWorldResult> {
    // Growth valve, checked before any validation or link consumption.
    if let Some(max) = svc.config.max_active_worlds.filter(|m| *m > 0) {
        if svc.repository.count_active_worlds().await? >= max {
            return Err(HttpError::new(503, "world_capacity_reached", "SharedWorld is at capacity right now, so new worlds can't be created. Please try again later."));
        }
    }
    let name = require_valid_world_name(request.name.as_ref())?;
    let uses_storage = request.storage_link_session_id.as_deref().is_some_and(|s| !s.is_empty())
        || request.use_linked_storage_account == Some(true);
    if uses_storage {
        let ok = request.import_source.as_ref().is_some_and(|src| {
            src.get("type").and_then(|t| t.as_str()) == Some("local-save")
                && src.get("id").and_then(|i| i.as_str()).is_some_and(|i| !i.trim().is_empty())
        });
        if !ok {
            return Err(HttpError::new(
                400,
                "invalid_import_source",
                "A local save import source is required.",
            ));
        }
    }
    let binding: StorageBinding =
        if let Some(link_id) = request.storage_link_session_id.as_deref().filter(|s| !s.is_empty()) {
            let link = svc.storage_links.require_completed_link_session(ctx, link_id).await?;
            WorldStorageBinding { provider: link.provider, storage_account_id: link.storage_account_id }
        } else if request.use_linked_storage_account == Some(true) {
            resolve_linked_storage_binding(svc, ctx).await?
        } else {
            WorldStorageBinding { provider: svc.storage_provider.provider(), storage_account_id: None }
        };
    let motd = normalize_motd(request.motd_line1.as_deref(), request.motd_line2.as_deref())?;
    let world = svc
        .repository
        .create_world(&ctx.actor(), &name, &slugify(&name), binding.clone(), motd, None)
        .await?;
    if let Some(icon) = request.custom_icon_png_base64.as_deref().filter(|s| !s.is_empty()) {
        let icon_binding = WorldStorageBinding {
            provider: world.summary.storage_provider,
            storage_account_id: binding.storage_account_id.clone(),
        };
        let key = store_custom_icon(svc, &icon_binding, icon).await?;
        let (l1, l2) = split_motd(world.summary.motd.as_deref());
        let updated = svc
            .repository
            .update_world(
                &ctx.actor(),
                &world.summary.id,
                WorldUpdateRecord {
                    name: world.summary.name.clone(),
                    motd_line1: l1,
                    motd_line2: l2,
                    clear_custom_icon: false,
                    custom_icon_storage_key: Some(Some(key)),
                },
            )
            .await?;
        return create_seeded_world_result(svc, ctx, updated, now).await;
    }
    create_seeded_world_result(svc, ctx, world, now).await
}

pub async fn get_world(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    now: Instant,
) -> HttpResult<WorldDetails> {
    let world = require_world_details(svc, world_id, &ctx.player_uuid).await?;
    let owner_uuid = world.summary.owner_uuid.clone();
    let mut hydrated = hydrate_world_details(svc, world, ctx);
    // 0.4.1+ clients fetch usage on demand; older ones keep the inline value.
    if ctx.client_at_least(0, 4, 1) {
        hydrated.storage_usage = None;
    } else {
        match legacy_cached_storage_usage(svc, &hydrated).await {
            Ok(usage) => hydrated.storage_usage = Some(usage),
            Err(e) => {
                tracing::warn!(world_id, error = %e, "SharedWorld storage usage unavailable for world details");
                hydrated.storage_usage = None;
            }
        }
    }
    hydrated.active_invite_code = if owner_uuid == ctx.player_uuid {
        svc.repository.get_active_invite(world_id, now).await?
    } else {
        None
    };
    Ok(hydrated)
}

pub async fn update_world(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &UpdateWorldRequest,
) -> HttpResult<WorldDetails> {
    let name = require_valid_world_name(request.name.as_ref())?;
    let world = require_world_details(svc, world_id, &ctx.player_uuid).await?;
    require_owner(&world, ctx, "edit this world")?;
    let motd = normalize_motd(request.motd_line1.as_deref(), request.motd_line2.as_deref())?;
    let binding = require_world_storage_binding(svc, world_id).await?;
    let mut custom_icon_storage_key = world.summary.custom_icon_storage_key.clone();
    let clear = request.clear_custom_icon == Some(true);
    if clear {
        custom_icon_storage_key = None;
    } else if let Some(icon) = request.custom_icon_png_base64.as_deref().filter(|s| !s.is_empty()) {
        custom_icon_storage_key = Some(store_custom_icon(svc, &binding, icon).await?);
    }
    let (l1, l2) = split_motd(motd.as_deref());
    let updated = svc
        .repository
        .update_world(
            &ctx.actor(),
            world_id,
            WorldUpdateRecord {
                name,
                motd_line1: l1,
                motd_line2: l2,
                clear_custom_icon: clear,
                custom_icon_storage_key: Some(custom_icon_storage_key),
            },
        )
        .await?;
    let old_icon = if world.summary.custom_icon_storage_key != updated.summary.custom_icon_storage_key {
        world.summary.custom_icon_storage_key.clone()
    } else {
        None
    };
    maybe_delete_unreferenced_blob(svc, &binding, old_icon.as_deref()).await?;
    publish_world_event(svc, world_id, RealtimeEventKind::WorldChanged).await?;
    Ok(hydrate_world_details(svc, updated, ctx))
}

pub async fn update_world_settings(
    svc: &Svc,
    ctx: &RequestContext,
    world_id: &str,
    request: &UpdateWorldSettingsRequest,
) -> HttpResult<WorldDetails> {
    let world = require_world_details(svc, world_id, &ctx.player_uuid).await?;
    require_owner(&world, ctx, "change world settings")?;
    let settings = validate_world_settings(&request.settings)?;
    let previous_max = svc
        .repository
        .get_world_settings(world_id)
        .await?
        .and_then(|s| s.settings)
        .and_then(|s| s.max_backups.flatten());
    if !svc
        .repository
        .update_world_settings(world_id, &serde_json::to_string(&settings).expect("json"))
        .await?
    {
        return Err(HttpError::new(404, "world_not_found", "This Shared World no longer exists."));
    }
    publish_world_event(svc, world_id, RealtimeEventKind::SettingsChanged).await?;
    // 0.4.5: a tightened cap takes effect now, after the response.
    let next_max = settings.max_backups.flatten();
    if let Some(next) = next_max {
        if previous_max.is_none_or(|p| next < p) {
            let svc2 = svc.clone();
            let wid = world_id.to_string();
            let budget = if ctx.defer.is_some() { Some(DEFERRED_BLOB_DELETE_BUDGET_MS) } else { None };
            ctx.run_after_response(async move {
                if let Err(e) = apply_snapshot_retention(&svc2, &wid, crate::time::now(), budget).await {
                    tracing::warn!(world_id = %wid, cause = %e, "SharedWorld retention after maxBackups change failed");
                }
            })
            .await;
        }
    }
    let updated = require_world_details(svc, world_id, &ctx.player_uuid).await?;
    Ok(hydrate_world_details(svc, updated, ctx))
}

/// Host-reported gamerule/difficulty/game-mode persistence (runtime-authorized).
pub async fn report_host_game_rules(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &HostGameRulesReportRequest,
    now: Instant,
) -> HttpResult<HostGameRulesReportResponse> {
    require_host_authority(
        svc,
        ctx,
        world_id,
        request.runtime_epoch,
        request.host_token.as_deref(),
        &[WorldRuntimePhase::HostLive, WorldRuntimePhase::HostFinalizing],
        now,
    )
    .await?;
    let gamerules = validate_game_rules(request.gamerules.as_ref())?;
    let difficulty = validate_optional_difficulty(request.difficulty.as_ref())?;
    let default_game_mode = validate_optional_game_mode(request.default_game_mode.as_ref())?;
    for _attempt in 0..3 {
        let stored =
            svc.repository.get_world_settings(world_id).await?.ok_or_else(|| {
                HttpError::new(404, "world_not_found", "This Shared World no longer exists.")
            })?;
        let mut merged = stored.settings.clone().unwrap_or_default();
        let mut merged_rules = merged.gamerules.clone().unwrap_or_default();
        merged_rules.extend(gamerules.iter().map(|(k, v)| (*k, *v)));
        merged.gamerules = Some(merged_rules);
        if let Some(d) = difficulty {
            merged.difficulty = Some(d);
        }
        if let Some(g) = default_game_mode {
            merged.default_game_mode = Some(g);
        }
        if svc
            .repository
            .update_world_settings_if_revision(
                world_id,
                &serde_json::to_string(&merged).expect("json"),
                stored.settings_revision,
            )
            .await?
        {
            publish_world_event(svc, world_id, RealtimeEventKind::SettingsChanged).await?;
            return Ok(HostGameRulesReportResponse {
                settings: merged,
                settings_revision: stored.settings_revision + 1,
            });
        }
    }
    Err(HttpError::new(
        409,
        "settings_conflict",
        "World settings changed while saving the game rule update. Please try again.",
    ))
}

pub async fn delete_world(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    now: Instant,
) -> HttpResult<()> {
    require_world_details(svc, world_id, &ctx.player_uuid).await?;
    let binding = require_world_storage_binding(svc, world_id).await?;
    let recipients: Vec<String> = svc
        .repository
        .list_memberships(world_id)
        .await?
        .into_iter()
        .filter(|m| m.deleted_at.is_none())
        .map(|m| m.player_uuid)
        .collect();
    let result = svc.repository.delete_world_for_player(&ctx.actor(), world_id, now).await?;
    if result.world_deleted {
        purge_world_snapshots(svc, &binding, world_id).await?;
        maybe_delete_unreferenced_blob(svc, &binding, result.deleted_custom_icon_storage_key.as_deref())
            .await?;
        // P5: the coordinator drops every runtime trace and pushes world-deleted.
        svc.realtime
            .registry
            .call(world_id, move |c| Box::pin(async move { c.destroy_world(recipients).await }))
            .await?;
    }
    Ok(())
}

pub async fn get_storage_usage(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
) -> HttpResult<StorageUsageSummary> {
    require_membership(svc, ctx, world_id).await?;
    let mut usage = svc.repository.get_storage_usage(world_id).await?;
    let binding = require_world_storage_binding(svc, world_id).await?;
    let quota = cached_quota(svc, &binding).await?;
    usage.quota_used_bytes = quota.used_bytes;
    usage.quota_total_bytes = quota.total_bytes;
    // Owner-only, like the summary field (this endpoint is membership-gated).
    if svc.repository.get_world_owner_uuid(world_id).await?.as_deref() != Some(&ctx.player_uuid) {
        usage.account_email = None;
    }
    Ok(usage)
}

async fn legacy_cached_storage_usage(
    svc: &ServiceContext,
    world: &WorldDetails,
) -> HttpResult<StorageUsageSummary> {
    let cache = &svc.storage_usage_cache;
    let latest = world.summary.last_snapshot_id.as_deref();
    let used = match cache.get_used_bytes(&world.summary.id, latest).await {
        Some(u) => u,
        None => {
            let u = svc.repository.get_storage_usage(&world.summary.id).await?.used_bytes;
            cache.put_used_bytes(&world.summary.id, latest, u).await;
            u
        }
    };
    let binding = require_world_storage_binding(svc, &world.summary.id).await?;
    let quota = cached_quota(svc, &binding).await?;
    Ok(StorageUsageSummary {
        provider: world.summary.storage_provider,
        linked: world.summary.storage_linked,
        used_bytes: used,
        quota_used_bytes: quota.used_bytes,
        quota_total_bytes: quota.total_bytes,
        account_email: world.summary.storage_account_email.clone(),
    })
}

/// Account quota behind a 15-min cache: one Drive `/about` per TTL, not per poll.
pub async fn cached_quota(svc: &ServiceContext, binding: &StorageBinding) -> HttpResult<StorageQuota> {
    if let Some(account) = &binding.storage_account_id {
        if let Some(q) = svc.storage_usage_cache.get_quota(account).await {
            return Ok(q);
        }
    }
    let fresh = svc.storage_provider.quota(binding).await?;
    if let Some(account) = &binding.storage_account_id {
        svc.storage_usage_cache.put_quota(account, fresh).await;
    }
    Ok(fresh)
}

pub fn hydrate_world_summary(
    svc: &ServiceContext,
    mut world: WorldSummary,
    viewer: &RequestContext,
) -> WorldSummary {
    // The owner's Google address is for the owner alone; members see the
    // world, not whose Drive it lives on.
    if world.owner_uuid != viewer.player_uuid {
        world.storage_account_email = None;
    }
    if let Some(key) = world.custom_icon_storage_key.clone() {
        world.custom_icon_download = Some(sign_download_for_world(
            svc,
            &world.id,
            &key,
            &viewer.player_uuid,
            viewer.request_origin.as_deref(),
        ));
    }
    world
}

pub fn hydrate_world_details(
    svc: &ServiceContext,
    mut world: WorldDetails,
    viewer: &RequestContext,
) -> WorldDetails {
    world.summary = hydrate_world_summary(svc, world.summary, viewer);
    world
}

/// A brand-new world starts with a host-starting runtime owned by its creator.
async fn create_seeded_world_result(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world: WorldDetails,
    now: Instant,
) -> HttpResult<CreateWorldResult> {
    let actor = session_actor_of(svc, ctx, &world.summary.id).await?;
    let wid = world.summary.id.clone();
    let decision = svc
        .realtime
        .registry
        .call(&wid, move |c| Box::pin(async move { c.enter_session(&actor, None, false, now).await }))
        .await;
    let decision = match decision {
        Ok(d) => d,
        Err(e) => {
            // P8: a failed create must leave nothing behind.
            svc.repository.delete_world_for_player(&ctx.actor(), &wid, now).await?;
            return Err(e);
        }
    };
    match (decision.action, decision.assignment) {
        (EnterSessionAction::Host, Some(assignment)) => Ok(CreateWorldResult {
            world: hydrate_world_details(svc, world, ctx),
            initial_upload_assignment: assignment,
        }),
        _ => {
            svc.repository.delete_world_for_player(&ctx.actor(), &wid, now).await?;
            Err(HttpError::new(409, "world_busy", "SharedWorld is already being set up."))
        }
    }
}

async fn store_custom_icon(
    svc: &ServiceContext,
    binding: &StorageBinding,
    icon_base64: &str,
) -> HttpResult<String> {
    let invalid = || HttpError::new(400, "invalid_custom_icon", "Custom icon must be a 64x64 PNG.");
    let bytes = base64::engine::general_purpose::STANDARD.decode(icon_base64).map_err(|_| invalid())?;
    if !is_png(&bytes) || png_dim(&bytes, 16) != 64 || png_dim(&bytes, 20) != 64 {
        return Err(invalid());
    }
    let hash = hex(&Sha256::digest(&bytes));
    let storage_key = format!("icons/{}/{}.png", &hash[..2], hash);
    if !svc.storage_provider.exists(binding, &storage_key).await? {
        svc.storage_provider.put(binding, &storage_key, PutBody::Bytes(bytes.into()), "image/png").await?;
    }
    Ok(storage_key)
}

async fn resolve_linked_storage_binding(
    svc: &ServiceContext,
    ctx: &RequestContext,
) -> HttpResult<StorageBinding> {
    let accounts = svc
        .repository
        .find_storage_accounts_by_owner(svc.storage_provider.provider(), &ctx.player_uuid)
        .await?;
    let account = accounts.into_iter().find(|a| a.refresh_token.is_some()).ok_or_else(|| {
        HttpError::new(
            409,
            "storage_not_linked",
            "Google Drive isn't connected yet. Connect it and try again.",
        )
    })?;
    Ok(WorldStorageBinding { provider: account.provider, storage_account_id: Some(account.id) })
}

fn settings_error(msg: impl Into<String>) -> HttpError {
    HttpError::new(400, "invalid_world_settings", msg)
}

/// Whitelist validation: reject unknown fields/values instead of storing them.
pub fn validate_world_settings(raw: &serde_json::Value) -> HttpResult<WorldSettings> {
    let obj = raw.as_object().ok_or_else(|| settings_error("World settings are missing or malformed."))?;
    let mut settings = WorldSettings::default();
    for key in obj.keys() {
        if !matches!(key.as_str(), "difficulty" | "defaultGameMode" | "gamerules" | "maxBackups") {
            return Err(settings_error(format!("Unknown world setting \"{key}\".")));
        }
    }
    if let Some(v) = obj.get("difficulty") {
        settings.difficulty = Some(
            serde_json::from_value(v.clone())
                .map_err(|_| settings_error("That difficulty isn't one of the supported values."))?,
        );
    }
    if let Some(v) = obj.get("defaultGameMode") {
        settings.default_game_mode = Some(
            serde_json::from_value(v.clone())
                .map_err(|_| settings_error("That game mode isn't one of the supported values."))?,
        );
    }
    if let Some(v) = obj.get("gamerules") {
        settings.gamerules = Some(validate_game_rules(Some(v))?);
    }
    if let Some(v) = obj.get("maxBackups") {
        // 0.4.5: floor 1 = keep only the current snapshot.
        if v.is_null() {
            settings.max_backups = Some(None);
        } else {
            let n = v
                .as_i64()
                .filter(|n| (1..=1000).contains(n))
                .filter(|_| v.as_f64().is_some_and(|f| f.fract() == 0.0));
            let Some(n) = n else {
                return Err(settings_error("maxBackups must be null or an integer between 1 and 1000."));
            };
            settings.max_backups = Some(Some(n));
        }
    }
    Ok(settings)
}

fn validate_optional_difficulty(raw: Option<&serde_json::Value>) -> HttpResult<Option<WorldDifficulty>> {
    match raw {
        None | Some(serde_json::Value::Null) => Ok(None),
        Some(v) => serde_json::from_value(v.clone())
            .map(Some)
            .map_err(|_| settings_error("That difficulty isn't one of the supported values.")),
    }
}

fn validate_optional_game_mode(raw: Option<&serde_json::Value>) -> HttpResult<Option<WorldDefaultGameMode>> {
    match raw {
        None | Some(serde_json::Value::Null) => Ok(None),
        Some(v) => serde_json::from_value(v.clone())
            .map(Some)
            .map_err(|_| settings_error("That game mode isn't one of the supported values.")),
    }
}

fn validate_game_rules(raw: Option<&serde_json::Value>) -> HttpResult<BTreeMap<WorldGameRule, bool>> {
    let obj = raw
        .and_then(|v| v.as_object())
        .ok_or_else(|| settings_error("World settings are missing or malformed."))?;
    let mut out = BTreeMap::new();
    for (rule, value) in obj {
        match (WorldGameRule::parse(rule), value.as_bool()) {
            (Some(r), Some(b)) => {
                out.insert(r, b);
            }
            _ => return Err(settings_error(format!("Unknown game rule \"{rule}\"."))),
        }
    }
    Ok(out)
}

const MAX_WORLD_NAME_LENGTH: usize = 128;

fn require_valid_world_name(raw: Option<&serde_json::Value>) -> HttpResult<String> {
    let name = raw.and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
    let len = name.chars().count();
    if len < 3 {
        return Err(HttpError::new(400, "invalid_world_name", "World name must be at least 3 characters."));
    }
    if len > MAX_WORLD_NAME_LENGTH {
        return Err(HttpError::new(
            400,
            "invalid_world_name",
            format!("World name must be at most {MAX_WORLD_NAME_LENGTH} characters."),
        ));
    }
    Ok(name)
}

fn normalize_motd(line1: Option<&str>, line2: Option<&str>) -> HttpResult<Option<String>> {
    let lines: Vec<String> = [line1.unwrap_or(""), line2.unwrap_or("")]
        .iter()
        .flat_map(|l| l.replace('\r', "").split('\n').map(|s| s.trim_end().to_string()).collect::<Vec<_>>())
        .filter(|l| !l.is_empty())
        .collect();
    if lines.len() > 2 {
        return Err(HttpError::new(400, "invalid_motd", "Shared World MOTD can use at most 2 lines."));
    }
    Ok(if lines.is_empty() { None } else { Some(lines.join("\n")) })
}

fn split_motd(motd: Option<&str>) -> (Option<String>, Option<String>) {
    let Some(m) = motd.filter(|m| !m.is_empty()) else { return (None, None) };
    let mut lines = m.split('\n');
    (lines.next().map(|s| s.to_string()), lines.next().map(|s| s.to_string()))
}

fn is_png(bytes: &[u8]) -> bool {
    bytes.starts_with(&[0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
}

fn png_dim(bytes: &[u8], offset: usize) -> u32 {
    if bytes.len() < 24 {
        return 0;
    }
    u32::from_be_bytes([bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]])
}
