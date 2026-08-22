//! `WorldRepository` + `RuntimeRepository`: worlds, settings, change facts,
//! storage usage, runtime mirror.

use std::collections::HashMap;

use rusqlite::params;
use serde_json::{json, Value};
use sw_contracts::{StorageProviderType, StorageUsageSummary, WorldDetails, WorldSummary};

use super::membership::list_memberships_in;
use super::records::*;
use super::storage::get_storage_account_in;
use super::summaries::{latest_snapshot_id_subquery, parse_world_settings};
use super::{join_motd_lines, json_list, new_id, Repository, IN_JSON_LIST};
use crate::error::DbError;
use crate::pool::Conn;
use crate::time;

impl Repository {
    pub async fn list_worlds_for_player(&self, player_uuid: &str) -> Result<Vec<WorldSummary>, DbError> {
        let p = player_uuid.to_string();
        self.db
            .read(move |c| {
                let ids = c.query(
                    "worlds.member_ids",
                    "SELECT w.id FROM worlds w
                     JOIN world_memberships wm ON wm.world_id = w.id
                     WHERE wm.player_uuid = ? AND wm.deleted_at IS NULL AND w.deleted_at IS NULL
                     ORDER BY w.name ASC",
                    params![p],
                    |r| r.get::<_, String>(0),
                )?;
                let mut summaries = Self::build_world_summaries_in(c, &ids, None)?;
                Ok(ids.iter().filter_map(|id| summaries.remove(id)).collect::<Vec<_>>())
            })
            .await
            .map(|worlds: Vec<WorldSummary>| {
                worlds
                    .into_iter()
                    .map(|mut w| {
                        w.storage_account_email = self.decrypt_email(w.storage_account_email.take());
                        w
                    })
                    .collect()
            })
    }

    /// Active worlds whose storage binding points at any storage account the
    /// player owns — the unlink / delete-account guard. Counts every bound
    /// world regardless of owner: unlinking under someone else's world (a
    /// pre-fix shared-Google-account binding) would orphan it just the same.
    pub async fn count_active_worlds_bound_to_player_accounts(
        &self,
        provider: StorageProviderType,
        owner_player_uuid: &str,
    ) -> Result<i64, DbError> {
        let p = owner_player_uuid.to_string();
        self.db
            .read(move |c| {
                Ok(c.query_one(
                    "worlds.count_bound_to_player_accounts",
                    "SELECT COUNT(*) FROM worlds
                     WHERE deleted_at IS NULL AND storage_provider = ? AND storage_account_id IN
                           (SELECT id FROM storage_accounts WHERE provider = ? AND owner_player_uuid = ?)",
                    params![provider.as_str(), provider.as_str(), p],
                    |r| r.get::<_, i64>(0),
                )?
                .unwrap_or(0))
            })
            .await
    }

    pub async fn get_world_owner_uuid(&self, world_id: &str) -> Result<Option<String>, DbError> {
        let w = world_id.to_string();
        self.db
            .read(move |c| {
                c.query_one(
                    "worlds.owner_uuid",
                    "SELECT owner_uuid FROM worlds WHERE id = ? AND deleted_at IS NULL",
                    params![w],
                    |r| r.get::<_, String>(0),
                )
            })
            .await
    }

    /// Every world the player ever owned, tombstones included (account
    /// deletion hard-deletes them bottom-up).
    pub async fn list_world_ids_for_owner(&self, owner_player_uuid: &str) -> Result<Vec<String>, DbError> {
        let p = owner_player_uuid.to_string();
        self.db
            .read(move |c| {
                c.query(
                    "worlds.ids_for_owner",
                    "SELECT id FROM worlds WHERE owner_uuid = ? ORDER BY id ASC",
                    params![p],
                    |r| r.get::<_, String>(0),
                )
            })
            .await
    }

