ALTER TABLE snapshot_files ADD COLUMN pack_id TEXT;

CREATE TABLE IF NOT EXISTS snapshot_packs (
  snapshot_id TEXT NOT NULL,
  pack_id TEXT NOT NULL,
  hash TEXT NOT NULL,
  size INTEGER NOT NULL,
  storage_key TEXT NOT NULL,
  transfer_mode TEXT NOT NULL,
  base_snapshot_id TEXT,
  base_hash TEXT,
  chain_depth INTEGER,
  PRIMARY KEY (snapshot_id, pack_id)
);
