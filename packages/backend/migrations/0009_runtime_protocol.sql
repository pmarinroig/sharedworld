ALTER TABLE host_leases ADD COLUMN runtime_phase TEXT;
ALTER TABLE host_leases ADD COLUMN runtime_epoch INTEGER NOT NULL DEFAULT 0;
ALTER TABLE host_leases ADD COLUMN runtime_token TEXT;
ALTER TABLE host_leases ADD COLUMN startup_deadline_at TEXT;
ALTER TABLE host_leases ADD COLUMN runtime_token_issued_at TEXT;
ALTER TABLE host_leases ADD COLUMN last_progress_at TEXT;

UPDATE host_leases
SET runtime_phase = CASE
  WHEN status = 'hosting' AND join_target IS NOT NULL THEN 'host-live'
  WHEN status = 'hosting' THEN 'host-starting'
  WHEN status = 'finalizing' THEN 'host-finalizing'
  WHEN status = 'handoff' THEN 'handoff-waiting'
  ELSE 'idle'
END
WHERE runtime_phase IS NULL;
