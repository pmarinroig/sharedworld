-- 0030 (server): per-world coordinator state, the box-side equivalent of
-- the coordinator Durable Object's kv storage and alarm. Written only by the
-- coordinator actor (write-through, mirrored in memory), read on first touch
-- of a world after a restart.
CREATE TABLE IF NOT EXISTS coordinator_kv (
  world_id TEXT NOT NULL,
  key TEXT NOT NULL,
  value TEXT NOT NULL,
  PRIMARY KEY (world_id, key)
);

CREATE TABLE IF NOT EXISTS coordinator_alarms (
  world_id TEXT PRIMARY KEY,
  alarm_at TEXT NOT NULL
);
