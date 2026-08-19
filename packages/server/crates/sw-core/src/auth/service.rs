//! `AuthDomainService` (`auth/service.ts`).

use std::sync::Arc;

use sw_contracts::{
    AuthChallenge, AuthCompleteCertRequest, DevAuthCompleteRequest, DevSessionToken, SessionToken,
};
use sw_db::repo::{AuthChallengeRecord, UserRecord};
use sw_db::Repository;

use super::certificate::*;
use super::services_keys::ServicesKeyProvider;
use crate::config::Config;
use crate::http_error::{HttpError, HttpResult};
use crate::ids::{random_id, random_server_id};
use crate::time::{self, Instant};

pub struct AuthService {
    repo: Repository,
    config: Arc<Config>,
    keys: Arc<ServicesKeyProvider>,
}

fn valid_player_name(name: &str) -> bool {
    // /^\w{1,16}$/ — JS \w = [A-Za-z0-9_]
    (1..=16).contains(&name.len()) && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
}

impl AuthService {
    pub fn new(repo: Repository, config: Arc<Config>) -> Self {
        let keys = ServicesKeyProvider::new(
            repo.clone(),
            config.mojang_player_certificate_keys.clone(),
            config.mojang_keys_self_refresh,
            config.mojang_publickeys_url.clone(),
        );
        Self { repo, config, keys }
    }

    pub fn services_keys(&self) -> Arc<ServicesKeyProvider> {
        self.keys.clone()
    }

    pub async fn create_challenge(&self, now: Instant) -> HttpResult<AuthChallenge> {
        let challenge = AuthChallengeRecord {
            server_id: random_server_id(),
            expires_at: time::plus_ms_iso(now, 5 * 60_000),
            used_at: None,
        };
        self.repo.create_challenge(challenge.clone()).await?;
        Ok(AuthChallenge { server_id: challenge.server_id, expires_at: challenge.expires_at })
    }

    /// Legacy ≤0.2.1 flow: terminal update notice.
    pub fn complete_auth(&self) -> HttpError {
        HttpError::new(
            403,
            "identity_verification_failed",
            "Minecraft no longer accepts the sign-in method used by SharedWorld 0.2.1 and older. Please update SharedWorld to the latest version.",
        )
    }

    pub async fn complete_cert_auth(
        &self,
        request: &AuthCompleteCertRequest,
        now: Instant,
    ) -> HttpResult<SessionToken> {
        match self.complete_cert_auth_checked(request, now).await {
            Ok(s) => Ok(s),
            Err(e) => {
                // The only line production logs have for "is a real certificate being rejected?".
                tracing::warn!(code = e.code, status = e.status, player_name = %request.player_name, player_uuid = %request.player_uuid, "SharedWorld certificate auth rejected");
                Err(e)
            }
        }
    }

    async fn complete_cert_auth_checked(
        &self,
        request: &AuthCompleteCertRequest,
        now: Instant,
    ) -> HttpResult<SessionToken> {
        let challenge = self.repo.get_challenge(&request.server_id).await?.ok_or_else(|| {
            HttpError::new(404, "challenge_not_found", "Authentication challenge not found.")
        })?;
        if challenge.used_at.is_some() {
            return Err(HttpError::new(
                409,
                "challenge_used",
                "Authentication challenge has already been used.",
            ));
        }
        if time::parse_iso(&challenge.expires_at).is_none_or(|t| t < now) {
            return Err(HttpError::new(410, "challenge_expired", "Authentication challenge has expired."));
        }
        let player_uuid = request.player_uuid.to_lowercase();
        let player_name = request.player_name.clone();
        if !valid_player_name(&player_name) {
            return Err(HttpError::new(
                400,
                "invalid_player_name",
                "Player name is not a valid Minecraft name.",
            ));
        }
        let public_key_der = decode_base64_field(
            &request.public_key,
            "certificate_invalid",
            "Minecraft profile certificate is invalid.",
            403,
        )?;
        let key_signature = decode_base64_field(
            &request.key_signature,
            "certificate_invalid",
            "Minecraft profile certificate is invalid.",
            403,
        )?;
        let nonce_signature = decode_base64_field(
            &request.nonce_signature,
            "signature_invalid",
            "Challenge signature is invalid.",
            403,
        )?;
        let expires_ms = request.public_key_expires_at_ms.as_f64().filter(|v| v.is_finite());
        let Some(expires_ms) = expires_ms.filter(|v| *v >= time::to_millis(now) as f64) else {
            return Err(HttpError::new(
                403,
                "certificate_expired",
                "Your Minecraft profile keys have expired. Restart the game to refresh them and try again.",
            ));
        };
        let payload = build_certificate_signed_payload(&player_uuid, expires_ms as i64, &public_key_der)?;
        let services_keys = self.keys.player_certificate_keys().await?;
        if !verify_certificate_signature(&payload, &key_signature, &services_keys) {
            return Err(HttpError::new(
                403,
                "certificate_invalid",
                "Minecraft profile certificate is not validly signed for this player.",
            ));
        }
        if !verify_nonce_signature(&public_key_der, &request.server_id, &nonce_signature) {
            return Err(HttpError::new(
                403,
                "signature_invalid",
                "Challenge signature does not match the certified profile key.",
            ));
        }
        let created_at = time::to_iso(now);
        let session = self.session_token(&player_uuid, &player_name, now);
        self.repo.mark_challenge_used(&request.server_id, &created_at).await?;
        self.repo
            .upsert_user(UserRecord {
                player_uuid: player_uuid.clone(),
                player_name: player_name.clone(),
                created_at,
            })
            .await?;
        self.repo.create_session(session.clone()).await?;
        Ok(session)
    }

    pub async fn complete_dev_auth(
        &self,
        request: &DevAuthCompleteRequest,
        now: Instant,
    ) -> HttpResult<DevSessionToken> {
        if !self.config.allow_dev_auth {
            return Err(HttpError::new(404, "not_found", "Route not found."));
        }
        if request.secret != self.config.dev_auth_secret.clone().unwrap_or_default() {
            return Err(HttpError::new(
                403,
                "invalid_dev_auth",
                "SharedWorld developer auth secret is invalid.",
            ));
        }
        let created_at = time::to_iso(now);
        let session = self.session_token(&request.player_uuid, &request.player_name, now);
        self.repo
            .upsert_user(UserRecord {
                player_uuid: request.player_uuid.clone(),
                player_name: request.player_name.clone(),
                created_at,
            })
            .await?;
        self.repo.create_session(session.clone()).await?;
        Ok(DevSessionToken { session, allow_insecure_e4mc: self.config.allow_dev_insecure_e4mc })
    }

    pub async fn get_session(&self, token: &str) -> HttpResult<Option<SessionToken>> {
        Ok(self.repo.get_session(token).await?)
    }

    fn session_token(&self, player_uuid: &str, player_name: &str, now: Instant) -> SessionToken {
        SessionToken {
            token: random_id("session"),
            player_uuid: player_uuid.to_string(),
            player_name: player_name.to_string(),
            expires_at: time::plus_ms_iso(now, self.config.session_ttl_hours * 60 * 60_000),
        }
    }
}
