-- Autosaves resend mostly-unchanged packs; re-inserting each pack's member
-- file rows dominated D1 rows-written. When a pack is identical to the base
-- snapshot's pack, its member rows are inherited from the snapshot named
-- here instead of being re-inserted. NULL = members live under my own
-- snapshot_id (legacy rows and freshly materialized packs). Always flattened
-- to the physical holder (one hop), never a chain.
ALTER TABLE snapshot_packs ADD COLUMN members_snapshot_id TEXT;
