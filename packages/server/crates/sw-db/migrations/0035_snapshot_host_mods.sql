-- Box-only (0.5.4): the gameplay mods the host had installed when it wrote
-- the snapshot, as a JSON array of {id, name}. The next host compares it with
-- its own mods before opening the world (missing mods delete their blocks and
-- items on load). NULL for snapshots from clients before 0.5.4: unknown, so
-- no comparison.
ALTER TABLE snapshots ADD COLUMN host_mods_json TEXT;
