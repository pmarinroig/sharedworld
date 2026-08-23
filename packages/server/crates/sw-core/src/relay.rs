//! Lane-D relay download tokens: per-step capabilities the Cloudflare relay
//! worker verifies WITHOUT calling the box. Envelope
//! `v2.<b64url claims>.<b64url ed25519 sig>`; claims
//! `{t:"rl", w, k, a, f, p, exp, dt}` where `dt` is the account's Drive
//! access token sealed with AES-256-GCM (nonce‖ciphertext, AAD = file id)
//! under a key only the box and the worker hold. The box signs with an
//! Ed25519 key; the worker holds only the public half (a worker compromise
//! cannot mint upload authority; `SIGNING_SECRET` never leaves the box).

use std::collections::HashMap;
use std::sync::Arc;

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use base64::Engine;
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use serde::{Deserialize, Serialize};
use sw_contracts::DownloadPlan;

use crate::http_error::{HttpError, HttpResult};
use crate::service::signer::RELAY_TOKEN_HEADER;
use crate::service::ServiceContext;
use crate::storage::StorageBinding;
use crate::time::{self, Instant};

pub const RELAY_TOKEN_TTL_MS: i64 = 3 * 60 * 60_000;
const VERSION: &str = "v2";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RelayClaims {
    pub t: String,
    pub w: String,
    pub k: String,
    pub a: String,
    pub f: String,
    pub p: String,
    pub exp: i64,
    pub dt: String,
}

/// What a provider hands out so the relay can fetch blobs directly.
#[derive(Debug, Clone)]
pub struct RelayGrant {
    pub access_token: String,
    /// Epoch millis the access token expires; bounds `exp` (minus slack).
    pub access_token_expires_at_ms: i64,
    /// storage key → provider file id.
    pub file_ids: HashMap<String, String>,
}

/// Provider capability: mint a short-lived direct-read grant for keys.
#[async_trait::async_trait]
pub trait RelayCapable: Send + Sync {
    async fn relay_grant(&self, binding: &StorageBinding, storage_keys: &[String]) -> HttpResult<RelayGrant>;
}

pub struct RelayKeys {
    signing: SigningKey,
    token_key: [u8; 32],
}

impl RelayKeys {
    /// Both keys are base64 (standard) 32-byte values.
    pub fn from_config(signing_b64: &str, token_key_b64: &str) -> Result<Arc<Self>, String> {
        let seed = base64::engine::general_purpose::STANDARD
            .decode(signing_b64.trim())
            .map_err(|e| format!("relay signing key: {e}"))?;
        let seed: [u8; 32] = seed.try_into().map_err(|_| "relay signing key must be 32 bytes".to_string())?;
        let tk = base64::engine::general_purpose::STANDARD
            .decode(token_key_b64.trim())
            .map_err(|e| format!("relay token key: {e}"))?;
        let token_key: [u8; 32] =
            tk.try_into().map_err(|_| "relay token key must be 32 bytes".to_string())?;
        Ok(Arc::new(Self { signing: SigningKey::from_bytes(&seed), token_key }))
    }

    pub fn generate() -> (String, String, String) {
        let mut seed = [0u8; 32];
        rand::fill(&mut seed);
        let mut tk = [0u8; 32];
        rand::fill(&mut tk);
        let signing = SigningKey::from_bytes(&seed);
        let b64 = |b: &[u8]| base64::engine::general_purpose::STANDARD.encode(b);
        (b64(&seed), b64(&tk), b64(signing.verifying_key().as_bytes()))
    }

    pub fn verifying_key_b64(&self) -> String {
        base64::engine::general_purpose::STANDARD.encode(self.signing.verifying_key().as_bytes())
    }

    fn seal(&self, plaintext: &[u8], aad: &[u8]) -> String {
        let cipher = Aes256Gcm::new((&self.token_key).into());
        let mut nonce = [0u8; 12];
        rand::fill(&mut nonce);
        let nonce_arr = Nonce::try_from(&nonce[..]).expect("12-byte nonce");
        let ct = cipher.encrypt(&nonce_arr, Payload { msg: plaintext, aad }).expect("aes-gcm");
        let mut out = nonce.to_vec();
        out.extend_from_slice(&ct);
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(out)
    }

