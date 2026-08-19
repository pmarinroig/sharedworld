//! SharedWorld domain core: services, realtime coordinator, storage
//! providers, stamps, jobs. No HTTP framework types leak in here; `sw-http`
//! adapts requests onto these APIs.

pub mod auth;
pub mod caches;
pub mod config;
pub mod http_error;
pub mod ids;
pub mod jobs;
pub mod realtime;
pub mod relay;
pub mod request;
pub mod service;
pub mod stamp;
pub mod storage;

pub use config::Config;
pub use request::RequestContext;

pub use http_error::HttpError;
pub use sw_db::time;
