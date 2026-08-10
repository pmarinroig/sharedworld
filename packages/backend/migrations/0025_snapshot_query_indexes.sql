-- Partial indexes for the hot snapshot read paths, measured against prod via
-- `wrangler d1 insights` (2026-08-10): manifest loads scanned every file row
-- of a snapshot to answer pack-member and loose-file queries (~50% / ~0%
-- efficient), and the retention delta-base walk scanned every snapshot_files
-- row of the world (eff 0.002). Each query now touches only the rows it
-- returns.
CREATE INDEX idx_snapshot_files_pack_members ON snapshot_files (snapshot_id, path) WHERE pack_id IS NOT NULL;
CREATE INDEX idx_snapshot_files_loose ON snapshot_files (snapshot_id, path) WHERE pack_id IS NULL;
CREATE INDEX idx_snapshot_files_delta_edges ON snapshot_files (snapshot_id, base_snapshot_id) WHERE base_snapshot_id IS NOT NULL;
CREATE INDEX idx_snapshot_packs_delta_edges ON snapshot_packs (snapshot_id, base_snapshot_id) WHERE base_snapshot_id IS NOT NULL;
