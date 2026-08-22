//! DTOs from `contracts.ts`. Names keep the TS identifier in the doc comment
//! so `grep` finds both sides. Serialization conventions: see crate docs.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

fn is_false(b: &bool) -> bool {
    !*b
}

/// `Option<Option<T>>` for TS `?: T | null`: absent → `None`, `null` →
/// `Some(None)`, value → `Some(Some(v))`. Serde's default collapses `null`
/// into `None`, so deserialization goes through this helper.
pub mod double_option {
    use serde::{Deserialize, Deserializer, Serialize, Serializer};

    pub fn deserialize<'de, T, D>(d: D) -> Result<Option<Option<T>>, D::Error>
    where
        T: Deserialize<'de>,
        D: Deserializer<'de>,
    {
        Option::<T>::deserialize(d).map(Some)
    }

    pub fn serialize<T, S>(v: &Option<Option<T>>, s: S) -> Result<S::Ok, S::Error>
    where
        T: Serialize,
        S: Serializer,
    {
        match v {
            Some(inner) => inner.serialize(s),
            None => s.serialize_none(),
        }
    }
}

// ---------------------------------------------------------------------------
// String enums
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum WorldStatus {
    Idle,
    Hosting,
    Finalizing,
    Handoff,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum WorldRuntimePhase {
    Idle,
    HostStarting,
    HostLive,
    HostFinalizing,
    HandoffWaiting,
}

impl WorldRuntimePhase {
    /// `runtimePhaseToWorldStatus` (`runtime-protocol.ts`).
    pub fn world_status(self) -> WorldStatus {
        match self {
            Self::Idle => WorldStatus::Idle,
            Self::HostStarting | Self::HostLive => WorldStatus::Hosting,
            Self::HostFinalizing => WorldStatus::Finalizing,
            Self::HandoffWaiting => WorldStatus::Handoff,
        }
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::HostStarting => "host-starting",
            Self::HostLive => "host-live",
            Self::HostFinalizing => "host-finalizing",
            Self::HandoffWaiting => "handoff-waiting",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MembershipRole {
    Owner,
    Member,
}

impl MembershipRole {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Owner => "owner",
            Self::Member => "member",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum InviteStatus {
    Active,
    Expired,
    Revoked,
    Redeemed,
}

impl InviteStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Active => "active",
            Self::Expired => "expired",
            Self::Revoked => "revoked",
            Self::Redeemed => "redeemed",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum EnterSessionAction {
    Connect,
    Host,
    Wait,
    WarnHost,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ObserveWaitingAction {
    Connect,
    Wait,
    Restart,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum StorageProviderType {
    GoogleDrive,
    R2,
}

impl StorageProviderType {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::GoogleDrive => "google-drive",
            Self::R2 => "r2",
        }
    }
    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "google-drive" => Some(Self::GoogleDrive),
            "r2" => Some(Self::R2),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum StorageLinkStatus {
    Pending,
    Linked,
    Expired,
    Failed,
    Cancelled,
}

impl StorageLinkStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Pending => "pending",
            Self::Linked => "linked",
            Self::Expired => "expired",
            Self::Failed => "failed",
            Self::Cancelled => "cancelled",
        }
    }
    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "pending" => Some(Self::Pending),
            "linked" => Some(Self::Linked),
            "expired" => Some(Self::Expired),
            "failed" => Some(Self::Failed),
            "cancelled" => Some(Self::Cancelled),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum StartupProgressMode {
    Determinate,
    Indeterminate,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum FileTransferMode {
    WholeGzip,
    RegionFull,
    RegionDelta,
    PackFull,
    PackDelta,
}

impl FileTransferMode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::WholeGzip => "whole-gzip",
            Self::RegionFull => "region-full",
            Self::RegionDelta => "region-delta",
            Self::PackFull => "pack-full",
            Self::PackDelta => "pack-delta",
        }
    }
    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "whole-gzip" => Some(Self::WholeGzip),
            "region-full" => Some(Self::RegionFull),
            "region-delta" => Some(Self::RegionDelta),
            "pack-full" => Some(Self::PackFull),
            "pack-delta" => Some(Self::PackDelta),
            _ => None,
        }
    }
    pub fn is_delta(self) -> bool {
        matches!(self, Self::RegionDelta | Self::PackDelta)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum WorldDifficulty {
    Peaceful,
    Easy,
    Normal,
    Hard,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum WorldDefaultGameMode {
    Survival,
    Creative,
    Adventure,
}

/// SharedWorld's own gamerule ids.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum WorldGameRule {
    KeepInventory,
    MobGriefing,
    DaylightCycle,
    WeatherCycle,
    Pvp,
}

impl WorldGameRule {
    pub const ALL: [WorldGameRule; 5] =
        [Self::KeepInventory, Self::MobGriefing, Self::DaylightCycle, Self::WeatherCycle, Self::Pvp];
    pub fn as_str(self) -> &'static str {
        match self {
            Self::KeepInventory => "keepInventory",
            Self::MobGriefing => "mobGriefing",
            Self::DaylightCycle => "daylightCycle",
            Self::WeatherCycle => "weatherCycle",
            Self::Pvp => "pvp",
        }
    }
    pub fn parse(s: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|r| r.as_str() == s)
    }
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthChallenge {
    pub server_id: String,
    pub expires_at: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthCompleteRequest {
    pub server_id: String,
    pub player_name: String,
}

/// Body for POST /auth/complete-cert (Mojang profile certificate proof).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthCompleteCertRequest {
    pub server_id: String,
    /// 32 lowercase hex chars, no hyphens.
    pub player_uuid: String,
    pub player_name: String,
    /// Base64 X.509 SPKI DER of the profile public key.
    pub public_key: String,
    /// Epoch millis; kept as f64 so a non-finite value is rejected by the
    /// service (403 certificate_expired) rather than by JSON parsing.
    pub public_key_expires_at_ms: serde_json::Value,
    pub key_signature: String,
    pub nonce_signature: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DevAuthCompleteRequest {
    pub player_uuid: String,
    pub player_name: String,
    pub secret: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionToken {
    pub token: String,
    pub player_uuid: String,
    pub player_name: String,
    pub expires_at: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DevSessionToken {
    #[serde(flatten)]
    pub session: SessionToken,
    pub allow_insecure_e4mc: bool,
}

// ---------------------------------------------------------------------------
// Blobs / sync policy / storage usage
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum SignedBlobMethod {
    PUT,
    GET,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SignedBlobUrl {
    pub method: SignedBlobMethod,
    pub url: String,
    pub headers: BTreeMap<String, String>,
    pub expires_at: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncPolicy {
    pub max_parallel_downloads: i64,
    pub max_concurrent_upload_preparations: i64,
    pub max_concurrent_uploads: i64,
    pub max_upload_starts_per_second: i64,
    pub retry_base_delay_ms: i64,
    pub retry_max_delay_ms: i64,
    /// Largest single blob body the relay accepts (clients preflight).
    pub max_upload_body_bytes: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageUsageSummary {
    pub provider: StorageProviderType,
    pub linked: bool,
    pub used_bytes: i64,
    pub quota_used_bytes: Option<i64>,
    pub quota_total_bytes: Option<i64>,
    pub account_email: Option<String>,
}

// ---------------------------------------------------------------------------
// World settings
// ---------------------------------------------------------------------------

/// Owner-chosen world settings. Absent fields mean "no override".
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldSettings {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub difficulty: Option<WorldDifficulty>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub default_game_mode: Option<WorldDefaultGameMode>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gamerules: Option<BTreeMap<WorldGameRule, bool>>,
    /// 0.4.2 hard cap on retained backups; null/absent = age policy only.
    /// Serialized as `null` when explicitly null (the TS stores `null`).
    #[serde(default, skip_serializing_if = "Option::is_none", with = "double_option")]
    pub max_backups: Option<Option<i64>>,
}

/// `UpdateWorldSettingsRequest` — the body is validated field by field by the
/// service (400 invalid_world_settings), so it is carried loosely here.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateWorldSettingsRequest {
    #[serde(default)]
    pub settings: serde_json::Value,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageAccountSummary {
    pub linked: bool,
    pub provider: StorageProviderType,
    pub email: Option<String>,
    pub healthy: bool,
}

/// One bounded step of `DELETE /account`; the client loops until `done`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountDeleteStepResponse {
    pub done: bool,
    /// `drive_sweep` while provider files are being deleted, then `finalizing`.
    pub phase: String,
    /// Known-remaining provider files (best-effort; more pages may follow).
    pub remaining: i64,
}

// ---------------------------------------------------------------------------
// Worlds / memberships / invites
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldSummary {
    pub id: String,
    pub slug: String,
    pub name: String,
    pub owner_uuid: String,
    pub motd: Option<String>,
    pub custom_icon_storage_key: Option<String>,
    pub custom_icon_download: Option<SignedBlobUrl>,
    pub member_count: i64,
    pub status: WorldStatus,
    pub last_snapshot_id: Option<String>,
    pub last_snapshot_at: Option<String>,
    pub active_host_uuid: Option<String>,
    pub active_host_player_name: Option<String>,
    pub active_join_target: Option<String>,
    pub online_player_count: i64,
    pub online_player_names: Vec<String>,
    pub storage_provider: StorageProviderType,
    pub storage_linked: bool,
    pub storage_account_email: Option<String>,
    pub last_snapshot_data_version: Option<i64>,
    pub last_snapshot_minecraft_version: Option<String>,
    pub settings: Option<WorldSettings>,
    pub settings_revision: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldMembership {
    pub world_id: String,
    pub player_uuid: String,
    pub player_name: String,
    pub role: MembershipRole,
    pub joined_at: String,
    pub deleted_at: Option<String>,
    pub can_use_commands: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateMemberPermissionsRequest {
    pub can_use_commands: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportedWorldSource {
    #[serde(rename = "type")]
    pub kind: String,
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateWorldRequest {
    #[serde(default)]
    pub name: Option<serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub motd_line1: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub motd_line2: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub custom_icon_png_base64: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub import_source: Option<serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub storage_link_session_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub use_linked_storage_account: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateWorldRequest {
    #[serde(default)]
    pub name: Option<serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub motd_line1: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub motd_line2: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub custom_icon_png_base64: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub clear_custom_icon: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldDetails {
    #[serde(flatten)]
    pub summary: WorldSummary,
    pub membership: WorldMembership,
    pub memberships: Vec<WorldMembership>,
    pub storage_usage: Option<StorageUsageSummary>,
    pub active_invite_code: Option<InviteCode>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateWorldResult {
    pub world: WorldDetails,
    pub initial_upload_assignment: HostAssignment,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InviteCode {
    pub id: String,
    pub world_id: String,
    pub code: String,
    pub created_by_uuid: String,
    pub created_at: String,
    pub expires_at: String,
    pub status: InviteStatus,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResetInviteResponse {
    pub revoked_invite_ids: Vec<String>,
    pub invite: InviteCode,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RedeemInviteRequest {
    #[serde(default)]
    pub code: Option<serde_json::Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KickMemberResponse {
    pub world_id: String,
    pub removed_player_uuid: String,
}

// ---------------------------------------------------------------------------
// Runtime
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostStartupProgress {
    pub label: String,
    pub mode: StartupProgressMode,
    pub fraction: Option<f64>,
    pub updated_at: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum UncleanShutdownPhase {
    HostLive,
    HostFinalizing,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UncleanShutdownWarning {
    pub host_uuid: String,
    pub host_player_name: String,
    pub phase: UncleanShutdownPhase,
    pub runtime_epoch: i64,
    pub recorded_at: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostAssignment {
    pub world_id: String,
    pub player_uuid: String,
    pub player_name: String,
    pub runtime_epoch: i64,
    pub host_token: String,
    pub startup_deadline_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldRuntimeStatus {
    pub world_id: String,
    pub phase: WorldRuntimePhase,
    pub runtime_epoch: i64,
    pub host_uuid: Option<String>,
    pub host_player_name: Option<String>,
    pub candidate_uuid: Option<String>,
    pub candidate_player_name: Option<String>,
    pub join_target: Option<String>,
    pub startup_deadline_at: Option<String>,
    pub runtime_token_issued_at: Option<String>,
    pub last_progress_at: Option<String>,
    pub updated_at: Option<String>,
    pub revoked_at: Option<String>,
    pub startup_progress: Option<HostStartupProgress>,
    pub unclean_shutdown_warning: Option<UncleanShutdownWarning>,
    pub host_minecraft_version: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub suggested_poll_interval_ms: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostHeartbeatMembership {
    pub player_uuid: String,
    pub player_name: String,
    pub can_use_commands: bool,
}

/// FLAT superset of `WorldRuntimeStatus` (older clients bind it to the base).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostHeartbeatResponse {
    #[serde(flatten)]
    pub runtime: WorldRuntimeStatus,
    pub memberships: Vec<HostHeartbeatMembership>,
    pub settings: Option<WorldSettings>,
    pub settings_revision: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub suggested_heartbeat_interval_ms: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub suggested_autosave_interval_ms: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnterSessionRequest {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub waiter_session_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub acknowledge_unclean_shutdown: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnterSessionResponse {
    pub action: EnterSessionAction,
    pub world: WorldSummary,
    pub latest_manifest: Option<SnapshotManifest>,
    pub runtime: WorldRuntimeStatus,
    pub assignment: Option<HostAssignment>,
    pub waiter_session_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WaiterSessionRequest {
    #[serde(default)]
    pub waiter_session_id: Option<serde_json::Value>,
}
pub type RefreshWaitingRequest = WaiterSessionRequest;
pub type CancelWaitingRequest = WaiterSessionRequest;
pub type ObserveWaitingRequest = WaiterSessionRequest;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ObserveWaitingResponse {
    pub action: ObserveWaitingAction,
    pub runtime: WorldRuntimeStatus,
    pub assignment: Option<HostAssignment>,
    pub waiter_session_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HeartbeatRequest {
    #[serde(default)]
    pub runtime_epoch: Option<i64>,
    #[serde(default)]
    pub host_token: Option<String>,
    #[serde(default)]
    pub join_target: Option<String>,
    #[serde(default)]
    pub minecraft_version: Option<String>,
}

/// Host-reported gamerule values (runtime-authorized). Validated by the
/// service, so `gamerules`/`difficulty`/`defaultGameMode` stay loose.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostGameRulesReportRequest {
    #[serde(default)]
    pub runtime_epoch: Option<i64>,
    #[serde(default)]
    pub host_token: Option<String>,
    #[serde(default)]
    pub gamerules: Option<serde_json::Value>,
    #[serde(default)]
    pub difficulty: Option<serde_json::Value>,
    #[serde(default)]
    pub default_game_mode: Option<serde_json::Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostGameRulesReportResponse {
    pub settings: WorldSettings,
    pub settings_revision: i64,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostStartupProgressRequest {
    #[serde(default)]
    pub runtime_epoch: Option<i64>,
    #[serde(default)]
    pub host_token: Option<String>,
    #[serde(default)]
    pub label: Option<String>,
    #[serde(default)]
    pub mode: Option<StartupProgressMode>,
    #[serde(default)]
    pub fraction: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PresenceHeartbeatRequest {
    #[serde(default)]
    pub present: Option<serde_json::Value>,
    #[serde(default)]
    pub guest_session_epoch: Option<serde_json::Value>,
    #[serde(default)]
    pub presence_sequence: Option<serde_json::Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PresenceHeartbeatResponse {
    pub world_id: String,
    pub present: bool,
    pub updated_at: String,
    pub expires_at: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub suggested_interval_ms: Option<i64>,
}

/// Merged guest heartbeat: FLAT superset of `PresenceHeartbeatResponse`
/// carrying the runtime facts minus `updatedAt` plus `lastSnapshotId`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GuestHeartbeatResponse {
    #[serde(flatten)]
    pub presence: PresenceHeartbeatResponse,
    pub phase: WorldRuntimePhase,
    pub runtime_epoch: i64,
    pub host_uuid: Option<String>,
    pub host_player_name: Option<String>,
    pub candidate_uuid: Option<String>,
    pub candidate_player_name: Option<String>,
    pub join_target: Option<String>,
    pub startup_deadline_at: Option<String>,
    pub runtime_token_issued_at: Option<String>,
    pub last_progress_at: Option<String>,
    pub revoked_at: Option<String>,
    pub startup_progress: Option<HostStartupProgress>,
    pub unclean_shutdown_warning: Option<UncleanShutdownWarning>,
    pub host_minecraft_version: Option<String>,
    pub last_snapshot_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReleaseHostRequest {
    #[serde(default)]
    pub snapshot_id: Option<String>,
    #[serde(default)]
    pub graceful: Option<serde_json::Value>,
    #[serde(default)]
    pub runtime_epoch: Option<i64>,
    #[serde(default)]
    pub host_token: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostAuthorityRequest {
    #[serde(default)]
    pub runtime_epoch: Option<i64>,
    #[serde(default)]
    pub host_token: Option<String>,
}
pub type BeginFinalizationRequest = HostAuthorityRequest;
pub type CompleteFinalizationRequest = HostAuthorityRequest;
pub type AbandonFinalizationRequest = HostAuthorityRequest;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FinalizationActionResult {
    pub world_id: String,
    pub next_host_uuid: Option<String>,
    pub next_host_player_name: Option<String>,
    pub status: WorldStatus,
}

// ---------------------------------------------------------------------------
// Manifests / snapshots
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestFile {
    pub path: String,
    pub hash: String,
    pub size: i64,
    pub compressed_size: i64,
    pub storage_key: String,
    pub content_type: String,
    #[serde(default)]
    pub transfer_mode: Option<FileTransferMode>,
    #[serde(default)]
    pub base_snapshot_id: Option<String>,
    #[serde(default)]
    pub base_hash: Option<String>,
    #[serde(default)]
    pub chain_depth: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PackedManifestFile {
    pub path: String,
    pub hash: String,
    pub size: i64,
    pub content_type: String,
}

/// One materialization step of a pack's delta chain.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PackChainStep {
    pub storage_key: String,
    /// Hash of the pack CONTENT after applying this step.
    pub hash: String,
    pub base_hash: Option<String>,
    pub transfer_mode: FileTransferMode,
    pub size: i64,
    pub delta_format_version: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotPack {
    pub pack_id: String,
    pub hash: String,
    pub size: i64,
    pub storage_key: String,
    pub transfer_mode: FileTransferMode,
    #[serde(default)]
    pub base_snapshot_id: Option<String>,
    #[serde(default)]
    pub base_hash: Option<String>,
    #[serde(default)]
    pub chain_depth: Option<i64>,
    #[serde(default)]
    pub delta_format_version: Option<i64>,
    #[serde(default)]
    pub delta_blob_size: Option<i64>,
    #[serde(default)]
    pub chain_delta_bytes: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub chain_steps: Option<Vec<PackChainStep>>,
    #[serde(default)]
    pub files: Vec<PackedManifestFile>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotManifest {
    pub world_id: String,
    pub snapshot_id: String,
    pub created_at: String,
    pub created_by_uuid: String,
    pub files: Vec<ManifestFile>,
    pub packs: Vec<SnapshotPack>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldSnapshotSummary {
    pub snapshot_id: String,
    pub created_at: String,
    pub created_by_uuid: String,
    pub data_version: Option<i64>,
    pub minecraft_version: Option<String>,
    pub file_count: i64,
    pub total_size: i64,
    pub total_compressed_size: i64,
    pub is_latest: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotActionResult {
    pub world_id: String,
    pub snapshot_id: String,
}

/// 0.4.5 bulk delete.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeleteSnapshotsRequest {
    #[serde(default)]
    pub snapshot_ids: Option<serde_json::Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeleteSnapshotsResult {
    pub world_id: String,
    pub deleted_snapshot_ids: Vec<String>,
}

// ---------------------------------------------------------------------------
// Upload / download plans
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadPlanRequest {
    #[serde(default)]
    pub runtime_epoch: Option<i64>,
    #[serde(default)]
    pub host_token: Option<String>,
    #[serde(default)]
    pub files: Vec<LocalFileDescriptor>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub non_region_pack: Option<LocalPackDescriptor>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub region_bundles: Option<Vec<LocalPackDescriptor>>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalFileDescriptor {
    pub path: String,
    pub hash: String,
    pub size: i64,
    pub compressed_size: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content_type: Option<String>,
    #[serde(default)]
    pub delta_capable: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalPackDescriptor {
    pub pack_id: String,
    pub hash: String,
    pub size: i64,
    pub file_count: i64,
    #[serde(default)]
    pub files: Vec<PackedManifestFile>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadPlanEntry {
    pub file: LocalFileDescriptor,
    pub already_present: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub storage_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transfer_mode: Option<FileTransferMode>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub upload: Option<SignedBlobUrl>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub full_storage_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub full_upload: Option<SignedBlobUrl>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delta_storage_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delta_upload: Option<SignedBlobUrl>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_snapshot_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_hash: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_chain_depth: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadPackPlan {
    pub pack: LocalPackDescriptor,
    pub already_present: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub storage_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transfer_mode: Option<FileTransferMode>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub upload: Option<SignedBlobUrl>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub full_storage_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub full_upload: Option<SignedBlobUrl>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delta_storage_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delta_upload: Option<SignedBlobUrl>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_snapshot_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_hash: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_chain_depth: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delta_format_version: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DirectUploadPolicy {
    pub chunk_size_bytes: i64,
    pub max_upload_bytes: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadPlan {
    pub world_id: String,
    pub snapshot_base_id: Option<String>,
    pub uploads: Vec<UploadPlanEntry>,
    #[serde(default, skip_serializing_if = "Option::is_none", with = "double_option")]
    pub non_region_pack_upload: Option<Option<UploadPackPlan>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub region_bundle_uploads: Option<Vec<UploadPackPlan>>,
    pub sync_policy: SyncPolicy,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_pack_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none", with = "double_option")]
    pub direct_upload: Option<Option<DirectUploadPolicy>>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateBlobSessionRequest {
    #[serde(default)]
    pub storage_key: Option<serde_json::Value>,
    #[serde(default)]
    pub runtime_epoch: Option<i64>,
    #[serde(default)]
    pub host_token: Option<String>,
    #[serde(default)]
    pub content_type: Option<serde_json::Value>,
    #[serde(default)]
    pub content_length: Option<serde_json::Value>,
    #[serde(default)]
    pub blob_stamp: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateBlobSessionResponse {
    pub upload_id: String,
    pub session_url: String,
    pub chunk_size_bytes: i64,
    pub expires_at: String,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommitBlobSessionRequest {
    #[serde(default)]
    pub upload_id: Option<serde_json::Value>,
    #[serde(default)]
    pub runtime_epoch: Option<i64>,
    #[serde(default)]
    pub host_token: Option<String>,
    #[serde(default)]
    pub blob_stamp: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommitBlobSessionResponse {
    pub storage_key: String,
    pub size: i64,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FinalizeSnapshotRequest {
    #[serde(default)]
    pub runtime_epoch: Option<i64>,
    #[serde(default)]
    pub host_token: Option<String>,
    #[serde(default)]
    pub base_snapshot_id: Option<String>,
    #[serde(default)]
    pub data_version: Option<i64>,
    #[serde(default)]
    pub minecraft_version: Option<String>,
    #[serde(default)]
    pub files: Vec<ManifestFile>,
    #[serde(default)]
    pub packs: Option<Vec<SnapshotPack>>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadPlanStep {
    pub transfer_mode: FileTransferMode,
    pub storage_key: String,
    pub artifact_size: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_snapshot_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_hash: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delta_format_version: Option<i64>,
    pub download: SignedBlobUrl,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadPlanEntry {
    pub path: String,
    pub hash: String,
    pub size: i64,
    pub content_type: String,
    pub steps: Vec<DownloadPlanStep>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadPackPlan {
    pub pack_id: String,
    pub hash: String,
    pub size: i64,
    pub files: Vec<PackedManifestFile>,
    pub steps: Vec<DownloadPlanStep>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadPlan {
    pub world_id: String,
    pub snapshot_id: Option<String>,
    pub downloads: Vec<DownloadPlanEntry>,
    #[serde(default, skip_serializing_if = "Option::is_none", with = "double_option")]
    pub non_region_pack_download: Option<Option<DownloadPackPlan>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub region_bundle_downloads: Option<Vec<DownloadPackPlan>>,
    pub retained_paths: Vec<String>,
    pub sync_policy: SyncPolicy,
}

// ---------------------------------------------------------------------------
// Storage link
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateStorageLinkRequest {
    #[serde(default)]
    pub provider: Option<serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub import_source: Option<serde_json::Value>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub force_consent: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageLinkSession {
    pub id: String,
    pub provider: StorageProviderType,
    pub status: StorageLinkStatus,
    pub auth_url: String,
    pub expires_at: String,
    pub linked_account_email: Option<String>,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageLinkCompleteRequest {
    pub session_id: String,
    #[serde(default)]
    pub code: Option<String>,
    #[serde(default)]
    pub state: Option<String>,
    #[serde(default)]
    pub mock_email: Option<String>,
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ApiErrorShape {
    pub error: String,
    pub message: String,
    pub status: u16,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn flat_supersets_stay_flat() {
        let runtime = WorldRuntimeStatus {
            world_id: "w".into(),
            phase: WorldRuntimePhase::HostLive,
            runtime_epoch: 2,
            host_uuid: Some("h".into()),
            host_player_name: Some("H".into()),
            candidate_uuid: None,
            candidate_player_name: None,
            join_target: Some("x:1".into()),
            startup_deadline_at: None,
            runtime_token_issued_at: None,
            last_progress_at: None,
            updated_at: Some("t".into()),
            revoked_at: None,
            startup_progress: None,
            unclean_shutdown_warning: None,
            host_minecraft_version: None,
            suggested_poll_interval_ms: None,
        };
        let hb = HostHeartbeatResponse {
            runtime,
            memberships: vec![],
            settings: None,
            settings_revision: 0,
            suggested_heartbeat_interval_ms: None,
            suggested_autosave_interval_ms: None,
        };
        let v: serde_json::Value = serde_json::to_value(&hb).unwrap();
        assert_eq!(v["phase"], "host-live");
        assert_eq!(v["candidateUuid"], serde_json::Value::Null);
        assert_eq!(v["settingsRevision"], 0);
        assert!(v.get("suggestedPollIntervalMs").is_none());
        assert!(v.get("memberships").unwrap().is_array());
    }

    #[test]
    fn world_settings_max_backups_null_vs_absent() {
        let s: WorldSettings = serde_json::from_str(r#"{"maxBackups":null}"#).unwrap();
        assert_eq!(s.max_backups, Some(None));
        assert_eq!(serde_json::to_string(&s).unwrap(), r#"{"maxBackups":null}"#);
        let s: WorldSettings = serde_json::from_str(r#"{"gamerules":{"pvp":false}}"#).unwrap();
        assert_eq!(s.max_backups, None);
        assert_eq!(serde_json::to_string(&s).unwrap(), r#"{"gamerules":{"pvp":false}}"#);
    }

    #[test]
    fn enums_roundtrip() {
        for (s, e) in
            [("whole-gzip", FileTransferMode::WholeGzip), ("pack-delta", FileTransferMode::PackDelta)]
        {
            assert_eq!(FileTransferMode::parse(s), Some(e));
            assert_eq!(serde_json::to_string(&e).unwrap(), format!("\"{s}\""));
        }
        assert_eq!(serde_json::to_string(&EnterSessionAction::WarnHost).unwrap(), "\"warn-host\"");
        assert_eq!(serde_json::to_string(&StorageProviderType::GoogleDrive).unwrap(), "\"google-drive\"");
        assert_eq!(serde_json::to_string(&SignedBlobMethod::PUT).unwrap(), "\"PUT\"");
    }
}
