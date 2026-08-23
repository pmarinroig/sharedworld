//! Storage link (Google OAuth) flow (`storage/link-service.ts`).

use std::sync::Arc;

use sw_contracts::{
    CreateStorageLinkRequest, StorageAccountSummary, StorageLinkCompleteRequest, StorageLinkSession,
    StorageLinkStatus, StorageProviderType, STORAGE_LINK_TTL_MS,
};
use sw_db::repo::{StorageAccountRecord, StorageLinkSessionRecord, StorageLinkSessionUpdate};
use sw_db::Repository;

use crate::config::Config;
use crate::http_error::{HttpError, HttpResult};
use crate::ids::random_id;
use crate::request::RequestContext;
use crate::time::{self, Instant};

const DRIVE_APPDATA_SCOPE: &str = "https://www.googleapis.com/auth/drive.appdata";
const EXPIRED_MESSAGE: &str = "The Google Drive sign-in took too long. Start it again from Minecraft.";

#[derive(Clone)]
pub struct StorageLinkService {
    repo: Repository,
    config: Arc<Config>,
    provider: StorageProviderType,
    http: reqwest::Client,
}

struct OAuthPayload {
    sub: String,
    email: Option<String>,
    access_token: String,
    refresh_token: Option<String>,
    expires_at: String,
}

#[derive(serde::Deserialize)]
struct TokenResponse {
    access_token: String,
    #[serde(default)]
    refresh_token: Option<String>,
    #[serde(default)]
    expires_in: Option<f64>,
    #[serde(default)]
    scope: Option<String>,
}

#[derive(serde::Deserialize)]
struct UserInfo {
    sub: String,
    #[serde(default)]
    email: Option<String>,
}

fn summarize(s: &StorageLinkSessionRecord) -> StorageLinkSession {
    StorageLinkSession {
        id: s.id.clone(),
        provider: s.provider,
        status: s.status,
        auth_url: s.auth_url.clone(),
        expires_at: s.expires_at.clone(),
        linked_account_email: s.linked_account_email.clone(),
        error_message: s.error_message.clone(),
    }
}

fn expired(s: &StorageLinkSessionRecord, now: Instant) -> bool {
    time::parse_iso(&s.expires_at).is_none_or(|t| t < now)
}

/// Google's granular consent: an absent `scope` means granted (RFC 6749); a
/// present one must contain the Drive scope.
fn require_drive_appdata_scope(granted: Option<&str>) -> HttpResult<()> {
    match granted {
        None => Ok(()),
        Some(s) if s.split_whitespace().any(|x| x == DRIVE_APPDATA_SCOPE) => Ok(()),
        Some(_) => Err(HttpError::new(
            409,
            "storage_link_needs_consent",
            "Google didn't grant SharedWorld access to its app folder in your Drive. Return to Minecraft, connect again, and tick the Drive access checkbox on the Google screen.",
        )),
    }
}

/// The S3 bring-your-own-bucket link form, as posted from the browser page.
#[derive(Debug, Clone, Default, serde::Deserialize)]
pub struct S3LinkForm {
    #[serde(default)]
    pub endpoint: String,
    #[serde(default)]
    pub region: String,
    #[serde(default)]
    pub bucket: String,
    #[serde(default)]
    pub access_key_id: String,
    #[serde(default)]
    pub secret_access_key: String,
    #[serde(default)]
    pub key_prefix: String,
}

/// SSRF guard for the user-supplied endpoint: https only, origin only (no
/// path/query/credentials), and no obviously-internal hosts — unless the dev
/// flag allows local MinIO. Returns the normalized origin.
pub fn validate_s3_endpoint(raw: &str, allow_insecure: bool) -> Result<String, String> {
    let trimmed = raw.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return Err(
            "Enter the S3 endpoint URL (for example https://<accountid>.r2.cloudflarestorage.com).".into()
        );
    }
    let url = url::Url::parse(trimmed).map_err(|_| "The endpoint is not a valid URL.".to_string())?;
    match url.scheme() {
        "https" => {}
        "http" if allow_insecure => {}
        "http" => return Err("The endpoint must use https.".into()),
        _ => return Err("The endpoint must be an http(s) URL.".into()),
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err("The endpoint must not contain credentials.".into());
    }
    if url.path() != "/" && !url.path().is_empty() {
        return Err("Enter just the endpoint origin, without a path (the bucket has its own field).".into());
    }
    if url.query().is_some() || url.fragment().is_some() {
        return Err("Enter just the endpoint origin, without query parameters.".into());
    }
    let Some(host) = url.host() else {
        return Err("The endpoint is missing a host.".into());
    };
    if !allow_insecure && is_internal_host(&host) {
        return Err("The endpoint must be a public address reachable from the internet.".into());
    }
    let port = url.port().map(|p| format!(":{p}")).unwrap_or_default();
    Ok(format!("{}://{}{port}", url.scheme(), url.host_str().unwrap_or_default()))
}

