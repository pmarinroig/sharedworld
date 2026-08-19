//! Authentication: challenge/certificate login, dev auth, sessions, Mojang
//! services keys.

pub mod certificate;
pub mod service;
pub mod services_keys;

pub use service::AuthService;
