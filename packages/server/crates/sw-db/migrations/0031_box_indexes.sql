-- Box-only indexes (lane D). The worker's statements are unchanged; these
-- back the scans that the per-statement FullscanStep metric flagged under
-- load: the per-login expired-session sweep, per-world invite lookups and
-- resets, the hourly challenge prune, and the GC drain's due-scan. Plain
-- indexes only — the database stays exportable back to D1 unchanged.
CREATE INDEX IF NOT EXISTS idx_user_sessions_expires ON user_sessions (expires_at);
CREATE INDEX IF NOT EXISTS idx_user_sessions_player ON user_sessions (player_uuid);
CREATE INDEX IF NOT EXISTS idx_invite_codes_world_status_created ON invite_codes (world_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_auth_challenges_expires ON auth_challenges (expires_at);
CREATE INDEX IF NOT EXISTS idx_pending_blob_deletes_drain ON pending_blob_deletes (attempts, enqueued_at);
