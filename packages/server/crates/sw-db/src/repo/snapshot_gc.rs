//! GC reference resolution (`referencedStorageKeys`) and delta-base edges.

use std::collections::HashSet;

use rusqlite::params;
use rusqlite::types::Value as SqlValue;

use super::records::*;
use super::{json_list, Repository, IN_JSON_LIST};
use crate::error::DbError;
use crate::pool::Conn;
use crate::time;

const MANIFEST_DOC_KEY_PREFIX: &str = "manifests/";
const WORLD_ICON_KEY_PREFIX: &str = "icons/";
/// Below this many candidate keys the directory legs pre-filter snapshot
/// rows with a substring test on packs_json before expanding json_each.
const DIRECTORY_PREFILTER_MAX_KEYS: usize = 256;

pub(crate) struct ResolvedInMemory {
    pub world_id: String,
    pub loaded_at: String,
}

/// Reference resolution for GC. Keys are routed by namespace; the
/// pack-directory + chain-recipe legs are json_each scans bounded by the
/// scope, the `created_at` floor and the caller's in-memory resolution.
pub(crate) fn referenced_storage_keys_in(
    c: &Conn<'_>,
    storage_keys: &[String],
    scope: Option<&StorageReferenceScope>,
    resolved_in_memory: Option<&ResolvedInMemory>,
) -> Result<HashSet<String>, DbError> {
    let mut referenced = HashSet::new();
    let mut unique: Vec<String> = storage_keys.to_vec();
    {
        let mut seen = HashSet::new();
        unique.retain(|k| seen.insert(k.clone()));
    }
    if unique.is_empty() {
        return Ok(referenced);
    }
    let mut snapshot_conditions: Vec<String> = Vec::new();
    let mut snapshot_params: Vec<SqlValue> = Vec::new();
    if let Some(scope) = scope {
        snapshot_conditions
            .push("s.world_id IN (SELECT w.id FROM worlds w WHERE w.storage_provider = ? AND w.storage_account_id IS ?)".into());
        snapshot_params.push(SqlValue::Text(scope.provider.as_str().into()));
        snapshot_params.push(scope.storage_account_id.clone().map(SqlValue::Text).unwrap_or(SqlValue::Null));
        if let Some(since) = &scope.snapshots_created_since {
            snapshot_conditions.push("s.created_at >= ?".into());
            snapshot_params.push(SqlValue::Text(since.clone()));
        }
    }
    let snapshot_scope = if snapshot_conditions.is_empty() {
        String::new()
    } else {
        format!(" AND {}", snapshot_conditions.join(" AND "))
    };
    let manifest_keys: Vec<String> =
        unique.iter().filter(|k| k.starts_with(MANIFEST_DOC_KEY_PREFIX)).cloned().collect();
    let icon_keys: Vec<String> =
        unique.iter().filter(|k| k.starts_with(WORLD_ICON_KEY_PREFIX)).cloned().collect();
    let blob_keys: Vec<String> = unique
        .iter()
        .filter(|k| !k.starts_with(MANIFEST_DOC_KEY_PREFIX) && !k.starts_with(WORLD_ICON_KEY_PREFIX))
        .cloned()
        .collect();

    if !manifest_keys.is_empty() {
        let mut p: Vec<SqlValue> = vec![SqlValue::Text(json_list(&manifest_keys))];
        p.extend(snapshot_params.iter().cloned());
        referenced.extend(c.query(
            "gc.manifest_keys",
            &format!("SELECT DISTINCT s.manifest_storage_key AS storage_key FROM snapshots s WHERE s.manifest_storage_key {IN_JSON_LIST}{snapshot_scope}"),
            rusqlite::params_from_iter(p),
            |r| r.get::<_, String>(0),
        )?);
    }
    if !icon_keys.is_empty() {
        let mut p: Vec<SqlValue> = vec![SqlValue::Text(json_list(&icon_keys))];
        let scope_sql = match scope {
            None => "",
            Some(s) => {
                p.push(SqlValue::Text(s.provider.as_str().into()));
                p.push(s.storage_account_id.clone().map(SqlValue::Text).unwrap_or(SqlValue::Null));
                " AND w.storage_provider = ? AND w.storage_account_id IS ?"
            }
        };
        referenced.extend(c.query(
            "gc.icon_keys",
            &format!("SELECT DISTINCT w.custom_icon_storage_key AS storage_key FROM worlds w WHERE w.deleted_at IS NULL AND w.custom_icon_storage_key {IN_JSON_LIST}{scope_sql}"),
            rusqlite::params_from_iter(p),
            |r| r.get::<_, String>(0),
        )?);
    }
    if blob_keys.is_empty() {
        return Ok(referenced);
    }
    let blob_keys_json = json_list(&blob_keys);
    let mut p: Vec<SqlValue> = vec![SqlValue::Text(blob_keys_json.clone())];
    p.extend(snapshot_params.iter().cloned());
    referenced.extend(c.query(
        "gc.snapshot_files",
        &format!("SELECT DISTINCT sf.storage_key FROM snapshot_files sf JOIN snapshots s ON s.id = sf.snapshot_id WHERE sf.storage_key {IN_JSON_LIST}{snapshot_scope}"),
        rusqlite::params_from_iter(p.clone()),
        |r| r.get::<_, String>(0),
    )?);
    referenced.extend(c.query(
        "gc.snapshot_packs",
        &format!("SELECT DISTINCT sp.storage_key FROM snapshot_packs sp JOIN snapshots s ON s.id = sp.snapshot_id WHERE sp.storage_key {IN_JSON_LIST}{snapshot_scope}"),
        rusqlite::params_from_iter(p),
        |r| r.get::<_, String>(0),
    )?);

    // Pack directories (0026) and S1 chain recipes: the unindexed legs.
    let mut conditions: Vec<String> = vec!["s.packs_json IS NOT NULL".into()];
    conditions.extend(snapshot_conditions.iter().cloned());
    let mut dir_params: Vec<SqlValue> = snapshot_params.clone();
    if let Some(r) = resolved_in_memory {
        conditions.push("(s.world_id != ? OR s.created_at >= ?)".into());
        dir_params.push(SqlValue::Text(r.world_id.clone()));
        let loaded = time::parse_iso(&r.loaded_at).unwrap_or_else(time::now);
        dir_params.push(SqlValue::Text(time::plus_ms_iso(loaded, -SNAPSHOT_CREATED_AT_SLACK_MS)));
    }
    if blob_keys.len() <= DIRECTORY_PREFILTER_MAX_KEYS {
        conditions.push(
            "EXISTS (SELECT 1 FROM json_each(?) AS candidate WHERE instr(s.packs_json, candidate.value) > 0)"
                .into(),
        );
        dir_params.push(SqlValue::Text(blob_keys_json.clone()));
    }
    let where_sql = conditions.join("\n           AND ");
    let mut all_params: Vec<SqlValue> = Vec::new();
    all_params.extend(dir_params.iter().cloned());
    all_params.push(SqlValue::Text(blob_keys_json.clone()));
    all_params.extend(dir_params.iter().cloned());
    all_params.push(SqlValue::Text(blob_keys_json));
    referenced.extend(
        c.query(
            "gc.pack_directories",
            &format!(
                "SELECT json_extract(pack.value, '$.storageKey') AS storage_key
               FROM snapshots s, json_each(COALESCE(s.packs_json, '[]')) AS pack
               WHERE {where_sql}
                 AND json_extract(pack.value, '$.storageKey') {IN_JSON_LIST}
             UNION ALL
             SELECT json_extract(step.value, '$.storageKey') AS storage_key
               FROM snapshots s, json_each(COALESCE(s.packs_json, '[]')) AS pack,
                    json_each(COALESCE(json_extract(pack.value, '$.chainSteps'), '[]')) AS step
               WHERE {where_sql}
                 AND json_extract(step.value, '$.storageKey') {IN_JSON_LIST}"
            ),
            rusqlite::params_from_iter(all_params),
            |r| r.get::<_, Option<String>>(0),
        )?
        .into_iter()
        .flatten(),
    );
    Ok(referenced)
}

