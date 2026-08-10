-- Direct-to-Drive resumable uploads: one row per initiated session so commit
-- can verify against the stored session URI and expired orphans can be swept.
CREATE TABLE storage_upload_sessions (
  upload_id TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  storage_account_id TEXT NOT NULL,
  world_id TEXT NOT NULL,
  storage_key TEXT NOT NULL,
  session_url TEXT NOT NULL,
  content_type TEXT NOT NULL,
  expected_size INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  confirmed_at TEXT
);

CREATE INDEX idx_storage_upload_sessions_account_created
  ON storage_upload_sessions (provider, storage_account_id, confirmed_at, created_at);
