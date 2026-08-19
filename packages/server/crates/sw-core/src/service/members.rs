//! Membership domain (`service/members.ts`).

use sw_contracts::*;

use super::runtime_access::*;
use super::worlds::get_world;
use super::ServiceContext;
use crate::http_error::{HttpError, HttpResult};
use crate::ids::{invite_code, random_id};
use crate::request::RequestContext;
use crate::time::{self, Instant};

pub async fn create_invite(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    now: Instant,
) -> HttpResult<InviteCode> {
    let world = require_world_details(svc, world_id, &ctx.player_uuid).await?;
    require_owner(&world, ctx, "manage invite codes")?;
    svc.repository.revoke_superseded_invites(world_id).await?;
    if let Some(active) = svc.repository.get_active_invite(world_id, now).await? {
        return Ok(active);
    }
    let invite = InviteCode {
        id: random_id("invite"),
        world_id: world_id.to_string(),
        code: invite_code(),
        created_by_uuid: ctx.player_uuid.clone(),
        created_at: time::to_iso(now),
        expires_at: time::plus_ms_iso(now, INVITE_TTL_MS),
        status: InviteStatus::Active,
    };
    let created = svc.repository.create_invite(world_id, invite).await?;
    // Concurrent resets/creates can each insert a code; self-heal so exactly one stays active.
    svc.repository.revoke_superseded_invites(world_id).await?;
    Ok(svc.repository.get_active_invite(world_id, now).await?.unwrap_or(created))
}

pub async fn redeem_invite(
    svc: &ServiceContext,
    ctx: &RequestContext,
    request: &RedeemInviteRequest,
    now: Instant,
) -> HttpResult<WorldDetails> {
    let code = request.code.as_ref().and_then(|c| c.as_str()).unwrap_or("").trim().to_uppercase();
    let invite = svc
        .repository
        .get_invite_by_code(&code)
        .await?
        .ok_or_else(|| HttpError::new(404, "invite_not_found", "Invite code not found."))?;
    if invite.status != InviteStatus::Active {
        return Err(HttpError::new(409, "invite_inactive", "Invite code is no longer active."));
    }
    if time::parse_iso(&invite.expires_at).is_none_or(|t| t < now) {
        return Err(HttpError::new(410, "invite_expired", "Invite code has expired."));
    }
    if !svc.repository.has_active_world(&invite.world_id).await? {
        return Err(world_not_found_error());
    }
    svc.repository
        .add_membership(WorldMembership {
            world_id: invite.world_id.clone(),
            player_uuid: ctx.player_uuid.clone(),
            player_name: ctx.player_name.clone(),
            role: MembershipRole::Member,
            joined_at: time::to_iso(now),
            deleted_at: None,
            can_use_commands: false,
        })
        .await?;
    publish_world_event(svc, &invite.world_id, RealtimeEventKind::MembershipChanged).await?;
    poke_memberships_changed(svc, &invite.world_id, now).await;
    get_world(svc, ctx, &invite.world_id, now).await
}

/// Best-effort coordinator poke after a membership write.
async fn poke_memberships_changed(svc: &ServiceContext, world_id: &str, now: Instant) {
    if let Err(e) = svc
        .realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.memberships_changed(now).await }))
        .await
    {
        tracing::warn!(world_id, error = %e, "SharedWorld membershipsChanged poke failed");
    }
}

pub async fn set_member_command_permission(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    target_player_uuid: &str,
    can_use_commands: bool,
) -> HttpResult<WorldMembership> {
    let world = require_world_details(svc, world_id, &ctx.player_uuid).await?;
    require_owner(&world, ctx, "change member command permissions")?;
    if target_player_uuid == world.summary.owner_uuid {
        return Err(HttpError::new(
            400,
            "cannot_modify_owner",
            "The SharedWorld owner always has full command permissions.",
        ));
    }
    if !svc
        .repository
        .set_membership_command_permission(world_id, target_player_uuid, can_use_commands)
        .await?
    {
        return Err(HttpError::new(404, "member_not_found", "SharedWorld member not found."));
    }
    let membership = svc
        .repository
        .list_memberships(world_id)
        .await?
        .into_iter()
        .find(|m| m.player_uuid == target_player_uuid)
        .ok_or_else(|| HttpError::new(404, "member_not_found", "SharedWorld member not found."))?;
    publish_world_event(svc, world_id, RealtimeEventKind::MembershipChanged).await?;
    poke_memberships_changed(svc, world_id, time::now()).await;
    Ok(membership)
}

pub async fn reset_invite(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    now: Instant,
) -> HttpResult<ResetInviteResponse> {
    let world = require_world_details(svc, world_id, &ctx.player_uuid).await?;
    require_owner(&world, ctx, "reset invite codes")?;
    let revoked_invite_ids = svc.repository.revoke_active_invites(world_id).await?;
    let invite = create_invite(svc, ctx, world_id, now).await?;
    Ok(ResetInviteResponse { revoked_invite_ids, invite })
}

pub async fn kick_member(
    svc: &ServiceContext,
    ctx: &RequestContext,
    world_id: &str,
    removed_player_uuid: &str,
    now: Instant,
) -> HttpResult<KickMemberResponse> {
    let world = require_world_details(svc, world_id, &ctx.player_uuid).await?;
    require_owner(&world, ctx, "remove members")?;
    if removed_player_uuid == world.summary.owner_uuid {
        return Err(HttpError::new(400, "cannot_remove_owner", "The SharedWorld owner cannot be removed."));
    }
    let result = svc
        .repository
        .kick_member(world_id, removed_player_uuid, &time::to_iso(now))
        .await?
        .ok_or_else(|| HttpError::new(404, "member_not_found", "SharedWorld member not found."))?;
    // Kicking rotates the share code.
    svc.repository.revoke_active_invites(world_id).await?;
    create_invite(svc, ctx, world_id, now).await?;
    // P6: a kicked host's runtime is marked revoked.
    let removed = removed_player_uuid.to_string();
    svc.realtime
        .registry
        .call(world_id, move |c| Box::pin(async move { c.member_revoked(&removed, now).await }))
        .await?;
    publish_world_event(svc, world_id, RealtimeEventKind::MembershipChanged).await?;
    // The kicked player misses the member fan-out — and needs the push most.
    svc.realtime.notify_users(
        RealtimeEvent {
            world_id: world_id.to_string(),
            kind: RealtimeEventKind::MembershipChanged,
            runtime: None,
            room_players: None,
        },
        &[removed_player_uuid.to_string()],
    );
    Ok(result)
}
