-- 0.1.7: owner-managed world settings (JSON, applied by the active host) and
-- an index for resolving a player's linked storage account across worlds.
ALTER TABLE worlds ADD COLUMN settings TEXT;
ALTER TABLE worlds ADD COLUMN settings_revision INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_storage_accounts_owner ON storage_accounts (owner_player_uuid);
