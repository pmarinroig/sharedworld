-- Single-row cache of Mojang's player-certificate public key set
-- (api.minecraftservices.com/publickeys), backing certificate-based auth so a
-- login never depends on reaching Mojang synchronously.
CREATE TABLE mojang_services_keys (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  fetched_at TEXT NOT NULL,
  keys_json TEXT NOT NULL
);