fn is_internal_host(host: &url::Host<&str>) -> bool {
    match host {
        url::Host::Domain(d) => {
            let d = d.to_ascii_lowercase();
            d == "localhost" || d.ends_with(".localhost") || d.ends_with(".local") || d.ends_with(".internal")
        }
        url::Host::Ipv4(ip) => {
            ip.is_loopback()
                || ip.is_private()
                || ip.is_link_local()
                || ip.is_unspecified()
                // CGNAT 100.64/10 (Tailscale addresses land here).
                || (ip.octets()[0] == 100 && (64..128).contains(&ip.octets()[1]))
        }
        url::Host::Ipv6(ip) => {
            ip.is_loopback()
                || ip.is_unspecified()
                // fc00::/7 unique-local + fe80::/10 link-local.
                || (ip.segments()[0] & 0xfe00) == 0xfc00
                || (ip.segments()[0] & 0xffc0) == 0xfe80
        }
    }
}

/// `state` round-trips as `<sessionId>:<nonce>` (or the bare nonce).
fn require_matching_state(session: &StorageLinkSessionRecord, presented: Option<&str>) -> HttpResult<()> {
    let nonce = presented.map(|p| p.strip_prefix(&format!("{}:", session.id)).unwrap_or(p));
    match nonce {
        Some(n) if !n.is_empty() && n == session.state => Ok(()),
        _ => Err(HttpError::new(
            403,
            "storage_link_state_mismatch",
            "This Google Drive sign-in could not be verified. Start it again from Minecraft.",
        )),
    }
}

fn form_encode(pairs: &[(&str, &str)]) -> String {
    pairs
        .iter()
        .map(|(k, v)| format!("{}={}", form_component(k), form_component(v)))
        .collect::<Vec<_>>()
        .join("&")
}

