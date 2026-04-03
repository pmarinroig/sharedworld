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

CREATE TABLE IF NOT EXISTS host_leases (
  world_id TEXT PRIMARY KEY,
  host_uuid TEXT NOT NULL,
  host_player_name TEXT NOT NULL,
  status TEXT NOT NULL,
  runtime_phase TEXT,
  runtime_epoch INTEGER NOT NULL DEFAULT 0,
  runtime_token TEXT,
  claimed_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  join_target TEXT,
  handoff_candidate_uuid TEXT,
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

CREATE TABLE IF NOT EXISTS handoff_waiters (
  world_id TEXT NOT NULL,
  player_uuid TEXT NOT NULL,
  player_name TEXT NOT NULL,
  waiter_session_id TEXT NOT NULL,
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
  present INTEGER NOT NULL,
  guest_session_epoch INTEGER NOT NULL,
  presence_sequence INTEGER NOT NULL,
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
  PRIMARY KEY (snapshot_id, pack_id),
  FOREIGN KEY (snapshot_id) REFERENCES snapshots(id)
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
