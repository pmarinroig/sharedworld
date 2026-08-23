//! Session/runtime shell routes (`service/session.ts`): membership facts
//! from SQL, coordinator decisions, response composition, pacing levers.

use sw_contracts::*;

use super::runtime_access::*;
use super::worlds::get_world;
use super::ServiceContext;
use crate::http_error::{HttpError, HttpResult};
use crate::realtime::*;
use crate::request::RequestContext;
use crate::time::{self, Instant};

pub async fn enter_session(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &EnterSessionRequest,
    now: Instant,
) -> HttpResult<EnterSessionResponse> {
    let actor = session_actor_of(svc, ctx, world_id).await?;
    // Access verdicts first; without touching coordinator state.
    if !actor.membership_active {
        let a = actor.clone();
        svc.realtime
            .registry
            .call(world_id, move |c| Box::pin(async move { c.assert_session_access(&a, false) }))
            .await?;
    }
    let world = get_world(svc, ctx, world_id, now).await?;
    // 0.3.2+ clients never read the manifest body here.
    let latest_manifest = if ctx.client_at_least(0, 3, 2) {
        None
    } else {
        svc.repository.get_latest_snapshot(world_id).await?.map(|m| (*m).clone())
    };
    let waiter = request.waiter_session_id.clone();
    let ack = request.acknowledge_unclean_shutdown == Some(true);
    let decision = svc
        .realtime
        .registry
        .call(world_id, move |c| {
            Box::pin(async move { c.enter_session(&actor, waiter.as_deref(), ack, now).await })
        })
        .await?;
    Ok(EnterSessionResponse {
        action: decision.action,
        world: world.summary,
        latest_manifest,
        runtime: decision.runtime,
        assignment: decision.assignment,
        waiter_session_id: decision.waiter_session_id,
    })
}

fn waiter_id_of(v: Option<&serde_json::Value>) -> Option<String> {
    v.and_then(|v| v.as_str()).map(|s| s.to_string())
}

pub async fn observe_waiting(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &ObserveWaitingRequest,
    now: Instant,
) -> HttpResult<ObserveWaitingResponse> {
    let actor = session_actor_of(svc, ctx, world_id).await?;
    let waiter = waiter_id_of(request.waiter_session_id.as_ref());
    let observation = svc
        .realtime
        .registry
        .call(world_id, move |c| {
            Box::pin(async move { c.observe_waiting(&actor, waiter.as_deref(), now).await })
        })
        .await?;
    Ok(ObserveWaitingResponse {
        action: observation.action,
        runtime: observation.runtime,
        assignment: None,
        waiter_session_id: observation.waiter_session_id,
    })
}

pub async fn runtime_status(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    now: Instant,
) -> HttpResult<WorldRuntimeStatus> {
    let actor = session_actor_of(svc, ctx, world_id).await?;
    let mut status = svc
        .realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.runtime_status(&actor, now).await }))
        .await?;
    if let Some(ms) = svc.config.suggested_runtime_poll_interval_ms.filter(|v| *v > 0) {
        status.suggested_poll_interval_ms = Some(ms);
    }
    Ok(status)
}

pub async fn cancel_waiting(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &CancelWaitingRequest,
    now: Instant,
) -> HttpResult<WorldRuntimeStatus> {
    let actor = session_actor_of(svc, ctx, world_id).await?;
    let waiter = waiter_id_of(request.waiter_session_id.as_ref()).unwrap_or_default();
    svc.realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.cancel_waiting(&actor, &waiter, now).await }))
        .await
}

pub async fn heartbeat_host(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &HeartbeatRequest,
    now: Instant,
) -> HttpResult<HostHeartbeatResponse> {
    let actor = session_actor_of(svc, ctx, world_id).await?;
    let args = HeartbeatArgs {
        runtime_epoch: request.runtime_epoch,
        host_token: request.host_token.clone(),
        join_target: request.join_target.clone(),
        minecraft_version: request.minecraft_version.clone(),
    };
    let status = svc
        .realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.heartbeat(&actor, &args, now).await }))
        .await?;
    with_heartbeat_memberships(svc, world_id, status).await
}

/// FLAT superset of `WorldRuntimeStatus` with memberships + settings + pacing.
async fn with_heartbeat_memberships(
    svc: &ServiceContext,
    world_id: &str,
    status: WorldRuntimeStatus,
) -> HttpResult<HostHeartbeatResponse> {
    let memberships: Vec<HostHeartbeatMembership> = svc
        .repository
        .list_memberships(world_id)
        .await?
        .into_iter()
        .map(|m| HostHeartbeatMembership {
            player_uuid: m.player_uuid,
            player_name: m.player_name,
            can_use_commands: m.can_use_commands,
        })
        .collect();
    let world_settings = svc.repository.get_world_settings(world_id).await?;
    Ok(HostHeartbeatResponse {
        runtime: status,
        memberships,
        settings: world_settings.as_ref().and_then(|s| s.settings.clone()),
        settings_revision: world_settings.as_ref().map(|s| s.settings_revision).unwrap_or(0),
        suggested_heartbeat_interval_ms: svc.config.suggested_host_heartbeat_interval_ms.filter(|v| *v > 0),
        suggested_autosave_interval_ms: svc.config.suggested_autosave_interval_ms.filter(|v| *v > 0),
    })
}

