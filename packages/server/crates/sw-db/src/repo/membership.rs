//! `MembershipRepository`: invites and memberships.

use rusqlite::{params, Row};
use sw_contracts::{InviteCode, InviteStatus, KickMemberResponse, MembershipRole, WorldMembership};

use super::Repository;
use crate::error::DbError;
use crate::time;

pub(crate) fn map_invite(r: &Row<'_>) -> rusqlite::Result<InviteCode> {
    let status: String = r.get("status")?;
    Ok(InviteCode {
        id: r.get("id")?,
        world_id: r.get("world_id")?,
        code: r.get("code")?,
        created_by_uuid: r.get("created_by_uuid")?,
        created_at: r.get("created_at")?,
        expires_at: r.get("expires_at")?,
        status: match status.as_str() {
            "active" => InviteStatus::Active,
            "expired" => InviteStatus::Expired,
            "revoked" => InviteStatus::Revoked,
            _ => InviteStatus::Redeemed,
        },
    })
}

pub(crate) fn map_membership(r: &Row<'_>) -> rusqlite::Result<WorldMembership> {
    let role: String = r.get("role")?;
    Ok(WorldMembership {
        world_id: r.get("world_id")?,
        player_uuid: r.get("player_uuid")?,
        player_name: r.get("player_name")?,
        role: if role == "owner" { MembershipRole::Owner } else { MembershipRole::Member },
        joined_at: r.get("joined_at")?,
        deleted_at: r.get("deleted_at")?,
        can_use_commands: r.get::<_, i64>("can_use_commands")? != 0,
    })
}

const INVITE_COLUMNS: &str =
    "id, world_id, code, created_by_uuid, created_at, expires_at, redeemed_by_uuid, redeemed_at, status";

impl Repository {
    pub async fn create_invite(&self, world_id: &str, invite: InviteCode) -> Result<InviteCode, DbError> {
        let world_id = world_id.to_string();
        let out = invite.clone();
        self.db
            .write(move |c| {
                // Physical expiry of stale rows happens here, on the write path.
                c.execute(
                    "invite_codes.expire_stale",
                    "UPDATE invite_codes SET status = 'expired' WHERE world_id = ? AND status = 'active' AND expires_at < ?",
                    params![world_id, invite.created_at],
                )?;
                c.execute(
                    "invite_codes.insert",
                    "INSERT INTO invite_codes (id, world_id, code, created_by_uuid, created_at, expires_at, status)
                     VALUES (?, ?, ?, ?, ?, ?, ?)",
                    params![
                        invite.id,
                        invite.world_id,
                        invite.code,
                        invite.created_by_uuid,
                        invite.created_at,
                        invite.expires_at,
                        invite.status.as_str()
                    ],
                )?;
                Ok(())
            })
            .await?;
        Ok(out)
    }

    pub async fn get_invite_by_code(&self, code: &str) -> Result<Option<InviteCode>, DbError> {
        let code = code.to_string();
        self.db
            .read(move |c| {
                c.query_one(
                    "invite_codes.by_code",
                    &format!("SELECT {INVITE_COLUMNS} FROM invite_codes WHERE code = ?"),
                    params![code],
                    map_invite,
                )
            })
            .await
    }

    pub async fn revoke_active_invites(&self, world_id: &str) -> Result<Vec<String>, DbError> {
        let world_id = world_id.to_string();
        self.db
            .write(move |c| {
                let ids = c.query(
                    "invite_codes.active_ids",
                    "SELECT id FROM invite_codes WHERE world_id = ? AND status = 'active'",
                    params![world_id],
                    |r| r.get::<_, String>(0),
                )?;
                c.execute(
                    "invite_codes.revoke_active",
                    "UPDATE invite_codes SET status = 'revoked' WHERE world_id = ? AND status = 'active'",
                    params![world_id],
                )?;
                Ok(ids)
            })
            .await
    }

