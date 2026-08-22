//! Repository: a port of the worker's `d1-repository.ts`. Same SQL, same
//! semantics, organised by area. Every method is `async` and runs its
//! statements on the pool; multi-statement reads run in one read
//! transaction, multi-statement writes in one `BEGIN IMMEDIATE` (the D1
//! `batch()` equivalent).

use std::sync::{Arc, RwLock};

use async_trait::async_trait;
use sw_contracts::{PackedManifestFile, SnapshotManifest};

use crate::error::DbError;
use crate::pool::Db;

pub mod coordinator;
pub mod membership;
pub mod pack_directory;
pub mod records;
pub mod session;
pub mod snapshot;
pub mod snapshot_delete;
pub mod snapshot_gc;
pub mod storage;
pub mod summaries;
pub mod world;

pub use pack_directory::PackDirectoryEntry;
pub use records::*;

/// 0027 manifest document (`manifest-doc.ts`): members-only projection.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotManifestDocument {
    pub format_version: i64,
    pub packs: Vec<ManifestDocumentPack>,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestDocumentPack {
    pub pack_id: String,
    pub files: Vec<PackedManifestFile>,
}

/// Resolves 0027 manifest documents from the world's storage provider.
/// `Ok(None)` only when the object genuinely does not exist.
#[async_trait]
pub trait ManifestDocumentReader: Send + Sync {
    async fn load(
        &self,
        binding: &WorldStorageBinding,
        storage_key: &str,
    ) -> Result<Option<SnapshotManifestDocument>, DbError>;
}

/// Manifest cache keyed by (world, snapshot); manifests are immutable per id.
#[async_trait]
pub trait ManifestCache: Send + Sync {
    async fn get(&self, world_id: &str, snapshot_id: &str) -> Option<Arc<SnapshotManifest>>;
    async fn put(&self, world_id: &str, snapshot_id: &str, manifest: Arc<SnapshotManifest>);
}

#[derive(Clone)]
pub struct Repository {
    pub(crate) db: Db,
    pub(crate) manifest_cache: Option<Arc<dyn ManifestCache>>,
    pub(crate) doc_reader: Arc<RwLock<Option<Arc<dyn ManifestDocumentReader>>>>,
    /// Tokens-at-rest cipher; `None` stores/reads plaintext.
    pub(crate) token_cipher: Option<Arc<crate::token_cipher::TokenCipher>>,
}

impl std::fmt::Debug for Repository {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Repository").field("db", &self.db).finish()
    }
}

impl Repository {
    pub fn new(db: Db, manifest_cache: Option<Arc<dyn ManifestCache>>) -> Self {
        Self { db, manifest_cache, doc_reader: Arc::new(RwLock::new(None)), token_cipher: None }
    }

    /// Encrypt OAuth tokens at rest with this cipher (reads decrypt; plaintext
    /// rows still read fine until `swctl encrypt-tokens` converts them).
    pub fn with_token_cipher(mut self, cipher: Arc<crate::token_cipher::TokenCipher>) -> Self {
        self.token_cipher = Some(cipher);
        self
    }

    pub fn token_cipher(&self) -> Option<Arc<crate::token_cipher::TokenCipher>> {
        self.token_cipher.clone()
    }

    /// The stored email is ciphertext at rest (like the tokens); results built
    /// by raw queries decrypt it at the repository boundary.
    pub(crate) fn decrypt_email(&self, v: Option<String>) -> Option<String> {
        storage::decrypt_opt(self.token_cipher.as_deref(), v)
    }

    pub fn db(&self) -> &Db {
        &self.db
    }

    /// 0027: attach the document resolver post-construction (the provider is
    /// built over this repository, so constructor injection would be a cycle).
    pub fn attach_manifest_document_reader(&self, reader: Arc<dyn ManifestDocumentReader>) {
        *self.doc_reader.write().unwrap() = Some(reader);
    }

    pub(crate) fn document_reader(&self) -> Option<Arc<dyn ManifestDocumentReader>> {
        self.doc_reader.read().unwrap().clone()
    }
}

/// `sqlPlaceholders(n)`.
pub(crate) fn placeholders(n: usize) -> String {
    let mut s = String::with_capacity(n * 3);
    for i in 0..n {
        if i > 0 {
            s.push_str(", ");
        }
        s.push('?');
    }
    s
}

/// D1 capped bound parameters at 100, so lists travel as one JSON array and
/// are unpacked with `json_each`. Kept: it is a fine idiom in SQLite too.
pub(crate) const IN_JSON_LIST: &str = "IN (SELECT value FROM json_each(?))";

pub(crate) fn json_list<S: AsRef<str>>(items: &[S]) -> String {
    serde_json::to_string(&items.iter().map(|s| s.as_ref()).collect::<Vec<_>>()).expect("string array")
}

/// `joinMotdLines` (`d1-support.ts`).
pub fn join_motd_lines(line1: Option<&str>, line2: Option<&str>) -> Option<String> {
    let lines: Vec<&str> = [line1.unwrap_or(""), line2.unwrap_or("")]
        .iter()
        .flat_map(|l| l.split('\n'))
        .map(|l| l.trim_end())
        .filter(|l| !l.is_empty())
        .collect();
    if lines.is_empty() {
        None
    } else {
        Some(lines.join("\n"))
    }
}

pub fn new_id(prefix: &str) -> String {
    format!("{prefix}_{}", uuid::Uuid::new_v4().simple())
}
