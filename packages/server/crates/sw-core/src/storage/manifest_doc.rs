//! Manifest-as-document (0027, `manifest-doc.ts`): the snapshot's pack MEMBER
//! lists live in one content-addressed JSON object in the world's own storage
//! provider instead of per-file rows. The document deliberately carries NO
//! snapshot identity and NO pack headers: headers stay solely in the pack
//! directory (one source of truth, readable without a provider round-trip),
//! and an identity-free document hashes identically for identical content,
//! so a restore, whose members are unchanged, reuses the existing object at
//! zero cost instead of uploading a duplicate.
//!
//! Canonical bytes are the JSON serialization of the document with packs
//! sorted by packId (`localeCompare`) and files by path; matching the
//! ordering the legacy row loader produces, because assembled manifests must
//! stay byte-identical per snapshot id (the manifest cache assumes
//! immutability).

use std::sync::Arc;

use async_trait::async_trait;
use sha2::{Digest, Sha256};
use sw_contracts::{PackedManifestFile, SnapshotPack};
use sw_db::collate::locale_compare;
use sw_db::repo::{
    ManifestDocumentPack, ManifestDocumentReader, SnapshotManifestDocument, WorldStorageBinding,
};
use sw_db::DbError;

use crate::http_error::{HttpError, HttpResult};
use crate::storage::StorageProvider;

pub const MANIFEST_DOCUMENT_FORMAT_VERSION: i64 = 1;

const DEFAULT_CONTENT_TYPE: &str = "application/octet-stream";

pub struct BuiltManifestDocument {
    pub bytes: Vec<u8>,
    pub storage_key: String,
}

pub fn manifest_document_storage_key(hash: &str) -> String {
    // JS `slice(0, 2)` on an ASCII hex digest; tolerate shorter strings.
    let end = hash.char_indices().nth(2).map(|(i, _)| i).unwrap_or(hash.len());
    format!("manifests/{}/{}.json", &hash[..end], hash)
}

/// Members-only projection of the finalize request's packs, canonicalized.
pub fn build_manifest_document(packs: &[SnapshotPack]) -> BuiltManifestDocument {
    let mut ordered: Vec<&SnapshotPack> = packs.iter().collect();
    ordered.sort_by(|a, b| locale_compare(&a.pack_id, &b.pack_id));
    let document = SnapshotManifestDocument {
        format_version: MANIFEST_DOCUMENT_FORMAT_VERSION,
        packs: ordered
            .into_iter()
            .map(|pack| {
                let mut files: Vec<PackedManifestFile> = pack
                    .files
                    .iter()
                    .map(|file| PackedManifestFile {
                        path: file.path.clone(),
                        hash: file.hash.clone(),
                        size: file.size,
                        content_type: if file.content_type.is_empty() {
                            DEFAULT_CONTENT_TYPE.to_string()
                        } else {
                            file.content_type.clone()
                        },
                    })
                    .collect();
                // Plain byte order, exactly like the TS `a.path < b.path`.
                files.sort_by(|a, b| a.path.cmp(&b.path));
                ManifestDocumentPack { pack_id: pack.pack_id.clone(), files }
            })
            .collect(),
    };
    let bytes = serde_json::to_vec(&document).expect("manifest document json");
    let storage_key = manifest_document_storage_key(&sha256_hex(&bytes));
    BuiltManifestDocument { bytes, storage_key }
}

pub fn parse_manifest_document(bytes: &[u8]) -> HttpResult<SnapshotManifestDocument> {
    let parsed: serde_json::Value = serde_json::from_slice(bytes)
        .map_err(|_| manifest_unavailable("Snapshot manifest document is not valid JSON."))?;
    // A future format version must never be silently misread as empty member
    // lists; that would corrupt download plans, not 404 them.
    if parsed.get("formatVersion").and_then(serde_json::Value::as_i64)
        != Some(MANIFEST_DOCUMENT_FORMAT_VERSION)
        || !parsed.get("packs").is_some_and(serde_json::Value::is_array)
    {
        return Err(manifest_unavailable("Snapshot manifest document has an unsupported format."));
    }
    serde_json::from_value(parsed)
        .map_err(|_| manifest_unavailable("Snapshot manifest document has an unsupported format."))
}

pub fn manifest_unavailable(message: impl Into<String>) -> HttpError {
    HttpError::new(502, "snapshot_manifest_unavailable", message)
}

/// Loads a snapshot's manifest document. Returns `None` only when the object
/// genuinely does not exist; transport failures propagate (the provider's
/// `get()` carries its own retry ladder for response establishment).
pub struct ProviderManifestDocumentReader {
    provider: Arc<dyn StorageProvider>,
}

