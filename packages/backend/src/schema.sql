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
  -- 0026: hourly retention throttle claim (compare-and-set at finalize).
  last_retention_at TEXT,
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


CREATE TABLE IF NOT EXISTS snapshots (
  id TEXT PRIMARY KEY,
  world_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  created_by_uuid TEXT NOT NULL,
  base_snapshot_id TEXT,
  data_version INTEGER,
  minecraft_version TEXT,
  -- 0026 pack directory: the snapshot's pack HEADERS as a JSON array
  -- (incl. membersSnapshotId + memberCount/memberTotalSize). NULL only on
  -- rows written by pre-0026 workers — readers fall back to snapshot_packs.
  packs_json TEXT,
  -- 0026 finalize-time aggregates over the snapshot's loose (non-pack) files.
  loose_file_count INTEGER,
  loose_total_size INTEGER,
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
  -- 0.4.0 delta v2: format version (NULL = v1), the delta blob's true byte
  -- size, and the accumulated delta bytes since the chain's last full
  -- artifact (NULL = unknown → the planner forces one full re-upload).
  delta_format_version INTEGER,
  delta_blob_size INTEGER,
  chain_delta_bytes INTEGER,
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

-- Partial indexes for the hot snapshot read paths: manifest loads split their
-- scans by pack membership, and the retention delta-base walk touches only
-- chained rows instead of every file row of the world.
CREATE INDEX IF NOT EXISTS idx_snapshot_files_pack_members ON snapshot_files (snapshot_id, path) WHERE pack_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_snapshot_files_loose ON snapshot_files (snapshot_id, path) WHERE pack_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_snapshot_files_delta_edges ON snapshot_files (snapshot_id, base_snapshot_id) WHERE base_snapshot_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_snapshot_packs_delta_edges ON snapshot_packs (snapshot_id, base_snapshot_id) WHERE base_snapshot_id IS NOT NULL;

-- 0.3.0 realtime: single-writer display mirror maintained by the world's
-- coordinator Durable Object. Summaries and legacy polls read it; nothing
-- else ever writes it. status_json = WorldRuntimeStatus, room_players_json
-- = RoomPlayer[].
CREATE TABLE IF NOT EXISTS world_runtime_mirror (
  world_id TEXT PRIMARY KEY,
  status_json TEXT,
  room_players_json TEXT,
  updated_at TEXT NOT NULL
);

-- 0.4.0 direct-to-Drive resumable uploads: one row per initiated session so
-- commit can verify against the stored session URI and expired orphans can
-- be swept opportunistically at session-init time.
CREATE TABLE IF NOT EXISTS storage_upload_sessions (
  upload_id TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  storage_account_id TEXT NOT NULL,
  world_id TEXT NOT NULL,
  storage_key TEXT NOT NULL,
  session_url TEXT NOT NULL,
  content_type TEXT NOT NULL,
  expected_size INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  confirmed_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_storage_upload_sessions_account_created
  ON storage_upload_sessions (provider, storage_account_id, confirmed_at, created_at);

-- 0026 transition-window safety: legacy referrer scan in deleteSnapshots.
CREATE INDEX IF NOT EXISTS idx_snapshot_packs_members_donor
  ON snapshot_packs (members_snapshot_id)
  WHERE members_snapshot_id IS NOT NULL;
