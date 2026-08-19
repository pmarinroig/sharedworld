//! Mojang player-certificate public keys (`auth/services-keys.ts`). Pins
//! from config win; else the cached row (seeded by tooling or, on the box,
//! self-refreshed from `api.minecraftservices.com/publickeys`).

use std::sync::Arc;

use sw_db::Repository;

use super::certificate::decode_base64_field;
use crate::http_error::{HttpError, HttpResult};
use crate::time;

pub struct ServicesKeyProvider {
    repo: Repository,
    pinned: Option<String>,
    self_refresh: bool,
    publickeys_url: String,
    http: reqwest::Client,
}

#[derive(serde::Deserialize)]
struct PublicKeysDoc {
    #[serde(default, rename = "playerCertificateKeys")]
    player_certificate_keys: Vec<KeyEntry>,
}

#[derive(serde::Deserialize)]
struct KeyEntry {
    #[serde(default, rename = "publicKey")]
    public_key: Option<String>,
}

fn unavailable() -> HttpError {
    HttpError::new(
        503,
        "identity_verification_unavailable",
        "Minecraft's key registry is not available to the SharedWorld server right now. Please try again in a minute.",
    )
}

impl ServicesKeyProvider {
    pub fn new(
        repo: Repository,
        pinned: Option<String>,
        self_refresh: bool,
        publickeys_url: String,
    ) -> Arc<Self> {
        Arc::new(Self {
            repo,
            pinned: pinned.map(|s| s.trim().to_string()).filter(|s| !s.is_empty()),
            self_refresh,
            publickeys_url,
            http: reqwest::Client::builder()
                .user_agent("sharedworld-server")
                .build()
                .expect("reqwest client"),
        })
    }

    pub async fn player_certificate_keys(&self) -> HttpResult<Vec<Vec<u8>>> {
        if let Some(pinned) = &self.pinned {
            return pinned.split(',').map(|e| decode_one(e.trim())).collect();
        }
        if let Some(cached) = self.repo.get_mojang_services_keys().await? {
            return parse_keys_json(&cached.keys_json);
        }
        if self.self_refresh {
            if let Ok(keys) = self.refresh().await {
                return Ok(keys);
            }
        }
        tracing::error!("SharedWorld Mojang publickeys cache is empty; seed it (swctl seed-mojang-keys) or enable self refresh");
        Err(unavailable())
    }

    /// Fetch the key set from Mojang and store it; returns the DER keys.
    pub async fn refresh(&self) -> HttpResult<Vec<Vec<u8>>> {
        let doc: PublicKeysDoc = self
            .http
            .get(&self.publickeys_url)
            .send()
            .await
            .map_err(|e| {
                tracing::warn!(error = %e, "SharedWorld Mojang publickeys fetch failed");
                unavailable()
            })?
            .error_for_status()
            .map_err(|e| {
                tracing::warn!(error = %e, "SharedWorld Mojang publickeys fetch rejected");
                unavailable()
            })?
            .json()
            .await
            .map_err(|_| unavailable())?;
        let keys: Vec<String> = doc
            .player_certificate_keys
            .into_iter()
            .filter_map(|k| k.public_key)
            .filter(|k| !k.is_empty())
            .collect();
        if keys.is_empty() {
            return Err(unavailable());
        }
        let keys_json = serde_json::to_string(&keys).expect("json");
        self.repo.put_mojang_services_keys(&time::now_iso(), &keys_json).await?;
        tracing::info!(count = keys.len(), "SharedWorld Mojang player-certificate keys refreshed");
        parse_keys_json(&keys_json)
    }
}

fn parse_keys_json(keys_json: &str) -> HttpResult<Vec<Vec<u8>>> {
    let keys: Vec<String> = serde_json::from_str(keys_json).map_err(|_| unavailable())?;
    keys.iter().map(|k| decode_one(k)).collect()
}

fn decode_one(value: &str) -> HttpResult<Vec<u8>> {
    decode_base64_field(
        value,
        "identity_verification_unavailable",
        "Minecraft's key registry returned an unusable key set.",
        503,
    )
}
