//! SQLite persistence for SharedWorld: connection pool with a single writer
//! thread, instrumented statements, migrations, and the repository (a port
//! of the worker's `d1-repository.ts`; same SQL, same semantics).

pub mod collate;
pub mod error;
pub mod migrate;
pub mod pool;
pub mod repo;
pub mod time;
pub mod token_cipher;

pub use error::DbError;
pub use pool::{Conn, Db, DbOptions};
pub use repo::Repository;
pub use token_cipher::TokenCipher;