    pub fn open(&self, sealed: &str, aad: &[u8]) -> Option<Vec<u8>> {
        let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(sealed).ok()?;
        if bytes.len() < 12 {
            return None;
        }
        let cipher = Aes256Gcm::new((&self.token_key).into());
        let nonce_arr = Nonce::try_from(&bytes[..12]).ok()?;
        cipher.decrypt(&nonce_arr, Payload { msg: &bytes[12..], aad }).ok()
    }

    pub fn mint(
        &self,
        world_id: &str,
        storage_key: &str,
        account_id: &str,
        file_id: &str,
        player_uuid: &str,
        access_token: &str,
        exp_ms: i64,
    ) -> String {
        let claims = RelayClaims {
            t: "rl".into(),
            w: world_id.into(),
            k: storage_key.into(),
            a: account_id.into(),
            f: file_id.into(),
            p: player_uuid.into(),
            exp: exp_ms,
            dt: self.seal(access_token.as_bytes(), file_id.as_bytes()),
        };
        let body = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(serde_json::to_vec(&claims).expect("json"));
        let sig = self.signing.sign(body.as_bytes());
        format!(
            "{VERSION}.{body}.{}",
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(sig.to_bytes())
        )
    }

    /// Verify with this key's public half (the worker does the same in JS).
    pub fn verify(verifying_key_b64: &str, token: &str, now: Instant) -> Option<RelayClaims> {
        let pk = base64::engine::general_purpose::STANDARD.decode(verifying_key_b64).ok()?;
        let pk: [u8; 32] = pk.try_into().ok()?;
        let vk = VerifyingKey::from_bytes(&pk).ok()?;
        let parts: Vec<&str> = token.split('.').collect();
        if parts.len() != 3 || parts[0] != VERSION {
            return None;
        }
        let sig =
            Signature::from_slice(&base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(parts[2]).ok()?)
                .ok()?;
        vk.verify(parts[1].as_bytes(), &sig).ok()?;
        let claims: RelayClaims =
            serde_json::from_slice(&base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(parts[1]).ok()?)
                .ok()?;
        if claims.t != "rl" || claims.exp <= time::to_millis(now) {
            return None;
        }
        Some(claims)
    }
}

