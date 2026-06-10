-- The legacy host_leases table predates the world_runtime protocol record and is no
-- longer read or written by any code path.
DROP TABLE IF EXISTS host_leases;