    /// Account deletion: removes a world row and everything hanging off it,
    /// in FK order. Callers pass tombstoned worlds — an active world must go
    /// through `delete_world_for_player` first (realtime teardown, blob GC).
    pub async fn hard_delete_world(&self, world_id: &str) -> Result<(), DbError> {
        let w = world_id.to_string();
        self.db
            .write(move |c| {
                c.execute(
                    "snapshot_files.hard_delete_world",
                    "DELETE FROM snapshot_files WHERE snapshot_id IN (SELECT id FROM snapshots WHERE world_id = ?)",
                    params![w],
                )?;
                c.execute(
                    "snapshot_packs.hard_delete_world",
                    "DELETE FROM snapshot_packs WHERE snapshot_id IN (SELECT id FROM snapshots WHERE world_id = ?)",
                    params![w],
                )?;
                c.execute("snapshots.hard_delete_world", "DELETE FROM snapshots WHERE world_id = ?", params![w])?;
                c.execute("invite_codes.hard_delete_world", "DELETE FROM invite_codes WHERE world_id = ?", params![w])?;
                c.execute(
                    "world_memberships.hard_delete_world",
                    "DELETE FROM world_memberships WHERE world_id = ?",
                    params![w],
                )?;
                c.execute(
                    "world_runtime_mirror.hard_delete_world",
                    "DELETE FROM world_runtime_mirror WHERE world_id = ?",
                    params![w],
                )?;
                c.execute(
                    "storage_upload_sessions.hard_delete_world",
                    "DELETE FROM storage_upload_sessions WHERE world_id = ?",
                    params![w],
                )?;
                c.execute("coordinator_kv.hard_delete_world", "DELETE FROM coordinator_kv WHERE world_id = ?", params![w])?;
                c.execute(
                    "coordinator_alarms.hard_delete_world",
                    "DELETE FROM coordinator_alarms WHERE world_id = ?",
                    params![w],
                )?;
                c.execute("worlds.hard_delete", "DELETE FROM worlds WHERE id = ?", params![w])?;
                Ok(())
            })
            .await
    }

    /// Account deletion: every remaining reference to the player in OTHER
    /// players' surviving worlds is re-pointed at the sentinel user (FK
    /// columns) or cleared, and the player's membership tombstones go away.
    /// Runs after the player's own worlds are hard-deleted.
    pub async fn scrub_player_references(
        &self,
        player_uuid: &str,
        sentinel_uuid: &str,
    ) -> Result<(), DbError> {
        let (p, s) = (player_uuid.to_string(), sentinel_uuid.to_string());
        self.db
            .write(move |c| {
                c.execute(
                    "snapshots.scrub_creator",
                    "UPDATE snapshots SET created_by_uuid = ? WHERE created_by_uuid = ?",
                    params![s, p],
                )?;
                c.execute(
                    "invite_codes.scrub_creator",
                    "UPDATE invite_codes SET created_by_uuid = ? WHERE created_by_uuid = ?",
                    params![s, p],
                )?;
                c.execute(
                    "invite_codes.scrub_redeemer",
                    "UPDATE invite_codes SET redeemed_by_uuid = NULL WHERE redeemed_by_uuid = ?",
                    params![p],
                )?;
                c.execute(
                    "world_memberships.scrub_player",
                    "DELETE FROM world_memberships WHERE player_uuid = ?",
                    params![p],
                )?;
                c.execute(
                    "worlds.scrub_unclean_host",
                    "UPDATE worlds SET unclean_shutdown_host_uuid = NULL, unclean_shutdown_host_player_name = NULL
                     WHERE unclean_shutdown_host_uuid = ?",
                    params![p],
                )?;
                Ok(())
            })
            .await
    }