    /// Self-healing guard for concurrent invite resets: only the newest
    /// active code survives.
    pub async fn revoke_superseded_invites(&self, world_id: &str) -> Result<(), DbError> {
        let world_id = world_id.to_string();
        self.db
            .write(move |c| {
                c.execute(
                    "invite_codes.revoke_superseded",
                    "UPDATE invite_codes SET status = 'revoked'
                     WHERE world_id = ? AND status = 'active'
                       AND id != (
                         SELECT id FROM invite_codes
                         WHERE world_id = ? AND status = 'active'
                         ORDER BY created_at DESC, id DESC
                         LIMIT 1
                       )",
                    params![world_id, world_id],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn get_active_invite(
        &self,
        world_id: &str,
        now: time::Instant,
    ) -> Result<Option<InviteCode>, DbError> {
        let world_id = world_id.to_string();
        let now_iso = time::to_iso(now);
        self.db
            .read(move |c| {
                c.query_one(
                    "invite_codes.active",
                    &format!(
                        "SELECT {INVITE_COLUMNS} FROM invite_codes
                         WHERE world_id = ? AND status = 'active' AND expires_at >= ?
                         ORDER BY created_at DESC, id DESC
                         LIMIT 1"
                    ),
                    params![world_id, now_iso],
                    map_invite,
                )
            })
            .await
    }

    pub async fn add_membership(&self, membership: WorldMembership) -> Result<(), DbError> {
        self.db
            .write(move |c| {
                c.execute(
                    "world_memberships.upsert",
                    "INSERT INTO world_memberships (world_id, player_uuid, player_name, role, joined_at, deleted_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT(world_id, player_uuid) DO UPDATE SET
                       player_name = excluded.player_name,
                       deleted_at = NULL,
                       can_use_commands = 0",
                    params![
                        membership.world_id,
                        membership.player_uuid,
                        membership.player_name,
                        membership.role.as_str(),
                        membership.joined_at,
                        membership.deleted_at
                    ],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn is_world_member(&self, world_id: &str, player_uuid: &str) -> Result<bool, DbError> {
        let (w, p) = (world_id.to_string(), player_uuid.to_string());
        Ok(self
            .db
            .read(move |c| {
                c.query_one(
                    "world_memberships.is_member",
                    "SELECT 1 AS present FROM world_memberships WHERE world_id = ? AND player_uuid = ? AND deleted_at IS NULL",
                    params![w, p],
                    |_| Ok(()),
                )
            })
            .await?
            .is_some())
    }

    pub async fn has_world_membership(&self, world_id: &str, player_uuid: &str) -> Result<bool, DbError> {
        let (w, p) = (world_id.to_string(), player_uuid.to_string());
        Ok(self
            .db
            .read(move |c| {
                c.query_one(
                    "world_memberships.has",
                    "SELECT 1 AS present FROM world_memberships WHERE world_id = ? AND player_uuid = ?",
                    params![w, p],
                    |_| Ok(()),
                )
            })
            .await?
            .is_some())
    }

    pub async fn kick_member(
        &self,
        world_id: &str,
        removed_player_uuid: &str,
        removed_at: &str,
    ) -> Result<Option<KickMemberResponse>, DbError> {
        let (w, p, at) = (world_id.to_string(), removed_player_uuid.to_string(), removed_at.to_string());
        self.db
            .write(move |c| {
                let present = c.query_one(
                    "world_memberships.is_member",
                    "SELECT player_uuid FROM world_memberships WHERE world_id = ? AND player_uuid = ? AND deleted_at IS NULL",
                    params![w, p],
                    |_| Ok(()),
                )?;
                if present.is_none() {
                    return Ok(None);
                }
                c.execute(
                    "world_memberships.kick",
                    "UPDATE world_memberships SET deleted_at = ? WHERE world_id = ? AND player_uuid = ? AND deleted_at IS NULL",
                    params![at, w, p],
                )?;
                Ok(Some(KickMemberResponse { world_id: w, removed_player_uuid: p }))
            })
            .await
    }

    pub async fn list_memberships(&self, world_id: &str) -> Result<Vec<WorldMembership>, DbError> {
        let w = world_id.to_string();
        self.db.read(move |c| list_memberships_in(c, &w)).await
    }

    pub async fn set_membership_command_permission(
        &self,
        world_id: &str,
        player_uuid: &str,
        can_use_commands: bool,
    ) -> Result<bool, DbError> {
        let (w, p) = (world_id.to_string(), player_uuid.to_string());
        self.db
            .write(move |c| {
                Ok(c.execute(
                    "world_memberships.set_commands",
                    "UPDATE world_memberships SET can_use_commands = ? WHERE world_id = ? AND player_uuid = ? AND deleted_at IS NULL",
                    params![i64::from(can_use_commands), w, p],
                )? > 0)
            })
            .await
    }
}

pub(crate) fn list_memberships_in(
    c: &crate::pool::Conn<'_>,
    world_id: &str,
) -> Result<Vec<WorldMembership>, DbError> {
    c.query(
        "world_memberships.list",
        "SELECT world_id, player_uuid, player_name, role, joined_at, deleted_at, can_use_commands
         FROM world_memberships
         WHERE world_id = ? AND deleted_at IS NULL
         ORDER BY joined_at ASC",
        params![world_id],
        map_membership,
    )
}
