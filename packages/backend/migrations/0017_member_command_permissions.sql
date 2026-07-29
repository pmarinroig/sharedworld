-- Per-member command permission flag: when set, the member is granted in-game
-- operator permissions (level 3) while a SharedWorld host is running the world.
ALTER TABLE world_memberships ADD COLUMN can_use_commands INTEGER NOT NULL DEFAULT 0;
