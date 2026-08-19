//! `SnapshotRepository` reads + finalize + chain-step stamping.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use rusqlite::params;
use sw_contracts::{
    FinalizeSnapshotRequest, PackChainStep, PackedManifestFile, SnapshotManifest, WorldSnapshotSummary,
};

use super::pack_directory::*;
use super::records::*;
use super::world::get_world_storage_binding_in;
use super::{json_list, new_id, placeholders, Repository, IN_JSON_LIST};
use crate::collate::locale_compare;
use crate::error::DbError;
use crate::pool::Conn;
use crate::time;

struct SnapshotRow {
    id: String,
    world_id: String,
    created_at: String,
    created_by_uuid: String,
}

fn latest_snapshot_row(c: &Conn<'_>, world_id: &str) -> Result<Option<SnapshotRow>, DbError> {
    c.query_one(
        "snapshots.latest_row",
        "SELECT id, world_id, created_at, created_by_uuid FROM snapshots WHERE world_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
        params![world_id],
        |r| Ok(SnapshotRow { id: r.get(0)?, world_id: r.get(1)?, created_at: r.get(2)?, created_by_uuid: r.get(3)? }),
    )
}

fn loose_files(c: &Conn<'_>, snapshot_id: &str) -> Result<Vec<sw_contracts::ManifestFile>, DbError> {
    c.query(
        "snapshot_files.loose",
        &format!("SELECT {LOOSE_FILE_COLUMNS} FROM snapshot_files WHERE snapshot_id = ? AND pack_id IS NULL ORDER BY path ASC"),
        params![snapshot_id],
        loose_file_of_row,
    )
}

/// Headers-only manifest (EMPTY member lists; no document, no cache).
fn load_snapshot_headers(
    c: &Conn<'_>,
    snapshot_id: &str,
    world_id: &str,
    created_at: &str,
    created_by_uuid: &str,
    raw_packs_json: Option<Option<&str>>,
) -> Result<SnapshotManifest, DbError> {
    let files = loose_files(c, snapshot_id)?;
    let directory = match raw_packs_json {
        None => pack_directory_of(c, snapshot_id)?,
        Some(raw) => pack_directory(c, snapshot_id, raw)?,
    };
    Ok(SnapshotManifest {
        world_id: world_id.to_string(),
        snapshot_id: snapshot_id.to_string(),
        created_at: created_at.to_string(),
        created_by_uuid: created_by_uuid.to_string(),
        files,
        packs: assemble_snapshot_packs(&directory, |_| Ok(vec![]), true)?,
    })
}

/// What `loadSnapshot` needs from SQL before member resolution.
struct SnapshotLoad {
    files: Vec<sw_contracts::ManifestFile>,
    directory: Vec<PackDirectoryEntry>,
    manifest_storage_key: Option<String>,
    binding: Option<WorldStorageBinding>,
    /// Row-mode member rows keyed by (members_snapshot_id, pack_id).
    members: HashMap<(String, String), Vec<PackedManifestFile>>,
}