/// Add `x-sharedworld-relay-token` to every step of a download plan when
/// relay keys are configured and the provider can grant direct reads.
/// Best-effort: a failure leaves the plan without tokens (the relay then
/// forwards the GET to the box, which serves it itself).
pub async fn attach_relay_tokens(
    svc: &ServiceContext,
    binding: &StorageBinding,
    plan: &mut DownloadPlan,
    player_uuid: &str,
) -> HttpResult<()> {
    let Some(keys) = svc.relay_keys.as_ref() else {
        tracing::info!(world_id = %plan.world_id, "relay tokens skipped: no relay keys");
        return Ok(());
    };
    let Some(relay) = svc.storage_provider.relay(binding) else {
        tracing::info!(world_id = %plan.world_id, "relay tokens skipped: provider has no relay");
        return Ok(());
    };
    let Some(account_id) = binding.storage_account_id.as_deref() else {
        tracing::info!(world_id = %plan.world_id, "relay tokens skipped: world has no storage account");
        return Ok(());
    };
    let mut storage_keys: Vec<String> = Vec::new();
    for entry in &plan.downloads {
        for step in &entry.steps {
            storage_keys.push(step.storage_key.clone());
        }
    }
    if let Some(Some(p)) = &plan.non_region_pack_download {
        for step in &p.steps {
            storage_keys.push(step.storage_key.clone());
        }
    }
    for p in plan.region_bundle_downloads.iter().flatten() {
        for step in &p.steps {
            storage_keys.push(step.storage_key.clone());
        }
    }
    storage_keys.sort();
    storage_keys.dedup();
    if storage_keys.is_empty() {
        tracing::info!(world_id = %plan.world_id, "relay tokens skipped: plan has no download steps");
        return Ok(());
    }
    tracing::info!(world_id = %plan.world_id, keys = storage_keys.len(), "relay tokens requested for download plan");
    let grant = match relay.relay_grant(binding, &storage_keys).await {
        Ok(g) => g,
        Err(e) => {
            tracing::warn!(world_id = %plan.world_id, error = %e, "relay grant unavailable; plan served without relay tokens");
            return Ok(());
        }
    };
    let now_ms = time::to_millis(time::now());
    let exp = (now_ms + RELAY_TOKEN_TTL_MS).min(grant.access_token_expires_at_ms.max(now_ms + 60_000));
    let stamp = |step: &mut sw_contracts::DownloadPlanStep| {
        if let Some(file_id) = grant.file_ids.get(&step.storage_key) {
            let token = keys.mint(
                &plan.world_id,
                &step.storage_key,
                account_id,
                file_id,
                player_uuid,
                &grant.access_token,
                exp,
            );
            step.download.headers.insert(RELAY_TOKEN_HEADER.into(), token);
        }
    };
    let world_id = plan.world_id.clone();
    let _ = &world_id;
    for entry in &mut plan.downloads {
        for step in &mut entry.steps {
            stamp(step);
        }
    }
    if let Some(Some(p)) = &mut plan.non_region_pack_download {
        for step in &mut p.steps {
            stamp(step);
        }
    }
    if let Some(list) = &mut plan.region_bundle_downloads {
        for p in list {
            for step in &mut p.steps {
                stamp(step);
            }
        }
    }
    Ok(())
}

/// Internal API for the relay worker: refresh an expired `dt` for a still
/// valid token (rare: a plan older than the access token's lifetime).
pub async fn authorize_relay_token(svc: &ServiceContext, token: &str) -> HttpResult<serde_json::Value> {
    let keys =
        svc.relay_keys.as_ref().ok_or_else(|| HttpError::new(404, "not_found", "Relay not configured."))?;
    let claims = RelayKeys::verify(&keys.verifying_key_b64(), token, time::now())
        .ok_or_else(|| HttpError::new(403, "relay_token_invalid", "Relay token is invalid or expired."))?;
    let binding = StorageBinding {
        provider: svc.storage_provider.provider(),
        storage_account_id: Some(claims.a.clone()),
    };
    let relay = svc
        .storage_provider
        .relay(&binding)
        .ok_or_else(|| HttpError::new(404, "not_found", "Relay not configured."))?;
    let grant = relay.relay_grant(&binding, std::slice::from_ref(&claims.k)).await?;
    Ok(serde_json::json!({
        "accessToken": grant.access_token,
        "expiresAtMs": grant.access_token_expires_at_ms,
        "fileId": grant.file_ids.get(&claims.k).cloned().unwrap_or(claims.f),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mint_verify_open_roundtrip() {
        let (s, t, pk) = RelayKeys::generate();
        let keys = RelayKeys::from_config(&s, &t).unwrap();
        assert_eq!(keys.verifying_key_b64(), pk);
        let now = time::now();
        let token = keys.mint("w", "k", "acct", "file-1", "p", "ya29.secret", time::to_millis(now) + 60_000);
        let claims = RelayKeys::verify(&pk, &token, now).unwrap();
        assert_eq!(claims.f, "file-1");
        assert_eq!(keys.open(&claims.dt, b"file-1").unwrap(), b"ya29.secret");
        assert!(keys.open(&claims.dt, b"other").is_none(), "AAD binds the file id");
        assert!(RelayKeys::verify(&pk, &token, now + chrono::Duration::hours(1)).is_none());
        let (_, _, other_pk) = RelayKeys::generate();
        assert!(RelayKeys::verify(&other_pk, &token, now).is_none());
        let mut tampered = token.clone();
        tampered.replace_range(3..4, if &token[3..4] == "A" { "B" } else { "A" });
        assert!(RelayKeys::verify(&pk, &tampered, now).is_none());
    }
}
