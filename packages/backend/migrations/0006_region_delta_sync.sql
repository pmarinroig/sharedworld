ALTER TABLE snapshot_files ADD COLUMN transfer_mode TEXT NOT NULL DEFAULT 'whole-gzip';
ALTER TABLE snapshot_files ADD COLUMN base_snapshot_id TEXT;
ALTER TABLE snapshot_files ADD COLUMN base_hash TEXT;
ALTER TABLE snapshot_files ADD COLUMN chain_depth INTEGER;
