//! Wire contracts shared with the Fabric mod — originally a 1:1
//! transcription of `packages/shared/src/{contracts,realtime,sync}.ts`.
//! That TS package was removed with the Cloudflare-era cleanup (2026-08);
//! this crate is now the single source of truth (the TS original lives in
//! git history under `packages/shared`).
//!
//! Field-level conventions (see `docs/server-wire.md`):
//! * TS `T | null` (required, nullable) → `Option<T>` serialized as `null`.
//! * TS `?: T` (optional, non-null) → `Option<T>` with `skip_serializing_if`.
//! * TS `?: T | null` → `Option<T>` that is omitted when `None`; the Java
//!   client (Gson records) treats absent and null identically, so this only
//!   matters for byte-parity tooling which normalizes the two.
//! * Timestamps are ISO-8601 strings exactly as `Date.prototype.toISOString`
//!   emits them (`YYYY-MM-DDTHH:MM:SS.mmmZ`); they are compared lexically in SQL.

pub mod realtime;
pub mod sync;
pub mod timing;
pub mod types;

pub use realtime::*;
pub use sync::*;
pub use timing::*;
pub use types::*;
