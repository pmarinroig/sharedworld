ALTER TABLE worlds ADD COLUMN unclean_shutdown_runtime_epoch INTEGER;
ALTER TABLE worlds ADD COLUMN last_runtime_epoch INTEGER NOT NULL DEFAULT 0;
