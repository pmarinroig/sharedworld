//! Timing constants (`contracts.ts:1-19`). Mirrored by the Java client
//! (`test/parity/timing-constants.test.ts`).

pub const HOST_HEARTBEAT_INTERVAL_MS: i64 = 30_000;
pub const HOST_LEASE_TIMEOUT_MS: i64 = 90_000;
/// Steady-state host-LIVE lease only (see the TS doc comment: it sets the
/// coordinator's self-wake cadence; startup/finalizing stay on the 90 s one).
pub const HOST_LIVE_LEASE_TIMEOUT_MS: i64 = 150_000;
pub const HANDOFF_WAITER_TIMEOUT_MS: i64 = 120_000;
pub const PLAYER_PRESENCE_HEARTBEAT_INTERVAL_MS: i64 = 15_000;
pub const PLAYER_PRESENCE_TIMEOUT_MS: i64 = 45_000;
pub const AUTOSAVE_INTERVAL_MS: i64 = 5 * 60_000;
pub const INVITE_TTL_MS: i64 = 7 * 24 * 60 * 60_000;
pub const STORAGE_LINK_TTL_MS: i64 = 15 * 60_000;