impl ProviderManifestDocumentReader {
    pub fn new(provider: Arc<dyn StorageProvider>) -> Self {
        Self { provider }
    }
}

#[async_trait]
impl ManifestDocumentReader for ProviderManifestDocumentReader {
    async fn load(
        &self,
        binding: &WorldStorageBinding,
        storage_key: &str,
    ) -> Result<Option<SnapshotManifestDocument>, DbError> {
        let Some(blob) = self.provider.get(binding, storage_key, None).await.map_err(db_error)? else {
            return Ok(None);
        };
        // First call site that buffers a StoredBlob: manifest documents are
        // ~100KB-2MB, far under memory limits (world blobs keep streaming
        // through the relay untouched).
        let bytes = blob.into_bytes().await.map_err(db_error)?;
        parse_manifest_document(&bytes).map(Some).map_err(db_error)
    }
}

fn db_error(error: HttpError) -> DbError {
    if error.code == "snapshot_manifest_unavailable" {
        DbError::ManifestUnavailable(error.message)
    } else {
        DbError::other(error.to_string())
    }
}

fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    digest.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use sw_contracts::FileTransferMode;

    fn pack(pack_id: &str, files: Vec<PackedManifestFile>) -> SnapshotPack {
        SnapshotPack {
            pack_id: pack_id.to_string(),
            hash: "h".into(),
            size: 1,
            storage_key: "packs/full/h.pack".into(),
            transfer_mode: FileTransferMode::PackFull,
            base_snapshot_id: None,
            base_hash: None,
            chain_depth: None,
            delta_format_version: None,
            delta_blob_size: None,
            chain_delta_bytes: None,
            chain_steps: None,
            files,
        }
    }

    fn member(path: &str) -> PackedManifestFile {
        PackedManifestFile {
            path: path.into(),
            hash: format!("hash-{path}"),
            size: 10,
            content_type: "application/octet-stream".into(),
        }
    }

    #[test]
    fn canonical_bytes_are_order_independent_and_content_addressed() {
        let a = build_manifest_document(&[
            pack("region-bundle-r.0.0", vec![member("region/r.0.0.mca")]),
            pack("non-region", vec![member("session.lock"), member("level.dat")]),
        ]);
        let b = build_manifest_document(&[
            pack("non-region", vec![member("level.dat"), member("session.lock")]),
            pack("region-bundle-r.0.0", vec![member("region/r.0.0.mca")]),
        ]);
        assert_eq!(a.storage_key, b.storage_key);
        assert_eq!(a.bytes, b.bytes);
        let hash = a.storage_key.trim_start_matches("manifests/");
        let (prefix, rest) = hash.split_once('/').unwrap();
        let hex = rest.trim_end_matches(".json");
        assert_eq!(prefix.len(), 2);
        assert_eq!(hex.len(), 64);
        assert!(hex.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()));
        assert!(hex.starts_with(prefix));

        let parsed = parse_manifest_document(&a.bytes).unwrap();
        assert_eq!(
            parsed.packs.iter().map(|p| p.pack_id.as_str()).collect::<Vec<_>>(),
            vec!["non-region", "region-bundle-r.0.0"]
        );
        assert_eq!(
            parsed.packs[0].files.iter().map(|f| f.path.as_str()).collect::<Vec<_>>(),
            vec!["level.dat", "session.lock"]
        );
    }

    #[test]
    fn field_order_matches_the_worker_bytes() {
        let doc = build_manifest_document(&[pack("non-region", vec![member("level.dat")])]);
        assert_eq!(
            String::from_utf8(doc.bytes).unwrap(),
            r#"{"formatVersion":1,"packs":[{"packId":"non-region","files":[{"path":"level.dat","hash":"hash-level.dat","size":10,"contentType":"application/octet-stream"}]}]}"#
        );
    }

    #[test]
    fn an_unknown_format_version_fails_loud() {
        let bytes = br#"{"formatVersion":999,"packs":[]}"#;
        let error = parse_manifest_document(bytes).unwrap_err();
        assert_eq!(error.status, 502);
        assert_eq!(error.code, "snapshot_manifest_unavailable");
        assert!(error.message.contains("unsupported format"));
    }

    #[test]
    fn broken_json_and_missing_packs_fail_loud() {
        let error = parse_manifest_document(b"not json").unwrap_err();
        assert!(error.message.contains("not valid JSON"));
        let error = parse_manifest_document(br#"{"formatVersion":1}"#).unwrap_err();
        assert!(error.message.contains("unsupported format"));
    }
}