fn load_snapshot_rows(c: &Conn<'_>, snapshot_id: &str, world_id: &str) -> Result<SnapshotLoad, DbError> {
    let files = loose_files(c, snapshot_id)?;
    let (raw, manifest_storage_key) = c
        .query_one(
            "snapshots.directory_pointer",
            "SELECT packs_json, manifest_storage_key FROM snapshots WHERE id = ?",
            params![snapshot_id],
            |r| Ok((r.get::<_, Option<String>>(0)?, r.get::<_, Option<String>>(1)?)),
        )?
        .unwrap_or((None, None));
    let directory = pack_directory(c, snapshot_id, raw.as_deref())?;
    let mut members = HashMap::new();
    let mut binding = None;
    if directory.is_empty() {
        // nothing to resolve
    } else if manifest_storage_key.is_some() {
        binding = get_world_storage_binding_in(c, world_id)?;
    } else {
        // Resolve every pack's member rows in one query; inherited packs
        // read from the donor snapshot (members_snapshot_id, one hop).
        let mut ids: Vec<String> = directory
            .iter()
            .map(|e| e.members_snapshot_id.clone().unwrap_or_else(|| snapshot_id.to_string()))
            .collect();
        ids.sort();
        ids.dedup();
        let p: Vec<&dyn rusqlite::ToSql> = ids.iter().map(|s| s as &dyn rusqlite::ToSql).collect();
        let rows = c.query(
            "snapshot_files.members",
            &format!(
                "SELECT snapshot_id, pack_id, path, hash, size, content_type FROM snapshot_files
                 WHERE pack_id IS NOT NULL AND snapshot_id IN ({}) ORDER BY path ASC",
                placeholders(ids.len())
            ),
            p.as_slice(),
            |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    PackedManifestFile {
                        path: r.get(2)?,
                        hash: r.get(3)?,
                        size: r.get(4)?,
                        content_type: r.get(5)?,
                    },
                ))
            },
        )?;
        for (sid, pid, file) in rows {
            members.entry((sid, pid)).or_insert_with(Vec::new).push(file);
        }
    }
    Ok(SnapshotLoad { files, directory, manifest_storage_key, binding, members })
}

impl Repository {
    async fn load_snapshot(
        &self,
        snapshot_id: &str,
        world_id: &str,
        created_at: &str,
        created_by_uuid: &str,
    ) -> Result<SnapshotManifest, DbError> {
        let (sid, wid) = (snapshot_id.to_string(), world_id.to_string());
        let load = self.db.read(move |c| load_snapshot_rows(c, &sid, &wid)).await?;
        let packs = if load.directory.is_empty() {
            vec![]
        } else if let Some(storage_key) = &load.manifest_storage_key {
            // 0027 read path: LOUD failures (502), never empty member lists.
            let reader = self.document_reader().ok_or_else(|| {
                DbError::ManifestUnavailable("Snapshot manifest document reader is not configured.".into())
            })?;
            let binding = load.binding.clone().ok_or_else(|| {
                DbError::ManifestUnavailable(
                    "Snapshot manifest document storage is unavailable for this world.".into(),
                )
            })?;
            let document = reader.load(&binding, storage_key).await?.ok_or_else(|| {
                tracing::warn!(
                    world_id,
                    snapshot_id,
                    storage_key,
                    "SharedWorld snapshot manifest document missing from storage"
                );
                DbError::ManifestUnavailable("Snapshot manifest document is missing from storage.".into())
            })?;
            let by_pack: HashMap<&str, &Vec<PackedManifestFile>> =
                document.packs.iter().map(|p| (p.pack_id.as_str(), &p.files)).collect();
            assemble_snapshot_packs(
                &load.directory,
                |entry| {
                    let Some(members) = by_pack.get(entry.pack_id.as_str()) else {
                        tracing::warn!(world_id, snapshot_id, storage_key, pack_id = %entry.pack_id, "SharedWorld snapshot manifest document lacks a directory pack");
                        return Err(DbError::ManifestUnavailable(
                            "Snapshot manifest document does not match the snapshot's pack directory.".into(),
                        ));
                    };
                    let mut sorted = (*members).clone();
                    sorted.sort_by(|a, b| a.path.cmp(&b.path));
                    Ok(sorted)
                },
                false,
            )?
        } else {
            assemble_snapshot_packs(
                &load.directory,
                |entry| {
                    let members_sid =
                        entry.members_snapshot_id.clone().unwrap_or_else(|| snapshot_id.to_string());
                    let members = load
                        .members
                        .get(&(members_sid.clone(), entry.pack_id.clone()))
                        .cloned()
                        .unwrap_or_default();
                    if members.is_empty() && members_sid != snapshot_id {
                        tracing::warn!(snapshot_id, pack_id = %entry.pack_id, members_snapshot_id = %members_sid, "SharedWorld snapshot pack inherited zero member rows — donor missing?");
                    }
                    Ok(members)
                },
                false,
            )?
        };
        Ok(SnapshotManifest {
            world_id: world_id.to_string(),
            snapshot_id: snapshot_id.to_string(),
            created_at: created_at.to_string(),
            created_by_uuid: created_by_uuid.to_string(),
            files: load.files,
            packs,
        })
    }