impl Repository {
    pub async fn is_storage_key_referenced(
        &self,
        storage_key: &str,
        scope: Option<StorageReferenceScope>,
    ) -> Result<bool, DbError> {
        Ok(self
            .filter_referenced_storage_keys(&[storage_key.to_string()], scope)
            .await?
            .contains(storage_key))
    }

    pub async fn filter_referenced_storage_keys(
        &self,
        storage_keys: &[String],
        scope: Option<StorageReferenceScope>,
    ) -> Result<HashSet<String>, DbError> {
        let keys = storage_keys.to_vec();
        self.db.read(move |c| referenced_storage_keys_in(c, &keys, scope.as_ref(), None)).await
    }

    /// Delta-base edges for retention; S1 self-contained snapshots contribute none.
    pub async fn list_snapshot_delta_bases(&self, world_id: &str) -> Result<Vec<SnapshotDeltaBase>, DbError> {
        let w = world_id.to_string();
        self.db
            .read(move |c| {
                let mut rows: Vec<(String, String)> = Vec::new();
                rows.extend(c.query(
                    "delta_bases.files",
                    "SELECT DISTINCT sf.snapshot_id, sf.base_snapshot_id FROM snapshot_files sf JOIN snapshots s ON s.id = sf.snapshot_id
                     WHERE s.world_id = ? AND sf.base_snapshot_id IS NOT NULL",
                    params![w],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )?);
                rows.extend(c.query(
                    "delta_bases.packs",
                    "SELECT DISTINCT sp.snapshot_id, sp.base_snapshot_id FROM snapshot_packs sp JOIN snapshots s ON s.id = sp.snapshot_id
                     WHERE s.world_id = ? AND sp.base_snapshot_id IS NOT NULL",
                    params![w],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )?);
                rows.extend(c.query(
                    "delta_bases.directories",
                    "SELECT DISTINCT s.id AS snapshot_id, json_extract(pack.value, '$.baseSnapshotId') AS base_snapshot_id
                     FROM snapshots s, json_each(COALESCE(s.packs_json, '[]')) AS pack
                     WHERE s.world_id = ? AND json_extract(pack.value, '$.baseSnapshotId') IS NOT NULL",
                    params![w],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )?);
                let self_contained: HashSet<String> = c
                    .query(
                        "delta_bases.self_contained",
                        "SELECT s.id FROM snapshots s
                         WHERE s.world_id = ? AND s.packs_json IS NOT NULL
                           AND NOT EXISTS (
                             SELECT 1 FROM json_each(COALESCE(s.packs_json, '[]')) AS pack
                             WHERE json_extract(pack.value, '$.baseSnapshotId') IS NOT NULL
                               AND json_extract(pack.value, '$.chainSteps') IS NULL
                           )
                           AND NOT EXISTS (
                             SELECT 1 FROM snapshot_files sf WHERE sf.snapshot_id = s.id AND sf.pack_id IS NULL AND sf.base_snapshot_id IS NOT NULL
                           )",
                        params![w],
                        |r| r.get::<_, String>(0),
                    )?
                    .into_iter()
                    .collect();
                let mut seen = HashSet::new();
                let mut edges = Vec::new();
                for (snapshot_id, base_snapshot_id) in rows {
                    if self_contained.contains(&snapshot_id) {
                        continue;
                    }
                    if seen.insert((snapshot_id.clone(), base_snapshot_id.clone())) {
                        edges.push(SnapshotDeltaBase { snapshot_id, base_snapshot_id });
                    }
                }
                Ok(edges)
            })
            .await
    }
}
