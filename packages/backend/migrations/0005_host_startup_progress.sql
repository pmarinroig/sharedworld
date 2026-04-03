ALTER TABLE host_leases ADD COLUMN startup_progress_label TEXT;
ALTER TABLE host_leases ADD COLUMN startup_progress_mode TEXT;
ALTER TABLE host_leases ADD COLUMN startup_progress_fraction REAL;
ALTER TABLE host_leases ADD COLUMN startup_progress_updated_at TEXT;
