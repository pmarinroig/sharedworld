-- Cross-version guardrail: record the Minecraft data/version a snapshot was
-- written with, and the live host's Minecraft version, so clients can refuse
-- downgrade-opening newer worlds and explain version mismatches to guests.
ALTER TABLE snapshots ADD COLUMN data_version INTEGER;
ALTER TABLE snapshots ADD COLUMN minecraft_version TEXT;
ALTER TABLE world_runtime ADD COLUMN host_minecraft_version TEXT;
