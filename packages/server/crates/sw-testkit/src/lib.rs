//! Test support for the SharedWorld server: an in-memory `ServiceContext`
//! over SQLite + the filesystem blob provider + in-process realtime, plus
//! fixtures mirroring `backend/test/support/*` (13 seeded players, owner /
//! guest / host-member contexts, a recording deferrer).

pub mod env;
pub mod fake_drive;
pub mod fixtures;
pub mod integration;
pub mod integration_drive;

pub use env::TestEnv;
pub use fake_drive::FakeDriveProvider;
pub use fixtures::*;
