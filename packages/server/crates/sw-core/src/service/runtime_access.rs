//! Shared authority guards (`service/runtime-access.ts`).

use sw_contracts::{RealtimeEvent, RealtimeEventKind, WorldDetails, WorldRuntimePhase};
use sw_db::repo::WorldStorageBinding;

use super::ServiceContext;
use crate::http_error::{HttpError, HttpResult};
use crate::realtime::SessionActor;
use crate::request::RequestContext;
use crate::time::Instant;

pub fn world_not_found_error() -> HttpError {
    HttpError::new(404, "world_not_found", "SharedWorld server not found.")
}

/// Membership facts for a session call; a missing/deleted world is the 404.
pub async fn session_actor_of(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
) -> HttpResult<SessionActor> {
    let facts = svc
        .repository
        .session_actor_facts(world_id, &ctx.player_uuid)
        .await?
        .ok_or_else(world_not_found_error)?;
    Ok(SessionActor {
        player_uuid: ctx.player_uuid.clone(),
        player_name: ctx.player_name.clone(),
        membership_active: facts.membership_active,
        ever_member: facts.ever_member,
    })
}

/// Active member or a labeled 403.
pub async fn require_active_membership(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
) -> HttpResult<()> {
    let facts = session_actor_of(svc, ctx, world_id).await?;
    if facts.membership_active {
        return Ok(());
    }
    if facts.ever_member {
        return Err(HttpError::new(403, "membership_revoked", "You were removed from this SharedWorld."));
    }
    Err(HttpError::new(403, "forbidden", "You do not have access to this SharedWorld server."))
}

/// Session access honoring the revoked-host exception (I7).
pub async fn require_session_access_allowing_revoked_host(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
) -> HttpResult<()> {
    let facts = session_actor_of(svc, ctx, world_id).await?;
    svc.realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.assert_session_access(&facts, true) }))
        .await
}

/// Host authority for HTTP write paths (uploads, finalize, gamerule reports).
pub async fn require_host_authority(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    runtime_epoch: Option<i64>,
    host_token: Option<&str>,
    allowed_phases: &'static [WorldRuntimePhase],
    now: Instant,
) -> HttpResult<()> {
    let facts = session_actor_of(svc, ctx, world_id).await?;
    let token = host_token.map(|s| s.to_string());
    svc.realtime
        .registry
        .call(world_id, move |c| {
            Box::pin(async move {
                c.validate_host_authority(&facts, runtime_epoch, token.as_deref(), allowed_phases, now).await
            })
        })
        .await
}

/// Fan one change event out to the world's active members' gateways.
pub async fn publish_world_event(
    svc: &ServiceContext,
    world_id: &str,
    kind: RealtimeEventKind,
) -> HttpResult<()> {
    let members = svc.repository.list_memberships(world_id).await?;
    let recipients: Vec<String> =
        members.into_iter().filter(|m| m.deleted_at.is_none()).map(|m| m.player_uuid).collect();
    svc.realtime.notify_users(
        RealtimeEvent { world_id: world_id.to_string(), kind, runtime: None, room_players: None },
        &recipients,
    );
    Ok(())
}

pub async fn require_membership(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
) -> HttpResult<()> {
    let facts = svc
        .repository
        .session_actor_facts(world_id, &ctx.player_uuid)
        .await?
        .ok_or_else(world_not_found_error)?;
    if !facts.membership_active {
        return Err(HttpError::new(403, "forbidden", "You do not have access to this SharedWorld server."));
    }
    Ok(())
}

pub async fn require_world_details(
    svc: &ServiceContext,
    world_id: &str,
    player_uuid: &str,
) -> HttpResult<WorldDetails> {
    svc.repository.get_world_details(world_id, player_uuid).await?.ok_or_else(world_not_found_error)
}

pub fn require_owner(world: &WorldDetails, ctx: &RequestContext, action: &str) -> HttpResult<()> {
    if world.summary.owner_uuid != ctx.player_uuid {
        return Err(HttpError::new(403, "forbidden", format!("Only the SharedWorld owner can {action}.")));
    }
    Ok(())
}

pub async fn require_world_storage_binding(
    svc: &ServiceContext,
    world_id: &str,
) -> HttpResult<WorldStorageBinding> {
    svc.repository.get_world_storage_binding(world_id).await?.ok_or_else(world_not_found_error)
}
