pub mod auth;
pub mod internal;
pub mod runtime;
pub mod snapshots;
pub mod storage;
pub mod testkit;
pub mod worlds;

/// Snapshot/blob routes (kept separate so the router assembly reads top-down).
pub fn extra_routes() -> Option<axum::Router<std::sync::Arc<crate::state::AppState>>> {
    Some(snapshots::routes().merge(internal::routes()))
}

use axum::extract::Path;
use sw_core::HttpError;

use crate::error::ApiError;

/// `requireParam`.
pub fn param(p: &Path<std::collections::HashMap<String, String>>, name: &str) -> Result<String, ApiError> {
    match p.get(name) {
        Some(v) if !v.is_empty() => Ok(v.clone()),
        _ => Err(ApiError(HttpError::new(400, "missing_param", format!("Missing URL parameter: {name}.")))),
    }
}

/// `decodeStorageKey` (`decodeURIComponent`).
pub fn decode_storage_key(raw: &str) -> Result<String, ApiError> {
    percent_decode(raw)
        .ok_or_else(|| ApiError(HttpError::new(400, "invalid_storage_key", "Storage key is malformed.")))
}

pub fn percent_decode(s: &str) -> Option<String> {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            if i + 2 >= bytes.len() {
                return None;
            }
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).ok()?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes() {
        assert_eq!(percent_decode("packs%2Ffull%2Fab%2Fx.pack").as_deref(), Some("packs/full/ab/x.pack"));
        assert_eq!(percent_decode("plain/key").as_deref(), Some("plain/key"));
        assert_eq!(percent_decode("%zz"), None);
        assert_eq!(percent_decode("%2"), None);
    }
}
