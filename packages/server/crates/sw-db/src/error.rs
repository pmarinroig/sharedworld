use thiserror::Error;

#[derive(Debug, Error)]
pub enum DbError {
    #[error("sqlite: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("json: {0}")]
    Json(#[from] serde_json::Error),
    #[error("database closed")]
    Closed,
    /// 0027 manifest document could not be loaded (maps to 502
    /// `snapshot_manifest_unavailable` in the HTTP layer).
    #[error("snapshot manifest unavailable: {0}")]
    ManifestUnavailable(String),
    #[error("{0}")]
    Other(String),
}

impl DbError {
    pub fn other(msg: impl Into<String>) -> Self {
        DbError::Other(msg.into())
    }
}
