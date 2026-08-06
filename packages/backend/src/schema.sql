CREATE TABLE IF NOT EXISTS users (
  player_uuid TEXT PRIMARY KEY,
  player_name TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_sessions (
  token TEXT PRIMARY KEY,
  player_uuid TEXT NOT NULL,
  player_name TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  FOREIGN KEY (player_uuid) REFERENCES users(player_uuid)
);

CREATE TABLE IF NOT EXISTS auth_challenges (
  nonce TEXT PRIMARY KEY,
  expires_at TEXT NOT NULL,
  used_at TEXT
);

CREATE TABLE IF NOT EXISTS mojang_services_keys (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  fetched_at TEXT NOT NULL,
  keys_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS worlds (
  id TEXT PRIMARY KEY,
  slug TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  motd TEXT,
  custom_icon_storage_key TEXT,
  owner_uuid TEXT NOT NULL,
  storage_provider TEXT NOT NULL DEFAULT 'google-drive',
  storage_account_id TEXT,
  unclean_shutdown_host_uuid TEXT,
  unclean_shutdown_host_player_name TEXT,
  unclean_shutdown_phase TEXT,
  unclean_shutdown_runtime_epoch INTEGER,
  unclean_shutdown_recorded_at TEXT,
  last_runtime_epoch INTEGER NOT NULL DEFAULT 0,
  settings TEXT,
  settings_revision INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  deleted_at TEXT,
  FOREIGN KEY (owner_uuid) REFERENCES users(player_uuid)
);

CREATE TABLE IF NOT EXISTS world_memberships (
  world_id TEXT NOT NULL,
  player_uuid TEXT NOT NULL,
  player_name TEXT NOT NULL,
  role TEXT NOT NULL,
  joined_at TEXT NOT NULL,
  deleted_at TEXT,
  can_use_commands INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (world_id, player_uuid),
  FOREIGN KEY (world_id) REFERENCES worlds(id),
  FOREIGN KEY (player_uuid) REFERENCES users(player_uuid)
);

CREATE TABLE IF NOT EXISTS invite_codes (
  id TEXT PRIMARY KEY,
  world_id TEXT NOT NULL,
  code TEXT NOT NULL UNIQUE,
  created_by_uuid TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  redeemed_by_uuid TEXT,
  redeemed_at TEXT,
  status TEXT NOT NULL,
  FOREIGN KEY (world_id) REFERENCES worlds(id),
  FOREIGN KEY (created_by_uuid) REFERENCES users(player_uuid)
);

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
  host_minecraft_version TEXT,
  FOREIGN KEY (world_id) REFERENCES worlds(id),
  FOREIGN KEY (host_uuid) REFERENCES users(player_uuid)
);

CREATE TABLE IF NOT EXISTS handoff_waiters (
  world_id TEXT NOT NULL,
  player_uuid TEXT NOT NULL,
  player_name TEXT NOT NULL,
  -- Added by ALTER TABLE in migration 0010, which cannot declare NOT NULL;
  -- schema.sql mirrors what migrated production databases actually enforce.
  waiter_session_id TEXT,
  waiting INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (world_id, player_uuid),
  FOREIGN KEY (world_id) REFERENCES worlds(id),
  FOREIGN KEY (player_uuid) REFERENCES users(player_uuid)
);

CREATE TABLE IF NOT EXISTS world_presence (
  world_id TEXT NOT NULL,
  player_uuid TEXT NOT NULL,
  player_name TEXT NOT NULL,
  -- Defaults mirror the ALTER TABLE migrations (0002/0013) that added these
  -- columns on production databases; inserts always set them explicitly.
  present INTEGER NOT NULL DEFAULT 1,
  guest_session_epoch INTEGER NOT NULL DEFAULT 0,
  presence_sequence INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (world_id, player_uuid),
  FOREIGN KEY (world_id) REFERENCES worlds(id),
  FOREIGN KEY (player_uuid) REFERENCES users(player_uuid)
);

CREATE TABLE IF NOT EXISTS snapshots (
  id TEXT PRIMARY KEY,
  world_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  created_by_uuid TEXT NOT NULL,
  base_snapshot_id TEXT,
  data_version INTEGER,
  minecraft_version TEXT,
  FOREIGN KEY (world_id) REFERENCES worlds(id),
  FOREIGN KEY (created_by_uuid) REFERENCES users(player_uuid)
);

CREATE TABLE IF NOT EXISTS snapshot_files (
  snapshot_id TEXT NOT NULL,
  path TEXT NOT NULL,
  hash TEXT NOT NULL,
  size INTEGER NOT NULL,
  compressed_size INTEGER NOT NULL,
  pack_id TEXT,
  storage_key TEXT NOT NULL,
  content_type TEXT NOT NULL,
  transfer_mode TEXT NOT NULL DEFAULT 'whole-gzip',
  base_snapshot_id TEXT,
  base_hash TEXT,
  chain_depth INTEGER,
  PRIMARY KEY (snapshot_id, path),
  FOREIGN KEY (snapshot_id) REFERENCES snapshots(id)
);

CREATE TABLE IF NOT EXISTS snapshot_packs (
  snapshot_id TEXT NOT NULL,
  pack_id TEXT NOT NULL,
  hash TEXT NOT NULL,
  size INTEGER NOT NULL,
  storage_key TEXT NOT NULL,
  transfer_mode TEXT NOT NULL,
  base_snapshot_id TEXT,
  base_hash TEXT,
  chain_depth INTEGER,
  -- When a pack is identical to the base snapshot's pack, its member
  -- snapshot_files rows are inherited from the snapshot named here instead
  -- of being re-inserted. NULL = members live under my own snapshot_id.
  -- Always flattened to the physical holder (one hop), never a chain.
  members_snapshot_id TEXT,
  -- No snapshots(id) foreign key: the migration that created this table never
  -- declared one, so production does not enforce it; schema.sql matches.
  PRIMARY KEY (snapshot_id, pack_id)
);

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

CREATE INDEX IF NOT EXISTS idx_storage_accounts_owner ON storage_accounts (owner_player_uuid);

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

CREATE INDEX IF NOT EXISTS idx_snapshot_files_storage_key ON snapshot_files (storage_key);
CREATE INDEX IF NOT EXISTS idx_snapshot_packs_storage_key ON snapshot_packs (storage_key);
CREATE INDEX IF NOT EXISTS idx_snapshots_world_created ON snapshots (world_id, created_at);
CREATE INDEX IF NOT EXISTS idx_world_memberships_player ON world_memberships (player_uuid);
