CREATE TABLE IF NOT EXISTS world_runtime (
  world_id TEXT PRIMARY KEY,
  host_uuid TEXT NOT NULL,
  host_player_name TEXT NOT NULL,
  runtime_phase TEXT NOT NULL,
  runtime_epoch INTEGER NOT NULL DEFAULT 0,
  runtime_token TEXT,
  claimed_at TEXT NOT NULL,
  expires_at TEXT,
  join_target TEXT,
  candidate_uuid TEXT,
  revoked_at TEXT,
  startup_deadline_at TEXT,
  runtime_token_issued_at TEXT,
  last_progress_at TEXT,
  startup_progress_label TEXT,
  startup_progress_mode TEXT,
  startup_progress_fraction REAL,
  startup_progress_updated_at TEXT,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (world_id) REFERENCES worlds(id),
  FOREIGN KEY (host_uuid) REFERENCES users(player_uuid)
);

INSERT INTO world_runtime (
  world_id,
  host_uuid,
  host_player_name,
  runtime_phase,
  runtime_epoch,
  runtime_token,
  claimed_at,
  expires_at,
  join_target,
  candidate_uuid,
  revoked_at,
  startup_deadline_at,
  runtime_token_issued_at,
  last_progress_at,
  startup_progress_label,
  startup_progress_mode,
  startup_progress_fraction,
  startup_progress_updated_at,
  updated_at
)
SELECT
  world_id,
  host_uuid,
  host_player_name,
  runtime_phase,
  runtime_epoch,
  runtime_token,
  claimed_at,
  expires_at,
  join_target,
  handoff_candidate_uuid,
  revoked_at,
  startup_deadline_at,
  runtime_token_issued_at,
  last_progress_at,
  startup_progress_label,
  startup_progress_mode,
  startup_progress_fraction,
  startup_progress_updated_at,
  updated_at
FROM host_leases
WHERE runtime_phase IN ('host-starting', 'host-live', 'host-finalizing')
  AND host_uuid IS NOT NULL
  AND host_uuid != '';

ALTER TABLE handoff_waiters ADD COLUMN waiter_session_id TEXT;

UPDATE handoff_waiters
SET waiter_session_id = COALESCE(waiter_session_id, 'legacy_' || player_uuid)
WHERE waiter_session_id IS NULL;