    /// Manifest content is immutable per snapshot id, so a cache hit skips
    /// the row loads entirely.
    async fn load_snapshot_cached(
        &self,
        snapshot_id: &str,
        world_id: &str,
        created_at: &str,
        created_by_uuid: &str,
    ) -> Result<Arc<SnapshotManifest>, DbError> {
        if let Some(cache) = &self.manifest_cache {
            if let Some(m) = cache.get(world_id, snapshot_id).await {
                return Ok(m);
            }
        }
        let manifest =
            Arc::new(self.load_snapshot(snapshot_id, world_id, created_at, created_by_uuid).await?);
        if let Some(cache) = &self.manifest_cache {
            cache.put(world_id, snapshot_id, manifest.clone()).await;
        }
        Ok(manifest)
    }

    pub async fn get_latest_snapshot_stamp(&self, world_id: &str) -> Result<Option<String>, DbError> {
        let w = world_id.to_string();
        self.db.read(move |c| Ok(latest_snapshot_row(c, &w)?.map(|r| r.id))).await
    }

    pub async fn get_latest_snapshot(
        &self,
        world_id: &str,
    ) -> Result<Option<Arc<SnapshotManifest>>, DbError> {
        let w = world_id.to_string();
        let Some(row) = self.db.read(move |c| latest_snapshot_row(c, &w)).await? else { return Ok(None) };
        Ok(Some(self.load_snapshot_cached(&row.id, world_id, &row.created_at, &row.created_by_uuid).await?))
    }

    pub async fn get_snapshot(
        &self,
        world_id: &str,
        snapshot_id: &str,
    ) -> Result<Option<Arc<SnapshotManifest>>, DbError> {
        let (w, s) = (world_id.to_string(), snapshot_id.to_string());
        // The DB existence check always runs first: a retention-deleted
        // snapshot must return null even while its manifest is cached.
        let row = self
            .db
            .read(move |c| {
                c.query_one(
                    "snapshots.get_row",
                    "SELECT id, world_id, created_at, created_by_uuid FROM snapshots WHERE world_id = ? AND id = ?",
                    params![w, s],
                    |r| Ok(SnapshotRow { id: r.get(0)?, world_id: r.get(1)?, created_at: r.get(2)?, created_by_uuid: r.get(3)? }),
                )
            })
            .await?;
        let Some(row) = row else { return Ok(None) };
        Ok(Some(
            self.load_snapshot_cached(&row.id, &row.world_id, &row.created_at, &row.created_by_uuid).await?,
        ))
    }

