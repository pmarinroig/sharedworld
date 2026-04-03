ALTER TABLE worlds ADD COLUMN storage_provider TEXT NOT NULL DEFAULT 'google-drive';
ALTER TABLE worlds ADD COLUMN storage_account_id TEXT;

CREATE TABLE IF NOT EXISTS storage_accounts (
  id TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  owner_player_uuid TEXT NOT NULL,
  external_account_id TEXT NOT NULL,
  email TEXT,
  display_name TEXT,
  access_token TEXT,
  refresh_token TEXT,
  token_expires_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS storage_link_sessions (
  id TEXT PRIMARY KEY,
  player_uuid TEXT NOT NULL,
  provider TEXT NOT NULL,
  status TEXT NOT NULL,
  auth_url TEXT NOT NULL,
  state TEXT NOT NULL,
  linked_account_email TEXT,
  account_display_name TEXT,
  storage_account_id TEXT,
  error_message TEXT,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  completed_at TEXT
);

CREATE TABLE IF NOT EXISTS storage_objects (
  provider TEXT NOT NULL,
  storage_account_id TEXT NOT NULL,
  storage_key TEXT NOT NULL,
  object_id TEXT NOT NULL,
  content_type TEXT NOT NULL,
  size INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (provider, storage_account_id, storage_key)
);