    /// Every input that feeds GET /worlds, as small deterministic fact rows
    /// (the service hashes this into the weak ETag).
    pub async fn worlds_change_facts(&self, player_uuid: &str) -> Result<Value, DbError> {
        let p = player_uuid.to_string();
        self.db
            .read(move |c| {
                let worlds = c.query(
                    "facts.worlds",
                    "SELECT w.id, w.name, w.motd, w.custom_icon_storage_key, w.storage_account_id, w.settings_revision, w.owner_uuid
                     FROM worlds w
                     JOIN world_memberships wm ON wm.world_id = w.id
                     WHERE wm.player_uuid = ? AND wm.deleted_at IS NULL AND w.deleted_at IS NULL
                     ORDER BY w.id ASC",
                    params![p],
                    |r| {
                        Ok(json!([
                            r.get::<_, String>(0)?,
                            r.get::<_, String>(1)?,
                            r.get::<_, Option<String>>(2)?,
                            r.get::<_, Option<String>>(3)?,
                            r.get::<_, Option<String>>(4)?,
                            r.get::<_, i64>(5)?,
                            r.get::<_, String>(6)?
                        ]))
                    },
                )?;
                if worlds.is_empty() {
                    return Ok(json!({ "worlds": [] }));
                }
                const MEMBER_WORLDS_FILTER: &str =
                    "world_id IN (SELECT world_id FROM world_memberships WHERE player_uuid = ? AND deleted_at IS NULL)";
                let memberships = c.query(
                    "facts.memberships",
                    &format!(
                        "SELECT world_id, player_uuid, player_name, role, can_use_commands, joined_at
                         FROM world_memberships
                         WHERE deleted_at IS NULL AND {MEMBER_WORLDS_FILTER}
                         ORDER BY world_id ASC, player_uuid ASC"
                    ),
                    params![p],
                    |r| {
                        Ok(json!([
                            r.get::<_, String>(0)?,
                            r.get::<_, String>(1)?,
                            r.get::<_, String>(2)?,
                            r.get::<_, String>(3)?,
                            r.get::<_, i64>(4)?,
                            r.get::<_, String>(5)?
                        ]))
                    },
                )?;
                let mirrors = c.query(
                    "facts.mirrors",
                    &format!("SELECT world_id, updated_at FROM world_runtime_mirror WHERE {MEMBER_WORLDS_FILTER} ORDER BY world_id ASC"),
                    params![p],
                    |r| Ok(json!([r.get::<_, String>(0)?, r.get::<_, String>(1)?])),
                )?;
                let latest = c.query(
                    "facts.latest",
                    &format!(
                        "SELECT wm.world_id AS world_id, {} AS id
                         FROM world_memberships wm
                         WHERE wm.player_uuid = ? AND wm.deleted_at IS NULL
                         ORDER BY wm.world_id ASC",
                        latest_snapshot_id_subquery("wm.world_id")
                    ),
                    params![p],
                    |r| Ok(json!([r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?])),
                )?;
                let mut account_ids: Vec<String> =
                    worlds.iter().filter_map(|w| w[4].as_str().map(|s| s.to_string())).collect();
                account_ids.sort();
                account_ids.dedup();
                let accounts = if account_ids.is_empty() {
                    vec![]
                } else {
                    c.query(
                        "facts.accounts",
                        // external_account_id, not email: the same re-link signal
                        // without hashing PII (the email is ciphertext at rest).
                        &format!("SELECT id, external_account_id FROM storage_accounts WHERE id {IN_JSON_LIST} ORDER BY id ASC"),
                        params![json_list(&account_ids)],
                        |r| Ok(json!([r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?])),
                    )?
                };
                Ok(json!({ "worlds": worlds, "memberships": memberships, "mirrors": mirrors, "latest": latest, "accounts": accounts }))
            })
            .await
    }

