//! `buildWorldSummaries` and the runtime-mirror projections.

use std::collections::{BTreeSet, HashMap};

use rusqlite::{params, Row};
use sw_contracts::{
    RoomPlayer, StorageProviderType, WorldRuntimePhase, WorldRuntimeStatus, WorldSettings, WorldStatus,
    WorldSummary,
};

use super::records::provider_of;
use super::{json_list, placeholders, Repository, IN_JSON_LIST};
use crate::error::DbError;
use crate::pool::Conn;

/// Newest snapshot id for one world as a correlated scalar (index
/// `idx_snapshots_world_created_id` → one reverse index step).
pub(crate) fn latest_snapshot_id_subquery(world_id_expr: &str) -> String {
    format!("(SELECT s.id FROM snapshots s WHERE s.world_id = {world_id_expr} ORDER BY s.created_at DESC, s.id DESC LIMIT 1)")
}

#[derive(Debug, Default)]
pub(crate) struct ParsedRuntimeMirror {
    pub status: Option<WorldRuntimeStatus>,
    pub room_players: Vec<RoomPlayer>,
}

pub(crate) fn parse_runtime_mirror(
    status_json: Option<&str>,
    room_players_json: Option<&str>,
) -> ParsedRuntimeMirror {
    ParsedRuntimeMirror {
        status: status_json.and_then(|s| serde_json::from_str(s).ok()),
        room_players: room_players_json.and_then(|s| serde_json::from_str(s).ok()).unwrap_or_default(),
    }
}

pub(crate) struct Lifecycle {
    pub status: WorldStatus,
    pub active_host_uuid: Option<String>,
    pub active_host_player_name: Option<String>,
    pub active_join_target: Option<String>,
}

pub(crate) fn lifecycle_of_mirror(m: &ParsedRuntimeMirror) -> Lifecycle {
    if let Some(status) = &m.status {
        if matches!(
            status.phase,
            WorldRuntimePhase::HostStarting | WorldRuntimePhase::HostLive | WorldRuntimePhase::HostFinalizing
        ) {
            return Lifecycle {
                status: status.phase.world_status(),
                active_host_uuid: status.host_uuid.clone(),
                active_host_player_name: status.host_player_name.clone(),
                active_join_target: status.join_target.clone(),
            };
        }
    }
    Lifecycle {
        status: if m.status.as_ref().is_some_and(|s| s.phase == WorldRuntimePhase::HandoffWaiting) {
            WorldStatus::Handoff
        } else {
            WorldStatus::Idle
        },
        active_host_uuid: None,
        active_host_player_name: None,
        active_join_target: None,
    }
}

fn canonical_uuid(uuid: &str) -> String {
    uuid.replace('-', "").to_lowercase()
}

/// Online players from the mirror: the room roster plus the active host
/// while a hosting session is up (hyphen-insensitive dedupe).
pub(crate) fn online_players_of_mirror(m: &ParsedRuntimeMirror) -> Vec<RoomPlayer> {
    let mut seen = BTreeSet::new();
    let mut out = Vec::new();
    if let Some(status) = &m.status {
        if matches!(status.phase, WorldRuntimePhase::HostStarting | WorldRuntimePhase::HostLive) {
            if let (Some(uuid), Some(name)) = (&status.host_uuid, &status.host_player_name) {
                seen.insert(canonical_uuid(uuid));
                out.push(RoomPlayer { player_uuid: uuid.clone(), player_name: name.clone() });
            }
        }
    }
    for p in &m.room_players {
        if seen.insert(canonical_uuid(&p.player_uuid)) {
            out.push(p.clone());
        }
    }
    out
}

pub(crate) fn parse_world_settings(raw: Option<&str>) -> Option<WorldSettings> {
    let text = raw?;
    let value: serde_json::Value = serde_json::from_str(text).ok()?;
    if !value.is_object() {
        return None;
    }
    serde_json::from_value(value).ok()
}

struct WorldRow {
    id: String,
    slug: String,
    name: String,
    motd: Option<String>,
    custom_icon_storage_key: Option<String>,
    owner_uuid: String,
    storage_provider: StorageProviderType,
    storage_account_id: Option<String>,
    settings: Option<String>,
    settings_revision: i64,
}

fn map_world_row(r: &Row<'_>) -> rusqlite::Result<WorldRow> {
    Ok(WorldRow {
        id: r.get("id")?,
        slug: r.get("slug")?,
        name: r.get("name")?,
        motd: r.get("motd")?,
        custom_icon_storage_key: r.get("custom_icon_storage_key")?,
        owner_uuid: r.get("owner_uuid")?,
        storage_provider: provider_of(&r.get::<_, Option<String>>("storage_provider")?.unwrap_or_default()),
        storage_account_id: r.get("storage_account_id")?,
        settings: r.get("settings")?,
        settings_revision: r.get::<_, Option<i64>>("settings_revision")?.unwrap_or(0),
    })
}

struct LatestRow {
    id: String,
    created_at: String,
    data_version: Option<i64>,
    minecraft_version: Option<String>,
}

