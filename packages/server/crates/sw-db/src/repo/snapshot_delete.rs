//! `deleteSnapshots`: row deletion with member-row promotion to the oldest
//! surviving heir, then account-scoped unreferenced-key resolution.

use std::collections::{HashMap, HashSet};

use rusqlite::params;

use super::pack_directory::PackDirectoryEntry;
use super::records::*;
use super::snapshot_gc::{referenced_storage_keys_in, ResolvedInMemory};
use super::{json_list, Repository, IN_JSON_LIST};
use crate::error::DbError;
use crate::time;

impl Repository {
    pub async fn delete_snapshots(
        &self,
        world_id: &str,
        snapshot_ids: &[String],
    ) -> Result<SnapshotDeletionResult, DbError> {
        if snapshot_ids.is_empty() {
            return Ok(SnapshotDeletionResult::default());
        }
        let w = world_id.to_string();
        let ids = snapshot_ids.to_vec();
        // Phase 1 (one write txn): load, promote, delete rows.
        struct Phase1 {
            deleted: Vec<String>,
            candidates: Vec<String>,
            scope: StorageReferenceScope,
            directories: Vec<(String, Option<Vec<PackDirectoryEntry>>)>,
            loaded_at: String,
        }
        let w1 = w.clone();
        let p1 = self
            .db
            .write(move |c| {
                let deleted_rows = c.query(
                    "snapshots.delete_targets",
                    &format!("SELECT id, manifest_storage_key FROM snapshots WHERE world_id = ? AND id {IN_JSON_LIST}"),
                    params![w1, json_list(&ids)],
                    |r| Ok((r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?)),
                )?;
                let deleted: Vec<String> = deleted_rows.iter().map(|(id, _)| id.clone()).collect();
                let doomed_docs: Vec<String> = deleted_rows.iter().filter_map(|(_, k)| k.clone()).collect();
                if deleted.is_empty() {
                    return Ok(None);
                }
                let deleted_json = json_list(&deleted);
                let doomed: HashSet<String> = deleted.iter().cloned().collect();
                let candidate_rows = c.query(
                    "snapshot_files.keys_of",
                    &format!("SELECT DISTINCT storage_key FROM snapshot_files WHERE snapshot_id {IN_JSON_LIST}"),
                    params![deleted_json],
                    |r| r.get::<_, String>(0),
                )?;
                let pack_candidate_rows = c.query(
                    "snapshot_packs.keys_of",
                    &format!("SELECT DISTINCT storage_key FROM snapshot_packs WHERE snapshot_id {IN_JSON_LIST}"),
                    params![deleted_json],
                    |r| r.get::<_, String>(0),
                )?;
                let binding = c.query_one(
                    "worlds.binding_any",
                    "SELECT storage_provider, storage_account_id FROM worlds WHERE id = ?",
                    params![w1],
                    |r| Ok((r.get::<_, Option<String>>(0)?, r.get::<_, Option<String>>(1)?)),
                )?;
                let scope = StorageReferenceScope {
                    provider: provider_of(&binding.as_ref().and_then(|b| b.0.clone()).unwrap_or_default()),
                    storage_account_id: binding.and_then(|b| b.1),
                    snapshots_created_since: None,
                };
                let loaded_at = time::now_iso();
                let world_rows = c.query(
                    "snapshots.world_directories",
                    "SELECT id, packs_json FROM snapshots WHERE world_id = ? ORDER BY created_at ASC, id ASC",
                    params![w1],
                    |r| Ok((r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?)),
                )?;
                let mut directories: Vec<(String, Option<Vec<PackDirectoryEntry>>)> = Vec::with_capacity(world_rows.len());
                for (id, raw) in world_rows {
                    let dir = match raw {
                        None => None,
                        Some(raw) => Some(serde_json::from_str::<Vec<PackDirectoryEntry>>(&raw)?),
                    };
                    directories.push((id, dir));
                }
                let dir_of = |id: &str| -> &[PackDirectoryEntry] {
                    directories.iter().find(|(i, _)| i == id).and_then(|(_, d)| d.as_deref()).unwrap_or(&[])
                };
                let mut candidates: Vec<String> = Vec::new();
                {
                    let mut seen = HashSet::new();
                    let mut push = |k: String| {
                        if seen.insert(k.clone()) {
                            candidates.push(k);
                        }
                    };
                    for k in candidate_rows {
                        push(k);
                    }
                    for k in pack_candidate_rows {
                        push(k);
                    }
                    for id in &deleted {
                        for e in dir_of(id) {
                            push(e.storage_key.clone());
                        }
                    }
                    for id in &deleted {
                        for e in dir_of(id) {
                            for step in e.chain_steps().into_iter().flatten() {
                                push(step.storage_key.clone());
                            }
                        }
                    }
                    for k in doomed_docs {
                        push(k);
                    }
                }
                // Member-row promotion.
                let legacy_referrers = c.query(
                    "snapshot_packs.legacy_referrers",
                    &format!(
                        "SELECT sp.snapshot_id, sp.pack_id, sp.members_snapshot_id FROM snapshot_packs sp JOIN snapshots s ON s.id = sp.snapshot_id
                         WHERE s.world_id = ? AND sp.members_snapshot_id {IN_JSON_LIST} AND sp.snapshot_id NOT {IN_JSON_LIST}"
                    ),
                    params![w1, deleted_json, deleted_json],
                    |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?)),
                )?;
                #[derive(Clone)]
                struct Referrer {
                    snapshot_id: String,
                    pack_id: String,
                    donor_id: String,
                    legacy: bool,
                }
                let mut referrers: Vec<Referrer> = Vec::new();
                for (sid, dir) in &directories {
                    if doomed.contains(sid) {
                        continue;
                    }
                    for e in dir.as_deref().unwrap_or(&[]) {
                        if let Some(m) = &e.members_snapshot_id {
                            if doomed.contains(m) {
                                referrers.push(Referrer { snapshot_id: sid.clone(), pack_id: e.pack_id.clone(), donor_id: m.clone(), legacy: false });
                            }
                        }
                    }
                    for (lsid, pid, donor) in &legacy_referrers {
                        if lsid == sid {
                            referrers.push(Referrer { snapshot_id: sid.clone(), pack_id: pid.clone(), donor_id: donor.clone(), legacy: true });
                        }
                    }
                }
                let mut promotion_target: HashMap<(String, String), String> = HashMap::new();
                let mut rewritten: HashSet<String> = HashSet::new();
                let mut dir_map: HashMap<String, Vec<PackDirectoryEntry>> =
                    directories.iter().filter_map(|(id, d)| d.clone().map(|d| (id.clone(), d))).collect();
                for r in &referrers {
                    let key = (r.donor_id.clone(), r.pack_id.clone());
                    let target = match promotion_target.get(&key) {
                        Some(t) => t.clone(),
                        None => {
                            promotion_target.insert(key.clone(), r.snapshot_id.clone());
                            c.execute(
                                "snapshot_files.promote",
                                "INSERT INTO snapshot_files (snapshot_id, path, hash, size, compressed_size, pack_id, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth)
                                 SELECT ?, path, hash, size, compressed_size, pack_id, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth
                                 FROM snapshot_files WHERE snapshot_id = ? AND pack_id = ?",
                                params![r.snapshot_id, r.donor_id, r.pack_id],
                            )?;
                            r.snapshot_id.clone()
                        }
                    };
                    let new_pointer: Option<String> = if r.snapshot_id == target { None } else { Some(target.clone()) };
                    if r.legacy {
                        c.execute(
                            "snapshot_packs.repoint",
                            "UPDATE snapshot_packs SET members_snapshot_id = ? WHERE snapshot_id = ? AND pack_id = ? AND members_snapshot_id = ?",
                            params![new_pointer, r.snapshot_id, r.pack_id, r.donor_id],
                        )?;
                    } else if let Some(dir) = dir_map.get_mut(&r.snapshot_id) {
                        if let Some(e) = dir.iter_mut().find(|e| e.pack_id == r.pack_id) {
                            if e.members_snapshot_id.as_deref() == Some(r.donor_id.as_str()) {
                                e.members_snapshot_id = new_pointer;
                                rewritten.insert(r.snapshot_id.clone());
                            }
                        }
                    }
                }
                for sid in &rewritten {
                    c.execute(
                        "snapshots.update_packs_json",
                        "UPDATE snapshots SET packs_json = ? WHERE id = ?",
                        params![serde_json::to_string(dir_map.get(sid).map(|v| v.as_slice()).unwrap_or(&[]))?, sid],
                    )?;
                }
                c.execute("snapshot_files.delete_of", &format!("DELETE FROM snapshot_files WHERE snapshot_id {IN_JSON_LIST}"), params![deleted_json])?;
                c.execute("snapshot_packs.delete_of", &format!("DELETE FROM snapshot_packs WHERE snapshot_id {IN_JSON_LIST}"), params![deleted_json])?;
                c.execute(
                    "snapshots.delete",
                    &format!("DELETE FROM snapshots WHERE world_id = ? AND id {IN_JSON_LIST}"),
                    params![w1, deleted_json],
                )?;
                // Keep the post-promotion directories for the in-memory resolution.
                let directories: Vec<(String, Option<Vec<PackDirectoryEntry>>)> =
                    directories.into_iter().map(|(id, _)| { let d = dir_map.remove(&id); (id, d) }).collect();
                Ok(Some(Phase1 { deleted, candidates, scope, directories, loaded_at }))
            })
            .await?;
        let Some(p1) = p1 else { return Ok(SnapshotDeletionResult::default()) };
        if p1.candidates.is_empty() {
            return Ok(SnapshotDeletionResult {
                deleted_snapshot_ids: p1.deleted,
                unreferenced_storage_keys: vec![],
            });
        }
        // Phase 2: resolve this world's survivors in memory, ask SQL only
        // about what memory cannot see.
        let doomed: HashSet<String> = p1.deleted.iter().cloned().collect();
        let candidate_set: HashSet<String> = p1.candidates.iter().cloned().collect();
        let mut still_referenced: HashSet<String> = HashSet::new();
        for (sid, dir) in &p1.directories {
            if doomed.contains(sid) {
                continue;
            }
            for e in dir.as_deref().unwrap_or(&[]) {
                if candidate_set.contains(&e.storage_key) {
                    still_referenced.insert(e.storage_key.clone());
                }
                for step in e.chain_steps().into_iter().flatten() {
                    if candidate_set.contains(&step.storage_key) {
                        still_referenced.insert(step.storage_key.clone());
                    }
                }
            }
        }
        let unresolved: Vec<String> =
            p1.candidates.iter().filter(|k| !still_referenced.contains(*k)).cloned().collect();
        let scope = p1.scope.clone();
        let resolved = ResolvedInMemory { world_id: w.clone(), loaded_at: p1.loaded_at.clone() };
        let from_sql = self
            .db
            .read(move |c| referenced_storage_keys_in(c, &unresolved, Some(&scope), Some(&resolved)))
            .await?;
        still_referenced.extend(from_sql);
        let mut unreferenced: Vec<String> =
            p1.candidates.iter().filter(|k| !still_referenced.contains(*k)).cloned().collect();
        unreferenced.sort();
        Ok(SnapshotDeletionResult {
            deleted_snapshot_ids: p1.deleted,
            unreferenced_storage_keys: unreferenced,
        })
    }
}