pub async fn set_host_startup_progress(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &HostStartupProgressRequest,
    now: Instant,
) -> HttpResult<WorldRuntimeStatus> {
    let actor = session_actor_of(svc, ctx, world_id).await?;
    let args = StartupProgressArgs {
        runtime_epoch: request.runtime_epoch,
        host_token: request.host_token.clone(),
        label: request.label.clone(),
        mode: request.mode,
        fraction: request.fraction,
    };
    svc.realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.set_startup_progress(&actor, &args, now).await }))
        .await
}

fn legacy_presence_args(request: &PresenceHeartbeatRequest) -> LegacyPresenceArgs {
    LegacyPresenceArgs {
        present: request.present.as_ref().and_then(|v| v.as_bool()).unwrap_or(false),
        guest_session_epoch: request.guest_session_epoch.as_ref().and_then(|v| v.as_i64()).unwrap_or(0),
        presence_sequence: request.presence_sequence.as_ref().and_then(|v| v.as_i64()).unwrap_or(0),
    }
}

/// The guest beat: FLAT superset carrying runtime status (minus updatedAt) + lastSnapshotId.
pub async fn set_player_presence(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &PresenceHeartbeatRequest,
    now: Instant,
) -> HttpResult<GuestHeartbeatResponse> {
    let actor = session_actor_of(svc, ctx, world_id).await?;
    let args = legacy_presence_args(request);
    let present = args.present;
    let status = svc
        .realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.guest_heartbeat(&actor, &args, now).await }))
        .await?;
    let latest = svc.repository.get_latest_snapshot_stamp(world_id).await?;
    Ok(GuestHeartbeatResponse {
        presence: PresenceHeartbeatResponse {
            world_id: world_id.to_string(),
            present,
            updated_at: time::to_iso(now),
            expires_at: time::plus_ms_iso(now, PLAYER_PRESENCE_TIMEOUT_MS),
            suggested_interval_ms: svc.config.suggested_presence_interval_ms.filter(|v| *v > 0),
        },
        phase: status.phase,
        runtime_epoch: status.runtime_epoch,
        host_uuid: status.host_uuid,
        host_player_name: status.host_player_name,
        candidate_uuid: status.candidate_uuid,
        candidate_player_name: status.candidate_player_name,
        join_target: status.join_target,
        startup_deadline_at: status.startup_deadline_at,
        runtime_token_issued_at: status.runtime_token_issued_at,
        last_progress_at: status.last_progress_at,
        revoked_at: status.revoked_at,
        startup_progress: status.startup_progress,
        unclean_shutdown_warning: status.unclean_shutdown_warning,
        host_minecraft_version: status.host_minecraft_version,
        last_snapshot_id: latest,
    })
}

fn authority_args(epoch: Option<i64>, token: Option<&String>) -> HostAuthorityArgs {
    HostAuthorityArgs { runtime_epoch: epoch, host_token: token.cloned() }
}

pub async fn begin_finalization(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &BeginFinalizationRequest,
    now: Instant,
) -> HttpResult<FinalizationActionResult> {
    let actor = session_actor_of(svc, ctx, world_id).await?;
    let args = authority_args(request.runtime_epoch, request.host_token.as_ref());
    svc.realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.begin_finalization(&actor, &args, now).await }))
        .await
}

pub async fn complete_finalization(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &CompleteFinalizationRequest,
    now: Instant,
) -> HttpResult<FinalizationActionResult> {
    let actor = session_actor_of(svc, ctx, world_id).await?;
    let args = authority_args(request.runtime_epoch, request.host_token.as_ref());
    svc.realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.complete_finalization(&actor, &args, now).await }))
        .await
}

/// Owner-only escape hatch; the ownership check stays here (SQL owns ownership).
pub async fn abandon_finalization(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    now: Instant,
) -> HttpResult<FinalizationActionResult> {
    let world = require_world_details(svc, world_id, &ctx.player_uuid).await?;
    require_owner(&world, ctx, "discard stranded finalization state")?;
    svc.realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.abandon_finalization(now).await }))
        .await
}

pub async fn release_host(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    request: &ReleaseHostRequest,
    now: Instant,
) -> HttpResult<ReleaseHostResult> {
    let actor = session_actor_of(svc, ctx, world_id).await?;
    let args = authority_args(request.runtime_epoch, request.host_token.as_ref());
    let graceful = request.graceful.as_ref().and_then(|v| v.as_bool()).unwrap_or(false);
    svc.realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.release_host(&actor, &args, graceful, now).await }))
        .await
}

#[allow(dead_code)]
fn _unused(_: HttpError) {}
