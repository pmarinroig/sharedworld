-- 0028: GC retry queue. Blob deletes that fail against the provider (Drive
-- 429/5xx) used to be dropped on the floor permanently; the unreferenced-key
-- computation only ever runs once, at snapshot deletion time. Failed deletes
-- now enqueue here and are retried by a bounded opportunistic sweep on the
-- upload-session and retention paths (no cron exists; all sweeps are
-- request-driven). Rows are dropped without deleting when the key has been
-- legitimately re-referenced in the meantime (content-addressed dedupe).
CREATE TABLE IF NOT EXISTS pending_blob_deletes (
  provider TEXT NOT NULL,
  storage_account_id TEXT NOT NULL,
  storage_key TEXT NOT NULL,
  enqueued_at TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  last_attempt_at TEXT,
  PRIMARY KEY (provider, storage_account_id, storage_key)
);
