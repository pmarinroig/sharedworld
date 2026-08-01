-- Indexes for the hot query paths that previously full-scanned:
--   snapshot_files/snapshot_packs(storage_key)  — blob reference checks before delete/GC
--   snapshots(world_id, created_at)             — latest-snapshot lookup on every session entry
--   world_memberships(player_uuid)              — listWorldsForPlayer joins on the non-leading PK column
CREATE INDEX IF NOT EXISTS idx_snapshot_files_storage_key ON snapshot_files (storage_key);
CREATE INDEX IF NOT EXISTS idx_snapshot_packs_storage_key ON snapshot_packs (storage_key);
CREATE INDEX IF NOT EXISTS idx_snapshots_world_created ON snapshots (world_id, created_at);
CREATE INDEX IF NOT EXISTS idx_world_memberships_player ON world_memberships (player_uuid);
