-- 0.3.0: runtime truth moves into the per-world coordinator Durable Object.
-- D1 keeps a single-writer display mirror for world summaries and legacy
-- polling reads. The old runtime tables are dropped at the end of this
-- migration; any runtime rows live at deploy time are intentionally reset
-- (active hosts re-enter and re-claim — one-time 0.3.0 deploy semantics).
CREATE TABLE IF NOT EXISTS world_runtime_mirror (
  world_id TEXT PRIMARY KEY,
  status_json TEXT,
  room_players_json TEXT,
  updated_at TEXT NOT NULL
);

DROP TABLE IF EXISTS world_runtime;
DROP TABLE IF EXISTS handoff_waiters;
DROP TABLE IF EXISTS world_presence;
