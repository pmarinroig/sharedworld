//! Blob-authority stamps (`service/blob-stamp.ts`): HMAC-SHA256 claims,
//! envelope `v1.<b64url claims>.<b64url mac>`, signed with `signing_secret`,
//! verified against it and `signing_secret_previous`.

use base64::Engine;
use ring::hmac;
use serde::{Deserialize, Serialize};

use crate::time::{self, Instant};

pub const BLOB_STAMP_TTL_MS: i64 = 60 * 60_000;
pub const DOWNLOAD_STAMP_TTL_MS: i64 = 3 * 60 * 60_000;
const STAMP_VERSION: &str = "v1";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct BlobStampClaims {
    pub w: String,
    pub e: i64,
    pub k: String,
    pub exp: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DownloadStampClaims {
    pub t: String,
    pub w: String,
    pub k: String,
    pub p: String,
    pub exp: i64,
}

#[derive(Clone, Default)]
pub struct StampKeys {
    pub current: Option<String>,
    pub previous: Option<String>,
}

impl StampKeys {
    pub fn new(current: Option<String>, previous: Option<String>) -> Self {
        Self { current: current.filter(|s| !s.is_empty()), previous: previous.filter(|s| !s.is_empty()) }
    }
}

fn b64url(bytes: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

fn b64url_decode(text: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(text).ok()
}

fn sign_claims<T: Serialize>(secret: &str, payload: &T) -> String {
    let body = b64url(serde_json::to_string(payload).expect("json").as_bytes());
    let key = hmac::Key::new(hmac::HMAC_SHA256, secret.as_bytes());
    let tag = hmac::sign(&key, body.as_bytes());
    format!("{STAMP_VERSION}.{body}.{}", b64url(tag.as_ref()))
}

/// Envelope check only: authentic under a configured secret and well-formed JSON.
fn open_claims(keys: &StampKeys, stamp: &str) -> Option<serde_json::Map<String, serde_json::Value>> {
    let parts: Vec<&str> = stamp.split('.').collect();
    if parts.len() != 3 || parts[0] != STAMP_VERSION {
        return None;
    }
    let body = parts[1];
    let signature = b64url_decode(parts[2])?;
    let authentic = [&keys.current, &keys.previous].into_iter().flatten().any(|secret| {
        let key = hmac::Key::new(hmac::HMAC_SHA256, secret.as_bytes());
        hmac::verify(&key, body.as_bytes(), &signature).is_ok()
    });
    if !authentic {
        return None;
    }
    let payload = b64url_decode(body)?;
    match serde_json::from_slice::<serde_json::Value>(&payload).ok()? {
        serde_json::Value::Object(m) => Some(m),
        _ => None,
    }
}

fn unexpired(claims: &serde_json::Map<String, serde_json::Value>, now: Instant) -> bool {
    claims.get("exp").and_then(|v| v.as_f64()).is_some_and(|exp| exp > time::to_millis(now) as f64)
}

pub fn mint_blob_stamp(
    keys: &StampKeys,
    world_id: &str,
    runtime_epoch: i64,
    storage_key: &str,
    now: Instant,
) -> Option<String> {
    let secret = keys.current.as_deref()?;
    Some(sign_claims(
        secret,
        &BlobStampClaims {
            w: world_id.into(),
            e: runtime_epoch,
            k: storage_key.into(),
            exp: time::to_millis(now) + BLOB_STAMP_TTL_MS,
        },
    ))
}

/// Returns the stamped runtime epoch when authentic, unexpired, an upload
/// stamp and scoped to this world+key. Callers still check the epoch against
/// the live runtime.
pub fn verify_blob_stamp(
    keys: &StampKeys,
    stamp: &str,
    world_id: &str,
    storage_key: &str,
    now: Instant,
) -> Option<i64> {
    let claims = open_claims(keys, stamp)?;
    if !unexpired(&claims, now) || claims.contains_key("t") {
        return None;
    }
    if claims.get("w").and_then(|v| v.as_str()) != Some(world_id)
        || claims.get("k").and_then(|v| v.as_str()) != Some(storage_key)
    {
        return None;
    }
    let epoch = claims.get("e")?.as_i64()?;
    if epoch < 0 {
        return None;
    }
    Some(epoch)
}

pub fn mint_download_stamp(
    keys: &StampKeys,
    world_id: &str,
    storage_key: &str,
    player_uuid: &str,
    now: Instant,
) -> Option<String> {
    let secret = keys.current.as_deref()?;
    Some(sign_claims(
        secret,
        &DownloadStampClaims {
            t: "dl".into(),
            w: world_id.into(),
            k: storage_key.into(),
            p: player_uuid.into(),
            exp: time::to_millis(now) + DOWNLOAD_STAMP_TTL_MS,
        },
    ))
}

pub fn verify_download_stamp(
    keys: &StampKeys,
    stamp: &str,
    world_id: &str,
    storage_key: &str,
    player_uuid: &str,
    now: Instant,
) -> bool {
    let Some(claims) = open_claims(keys, stamp) else { return false };
    if !unexpired(&claims, now) || claims.get("t").and_then(|v| v.as_str()) != Some("dl") {
        return false;
    }
    claims.get("w").and_then(|v| v.as_str()) == Some(world_id)
        && claims.get("k").and_then(|v| v.as_str()) == Some(storage_key)
        && claims.get("p").and_then(|v| v.as_str()) == Some(player_uuid)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_and_rotation() {
        let keys = StampKeys::new(Some("s1".into()), None);
        let now = time::now();
        let stamp = mint_blob_stamp(&keys, "w", 3, "k", now).unwrap();
        assert!(stamp.starts_with("v1."));
        assert_eq!(verify_blob_stamp(&keys, &stamp, "w", "k", now), Some(3));
        assert_eq!(verify_blob_stamp(&keys, &stamp, "w", "other", now), None);
        assert_eq!(verify_blob_stamp(&keys, &stamp, "w", "k", now + chrono::Duration::hours(2)), None);
        let rotated = StampKeys::new(Some("s2".into()), Some("s1".into()));
        assert_eq!(verify_blob_stamp(&rotated, &stamp, "w", "k", now), Some(3));
        let gone = StampKeys::new(Some("s2".into()), None);
        assert_eq!(verify_blob_stamp(&gone, &stamp, "w", "k", now), None);
        let dl = mint_download_stamp(&keys, "w", "k", "p", now).unwrap();
        assert!(verify_download_stamp(&keys, &dl, "w", "k", "p", now));
        assert!(!verify_download_stamp(&keys, &dl, "w", "k", "q", now));
        assert_eq!(verify_blob_stamp(&keys, &dl, "w", "k", now), None, "cross-kind rejected");
        assert!(mint_blob_stamp(&StampKeys::default(), "w", 1, "k", now).is_none());
    }
}
