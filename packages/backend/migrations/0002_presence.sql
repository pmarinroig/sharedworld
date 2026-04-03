CREATE TABLE IF NOT EXISTS world_presence (
  world_id TEXT NOT NULL,
  player_uuid TEXT NOT NULL,
  player_name TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (world_id, player_uuid),
  FOREIGN KEY (world_id) REFERENCES worlds(id),
  FOREIGN KEY (player_uuid) REFERENCES users(player_uuid)
);
