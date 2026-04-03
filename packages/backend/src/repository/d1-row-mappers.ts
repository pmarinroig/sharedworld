import type {
  InviteCode,
  StartupProgressMode,
  StorageProviderType,
  UncleanShutdownWarning,
  WorldRuntimePhase
} from "../../../shared/src/index.ts";

import type {
  StorageAccountRecord,
  StorageLinkSessionRecord,
  StorageObjectRecord,
  WorldUncleanShutdownWarningRecord
} from "../repository.ts";
import type { WorldRuntimeRecord } from "../runtime-protocol.ts";
import { asNullableString, clampFraction, type LegacyHostLease, type Row } from "./d1-support.ts";

export function mapInvite(row: Row): InviteCode {
  return {
    id: String(row.id),
    worldId: String(row.world_id),
    code: String(row.code),
    createdByUuid: String(row.created_by_uuid),
    createdAt: String(row.created_at),
    expiresAt: String(row.expires_at),
    status: String(row.status) as InviteCode["status"]
  };
}

export function mapUncleanShutdownWarning(row: Row | null): WorldUncleanShutdownWarningRecord | null {
  if (!row) {
    return null;
  }
  const hostUuid = asNullableString(row.unclean_shutdown_host_uuid);
  const hostPlayerName = asNullableString(row.unclean_shutdown_host_player_name);
  const phase = asNullableString(row.unclean_shutdown_phase);
  const runtimeEpoch = row.unclean_shutdown_runtime_epoch == null ? 1 : Number(row.unclean_shutdown_runtime_epoch);
  const recordedAt = asNullableString(row.unclean_shutdown_recorded_at);
  if (hostUuid == null || hostPlayerName == null || phase == null || recordedAt == null) {
    return null;
  }
  return {
    hostUuid,
    hostPlayerName,
    phase: phase as UncleanShutdownWarning["phase"],
    runtimeEpoch,
    recordedAt
  };
}

export function mapLease(row: Row): LegacyHostLease {
  return {
    worldId: String(row.world_id),
    hostUuid: String(row.host_uuid),
    hostPlayerName: String(row.host_player_name),
    status: String(row.status) as LegacyHostLease["status"],
    runtimePhase: asNullableString(row.runtime_phase) as WorldRuntimePhase | null,
    runtimeEpoch: row.runtime_epoch == null ? null : Number(row.runtime_epoch),
    runtimeToken: asNullableString(row.runtime_token),
    claimedAt: String(row.claimed_at),
    expiresAt: String(row.expires_at),
    updatedAt: String(row.updated_at),
    joinTarget: asNullableString(row.join_target),
    handoffCandidateUuid: asNullableString(row.handoff_candidate_uuid),
    revokedAt: asNullableString(row.revoked_at),
    startupDeadlineAt: asNullableString(row.startup_deadline_at),
    runtimeTokenIssuedAt: asNullableString(row.runtime_token_issued_at),
    lastProgressAt: asNullableString(row.last_progress_at),
    startupProgress: asNullableString(row.startup_progress_label) != null
      && asNullableString(row.startup_progress_mode) != null
      && asNullableString(row.startup_progress_updated_at) != null
      ? {
          label: String(row.startup_progress_label),
          mode: String(row.startup_progress_mode) as StartupProgressMode,
          fraction: row.startup_progress_fraction == null ? null : clampFraction(Number(row.startup_progress_fraction)),
          updatedAt: String(row.startup_progress_updated_at)
        }
      : null
  };
}

export function mapRuntimeRow(row: Row): WorldRuntimeRecord {
  return {
    worldId: String(row.world_id),
    phase: String(row.runtime_phase) as WorldRuntimeRecord["phase"],
    runtimeEpoch: Number(row.runtime_epoch),
    runtimeToken: asNullableString(row.runtime_token),
    hostUuid: String(row.host_uuid),
    hostPlayerName: String(row.host_player_name),
    candidateUuid: asNullableString(row.candidate_uuid),
    joinTarget: asNullableString(row.join_target),
    claimedAt: asNullableString(row.claimed_at),
    expiresAt: asNullableString(row.expires_at),
    startupDeadlineAt: asNullableString(row.startup_deadline_at),
    runtimeTokenIssuedAt: asNullableString(row.runtime_token_issued_at),
    lastProgressAt: asNullableString(row.last_progress_at),
    updatedAt: String(row.updated_at),
    revokedAt: asNullableString(row.revoked_at),
    startupProgress: asNullableString(row.startup_progress_label) != null
      && asNullableString(row.startup_progress_mode) != null
      && asNullableString(row.startup_progress_updated_at) != null
      ? {
          label: String(row.startup_progress_label),
          mode: String(row.startup_progress_mode) as StartupProgressMode,
          fraction: row.startup_progress_fraction == null ? null : clampFraction(Number(row.startup_progress_fraction)),
          updatedAt: String(row.startup_progress_updated_at)
        }
      : null
  };
}

export function mapStorageAccount(row: Row): StorageAccountRecord {
  return {
    id: String(row.id),
    provider: String(row.provider) as StorageProviderType,
    ownerPlayerUuid: String(row.owner_player_uuid),
    externalAccountId: String(row.external_account_id),
    email: asNullableString(row.email),
    displayName: asNullableString(row.display_name),
    accessToken: asNullableString(row.access_token),
    refreshToken: asNullableString(row.refresh_token),
    tokenExpiresAt: asNullableString(row.token_expires_at),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at)
  };
}

export function mapStorageLinkSession(row: Row): StorageLinkSessionRecord {
  return {
    id: String(row.id),
    playerUuid: String(row.player_uuid),
    provider: String(row.provider) as StorageProviderType,
    status: String(row.status) as StorageLinkSessionRecord["status"],
    authUrl: String(row.auth_url),
    state: String(row.state),
    linkedAccountEmail: asNullableString(row.linked_account_email),
    accountDisplayName: asNullableString(row.account_display_name),
    storageAccountId: asNullableString(row.storage_account_id),
    errorMessage: asNullableString(row.error_message),
    createdAt: String(row.created_at),
    expiresAt: String(row.expires_at),
    completedAt: asNullableString(row.completed_at)
  };
}

export function mapStorageObject(row: Row): StorageObjectRecord {
  return {
    provider: String(row.provider) as StorageProviderType,
    storageAccountId: String(row.storage_account_id),
    storageKey: String(row.storage_key),
    objectId: String(row.object_id),
    contentType: String(row.content_type),
    size: Number(row.size),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at)
  };
}
