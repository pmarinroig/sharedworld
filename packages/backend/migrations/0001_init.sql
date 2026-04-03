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
  owner_uuid TEXT NOT NULL,
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
  claimed_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  join_target TEXT,
  handoff_candidate_uuid TEXT,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (world_id) REFERENCES worlds(id),
  FOREIGN KEY (host_uuid) REFERENCES users(player_uuid)
);

CREATE TABLE IF NOT EXISTS handoff_waiters (
  world_id TEXT NOT NULL,
  player_uuid TEXT NOT NULL,
  player_name TEXT NOT NULL,
  waiting INTEGER NOT NULL,
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
  storage_key TEXT NOT NULL,
  content_type TEXT NOT NULL,
  PRIMARY KEY (snapshot_id, path),
  FOREIGN KEY (snapshot_id) REFERENCES snapshots(id)
);