impl Repository {
    /// Set-based summary builder: five fixed queries for any number of
    /// worlds. Deleted/unknown worlds are absent from the result.
    pub(crate) fn build_world_summaries_in(
        c: &Conn<'_>,
        world_ids: &[String],
        member_counts: Option<HashMap<String, i64>>,
    ) -> Result<HashMap<String, WorldSummary>, DbError> {
        let mut result = HashMap::new();
        if world_ids.is_empty() {
            return Ok(result);
        }
        let ph = placeholders(world_ids.len());
        let id_params: Vec<&dyn rusqlite::ToSql> =
            world_ids.iter().map(|s| s as &dyn rusqlite::ToSql).collect();
        let worlds = c.query(
            "worlds.summaries",
            &format!(
                "SELECT id, slug, name, motd, custom_icon_storage_key, owner_uuid, storage_provider, storage_account_id, settings, settings_revision
                 FROM worlds WHERE deleted_at IS NULL AND id IN ({ph})"
            ),
            id_params.as_slice(),
            map_world_row,
        )?;
        if worlds.is_empty() {
            return Ok(result);
        }
        let member_counts = match member_counts {
            Some(m) => m,
            None => c
                .query(
                    "world_memberships.counts",
                    &format!(
                        "SELECT world_id, COUNT(*) AS count FROM world_memberships
                         WHERE deleted_at IS NULL AND world_id IN ({ph}) GROUP BY world_id"
                    ),
                    id_params.as_slice(),
                    |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)),
                )?
                .into_iter()
                .collect(),
        };
        let mirrors: HashMap<String, ParsedRuntimeMirror> = c
            .query(
                "world_runtime_mirror.batch",
                &format!("SELECT world_id, status_json, room_players_json FROM world_runtime_mirror WHERE world_id IN ({ph})"),
                id_params.as_slice(),
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        parse_runtime_mirror(
                            r.get::<_, Option<String>>(1)?.as_deref(),
                            r.get::<_, Option<String>>(2)?.as_deref(),
                        ),
                    ))
                },
            )?
            .into_iter()
            .collect();
        // Latest snapshot per world: one 1-row index walk per world (0029).
        let latest: HashMap<String, LatestRow> = c
            .query(
                "snapshots.latest_batch",
                &format!(
                    "SELECT id, world_id, created_at, data_version, minecraft_version
                     FROM snapshots
                     WHERE id IN (SELECT {} FROM json_each(?) j)",
                    latest_snapshot_id_subquery("j.value")
                ),
                params![json_list(world_ids)],
                |r| {
                    Ok((
                        r.get::<_, String>("world_id")?,
                        LatestRow {
                            id: r.get("id")?,
                            created_at: r.get("created_at")?,
                            data_version: r.get("data_version")?,
                            minecraft_version: r.get("minecraft_version")?,
                        },
                    ))
                },
            )?
            .into_iter()
            .collect();
        let account_ids: Vec<String> = {
            let mut v: Vec<String> = worlds.iter().filter_map(|w| w.storage_account_id.clone()).collect();
            v.sort();
            v.dedup();
            v
        };
        let account_emails: HashMap<String, Option<String>> = if account_ids.is_empty() {
            HashMap::new()
        } else {
            c.query(
                "storage_accounts.emails",
                &format!("SELECT id, email FROM storage_accounts WHERE id {IN_JSON_LIST}"),
                params![json_list(&account_ids)],
                |r| Ok((r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?)),
            )?
            .into_iter()
            .collect()
        };
        let empty = ParsedRuntimeMirror::default();
        for w in worlds {
            let mirror = mirrors.get(&w.id).unwrap_or(&empty);
            let lifecycle = lifecycle_of_mirror(mirror);
            let online = online_players_of_mirror(mirror);
            let latest = latest.get(&w.id);
            let storage_linked = w.storage_account_id.is_some();
            let account_email =
                w.storage_account_id.as_ref().and_then(|id| account_emails.get(id).cloned().flatten());
            result.insert(
                w.id.clone(),
                WorldSummary {
                    id: w.id.clone(),
                    slug: w.slug,
                    name: w.name,
                    owner_uuid: w.owner_uuid,
                    motd: w.motd,
                    custom_icon_storage_key: w.custom_icon_storage_key,
                    custom_icon_download: None,
                    member_count: member_counts.get(&w.id).copied().unwrap_or(0),
                    status: lifecycle.status,
                    last_snapshot_id: latest.map(|l| l.id.clone()),
                    last_snapshot_at: latest.map(|l| l.created_at.clone()),
                    active_host_uuid: lifecycle.active_host_uuid,
                    active_host_player_name: lifecycle.active_host_player_name,
                    active_join_target: lifecycle.active_join_target,
                    online_player_count: online.len() as i64,
                    online_player_names: online.into_iter().map(|p| p.player_name).collect(),
                    storage_provider: w.storage_provider,
                    storage_linked,
                    storage_account_email: account_email,
                    last_snapshot_data_version: latest.and_then(|l| l.data_version),
                    last_snapshot_minecraft_version: latest.and_then(|l| l.minecraft_version.clone()),
                    settings: parse_world_settings(w.settings.as_deref()),
                    settings_revision: w.settings_revision,
                },
            );
        }
        Ok(result)
    }
}
