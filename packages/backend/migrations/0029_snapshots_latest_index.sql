-- 0029: latest-snapshot lookups. The world-list and world-details paths (and
-- their ETag change-facts, i.e. the 60s 304 poll) resolved "latest snapshot
-- per world" with a ROW_NUMBER() window that read EVERY snapshot of every
-- member world on each call — measured at ~53% of all D1 rows read/day. The
-- replacement is a correlated `ORDER BY created_at DESC, id DESC LIMIT 1`
-- per world; SQLite only turns that into a 1-row reverse index walk when the
-- index also carries the `id` tiebreak (otherwise it sorts the partition in a
-- temp b-tree). This index supersedes idx_snapshots_world_created (same
-- prefix), which is dropped so each snapshot insert stops paying for both.
CREATE INDEX IF NOT EXISTS idx_snapshots_world_created_id ON snapshots (world_id, created_at, id);
DROP INDEX IF EXISTS idx_snapshots_world_created;