/// `URLSearchParams` encoding: application/x-www-form-urlencoded (space → `+`).
fn form_component(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'*' | b'-' | b'.' | b'_' => out.push(b as char),
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

impl StorageLinkService {
    pub fn new(
        repo: Repository,
        config: Arc<Config>,
        provider: StorageProviderType,
        http: reqwest::Client,
    ) -> Self {
        Self { repo, config, provider, http }
    }

    fn redirect_uri(&self) -> String {
        self.config.google_oauth_redirect_uri.clone().unwrap_or_else(|| {
            format!(
                "{}/storage/google/callback",
                self.config.public_base_url.clone().unwrap_or_else(|| "http://127.0.0.1:8787".into())
            )
        })
    }

    pub async fn create_storage_link(
        &self,
        ctx: &RequestContext,
        request: &CreateStorageLinkRequest,
        now: Instant,
    ) -> HttpResult<StorageLinkSession> {
        let provider = request
            .provider
            .as_ref()
            .and_then(|v| v.as_str())
            .and_then(StorageProviderType::parse)
            .unwrap_or(self.provider);
        if provider == StorageProviderType::S3 && !self.config.s3_link_enabled {
            return Err(HttpError::new(
                404,
                "s3_link_disabled",
                "Linking S3 buckets is currently disabled on this server.",
            ));
        }
        let id = random_id("link");
        let state = random_id("state");
        let now_iso = time::to_iso(now);
        let expires_at = time::plus_ms_iso(now, STORAGE_LINK_TTL_MS);
        let auth_url = if provider == StorageProviderType::S3 {
            self.build_s3_link_url(&id, &state)
        } else {
            let has_refreshable = self
                .repo
                .find_storage_accounts_by_owner(provider, &ctx.player_uuid)
                .await?
                .iter()
                .any(|a| a.refresh_token.is_some());
            let force_consent = request.force_consent || !has_refreshable;
            self.build_storage_auth_url(&id, &state, force_consent)
        };
        self.repo
            .create_storage_link_session(StorageLinkSessionRecord {
                id: id.clone(),
                player_uuid: ctx.player_uuid.clone(),
                provider,
                status: StorageLinkStatus::Pending,
                auth_url: auth_url.clone(),
                state,
                linked_account_email: None,
                account_display_name: None,
                storage_account_id: None,
                error_message: None,
                created_at: now_iso.clone(),
                expires_at: expires_at.clone(),
                completed_at: None,
            })
            .await?;
        self.repo.cancel_pending_storage_link_sessions(&ctx.player_uuid, provider, &id, &now_iso).await?;
        Ok(StorageLinkSession {
            id,
            provider,
            status: StorageLinkStatus::Pending,
            auth_url,
            expires_at,
            linked_account_email: None,
            error_message: None,
        })
    }

    pub async fn get_storage_link_session(
        &self,
        ctx: &RequestContext,
        session_id: &str,
        now: Instant,
    ) -> HttpResult<StorageLinkSession> {
        let mut session = self.require_link_session_owner(ctx, session_id).await?;
        if expired(&session, now) && session.status == StorageLinkStatus::Pending {
            self.repo
                .update_storage_link_session(
                    &session.id,
                    StorageLinkSessionUpdate {
                        status: Some(StorageLinkStatus::Expired),
                        error_message: Some(Some(EXPIRED_MESSAGE.into())),
                        ..Default::default()
                    },
                )
                .await?;
            session.status = StorageLinkStatus::Expired;
            session.error_message = Some(EXPIRED_MESSAGE.into());
        }
        Ok(summarize(&session))
    }

    pub async fn cancel_storage_link(
        &self,
        ctx: &RequestContext,
        session_id: &str,
        now: Instant,
    ) -> HttpResult<StorageLinkSession> {
        let mut session = self.require_link_session_owner(ctx, session_id).await?;
        if expired(&session, now) && session.status == StorageLinkStatus::Pending {
            self.repo
                .update_storage_link_session(
                    &session.id,
                    StorageLinkSessionUpdate {
                        status: Some(StorageLinkStatus::Expired),
                        error_message: Some(Some(EXPIRED_MESSAGE.into())),
                        ..Default::default()
                    },
                )
                .await?;
            session.status = StorageLinkStatus::Expired;
            session.error_message = Some(EXPIRED_MESSAGE.into());
            return Ok(summarize(&session));
        }
        if session.status == StorageLinkStatus::Pending {
            let completed_at = time::to_iso(now);
            self.repo.cancel_storage_link_session(&session.id, &completed_at).await?;
            session.status = StorageLinkStatus::Cancelled;
            session.error_message = None;
            session.completed_at = Some(completed_at);
        }
        Ok(summarize(&session))
    }

    pub async fn complete_storage_link(
        &self,
        session_id: &str,
        request: &StorageLinkCompleteRequest,
        now: Instant,
    ) -> HttpResult<StorageLinkSession> {
        let session = self.repo.get_storage_link_session(session_id).await?.ok_or_else(|| {
            HttpError::new(
                404,
                "storage_link_not_found",
                "This Google Drive sign-in is no longer active. Start it again from Minecraft.",
            )
        })?;
        if session.status == StorageLinkStatus::Cancelled {
            return Err(HttpError::new(
                409,
                "storage_link_cancelled",
                "This Google Drive link is no longer active. Return to Minecraft and start again.",
            ));
        }
        if expired(&session, now) {
            return Err(HttpError::new(410, "storage_link_expired", EXPIRED_MESSAGE));
        }
        require_matching_state(&session, request.state.as_deref())?;
        let account = match self.exchange_google_auth(&session, request, now).await {
            Ok(a) => a,
            Err(e) => {
                if e.code == "storage_link_needs_consent" || e.code == "storage_account_already_linked" {
                    self.repo
                        .update_storage_link_session(
                            session_id,
                            StorageLinkSessionUpdate {
                                status: Some(StorageLinkStatus::Failed),
                                error_message: Some(Some(e.message.clone())),
                                completed_at: Some(Some(time::to_iso(now))),
                                ..Default::default()
                            },
                        )
                        .await?;
                }
                return Err(e);
            }
        };
        if account.refresh_token.is_none() {
            let message = "Google didn't give SharedWorld lasting access to this account. Return to Minecraft and try connecting again.";
            self.repo
                .update_storage_link_session(
                    session_id,
                    StorageLinkSessionUpdate {
                        status: Some(StorageLinkStatus::Failed),
                        error_message: Some(Some(message.into())),
                        completed_at: Some(Some(time::to_iso(now))),
                        ..Default::default()
                    },
                )
                .await?;
            return Err(HttpError::new(409, "storage_link_needs_consent", message));
        }
        self.repo
            .update_storage_link_session(
                session_id,
                StorageLinkSessionUpdate {
                    status: Some(StorageLinkStatus::Linked),
                    linked_account_email: Some(account.email.clone()),
                    storage_account_id: Some(Some(account.id.clone())),
                    completed_at: Some(Some(time::to_iso(now))),
                    error_message: Some(None),
                },
            )
            .await?;
        let refreshed = self.repo.get_storage_link_session(session_id).await?.ok_or_else(|| {
            HttpError::new(
                500,
                "storage_link_missing",
                "Connecting Google Drive didn't finish. Try again from Minecraft.",
            )
        })?;
        Ok(summarize(&refreshed))
    }

    pub async fn get_storage_account_summary(
        &self,
        ctx: &RequestContext,
        provider: Option<StorageProviderType>,
    ) -> HttpResult<StorageAccountSummary> {
        // No explicit provider = the pre-0.5.0 wire shape (old clients only
        // know the deployment default).
        let provider = provider.unwrap_or(self.provider);
        // "Healthy" is provider-shaped: a Drive account needs a live refresh
        // token; an S3 account just needs its key pair on record.
        let healthy = |a: &&StorageAccountRecord| match provider {
            StorageProviderType::S3 => a.access_token.is_some(),
            _ => a.refresh_token.is_some(),
        };
        let accounts = self.repo.find_storage_accounts_by_owner(provider, &ctx.player_uuid).await?;
        let best = accounts.iter().find(healthy).or(accounts.first());
        Ok(StorageAccountSummary {
            linked: best.is_some(),
            provider,
            email: best.and_then(|a| a.email.clone()),
            healthy: best.is_some_and(|a| healthy(&a)),
        })
    }

    pub async fn require_completed_link_session(
        &self,
        ctx: &RequestContext,
        session_id: &str,
    ) -> HttpResult<StorageLinkSessionRecord> {
        let session = self.require_link_session_owner(ctx, session_id).await?;
        if session.status != StorageLinkStatus::Linked || session.storage_account_id.is_none() {
            return Err(HttpError::new(
                409,
                "storage_link_incomplete",
                "Google Drive authorization is not complete yet.",
            ));
        }
        Ok(session)
    }

    pub async fn require_link_session_owner(
        &self,
        ctx: &RequestContext,
        session_id: &str,
    ) -> HttpResult<StorageLinkSessionRecord> {
        let session = self.repo.get_storage_link_session(session_id).await?.ok_or_else(|| {
            HttpError::new(
                404,
                "storage_link_not_found",
                "This Google Drive sign-in is no longer active. Start it again from Minecraft.",
            )
        })?;
        if session.player_uuid != ctx.player_uuid {
            return Err(HttpError::new(
                403,
                "forbidden",
                "Storage link session does not belong to this player.",
            ));
        }
        Ok(session)
    }

    fn build_s3_link_url(&self, session_id: &str, state: &str) -> String {
        let base = self.config.public_base_url.clone().unwrap_or_else(|| "http://127.0.0.1:8787".into());
        format!(
            "{base}/storage/s3/link?session={}&state={}",
            super::super::service::signer::url_encode(session_id),
            super::super::service::signer::url_encode(&format!("{session_id}:{state}"))
        )
    }

    /// Completes an S3 link session from the browser form: state check,
    /// endpoint validation, a live bucket probe (write/read/delete), then the
    /// account upsert. Probe/validation failures return
    /// `s3_link_form_invalid` WITHOUT failing the session, so the form can
    /// re-render and the user can fix a typo; terminal session states map to
    /// the same errors the Google callback produces.
    pub async fn complete_s3_link(
        &self,
        session_id: &str,
        presented_state: Option<&str>,
        form: &S3LinkForm,
        now: Instant,
    ) -> HttpResult<StorageLinkSession> {
        let session = self.repo.get_storage_link_session(session_id).await?.ok_or_else(|| {
            HttpError::new(
                404,
                "storage_link_not_found",
                "This bucket link is no longer active. Start it again from Minecraft.",
            )
        })?;
        if session.provider != StorageProviderType::S3 {
            return Err(HttpError::new(404, "storage_link_not_found", "This link is not an S3 link."));
        }
        if session.status == StorageLinkStatus::Cancelled {
            return Err(HttpError::new(
                409,
                "storage_link_cancelled",
                "This bucket link is no longer active. Return to Minecraft and start again.",
            ));
        }
        if expired(&session, now) {
            return Err(HttpError::new(
                410,
                "storage_link_expired",
                "This bucket link took too long. Start it again from Minecraft.",
            ));
        }
        require_matching_state(&session, presented_state)?;

        let form_error = |message: String| HttpError::new(400, "s3_link_form_invalid", message);
        let endpoint = validate_s3_endpoint(&form.endpoint, self.config.allow_insecure_s3_endpoint)
            .map_err(form_error)?;
        let bucket = form.bucket.trim().to_string();
        if bucket.is_empty()
            || bucket.len() > 255
            || !bucket.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '.' || c == '_')
        {
            return Err(form_error("Enter the bucket name (letters, digits, dots and dashes).".into()));
        }
        let access_key_id = form.access_key_id.trim().to_string();
        let secret_access_key = form.secret_access_key.trim().to_string();
        if access_key_id.is_empty() || secret_access_key.is_empty() {
            return Err(form_error("Enter both the access key id and the secret access key.".into()));
        }
        let region = {
            let r = form.region.trim();
            if r.is_empty() {
                "auto".to_string()
            } else {
                r.to_string()
            }
        };
        // Empty form field = the default prefix; an explicit "/" = bucket root.
        let key_prefix = {
            let raw = form.key_prefix.trim();
            if raw.is_empty() {
                super::s3::DEFAULT_KEY_PREFIX.to_string()
            } else {
                super::s3::normalize_key_prefix(Some(raw))
            }
        };

        let existing =
            self.repo.find_storage_account_by_external_id(StorageProviderType::S3, &access_key_id).await?;
        if existing.as_ref().is_some_and(|e| e.owner_player_uuid != session.player_uuid) {
            return Err(HttpError::new(
                409,
                "storage_account_already_linked",
                "This access key is already linked to another Minecraft player. Use a different key.",
            ));
        }

        super::s3::probe_bucket(
            &super::s3::S3ConnectionParams {
                endpoint: endpoint.clone(),
                region: region.clone(),
                bucket: bucket.clone(),
                key_prefix: key_prefix.clone(),
                access_key_id: access_key_id.clone(),
                secret_access_key: secret_access_key.clone(),
            },
            &random_id("probe"),
        )
        .await
        .map_err(form_error)?;

        let label = format!("{bucket} @ {}", endpoint.split("://").nth(1).unwrap_or(&endpoint));
        let now_iso = time::to_iso(now);
        let account = self
            .repo
            .create_or_update_storage_account(StorageAccountRecord {
                id: existing.as_ref().map(|e| e.id.clone()).unwrap_or_else(|| random_id("storage")),
                provider: StorageProviderType::S3,
                owner_player_uuid: session.player_uuid.clone(),
                external_account_id: access_key_id,
                email: Some(label.clone()),
                display_name: None,
                access_token: Some(secret_access_key),
                refresh_token: None,
                token_expires_at: None,
                s3_endpoint: Some(endpoint),
                s3_region: Some(region),
                s3_bucket: Some(bucket),
                s3_key_prefix: Some(key_prefix),
                created_at: existing
                    .as_ref()
                    .map(|e| e.created_at.clone())
                    .unwrap_or_else(|| now_iso.clone()),
                updated_at: now_iso,
            })
            .await?;
        self.repo
            .update_storage_link_session(
                session_id,
                StorageLinkSessionUpdate {
                    status: Some(StorageLinkStatus::Linked),
                    linked_account_email: Some(Some(label)),
                    storage_account_id: Some(Some(account.id.clone())),
                    completed_at: Some(Some(time::to_iso(now))),
                    error_message: Some(None),
                },
            )
            .await?;
        let refreshed = self.repo.get_storage_link_session(session_id).await?.ok_or_else(|| {
            HttpError::new(
                500,
                "storage_link_missing",
                "Linking the bucket didn't finish. Try again from Minecraft.",
            )
        })?;
        Ok(summarize(&refreshed))
    }

    fn build_storage_auth_url(&self, session_id: &str, state: &str, force_consent: bool) -> String {
        let redirect_uri = self.redirect_uri();
        if self.config.allow_dev_google_oauth {
            let mock_email = super::super::service::signer::url_encode(
                self.config.dev_google_email.as_deref().unwrap_or("dev-google@example.com"),
            );
            return format!(
                "{redirect_uri}?sessionId={}&state={}&mock=1&mockEmail={mock_email}",
                super::super::service::signer::url_encode(session_id),
                super::super::service::signer::url_encode(state)
            );
        }
        // No `profile`: the account's display name is PII we never use — the
        // email (from the `email` scope) is the only human-readable handle.
        let scope = self
            .config
            .google_oauth_scopes
            .clone()
            .unwrap_or_else(|| "openid email https://www.googleapis.com/auth/drive.appdata".into());
        let state_param = format!("{session_id}:{state}");
        let mut pairs: Vec<(&str, &str)> = vec![
            ("client_id", self.config.google_oauth_client_id.as_deref().unwrap_or("")),
            ("redirect_uri", &redirect_uri),
            ("response_type", "code"),
            ("access_type", "offline"),
            ("scope", &scope),
            ("state", &state_param),
        ];
        if force_consent {
            pairs.push(("prompt", "consent"));
        }
        format!("https://accounts.google.com/o/oauth2/v2/auth?{}", form_encode(&pairs))
    }

    async fn exchange_google_auth(
        &self,
        session: &StorageLinkSessionRecord,
        request: &StorageLinkCompleteRequest,
        now: Instant,
    ) -> HttpResult<StorageAccountRecord> {
        if self.config.allow_dev_google_oauth {
            if let Some(mock) = request.mock_email.as_deref().filter(|m| !m.is_empty()) {
                return self
                    .upsert_storage_account_from_oauth(
                        session,
                        OAuthPayload {
                            sub: mock.into(),
                            email: Some(mock.into()),
                            access_token: "dev-google-token".into(),
                            refresh_token: Some("dev-google-refresh".into()),
                            expires_at: time::plus_ms_iso(now, 60 * 60_000),
                        },
                        now,
                    )
                    .await;
            }
        }
        let Some(code) = request.code.as_deref().filter(|c| !c.is_empty()) else {
            return Err(HttpError::new(400, "missing_oauth_code", "Google OAuth callback code is required."));
        };
        let redirect_uri = self.redirect_uri();
        let body = form_encode(&[
            ("code", code),
            ("client_id", self.config.google_oauth_client_id.as_deref().unwrap_or("")),
            ("client_secret", self.config.google_oauth_client_secret.as_deref().unwrap_or("")),
            ("redirect_uri", &redirect_uri),
            ("grant_type", "authorization_code"),
        ]);
        let exchange_failed =
            || HttpError::new(401, "oauth_exchange_failed", "Failed to exchange Google OAuth code.");
        let token_response = self
            .http
            .post("https://oauth2.googleapis.com/token")
            .header("content-type", "application/x-www-form-urlencoded")
            .body(body)
            .send()
            .await
            .map_err(|_| exchange_failed())?;
        if !token_response.status().is_success() {
            return Err(exchange_failed());
        }
        let token: TokenResponse = token_response.json().await.map_err(|_| exchange_failed())?;
        require_drive_appdata_scope(token.scope.as_deref())?;
        let profile_failed =
            || HttpError::new(401, "oauth_profile_failed", "Failed to read Google account profile.");
        let user_response = self
            .http
            .get("https://openidconnect.googleapis.com/v1/userinfo")
            .bearer_auth(&token.access_token)
            .send()
            .await
            .map_err(|_| profile_failed())?;
        if !user_response.status().is_success() {
            return Err(profile_failed());
        }
        let user: UserInfo = user_response.json().await.map_err(|_| profile_failed())?;
        let expires_in_ms = (token.expires_in.unwrap_or(0.0) * 1000.0) as i64;
        self.upsert_storage_account_from_oauth(
            session,
            OAuthPayload {
                sub: user.sub,
                email: user.email,
                access_token: token.access_token,
                refresh_token: token.refresh_token,
                expires_at: time::plus_ms_iso(now, expires_in_ms),
            },
            now,
        )
        .await
    }

    async fn upsert_storage_account_from_oauth(
        &self,
        session: &StorageLinkSessionRecord,
        payload: OAuthPayload,
        now: Instant,
    ) -> HttpResult<StorageAccountRecord> {
        let existing = self.repo.find_storage_account_by_external_id(session.provider, &payload.sub).await?;
        if existing.as_ref().is_some_and(|e| e.owner_player_uuid != session.player_uuid) {
            return Err(HttpError::new(
                409,
                "storage_account_already_linked",
                "This Google account is already linked to another Minecraft player. Use a different Google account.",
            ));
        }
        let now_iso = time::to_iso(now);
        Ok(self
            .repo
            .create_or_update_storage_account(StorageAccountRecord {
                id: existing.as_ref().map(|e| e.id.clone()).unwrap_or_else(|| random_id("storage")),
                provider: session.provider,
                owner_player_uuid: session.player_uuid.clone(),
                external_account_id: payload.sub,
                email: payload.email,
                // Never stored: the profile scope is not requested any more.
                display_name: None,
                access_token: Some(payload.access_token),
                refresh_token: payload
                    .refresh_token
                    .or_else(|| existing.as_ref().and_then(|e| e.refresh_token.clone())),
                token_expires_at: Some(payload.expires_at),
                s3_endpoint: None,
                s3_region: None,
                s3_bucket: None,
                s3_key_prefix: None,
                created_at: existing
                    .as_ref()
                    .map(|e| e.created_at.clone())
                    .unwrap_or_else(|| now_iso.clone()),
                updated_at: now_iso,
            })
            .await?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn s3_endpoint_validation() {
        assert_eq!(
            validate_s3_endpoint("https://abc.r2.cloudflarestorage.com/", false).unwrap(),
            "https://abc.r2.cloudflarestorage.com"
        );
        assert_eq!(
            validate_s3_endpoint(" https://s3.us-west-004.backblazeb2.com ", false).unwrap(),
            "https://s3.us-west-004.backblazeb2.com"
        );
        assert_eq!(validate_s3_endpoint("http://127.0.0.1:9000", true).unwrap(), "http://127.0.0.1:9000");
        assert!(validate_s3_endpoint("", false).is_err());
        assert!(validate_s3_endpoint("not a url", false).is_err());
        assert!(validate_s3_endpoint("http://minio.example.com", false).is_err());
        assert!(validate_s3_endpoint("https://user:pw@host.example.com", false).is_err());
        assert!(validate_s3_endpoint("https://host.example.com/bucket", false).is_err());
        assert!(validate_s3_endpoint("https://host.example.com?x=1", false).is_err());
        assert!(validate_s3_endpoint("https://localhost", false).is_err());
        assert!(validate_s3_endpoint("https://127.0.0.1:9000", false).is_err());
        assert!(validate_s3_endpoint("https://10.0.0.5", false).is_err());
        assert!(validate_s3_endpoint("https://192.168.1.10", false).is_err());
        assert!(validate_s3_endpoint("https://100.90.1.2", false).is_err());
        assert!(validate_s3_endpoint("https://[::1]", false).is_err());
        assert!(validate_s3_endpoint("https://minio.tail1234.ts.net.internal", false).is_err());
        assert!(validate_s3_endpoint("ftp://host.example.com", false).is_err());
    }

    #[test]
    fn scope_and_state_checks() {
        assert!(require_drive_appdata_scope(None).is_ok());
        assert!(require_drive_appdata_scope(Some(
            "openid email https://www.googleapis.com/auth/drive.appdata"
        ))
        .is_ok());
        assert_eq!(
            require_drive_appdata_scope(Some("openid email")).unwrap_err().code,
            "storage_link_needs_consent"
        );
        assert_eq!(form_encode(&[("a b", "c&d")]), "a+b=c%26d");
    }
}