    pub async fn get_latest_snapshot_headers(
        &self,
        world_id: &str,
    ) -> Result<Option<SnapshotManifest>, DbError> {
        let w = world_id.to_string();
        self.db
            .read(move |c| {
                let row = c.query_one(
                    "snapshots.latest_with_directory",
                    "SELECT id, world_id, created_at, created_by_uuid, packs_json FROM snapshots WHERE world_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                    params![w],
                    |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, String>(3)?, r.get::<_, Option<String>>(4)?)),
                )?;
                let Some((id, world_id, created_at, created_by, raw)) = row else { return Ok(None) };
                Ok(Some(load_snapshot_headers(c, &id, &world_id, &created_at, &created_by, Some(raw.as_deref()))?))
            })
            .await
    }

    pub async fn get_snapshot_headers(
        &self,
        world_id: &str,
        snapshot_id: &str,
    ) -> Result<Option<SnapshotManifest>, DbError> {
        let (w, s) = (world_id.to_string(), snapshot_id.to_string());
        self.db
            .read(move |c| {
                let row = c.query_one(
                    "snapshots.row_with_directory",
                    "SELECT id, world_id, created_at, created_by_uuid, packs_json FROM snapshots WHERE world_id = ? AND id = ?",
                    params![w, s],
                    |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, String>(3)?, r.get::<_, Option<String>>(4)?)),
                )?;
                let Some((id, world_id, created_at, created_by, raw)) = row else { return Ok(None) };
                Ok(Some(load_snapshot_headers(c, &id, &world_id, &created_at, &created_by, Some(raw.as_deref()))?))
            })
            .await
    }

    pub async fn existing_snapshot_ids(
        &self,
        world_id: &str,
        snapshot_ids: &[String],
    ) -> Result<HashSet<String>, DbError> {
        let mut ids = snapshot_ids.to_vec();
        ids.sort();
        ids.dedup();
        if ids.is_empty() {
            return Ok(HashSet::new());
        }
        let w = world_id.to_string();
        self.db
            .read(move |c| {
                Ok(c.query(
                    "snapshots.existing_ids",
                    &format!("SELECT id FROM snapshots WHERE world_id = ? AND id {IN_JSON_LIST}"),
                    params![w, json_list(&ids)],
                    |r| r.get::<_, String>(0),
                )?
                .into_iter()
                .collect())
            })
            .await
    }

    /// Headers for many snapshots of one world in a fixed number of queries.
    pub async fn get_snapshot_headers_batch(
        &self,
        world_id: &str,
        snapshot_ids: &[String],
    ) -> Result<HashMap<String, SnapshotManifest>, DbError> {
        let mut ids = snapshot_ids.to_vec();
        ids.sort();
        ids.dedup();
        if ids.is_empty() {
            return Ok(HashMap::new());
        }
        let w = world_id.to_string();
        self.db
            .read(move |c| {
                let ids_json = json_list(&ids);
                let rows = c.query(
                    "snapshots.headers_batch",
                    &format!("SELECT id, world_id, created_at, created_by_uuid, packs_json FROM snapshots WHERE world_id = ? AND id {IN_JSON_LIST}"),
                    params![w, ids_json],
                    |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, String>(3)?, r.get::<_, Option<String>>(4)?)),
                )?;
                let mut result = HashMap::new();
                if rows.is_empty() {
                    return Ok(result);
                }
                let loose = c.query(
                    "snapshot_files.loose_batch",
                    &format!("SELECT snapshot_id, {LOOSE_FILE_COLUMNS} FROM snapshot_files WHERE snapshot_id {IN_JSON_LIST} AND pack_id IS NULL ORDER BY snapshot_id ASC, path ASC"),
                    params![ids_json],
                    |r| Ok((r.get::<_, String>("snapshot_id")?, loose_file_of_row(r)?)),
                )?;
                let mut loose_by: HashMap<String, Vec<sw_contracts::ManifestFile>> = HashMap::new();
                for (sid, f) in loose {
                    loose_by.entry(sid).or_default().push(f);
                }
                for (id, world_id, created_at, created_by, raw) in rows {
                    let directory = pack_directory(c, &id, raw.as_deref())?;
                    result.insert(
                        id.clone(),
                        SnapshotManifest {
                            world_id,
                            snapshot_id: id.clone(),
                            created_at,
                            created_by_uuid: created_by,
                            files: loose_by.remove(&id).unwrap_or_default(),
                            packs: assemble_snapshot_packs(&directory, |_| Ok(vec![]), true)?,
                        },
                    );
                }
                Ok(result)
            })
            .await
    }

    /// Four fixed queries regardless of world or history size.
    pub async fn list_snapshot_summaries(
        &self,
        world_id: &str,
    ) -> Result<Vec<WorldSnapshotSummary>, DbError> {
        let w = world_id.to_string();
        self.db
            .read(move |c| {
                let Some(binding) = get_world_storage_binding_in(c, &w)? else { return Ok(vec![]) };
                struct R {
                    id: String,
                    created_at: String,
                    created_by_uuid: String,
                    data_version: Option<i64>,
                    minecraft_version: Option<String>,
                    packs_json: Option<String>,
                    loose_file_count: Option<i64>,
                    loose_total_size: Option<i64>,
                }
                let rows = c.query(
                    "snapshots.summary_rows",
                    "SELECT s.id, s.created_at, s.created_by_uuid, s.data_version, s.minecraft_version, s.packs_json, s.loose_file_count, s.loose_total_size
                     FROM snapshots s WHERE s.world_id = ? ORDER BY s.created_at DESC, s.id DESC",
                    params![w],
                    |r| {
                        Ok(R {
                            id: r.get(0)?,
                            created_at: r.get(1)?,
                            created_by_uuid: r.get(2)?,
                            data_version: r.get(3)?,
                            minecraft_version: r.get(4)?,
                            packs_json: r.get(5)?,
                            loose_file_count: r.get(6)?,
                            loose_total_size: r.get(7)?,
                        })
                    },
                )?;
                if rows.is_empty() {
                    return Ok(vec![]);
                }
                let latest_id = rows[0].id.clone();
                let legacy_ids: Vec<String> =
                    rows.iter().filter(|r| r.packs_json.is_none() || r.loose_file_count.is_none()).map(|r| r.id.clone()).collect();
                let mut legacy_loose: HashMap<String, (i64, i64)> = HashMap::new();
                let mut legacy_packs: HashMap<String, (i64, i64)> = HashMap::new();
                if !legacy_ids.is_empty() {
                    let ph = placeholders(legacy_ids.len());
                    let p: Vec<&dyn rusqlite::ToSql> = legacy_ids.iter().map(|s| s as &dyn rusqlite::ToSql).collect();
                    for (sid, n, total) in c.query(
                        "snapshot_files.legacy_loose_agg",
                        &format!("SELECT sf.snapshot_id, COUNT(*), COALESCE(SUM(sf.size), 0) FROM snapshot_files sf WHERE sf.pack_id IS NULL AND sf.snapshot_id IN ({ph}) GROUP BY sf.snapshot_id"),
                        p.as_slice(),
                        |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, i64>(2)?)),
                    )? {
                        legacy_loose.insert(sid, (n, total));
                    }
                    for (sid, n, total) in c.query(
                        "snapshot_packs.legacy_agg",
                        &format!(
                            "SELECT sp.snapshot_id, COUNT(sf.path), COALESCE(SUM(sf.size), 0)
                             FROM snapshot_packs sp
                             LEFT JOIN snapshot_files sf ON sf.snapshot_id = COALESCE(sp.members_snapshot_id, sp.snapshot_id) AND sf.pack_id = sp.pack_id
                             WHERE sp.snapshot_id IN ({ph}) GROUP BY sp.snapshot_id"
                        ),
                        p.as_slice(),
                        |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, i64>(2)?)),
                    )? {
                        legacy_packs.insert(sid, (n, total));
                    }
                }
                let stored: HashMap<String, i64> = c
                    .query(
                        "snapshots.stored_bytes",
                        "WITH referenced_keys AS (
                           SELECT sf.snapshot_id AS sid, sf.storage_key AS storage_key, MAX(sf.compressed_size) AS fallback_size
                           FROM snapshot_files sf JOIN snapshots s ON s.id = sf.snapshot_id
                           WHERE s.world_id = ? AND sf.pack_id IS NULL
                           GROUP BY sf.snapshot_id, sf.storage_key
                           UNION
                           SELECT sp.snapshot_id AS sid, sp.storage_key AS storage_key, NULL AS fallback_size
                           FROM snapshot_packs sp JOIN snapshots s ON s.id = sp.snapshot_id WHERE s.world_id = ?
                           UNION
                           SELECT s.id AS sid, json_extract(pack.value, '$.storageKey') AS storage_key, NULL AS fallback_size
                           FROM snapshots s, json_each(COALESCE(s.packs_json, '[]')) AS pack WHERE s.world_id = ?
                         ),
                         deduped_keys AS (
                           SELECT sid, storage_key, MAX(fallback_size) AS fallback_size FROM referenced_keys GROUP BY sid, storage_key
                         )
                         SELECT dk.sid AS sid, COALESCE(SUM(COALESCE(so.size, dk.fallback_size, 0)), 0) AS used
                         FROM deduped_keys dk
                         LEFT JOIN storage_objects so ON so.provider = ? AND so.storage_account_id = ? AND so.storage_key = dk.storage_key
                         GROUP BY dk.sid",
                        params![w, w, w, binding.provider.as_str(), binding.storage_account_id.clone().unwrap_or_default()],
                        |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)),
                    )?
                    .into_iter()
                    .collect();
                let mut out = Vec::with_capacity(rows.len());
                for r in rows {
                    let (file_count, total_size) = match (&r.packs_json, r.loose_file_count) {
                        (Some(raw), Some(loose_count)) => {
                            let directory: Vec<PackDirectoryEntry> = serde_json::from_str(raw)?;
                            (
                                loose_count + directory.iter().map(|e| e.member_count.unwrap_or(0)).sum::<i64>(),
                                r.loose_total_size.unwrap_or(0) + directory.iter().map(|e| e.member_total_size.unwrap_or(0)).sum::<i64>(),
                            )
                        }
                        _ => {
                            let l = legacy_loose.get(&r.id).copied().unwrap_or((0, 0));
                            let p = legacy_packs.get(&r.id).copied().unwrap_or((0, 0));
                            (l.0 + p.0, l.1 + p.1)
                        }
                    };
                    out.push(WorldSnapshotSummary {
                        is_latest: r.id == latest_id,
                        total_compressed_size: stored.get(&r.id).copied().unwrap_or(0),
                        snapshot_id: r.id,
                        created_at: r.created_at,
                        created_by_uuid: r.created_by_uuid,
                        data_version: r.data_version,
                        minecraft_version: r.minecraft_version,
                        file_count,
                        total_size,
                    });
                }
                Ok(out)
            })
            .await
    }

    pub async fn list_snapshots_for_world(&self, world_id: &str) -> Result<Vec<SnapshotRecord>, DbError> {
        let w = world_id.to_string();
        self.db
            .read(move |c| {
                c.query(
                    "snapshots.list",
                    "SELECT id, world_id, created_at, created_by_uuid FROM snapshots WHERE world_id = ? ORDER BY created_at DESC, id DESC",
                    params![w],
                    |r| Ok(SnapshotRecord { snapshot_id: r.get(0)?, world_id: r.get(1)?, created_at: r.get(2)?, created_by_uuid: r.get(3)? }),
                )
            })
            .await
    }

    pub async fn get_snapshot_game_versions(
        &self,
        world_id: &str,
        snapshot_id: &str,
    ) -> Result<Option<SnapshotGameVersions>, DbError> {
        let (w, s) = (world_id.to_string(), snapshot_id.to_string());
        self.db
            .read(move |c| {
                c.query_one(
                    "snapshots.game_versions",
                    "SELECT data_version, minecraft_version FROM snapshots WHERE world_id = ? AND id = ?",
                    params![w, s],
                    |r| Ok(SnapshotGameVersions { data_version: r.get(0)?, minecraft_version: r.get(1)? }),
                )
            })
            .await
    }

    /// CAS claim of the world's retention slot.
    pub async fn claim_retention_slot(
        &self,
        world_id: &str,
        now: time::Instant,
        interval_ms: i64,
    ) -> Result<bool, DbError> {
        let w = world_id.to_string();
        let now_iso = time::to_iso(now);
        let before = time::plus_ms_iso(now, -interval_ms);
        self.db
            .write(move |c| {
                Ok(c.execute(
                    "worlds.claim_retention",
                    "UPDATE worlds SET last_retention_at = ? WHERE id = ? AND deleted_at IS NULL AND (last_retention_at IS NULL OR last_retention_at < ?)",
                    params![now_iso, w, before],
                )? > 0)
            })
            .await
    }

    /// S1 lazy upgrade: merge chainSteps recipes into an existing snapshot's
    /// pack directory (directory-only, cache-safe).
    pub async fn stamp_snapshot_chain_steps(
        &self,
        snapshot_id: &str,
        steps_by_pack_id: HashMap<String, Vec<PackChainStep>>,
    ) -> Result<(), DbError> {
        if steps_by_pack_id.is_empty() {
            return Ok(());
        }
        let s = snapshot_id.to_string();
        self.db
            .write(move |c| {
                let raw = c
                    .query_one(
                        "snapshots.packs_json",
                        "SELECT packs_json FROM snapshots WHERE id = ?",
                        params![s],
                        |r| r.get::<_, Option<String>>(0),
                    )?
                    .flatten();
                let Some(raw) = raw else { return Ok(()) };
                // Keep the stored order; only add recipes where absent.
                let mut directory: Vec<PackDirectoryEntry> = serde_json::from_str(&raw)?;
                let mut changed = false;
                for entry in &mut directory {
                    if let Some(steps) = steps_by_pack_id.get(&entry.pack_id) {
                        if entry.chain_steps().is_none() {
                            entry.chain_steps = Some(Some(steps.clone()));
                            changed = true;
                        }
                    }
                }
                if changed {
                    c.execute(
                        "snapshots.update_packs_json",
                        "UPDATE snapshots SET packs_json = ? WHERE id = ?",
                        params![serde_json::to_string(&directory)?, s],
                    )?;
                }
                Ok(())
            })
            .await
    }

    /// One transactional batch: a failure mid-write must not leave a partial
    /// snapshot behind. `manifest_storage_key` (0027) = doc mode, zero member rows.
    pub async fn finalize_snapshot(
        &self,
        world_id: &str,
        actor: &Actor,
        request: &FinalizeSnapshotRequest,
        now: time::Instant,
        manifest_storage_key: Option<String>,
    ) -> Result<Arc<SnapshotManifest>, DbError> {
        let snapshot_id = new_id("snapshot");
        let now_iso = time::to_iso(now);
        let w = world_id.to_string();
        let player_uuid = actor.player_uuid.clone();
        let req = request.clone();
        let sid = snapshot_id.clone();
        let created_by = player_uuid.clone();
        let now2 = now_iso.clone();
        self.db
            .write(move |c| {
                let base_packs: Option<HashMap<String, PackDirectoryEntry>> =
                    match (&manifest_storage_key, &req.base_snapshot_id) {
                        (None, Some(base)) => Some(base_pack_rows_for_inheritance(c, &w, base)?),
                        _ => None,
                    };
                const FILE_INSERT: &str = "INSERT INTO snapshot_files (
                      snapshot_id, path, hash, size, compressed_size, pack_id, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                let mut directory: Vec<PackDirectoryEntry> = Vec::new();
                let mut member_inserts: Vec<(String, String, i64, i64, String, String, String, String, Option<String>, Option<String>, Option<i64>)> = Vec::new();
                for file in &req.files {
                    member_inserts.push((
                        file.path.clone(),
                        file.hash.clone(),
                        file.size,
                        file.compressed_size,
                        String::new(), // loose: no pack id
                        file.storage_key.clone(),
                        file.content_type.clone(),
                        file.transfer_mode.map(|t| t.as_str().to_string()).unwrap_or_else(|| "whole-gzip".into()),
                        file.base_snapshot_id.clone(),
                        file.base_hash.clone(),
                        file.chain_depth,
                    ));
                }
                let mut pack_inserts: Vec<(String, String, i64, String, String, String, String, Option<String>, Option<String>, Option<i64>)> = Vec::new();
                for pack in req.packs.as_deref().unwrap_or(&[]) {
                    let base = base_packs.as_ref().and_then(|m| m.get(&pack.pack_id));
                    let inherit_from = match base {
                        Some(b)
                            if pack.hash == b.hash
                                && pack.size == b.size
                                && pack.storage_key == b.storage_key
                                && pack.transfer_mode.as_str() == b.transfer_mode
                                && pack.base_snapshot_id == b.base_snapshot_id
                                && pack.base_hash == b.base_hash
                                && pack.chain_depth == b.chain_depth
                                && pack.delta_format_version == b.delta_format_version
                                && pack.delta_blob_size == b.delta_blob_size
                                && pack.chain_delta_bytes == b.chain_delta_bytes =>
                        {
                            b.members_snapshot_id.clone().or_else(|| req.base_snapshot_id.clone())
                        }
                        _ => None,
                    };
                    directory.push(PackDirectoryEntry {
                        pack_id: pack.pack_id.clone(),
                        hash: pack.hash.clone(),
                        size: pack.size,
                        storage_key: pack.storage_key.clone(),
                        transfer_mode: pack.transfer_mode.as_str().to_string(),
                        base_snapshot_id: pack.base_snapshot_id.clone(),
                        base_hash: pack.base_hash.clone(),
                        chain_depth: pack.chain_depth,
                        members_snapshot_id: inherit_from.clone(),
                        delta_format_version: pack.delta_format_version,
                        delta_blob_size: pack.delta_blob_size,
                        chain_delta_bytes: pack.chain_delta_bytes,
                        member_count: Some(pack.files.len() as i64),
                        member_total_size: Some(pack.files.iter().map(|f| f.size).sum()),
                        // Server-stamped upstream; explicit null when absent.
                        chain_steps: Some(pack.chain_steps.clone()),
                    });
                    if inherit_from.is_some() || manifest_storage_key.is_some() {
                        continue;
                    }
                    for file in &pack.files {
                        pack_inserts.push((
                            file.path.clone(),
                            file.hash.clone(),
                            file.size,
                            pack.pack_id.clone(),
                            pack.storage_key.clone(),
                            file.content_type.clone(),
                            pack.transfer_mode.as_str().to_string(),
                            pack.base_snapshot_id.clone(),
                            pack.base_hash.clone(),
                            pack.chain_depth,
                        ));
                    }
                }
                directory.sort_by(|a, b| locale_compare(&a.pack_id, &b.pack_id));
                let directory_json = serde_json::to_string(&directory)?;
                if directory_json.len() > 1_000_000 {
                    tracing::warn!(world_id = %w, bytes = directory_json.len(), packs = directory.len(), "SharedWorld pack directory unusually large");
                }
                c.execute(
                    "snapshots.insert",
                    "INSERT INTO snapshots (id, world_id, created_at, created_by_uuid, base_snapshot_id, data_version, minecraft_version, packs_json, loose_file_count, loose_total_size, manifest_storage_key)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    params![
                        sid,
                        w,
                        now2,
                        created_by,
                        req.base_snapshot_id,
                        req.data_version,
                        req.minecraft_version,
                        directory_json,
                        req.files.len() as i64,
                        req.files.iter().map(|f| f.size).sum::<i64>(),
                        manifest_storage_key
                    ],
                )?;
                for (path, hash, size, csize, _pack, key, ctype, mode, bsid, bhash, depth) in member_inserts {
                    c.execute(
                        "snapshot_files.insert",
                        FILE_INSERT,
                        params![sid, path, hash, size, csize, Option::<String>::None, key, ctype, mode, bsid, bhash, depth],
                    )?;
                }
                for (path, hash, size, pack_id, key, ctype, mode, bsid, bhash, depth) in pack_inserts {
                    c.execute(
                        "snapshot_files.insert",
                        FILE_INSERT,
                        params![sid, path, hash, size, size, pack_id, key, ctype, mode, bsid, bhash, depth],
                    )?;
                }
                Ok(())
            })
            .await?;
        // Cached loader on purpose: populates the cache while every reader is about to ask.
        self.load_snapshot_cached(&snapshot_id, world_id, &now_iso, &player_uuid).await
    }
}

/// Pack rows of a base snapshot keyed by pack id, for member-row inheritance.
fn base_pack_rows_for_inheritance(
    c: &Conn<'_>,
    world_id: &str,
    base_snapshot_id: &str,
) -> Result<HashMap<String, PackDirectoryEntry>, DbError> {
    let owned = c.query_one(
        "snapshots.base_pointer",
        "SELECT manifest_storage_key FROM snapshots WHERE id = ? AND world_id = ?",
        params![base_snapshot_id, world_id],
        |r| r.get::<_, Option<String>>(0),
    )?;
    match owned {
        None => Ok(HashMap::new()),
        // 0027 guard: a doc-format base has NO member rows; never "inherit" from it.
        Some(Some(_)) => Ok(HashMap::new()),
        Some(None) => {
            Ok(pack_directory_of(c, base_snapshot_id)?.into_iter().map(|e| (e.pack_id.clone(), e)).collect())
        }
    }
}
