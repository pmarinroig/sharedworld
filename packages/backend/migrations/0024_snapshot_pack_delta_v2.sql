-- Delta v2 metadata: format version per delta pack, the delta blob's true
-- byte size (client-reported, validated), and the server-accumulated
-- cumulative delta bytes since the chain's last full artifact. NULL
-- accumulator on legacy rows deliberately forces one full re-upload, which
-- restarts accounting cleanly and keeps v2 deltas off unaccounted v1 chains.
ALTER TABLE snapshot_packs ADD COLUMN delta_format_version INTEGER;
ALTER TABLE snapshot_packs ADD COLUMN delta_blob_size INTEGER;
ALTER TABLE snapshot_packs ADD COLUMN chain_delta_bytes INTEGER;