    /// Single-world variant for GET /worlds/:id; `None` when the caller has no access.
    pub async fn world_change_facts(
        &self,
        world_id: &str,
        player_uuid: &str,
        now: time::Instant,
    ) -> Result<Option<Value>, DbError> {
        let (w, p) = (world_id.to_string(), player_uuid.to_string());
        let now_iso = time::to_iso(now);
        self.db
            .read(move |c| {
                let world = c.query_one(
                    "facts.world",
                    "SELECT w.id, w.name, w.motd, w.custom_icon_storage_key, w.storage_account_id, w.settings_revision, w.owner_uuid
                     FROM worlds w
                     JOIN world_memberships wm ON wm.world_id = w.id AND wm.player_uuid = ? AND wm.deleted_at IS NULL
                     WHERE w.id = ? AND w.deleted_at IS NULL",
                    params![p, w],
                    |r| {
                        Ok((
                            json!([
                                r.get::<_, String>(0)?,
                                r.get::<_, String>(1)?,
                                r.get::<_, Option<String>>(2)?,
                                r.get::<_, Option<String>>(3)?,
                                r.get::<_, Option<String>>(4)?,
                                r.get::<_, i64>(5)?,
                                r.get::<_, String>(6)?
                            ]),
                            r.get::<_, Option<String>>(4)?,
                            r.get::<_, String>(6)?,
                        ))
                    },
                )?;
                let Some((world, account_id, owner_uuid)) = world else { return Ok(None) };
                let memberships = c.query(
                    "facts.world_memberships",
                    "SELECT player_uuid, player_name, role, can_use_commands, joined_at
                     FROM world_memberships WHERE world_id = ? AND deleted_at IS NULL ORDER BY player_uuid ASC",
                    params![w],
                    |r| {
                        Ok(json!([
                            r.get::<_, String>(0)?,
                            r.get::<_, String>(1)?,
                            r.get::<_, String>(2)?,
                            r.get::<_, i64>(3)?,
                            r.get::<_, String>(4)?
                        ]))
                    },
                )?;
                let mirror = c.query_one(
                    "facts.world_mirror",
                    "SELECT updated_at FROM world_runtime_mirror WHERE world_id = ?",
                    params![w],
                    |r| r.get::<_, String>(0),
                )?;
                let latest = c.query_one(
                    "facts.world_latest",
                    "SELECT id FROM snapshots WHERE world_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                    params![w],
                    |r| r.get::<_, String>(0),
                )?;
                // external_account_id, not email: same re-link signal, no PII.
                let account_external_id = match &account_id {
                    Some(id) => c
                        .query_one("facts.account_external", "SELECT external_account_id FROM storage_accounts WHERE id = ?", params![id], |r| {
                            r.get::<_, Option<String>>(0)
                        })?
                        .flatten(),
                    None => None,
                };
                let invite = if owner_uuid == p {
                    c.query_one(
                        "facts.invite",
                        "SELECT id, expires_at FROM invite_codes WHERE world_id = ? AND status = 'active' ORDER BY created_at DESC, id DESC LIMIT 1",
                        params![w],
                        |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)),
                    )?
                    .map(|(id, expires_at)| json!({ "id": id, "valid": expires_at >= now_iso }))
                    .unwrap_or(Value::Null)
                } else {
                    Value::Null
                };
                Ok(Some(json!({
                    "world": world,
                    "memberships": memberships,
                    "mirrorUpdatedAt": mirror,
                    "latestSnapshotId": latest,
                    "accountExternalId": account_external_id,
                    "invite": invite
                })))
            })
            .await
    }

    pub async fn session_actor_facts(
        &self,
        world_id: &str,
        player_uuid: &str,
    ) -> Result<Option<SessionActorFacts>, DbError> {
        let (w, p) = (world_id.to_string(), player_uuid.to_string());
        self.db
            .read(move |c| {
                c.query_one(
                    "worlds.session_actor_facts",
                    "SELECT MAX(CASE WHEN wm.player_uuid IS NOT NULL AND wm.deleted_at IS NULL THEN 1 ELSE 0 END) AS active,
                            COUNT(wm.player_uuid) AS ever
                     FROM worlds w
                     LEFT JOIN world_memberships wm ON wm.world_id = w.id AND wm.player_uuid = ?
                     WHERE w.id = ? AND w.deleted_at IS NULL
                     GROUP BY w.id",
                    params![p, w],
                    |r| {
                        Ok(SessionActorFacts {
                            membership_active: r.get::<_, Option<i64>>(0)?.unwrap_or(0) == 1,
                            ever_member: r.get::<_, Option<i64>>(1)?.unwrap_or(0) > 0,
                        })
                    },
                )
            })
            .await
    }

    pub async fn has_active_world(&self, world_id: &str) -> Result<bool, DbError> {
        let w = world_id.to_string();
        Ok(self
            .db
            .read(move |c| {
                c.query_one(
                    "worlds.has_active",
                    "SELECT 1 FROM worlds WHERE id = ? AND deleted_at IS NULL LIMIT 1",
                    params![w],
                    |_| Ok(()),
                )
            })
            .await?
            .is_some())
    }

    pub async fn count_active_worlds(&self) -> Result<i64, DbError> {
        self.db
            .read(|c| {
                Ok(c.query_one(
                    "worlds.count_active",
                    "SELECT COUNT(*) FROM worlds WHERE deleted_at IS NULL",
                    [],
                    |r| r.get(0),
                )?
                .unwrap_or(0))
            })
            .await
    }

    pub async fn create_world(
        &self,
        actor: &Actor,
        name: &str,
        slug: &str,
        storage: WorldStorageBinding,
        motd: Option<String>,
        custom_icon_storage_key: Option<String>,
    ) -> Result<WorldDetails, DbError> {
        let id = new_id("world");
        let now = time::now_iso();
        let unique_slug = format!("{slug}-{}", &id[id.len().saturating_sub(8)..]);
        let actor = actor.clone();
        let player_uuid = actor.player_uuid.clone();
        let name = name.to_string();
        let id2 = id.clone();
        self.db
            .write(move |c| {
                c.execute(
                    "worlds.insert",
                    "INSERT INTO worlds (id, slug, name, motd, custom_icon_storage_key, owner_uuid, storage_provider, storage_account_id, created_at, deleted_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                    params![
                        id2,
                        unique_slug,
                        name,
                        motd,
                        custom_icon_storage_key,
                        actor.player_uuid,
                        storage.provider.as_str(),
                        storage.storage_account_id,
                        now
                    ],
                )?;
                c.execute(
                    "world_memberships.insert_owner",
                    "INSERT INTO world_memberships (world_id, player_uuid, player_name, role, joined_at, deleted_at)
                     VALUES (?, ?, ?, 'owner', ?, NULL)",
                    params![id2, actor.player_uuid, actor.player_name, now],
                )?;
                Ok(())
            })
            .await?;
        self.get_world_details(&id, &player_uuid)
            .await?
            .ok_or_else(|| DbError::other("World creation failed."))
    }

    pub async fn get_world_details(
        &self,
        world_id: &str,
        player_uuid: &str,
    ) -> Result<Option<WorldDetails>, DbError> {
        let (w, p) = (world_id.to_string(), player_uuid.to_string());
        let mut details = self.db.read(move |c| get_world_details_in(c, &w, &p)).await?;
        if let Some(d) = details.as_mut() {
            d.summary.storage_account_email = self.decrypt_email(d.summary.storage_account_email.take());
        }
        Ok(details)
    }

    pub async fn update_world(
        &self,
        actor: &Actor,
        world_id: &str,
        request: WorldUpdateRecord,
    ) -> Result<WorldDetails, DbError> {
        let (w, p) = (world_id.to_string(), actor.player_uuid.clone());
        self.db
            .write(move |c| {
                let motd = join_motd_lines(request.motd_line1.as_deref(), request.motd_line2.as_deref());
                let icon = if request.clear_custom_icon {
                    None
                } else {
                    match request.custom_icon_storage_key {
                        None => c
                            .query_one(
                                "worlds.current_icon",
                                "SELECT custom_icon_storage_key FROM worlds WHERE id = ?",
                                params![w],
                                |r| r.get::<_, Option<String>>(0),
                            )?
                            .flatten(),
                        Some(k) => k,
                    }
                };
                c.execute(
                    "worlds.update",
                    "UPDATE worlds SET name = ?, motd = ?, custom_icon_storage_key = ?
                     WHERE id = ? AND owner_uuid = ? AND deleted_at IS NULL",
                    params![request.name, motd, icon, w, p],
                )?;
                get_world_details_in(c, &w, &p)?.ok_or_else(|| DbError::other("World update failed."))
            })
            .await
            .map(|mut d| {
                d.summary.storage_account_email = self.decrypt_email(d.summary.storage_account_email.take());
                d
            })
    }

    pub async fn update_world_settings(&self, world_id: &str, settings_json: &str) -> Result<bool, DbError> {
        let (w, s) = (world_id.to_string(), settings_json.to_string());
        self.db
            .write(move |c| {
                Ok(c.execute(
                    "worlds.update_settings",
                    "UPDATE worlds SET settings = ?, settings_revision = settings_revision + 1 WHERE id = ? AND deleted_at IS NULL",
                    params![s, w],
                )? > 0)
            })
            .await
    }

    pub async fn update_world_settings_if_revision(
        &self,
        world_id: &str,
        settings_json: &str,
        expected_revision: i64,
    ) -> Result<bool, DbError> {
        let (w, s) = (world_id.to_string(), settings_json.to_string());
        self.db
            .write(move |c| {
                Ok(c.execute(
                    "worlds.update_settings_cas",
                    "UPDATE worlds SET settings = ?, settings_revision = settings_revision + 1
                     WHERE id = ? AND deleted_at IS NULL AND settings_revision = ?",
                    params![s, w, expected_revision],
                )? > 0)
            })
            .await
    }

    pub async fn get_world_settings(&self, world_id: &str) -> Result<Option<WorldSettingsRow>, DbError> {
        let w = world_id.to_string();
        self.db
            .read(move |c| {
                c.query_one(
                    "worlds.settings",
                    "SELECT settings, settings_revision FROM worlds WHERE id = ? AND deleted_at IS NULL",
                    params![w],
                    |r| {
                        Ok(WorldSettingsRow {
                            settings: parse_world_settings(r.get::<_, Option<String>>(0)?.as_deref()),
                            settings_revision: r.get::<_, Option<i64>>(1)?.unwrap_or(0),
                        })
                    },
                )
            })
            .await
    }

    pub async fn delete_world_for_player(
        &self,
        actor: &Actor,
        world_id: &str,
        now: time::Instant,
    ) -> Result<DeleteWorldResult, DbError> {
        let (w, p) = (world_id.to_string(), actor.player_uuid.clone());
        let deleted_at = time::to_iso(now);
        self.db
            .write(move |c| {
                let world = c.query_one(
                    "worlds.owner_icon",
                    "SELECT owner_uuid, custom_icon_storage_key FROM worlds WHERE id = ? AND deleted_at IS NULL",
                    params![w],
                    |r| Ok((r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?)),
                )?;
                let Some((owner_uuid, icon)) = world else {
                    return Ok(DeleteWorldResult { world_deleted: false, deleted_custom_icon_storage_key: None });
                };
                if owner_uuid == p {
                    c.execute(
                        "world_memberships.delete_all",
                        "UPDATE world_memberships SET deleted_at = ? WHERE world_id = ? AND deleted_at IS NULL",
                        params![deleted_at, w],
                    )?;
                    return tear_down_world(c, &w, &deleted_at, icon);
                }
                c.execute(
                    "world_memberships.leave",
                    "UPDATE world_memberships SET deleted_at = ? WHERE world_id = ? AND player_uuid = ? AND deleted_at IS NULL",
                    params![deleted_at, w, p],
                )?;
                let count: i64 = c
                    .query_one(
                        "world_memberships.count_active",
                        "SELECT COUNT(*) FROM world_memberships WHERE world_id = ? AND deleted_at IS NULL",
                        params![w],
                        |r| r.get(0),
                    )?
                    .unwrap_or(0);
                if count == 0 {
                    return tear_down_world(c, &w, &deleted_at, icon);
                }
                Ok(DeleteWorldResult { world_deleted: false, deleted_custom_icon_storage_key: None })
            })
            .await
    }

    pub async fn get_world_storage_binding(
        &self,
        world_id: &str,
    ) -> Result<Option<WorldStorageBinding>, DbError> {
        let w = world_id.to_string();
        self.db.read(move |c| get_world_storage_binding_in(c, &w)).await
    }

    pub async fn get_storage_usage(&self, world_id: &str) -> Result<StorageUsageSummary, DbError> {
        let w = world_id.to_string();
        self.db
            .read(move |c| {
                let world = c.query_one(
                    "worlds.binding",
                    "SELECT storage_provider, storage_account_id FROM worlds WHERE id = ? AND deleted_at IS NULL",
                    params![w],
                    |r| Ok((r.get::<_, Option<String>>(0)?, r.get::<_, Option<String>>(1)?)),
                )?;
                let Some((provider, account_id)) = world else {
                    return Err(DbError::other(format!("Unknown world {w}")));
                };
                let provider_str = provider.unwrap_or_else(|| "google-drive".into());
                let used: i64 = c
                    .query_one(
                        "storage_usage.used",
                        "WITH referenced_keys AS (
                           SELECT sf.storage_key AS storage_key
                           FROM snapshot_files sf JOIN snapshots s ON s.id = sf.snapshot_id WHERE s.world_id = ?
                           UNION
                           SELECT sp.storage_key AS storage_key
                           FROM snapshot_packs sp JOIN snapshots s ON s.id = sp.snapshot_id WHERE s.world_id = ?
                           UNION
                           SELECT json_extract(pack.value, '$.storageKey') AS storage_key
                           FROM snapshots s, json_each(COALESCE(s.packs_json, '[]')) AS pack WHERE s.world_id = ?
                           UNION
                           SELECT json_extract(step.value, '$.storageKey') AS storage_key
                           FROM snapshots s, json_each(COALESCE(s.packs_json, '[]')) AS pack,
                                json_each(COALESCE(json_extract(pack.value, '$.chainSteps'), '[]')) AS step
                           WHERE s.world_id = ?
                           UNION
                           SELECT s.manifest_storage_key AS storage_key FROM snapshots s
                           WHERE s.world_id = ? AND s.manifest_storage_key IS NOT NULL
                           UNION
                           SELECT w.custom_icon_storage_key AS storage_key FROM worlds w
                           WHERE w.id = ? AND w.deleted_at IS NULL AND w.custom_icon_storage_key IS NOT NULL
                         )
                         SELECT COALESCE(SUM(so.size), 0) AS used
                         FROM referenced_keys rk
                         JOIN storage_objects so
                           ON so.provider = ? AND so.storage_account_id = ? AND so.storage_key = rk.storage_key",
                        params![w, w, w, w, w, w, provider_str, account_id.clone().unwrap_or_default()],
                        |r| r.get(0),
                    )?
                    .unwrap_or(0);
                let account = match &account_id {
                    Some(id) => get_storage_account_in(c, id)?,
                    None => None,
                };
                Ok(StorageUsageSummary {
                    provider: provider_of(&provider_str),
                    linked: account_id.is_some(),
                    used_bytes: used,
                    quota_used_bytes: None,
                    quota_total_bytes: None,
                    account_email: account.and_then(|a| a.email),
                })
            })
            .await
            .map(|mut usage| {
                usage.account_email = self.decrypt_email(usage.account_email.take());
                usage
            })
    }

    /// Runtime mirror (single writer: the coordinator). Null fields leave the
    /// stored column untouched.
    pub async fn upsert_runtime_mirror(
        &self,
        world_id: &str,
        status_json: Option<String>,
        room_players_json: Option<String>,
    ) -> Result<(), DbError> {
        let w = world_id.to_string();
        let now = time::now_iso();
        self.db
            .write(move |c| {
                c.execute(
                    "world_runtime_mirror.upsert",
                    "INSERT INTO world_runtime_mirror (world_id, status_json, room_players_json, updated_at)
                     VALUES (?, ?, ?, ?)
                     ON CONFLICT(world_id) DO UPDATE SET
                       status_json = COALESCE(excluded.status_json, world_runtime_mirror.status_json),
                       room_players_json = COALESCE(excluded.room_players_json, world_runtime_mirror.room_players_json),
                       updated_at = excluded.updated_at",
                    params![w, status_json, room_players_json, now],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn get_runtime_mirror(&self, world_id: &str) -> Result<Option<RuntimeMirrorRow>, DbError> {
        let w = world_id.to_string();
        self.db
            .read(move |c| {
                c.query_one(
                    "world_runtime_mirror.get",
                    "SELECT status_json, room_players_json FROM world_runtime_mirror WHERE world_id = ?",
                    params![w],
                    |r| Ok(RuntimeMirrorRow { status_json: r.get(0)?, room_players_json: r.get(1)? }),
                )
            })
            .await
    }
}

pub(crate) fn get_world_storage_binding_in(
    c: &Conn<'_>,
    world_id: &str,
) -> Result<Option<WorldStorageBinding>, DbError> {
    c.query_one(
        "worlds.binding",
        "SELECT storage_provider, storage_account_id FROM worlds WHERE id = ? AND deleted_at IS NULL",
        params![world_id],
        |r| {
            Ok(WorldStorageBinding {
                provider: provider_of(&r.get::<_, Option<String>>(0)?.unwrap_or_default()),
                storage_account_id: r.get(1)?,
            })
        },
    )
}

fn tear_down_world(
    c: &Conn<'_>,
    world_id: &str,
    deleted_at: &str,
    icon: Option<String>,
) -> Result<DeleteWorldResult, DbError> {
    c.execute(
        "worlds.soft_delete",
        "UPDATE worlds SET deleted_at = ? WHERE id = ?",
        params![deleted_at, world_id],
    )?;
    c.execute("invite_codes.delete_world", "DELETE FROM invite_codes WHERE world_id = ?", params![world_id])?;
    // The mirror is only ever read for live worlds; leaving the row used to
    // leak the final host/roster names forever.
    c.execute(
        "world_runtime_mirror.delete_world",
        "DELETE FROM world_runtime_mirror WHERE world_id = ?",
        params![world_id],
    )?;
    Ok(DeleteWorldResult { world_deleted: true, deleted_custom_icon_storage_key: icon })
}

pub(crate) fn get_world_details_in(
    c: &Conn<'_>,
    world_id: &str,
    player_uuid: &str,
) -> Result<Option<WorldDetails>, DbError> {
    // The membership list doubles as the access gate and the member count.
    let memberships = list_memberships_in(c, world_id)?;
    let Some(membership) = memberships.iter().find(|m| m.player_uuid == player_uuid).cloned() else {
        return Ok(None);
    };
    let mut counts = HashMap::new();
    counts.insert(world_id.to_string(), memberships.len() as i64);
    let mut summaries = Repository::build_world_summaries_in(c, &[world_id.to_string()], Some(counts))?;
    let Some(summary) = summaries.remove(world_id) else { return Ok(None) };
    Ok(Some(WorldDetails { summary, membership, memberships, storage_usage: None, active_invite_code: None }))
}

#[allow(dead_code)]
fn _provider_type_used(_: StorageProviderType) {}
