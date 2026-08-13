-- 0027: manifest-as-document. Pack MEMBER lists move from snapshot_files rows
-- into a content-addressed JSON document in the world's storage provider
-- (manifests/<hash[0:2]>/<hash>.json); this column is the pointer. NULL =
-- legacy row-based snapshot (readers fall back, exactly like packs_json in
-- 0026). Non-null implies zero pack-member rows for the snapshot and
-- membersSnapshotId null in every packs_json entry.
ALTER TABLE snapshots ADD COLUMN manifest_storage_key TEXT;

-- Reference leg for GC: isStorageKeyReferenced and deleteSnapshots'
-- post-delete re-check look up specific doc keys account-wide (docs are
-- content-addressed and shared across snapshots, e.g. by restore).
CREATE INDEX IF NOT EXISTS idx_snapshots_manifest_storage_key
  ON snapshots (manifest_storage_key)
  WHERE manifest_storage_key IS NOT NULL;
