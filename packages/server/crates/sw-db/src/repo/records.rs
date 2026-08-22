//! Record types from `repository.ts` (DB-side shapes that are not wire DTOs).

use sw_contracts::{StorageLinkStatus, StorageProviderType};

#[derive(Debug, Clone, PartialEq)]
pub struct AuthChallengeRecord {
    pub server_id: String,
    pub expires_at: String,
    pub used_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct UserRecord {
    pub player_uuid: String,
    pub player_name: String,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SnapshotRecord {
    pub snapshot_id: String,
    pub world_id: String,
    pub created_at: String,
    pub created_by_uuid: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct StorageAccountRecord {
    pub id: String,
    pub provider: StorageProviderType,
    pub owner_player_uuid: String,
    pub external_account_id: String,
    pub email: Option<String>,
    pub display_name: Option<String>,
    pub access_token: Option<String>,
    pub refresh_token: Option<String>,
    pub token_expires_at: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct StorageLinkSessionRecord {
    pub id: String,
    pub player_uuid: String,
    pub provider: StorageProviderType,
    pub status: StorageLinkStatus,
    pub auth_url: String,
    pub state: String,
    pub linked_account_email: Option<String>,
    pub account_display_name: Option<String>,
    pub storage_account_id: Option<String>,
    pub error_message: Option<String>,
    pub created_at: String,
    pub expires_at: String,
    pub completed_at: Option<String>,
}

/// Partial update for a link session: `Some(x)` sets (x may be `None` for an
/// explicit clear), `None` keeps the current value.
#[derive(Debug, Clone, Default)]
pub struct StorageLinkSessionUpdate {
    pub status: Option<StorageLinkStatus>,
    pub linked_account_email: Option<Option<String>>,
    pub error_message: Option<Option<String>>,
    pub storage_account_id: Option<Option<String>>,
    pub completed_at: Option<Option<String>>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct StorageObjectRecord {
    pub provider: StorageProviderType,
    pub storage_account_id: String,
    pub storage_key: String,
    pub object_id: String,
    pub content_type: String,
    pub size: i64,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct StorageUploadSessionRecord {
    pub upload_id: String,
    pub provider: StorageProviderType,
    pub storage_account_id: String,
    pub world_id: String,
    pub storage_key: String,
    pub session_url: String,
    pub content_type: String,
    pub expected_size: i64,
    pub created_at: String,
    pub confirmed_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct SnapshotDeletionResult {
    pub deleted_snapshot_ids: Vec<String>,
    pub unreferenced_storage_keys: Vec<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DeleteWorldResult {
    pub world_deleted: bool,
    pub deleted_custom_icon_storage_key: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct WorldStorageBinding {
    pub provider: StorageProviderType,
    pub storage_account_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PendingBlobDeleteRecord {
    pub provider: StorageProviderType,
    pub storage_account_id: String,
    pub storage_key: String,
    pub attempts: i64,
    pub enqueued_at: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PendingBlobDeleteEntry {
    pub storage_key: String,
    pub attempts: i64,
    pub enqueued_at: String,
}

/// Where a storage key could still be referenced from (see `repository.ts`).
#[derive(Debug, Clone, PartialEq)]
pub struct StorageReferenceScope {
    pub provider: StorageProviderType,
    pub storage_account_id: Option<String>,
    pub snapshots_created_since: Option<String>,
}

/// Slack applied to `created_at` bounds on snapshots (see `repository.ts`).
pub const SNAPSHOT_CREATED_AT_SLACK_MS: i64 = 15 * 60_000;

/// The caller identity the repository needs (`RequestContext` minus runtime
/// concerns like `defer`, which live in the service layer).
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Actor {
    pub player_uuid: String,
    pub player_name: String,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct WorldUpdateRecord {
    pub name: String,
    pub motd_line1: Option<String>,
    pub motd_line2: Option<String>,
    pub clear_custom_icon: bool,
    /// `None` = keep the current icon key; `Some(x)` = set to x.
    pub custom_icon_storage_key: Option<Option<String>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SessionActorFacts {
    pub membership_active: bool,
    pub ever_member: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct WorldSettingsRow {
    pub settings: Option<sw_contracts::WorldSettings>,
    pub settings_revision: i64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RuntimeMirrorRow {
    pub status_json: Option<String>,
    pub room_players_json: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SnapshotGameVersions {
    pub data_version: Option<i64>,
    pub minecraft_version: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct SnapshotDeltaBase {
    pub snapshot_id: String,
    pub base_snapshot_id: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MojangServicesKeysRow {
    pub fetched_at: String,
    pub keys_json: String,
}

pub(crate) fn provider_of(s: &str) -> StorageProviderType {
    StorageProviderType::parse(s).unwrap_or(StorageProviderType::GoogleDrive)
}
