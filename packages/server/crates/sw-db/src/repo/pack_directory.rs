//! The 0026 pack directory (`snapshots.packs_json`) and the single
//! directory→`SnapshotPack` mapper shared by the row- and document-based
//! member sources.

use rusqlite::{params, Row};
use sw_contracts::{FileTransferMode, ManifestFile, PackChainStep, PackedManifestFile, SnapshotPack};

use crate::collate::locale_compare;
use crate::error::DbError;
use crate::pool::Conn;

/// One pack header inside `packs_json`. Field names are the manifest's own
/// camelCase; `memberCount`/`memberTotalSize` are finalize-time aggregates
/// (null on entries derived from legacy `snapshot_packs` rows).
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PackDirectoryEntry {
    pub pack_id: String,
    pub hash: String,
    pub size: i64,
    pub storage_key: String,
    pub transfer_mode: String,
    pub base_snapshot_id: Option<String>,
    pub base_hash: Option<String>,
    pub chain_depth: Option<i64>,
    pub members_snapshot_id: Option<String>,
    pub delta_format_version: Option<i64>,
    pub delta_blob_size: Option<i64>,
    pub chain_delta_bytes: Option<i64>,
    pub member_count: Option<i64>,
    pub member_total_size: Option<i64>,
    /// Absent on legacy entries (omitted, not null, so their manifests stay
    /// byte-identical); explicit `null` on finalized entries without a recipe.
    #[serde(default, skip_serializing_if = "Option::is_none", with = "sw_contracts::double_option")]
    pub chain_steps: Option<Option<Vec<PackChainStep>>>,
}

impl PackDirectoryEntry {
    pub fn chain_steps(&self) -> Option<&Vec<PackChainStep>> {
        self.chain_steps.as_ref().and_then(|s| s.as_ref())
    }
}

pub(crate) fn sort_directory(mut directory: Vec<PackDirectoryEntry>) -> Vec<PackDirectoryEntry> {
    directory.sort_by(|a, b| locale_compare(&a.pack_id, &b.pack_id));
    directory
}

pub(crate) fn parse_directory(raw: &str) -> Result<Vec<PackDirectoryEntry>, DbError> {
    Ok(sort_directory(serde_json::from_str(raw)?))
}

fn legacy_pack_row_to_entry(r: &Row<'_>) -> rusqlite::Result<PackDirectoryEntry> {
    Ok(PackDirectoryEntry {
        pack_id: r.get("pack_id")?,
        hash: r.get("hash")?,
        size: r.get("size")?,
        storage_key: r.get("storage_key")?,
        transfer_mode: r.get("transfer_mode")?,
        base_snapshot_id: r.get("base_snapshot_id")?,
        base_hash: r.get("base_hash")?,
        chain_depth: r.get("chain_depth")?,
        members_snapshot_id: r.get("members_snapshot_id")?,
        delta_format_version: r.get("delta_format_version")?,
        delta_blob_size: r.get("delta_blob_size")?,
        chain_delta_bytes: r.get("chain_delta_bytes")?,
        member_count: None,
        member_total_size: None,
        chain_steps: None,
    })
}

/// The snapshot's pack headers: from `packs_json`, or the legacy
/// `snapshot_packs` rows where the directory is absent. Sorted by pack id.
pub(crate) fn pack_directory(
    c: &Conn<'_>,
    snapshot_id: &str,
    raw_packs_json: Option<&str>,
) -> Result<Vec<PackDirectoryEntry>, DbError> {
    if let Some(raw) = raw_packs_json {
        return parse_directory(raw);
    }
    c.query(
        "snapshot_packs.directory",
        "SELECT pack_id, hash, size, storage_key, transfer_mode, base_snapshot_id, base_hash, chain_depth, members_snapshot_id,
                delta_format_version, delta_blob_size, chain_delta_bytes
         FROM snapshot_packs WHERE snapshot_id = ? ORDER BY pack_id ASC",
        params![snapshot_id],
        legacy_pack_row_to_entry,
    )
}

pub(crate) fn pack_directory_of(c: &Conn<'_>, snapshot_id: &str) -> Result<Vec<PackDirectoryEntry>, DbError> {
    let raw = c
        .query_one(
            "snapshots.packs_json",
            "SELECT packs_json FROM snapshots WHERE id = ?",
            params![snapshot_id],
            |r| r.get::<_, Option<String>>(0),
        )?
        .flatten();
    pack_directory(c, snapshot_id, raw.as_deref())
}

/// `assembleSnapshotPacks`: whatever produced the members, the assembled
/// pack must be shape- and order-identical.
pub(crate) fn assemble_snapshot_packs<F>(
    directory: &[PackDirectoryEntry],
    mut members_for: F,
    include_chain_steps: bool,
) -> Result<Vec<SnapshotPack>, DbError>
where
    F: FnMut(&PackDirectoryEntry) -> Result<Vec<PackedManifestFile>, DbError>,
{
    directory
        .iter()
        .map(|e| {
            Ok(SnapshotPack {
                pack_id: e.pack_id.clone(),
                hash: e.hash.clone(),
                size: e.size,
                storage_key: e.storage_key.clone(),
                transfer_mode: FileTransferMode::parse(&e.transfer_mode)
                    .unwrap_or(FileTransferMode::PackFull),
                base_snapshot_id: e.base_snapshot_id.clone(),
                base_hash: e.base_hash.clone(),
                chain_depth: e.chain_depth,
                delta_format_version: e.delta_format_version,
                delta_blob_size: e.delta_blob_size,
                chain_delta_bytes: e.chain_delta_bytes,
                // Backend-internal (headers path only, never cached or served).
                chain_steps: if include_chain_steps { e.chain_steps().cloned() } else { None },
                files: members_for(e)?,
            })
        })
        .collect()
}

pub(crate) fn loose_file_of_row(r: &Row<'_>) -> rusqlite::Result<ManifestFile> {
    Ok(ManifestFile {
        path: r.get("path")?,
        hash: r.get("hash")?,
        size: r.get("size")?,
        compressed_size: r.get("compressed_size")?,
        storage_key: r.get("storage_key")?,
        content_type: r.get("content_type")?,
        transfer_mode: Some(
            FileTransferMode::parse(
                &r.get::<_, Option<String>>("transfer_mode")?.unwrap_or_else(|| "whole-gzip".into()),
            )
            .unwrap_or(FileTransferMode::WholeGzip),
        ),
        base_snapshot_id: r.get("base_snapshot_id")?,
        base_hash: r.get("base_hash")?,
        chain_depth: r.get("chain_depth")?,
    })
}

pub(crate) const LOOSE_FILE_COLUMNS: &str =
    "path, hash, size, compressed_size, storage_key, content_type, transfer_mode, base_snapshot_id, base_hash, chain_depth";
