-- 0026: snapshot pack directory + finalize-time aggregates + retention throttle.
--
-- Pack HEADERS move from per-snapshot snapshot_packs rows into a JSON
-- directory column on the snapshots row itself: an unchanged 300-pack world
-- used to write (and later retention-delete) 300 header rows plus their
-- index entries per autosave. Member FILE rows keep the proven 0021
-- inheritance machinery unchanged. The legacy table is retained; rows
-- written by a pre-0026 worker mid-deploy stay readable (readers fall back
-- where packs_json IS NULL, and global storage-key scans union both
-- representations).
--
-- The directory also carries per-pack memberCount/memberTotalSize and the
-- snapshots row carries loose-file aggregates, so the backups screen lists
-- snapshots from O(snapshots) rows instead of a quadratic member-row join.
ALTER TABLE snapshots ADD COLUMN packs_json TEXT;
ALTER TABLE snapshots ADD COLUMN loose_file_count INTEGER;
ALTER TABLE snapshots ADD COLUMN loose_total_size INTEGER;

-- Hourly retention throttle claim (compare-and-set at finalize).
ALTER TABLE worlds ADD COLUMN last_retention_at TEXT;

-- Backfill every existing snapshot from its live rows.
UPDATE snapshots SET packs_json = COALESCE((
  SELECT json_group_array(json_object(
    'packId', sp.pack_id,
    'hash', sp.hash,
    'size', sp.size,
    'storageKey', sp.storage_key,
    'transferMode', sp.transfer_mode,
    'baseSnapshotId', sp.base_snapshot_id,
    'baseHash', sp.base_hash,
    'chainDepth', sp.chain_depth,
    'membersSnapshotId', sp.members_snapshot_id,
    'deltaFormatVersion', sp.delta_format_version,
    'deltaBlobSize', sp.delta_blob_size,
    'chainDeltaBytes', sp.chain_delta_bytes,
    'memberCount', (
      SELECT COUNT(*) FROM snapshot_files sf
      WHERE sf.snapshot_id = COALESCE(sp.members_snapshot_id, sp.snapshot_id) AND sf.pack_id = sp.pack_id
    ),
    'memberTotalSize', (
      SELECT COALESCE(SUM(sf.size), 0) FROM snapshot_files sf
      WHERE sf.snapshot_id = COALESCE(sp.members_snapshot_id, sp.snapshot_id) AND sf.pack_id = sp.pack_id
    )
  ))
  FROM snapshot_packs sp WHERE sp.snapshot_id = snapshots.id
), '[]');

UPDATE snapshots SET
  loose_file_count = (
    SELECT COUNT(*) FROM snapshot_files sf WHERE sf.snapshot_id = snapshots.id AND sf.pack_id IS NULL
  ),
  loose_total_size = (
    SELECT COALESCE(SUM(sf.size), 0) FROM snapshot_files sf WHERE sf.snapshot_id = snapshots.id AND sf.pack_id IS NULL
  );

-- Transition-window safety: the legacy referrer scan in deleteSnapshots
-- (heirs pointing at a doomed donor's member rows) had no index on
-- members_snapshot_id and read every pack row of the world.
CREATE INDEX IF NOT EXISTS idx_snapshot_packs_members_donor
  ON snapshot_packs (members_snapshot_id)
  WHERE members_snapshot_id IS NOT NULL;
