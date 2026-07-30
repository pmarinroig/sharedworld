package link.sharedworld.api;

import java.util.Map;

public final class SharedWorldModels {
    private SharedWorldModels() {
    }

    public record AuthChallengeDto(String serverId, String expiresAt) {
    }

    public record SessionTokenDto(String token, String playerUuid, String playerName, String expiresAt) {
    }

    public record DevSessionTokenDto(
            String token,
            String playerUuid,
            String playerName,
            String expiresAt,
            boolean allowInsecureE4mc
    ) {
        public SessionTokenDto sessionToken() {
            return new SessionTokenDto(this.token, this.playerUuid, this.playerName, this.expiresAt);
        }
    }

    public record WorldSummaryDto(
            String id,
            String slug,
            String name,
            String ownerUuid,
            String motd,
            String customIconStorageKey,
            SignedBlobUrlDto customIconDownload,
            int memberCount,
            String status,
            String lastSnapshotId,
            String lastSnapshotAt,
            String activeHostUuid,
            String activeHostPlayerName,
            String activeJoinTarget,
            int onlinePlayerCount,
            String[] onlinePlayerNames,
            String storageProvider,
            boolean storageLinked,
            String storageAccountEmail,
            Integer lastSnapshotDataVersion,
            String lastSnapshotMinecraftVersion
    ) {
        /** Pre-guardrail arity: callers without version knowledge leave both fields null. */
        public WorldSummaryDto(
                String id,
                String slug,
                String name,
                String ownerUuid,
                String motd,
                String customIconStorageKey,
                SignedBlobUrlDto customIconDownload,
                int memberCount,
                String status,
                String lastSnapshotId,
                String lastSnapshotAt,
                String activeHostUuid,
                String activeHostPlayerName,
                String activeJoinTarget,
                int onlinePlayerCount,
                String[] onlinePlayerNames,
                String storageProvider,
                boolean storageLinked,
                String storageAccountEmail
        ) {
            this(id, slug, name, ownerUuid, motd, customIconStorageKey, customIconDownload, memberCount, status,
                    lastSnapshotId, lastSnapshotAt, activeHostUuid, activeHostPlayerName, activeJoinTarget,
                    onlinePlayerCount, onlinePlayerNames, storageProvider, storageLinked, storageAccountEmail,
                    null, null);
        }
    }

    public record WorldMembershipDto(
            String worldId,
            String playerUuid,
            String playerName,
            String role,
            String joinedAt,
            String deletedAt,
            boolean canUseCommands
    ) {
        public WorldMembershipDto(
                String worldId,
                String playerUuid,
                String playerName,
                String role,
                String joinedAt,
                String deletedAt
        ) {
            this(worldId, playerUuid, playerName, role, joinedAt, deletedAt, false);
        }
    }

    public record WorldDetailsDto(
            String id,
            String slug,
            String name,
            String ownerUuid,
            String motd,
            String customIconStorageKey,
            SignedBlobUrlDto customIconDownload,
            int memberCount,
            String status,
            String lastSnapshotId,
            String lastSnapshotAt,
            String activeHostUuid,
            String activeHostPlayerName,
            String activeJoinTarget,
            int onlinePlayerCount,
            String[] onlinePlayerNames,
            String storageProvider,
            boolean storageLinked,
            String storageAccountEmail,
            WorldMembershipDto membership,
            WorldMembershipDto[] memberships,
            StorageUsageSummaryDto storageUsage,
            InviteCodeDto activeInviteCode
    ) {
    }

    public record CreateWorldResultDto(
            WorldDetailsDto world,
            HostAssignmentDto initialUploadAssignment
    ) {
    }

    /** Summary view of freshly fetched details, for screens that take the list row shape. */
    public static WorldSummaryDto summaryOf(WorldDetailsDto details) {
        return new WorldSummaryDto(
                details.id(),
                details.slug(),
                details.name(),
                details.ownerUuid(),
                details.motd(),
                details.customIconStorageKey(),
                details.customIconDownload(),
                details.memberCount(),
                details.status(),
                details.lastSnapshotId(),
                details.lastSnapshotAt(),
                details.activeHostUuid(),
                details.activeHostPlayerName(),
                details.activeJoinTarget(),
                details.onlinePlayerCount(),
                details.onlinePlayerNames(),
                details.storageProvider(),
                details.storageLinked(),
                details.storageAccountEmail(),
                null,
                null
        );
    }

    public record ImportedWorldSourceDto(
            String type,
            String id,
            String name
    ) {
    }

    public record StorageLinkSessionDto(
            String id,
            String provider,
            String status,
            String authUrl,
            String expiresAt,
            String linkedAccountEmail,
            String accountDisplayName,
            String errorMessage
    ) {
    }

    /** The caller's reusable linked storage account (GET /storage/account). */
    public record StorageAccountSummaryDto(
            boolean linked,
            String provider,
            String email,
            String displayName,
            boolean healthy
    ) {
    }

    public record StorageUsageSummaryDto(
            String provider,
            boolean linked,
            long usedBytes,
            Long quotaUsedBytes,
            Long quotaTotalBytes,
            String accountEmail
    ) {
    }

    public record WorldSnapshotSummaryDto(
            String snapshotId,
            String createdAt,
            String createdByUuid,
            int fileCount,
            long totalSize,
            long totalCompressedSize,
            boolean isLatest
    ) {
    }

    public record SnapshotActionResultDto(
            String worldId,
            String snapshotId
    ) {
    }

    public record ResetInviteResponseDto(
            String[] revokedInviteIds,
            InviteCodeDto invite
    ) {
    }

    public record InviteCodeDto(
            String id,
            String worldId,
            String code,
            String createdByUuid,
            String createdAt,
            String expiresAt,
            String status
    ) {
    }

    public record FinalizationActionResultDto(
            String worldId,
            String nextHostUuid,
            String nextHostPlayerName,
            String status
    ) {
    }

    public record StartupProgressDto(
            String label,
            String mode,
            Double fraction,
            String updatedAt
    ) {
    }

    public record ManifestFileDto(
            String path,
            String hash,
            long size,
            long compressedSize,
            String storageKey,
            String contentType,
            String transferMode,
            String baseSnapshotId,
            String baseHash,
            Integer chainDepth
    ) {
    }

    public record PackedManifestFileDto(
            String path,
            String hash,
            long size,
            String contentType
    ) {
    }

    public record SnapshotPackDto(
            String packId,
            String hash,
            long size,
            String storageKey,
            String transferMode,
            String baseSnapshotId,
            String baseHash,
            Integer chainDepth,
            PackedManifestFileDto[] files
    ) {
    }

    public record SnapshotManifestDto(
            String worldId,
            String snapshotId,
            String createdAt,
            String createdByUuid,
            ManifestFileDto[] files,
            SnapshotPackDto[] packs
    ) {
    }

    public record LocalFileDescriptorDto(
            String path,
            String hash,
            long size,
            long compressedSize,
            String contentType,
            boolean deltaCapable
    ) {
    }

    public record LocalPackDescriptorDto(
            String packId,
            String hash,
            long size,
            int fileCount,
            PackedManifestFileDto[] files
    ) {
    }

    public record SignedBlobUrlDto(
            String method,
            String url,
            Map<String, String> headers,
            String expiresAt
    ) {
    }

    public record UploadPlanEntryDto(
            LocalFileDescriptorDto file,
            boolean alreadyPresent,
            String storageKey,
            String transferMode,
            SignedBlobUrlDto upload,
            String fullStorageKey,
            SignedBlobUrlDto fullUpload,
            String deltaStorageKey,
            SignedBlobUrlDto deltaUpload,
            String baseSnapshotId,
            String baseHash,
            Integer baseChainDepth
    ) {
    }

    public record UploadPackPlanDto(
            LocalPackDescriptorDto pack,
            boolean alreadyPresent,
            String storageKey,
            String transferMode,
            SignedBlobUrlDto upload,
            String fullStorageKey,
            SignedBlobUrlDto fullUpload,
            String deltaStorageKey,
            SignedBlobUrlDto deltaUpload,
            String baseSnapshotId,
            String baseHash,
            Integer baseChainDepth
    ) {
    }

    public record UploadPlanDto(
            String worldId,
            String snapshotBaseId,
            UploadPlanEntryDto[] uploads,
            UploadPackPlanDto nonRegionPackUpload,
            UploadPackPlanDto[] regionBundleUploads,
            SyncPolicyDto syncPolicy
    ) {
    }

    public record IconUploadPrepareResponseDto(
            String storageKey,
            boolean alreadyPresent,
            SignedBlobUrlDto upload
    ) {
    }

    public record DownloadPlanStepDto(
            String transferMode,
            String storageKey,
            long artifactSize,
            String baseSnapshotId,
            String baseHash,
            SignedBlobUrlDto download
    ) {
    }

    public record DownloadPlanEntryDto(
            String path,
            String hash,
            long size,
            String contentType,
            DownloadPlanStepDto[] steps
    ) {
    }

    public record DownloadPackPlanDto(
            String packId,
            String hash,
            long size,
            PackedManifestFileDto[] files,
            DownloadPlanStepDto[] steps
    ) {
    }

    public record DownloadPlanDto(
            String worldId,
            String snapshotId,
            DownloadPlanEntryDto[] downloads,
            DownloadPackPlanDto nonRegionPackDownload,
            DownloadPackPlanDto[] regionBundleDownloads,
            String[] retainedPaths,
            SyncPolicyDto syncPolicy
    ) {
    }

    public record SyncPolicyDto(
            int maxParallelDownloads,
            int maxConcurrentUploadPreparations,
            int maxConcurrentUploads,
            int maxUploadStartsPerSecond,
            int retryBaseDelayMs,
            int retryMaxDelayMs
    ) {
    }

    public record UncleanShutdownWarningDto(
            String hostUuid,
            String hostPlayerName,
            String phase,
            long runtimeEpoch,
            String recordedAt
    ) {
    }

    public record WorldRuntimeStatusDto(
            String worldId,
            String phase,
            long runtimeEpoch,
            String hostUuid,
            String hostPlayerName,
            String candidateUuid,
            String candidatePlayerName,
            String joinTarget,
            String startupDeadlineAt,
            String runtimeTokenIssuedAt,
            String lastProgressAt,
            String revokedAt,
            StartupProgressDto startupProgress,
            UncleanShutdownWarningDto uncleanShutdownWarning,
            String hostMinecraftVersion
    ) {
        public WorldRuntimeStatusDto(
                String worldId,
                String phase,
                long runtimeEpoch,
                String hostUuid,
                String hostPlayerName,
                String candidateUuid,
                String candidatePlayerName,
                String joinTarget,
                String startupDeadlineAt,
                String runtimeTokenIssuedAt,
                String lastProgressAt,
                StartupProgressDto startupProgress,
                UncleanShutdownWarningDto uncleanShutdownWarning
        ) {
            this(
                    worldId,
                    phase,
                    runtimeEpoch,
                    hostUuid,
                    hostPlayerName,
                    candidateUuid,
                    candidatePlayerName,
                    joinTarget,
                    startupDeadlineAt,
                    runtimeTokenIssuedAt,
                    lastProgressAt,
                    null,
                    startupProgress,
                    uncleanShutdownWarning,
                    null
            );
        }

        public WorldRuntimeStatusDto(
                String worldId,
                String phase,
                long runtimeEpoch,
                String hostUuid,
                String hostPlayerName,
                String candidateUuid,
                String candidatePlayerName,
                String joinTarget,
                String startupDeadlineAt,
                String runtimeTokenIssuedAt,
                String lastProgressAt,
                StartupProgressDto startupProgress
        ) {
            this(
                    worldId,
                    phase,
                    runtimeEpoch,
                    hostUuid,
                    hostPlayerName,
                    candidateUuid,
                    candidatePlayerName,
                    joinTarget,
                    startupDeadlineAt,
                    runtimeTokenIssuedAt,
                    lastProgressAt,
                    null,
                    startupProgress,
                    null,
                    null
            );
        }
    }

    public record HostAssignmentDto(
            String worldId,
            String playerUuid,
            String playerName,
            long runtimeEpoch,
            String hostToken,
            String startupDeadlineAt
    ) {
    }

    public record HostHeartbeatMembershipDto(
            String playerUuid,
            String playerName,
            boolean canUseCommands
    ) {
    }

    /**
     * The heartbeat response body is a flat superset of {@link WorldRuntimeStatusDto}:
     * the same runtime fields at the top level plus the world's active membership
     * list. Older clients bind the identical body to WorldRuntimeStatusDto and
     * ignore the extra field, so this record must mirror it field-for-field.
     */
    public record HostHeartbeatResponseDto(
            String worldId,
            String phase,
            long runtimeEpoch,
            String hostUuid,
            String hostPlayerName,
            String candidateUuid,
            String candidatePlayerName,
            String joinTarget,
            String startupDeadlineAt,
            String runtimeTokenIssuedAt,
            String lastProgressAt,
            String revokedAt,
            StartupProgressDto startupProgress,
            UncleanShutdownWarningDto uncleanShutdownWarning,
            String hostMinecraftVersion,
            HostHeartbeatMembershipDto[] memberships
    ) {
        public WorldRuntimeStatusDto toRuntimeStatus() {
            return new WorldRuntimeStatusDto(
                    worldId,
                    phase,
                    runtimeEpoch,
                    hostUuid,
                    hostPlayerName,
                    candidateUuid,
                    candidatePlayerName,
                    joinTarget,
                    startupDeadlineAt,
                    runtimeTokenIssuedAt,
                    lastProgressAt,
                    revokedAt,
                    startupProgress,
                    uncleanShutdownWarning,
                    hostMinecraftVersion
            );
        }
    }

    public record EnterSessionResponseDto(
            String action,
            WorldSummaryDto world,
            SnapshotManifestDto latestManifest,
            WorldRuntimeStatusDto runtime,
            HostAssignmentDto assignment,
            String waiterSessionId
    ) {
    }

    public record ObserveWaitingResponseDto(
            String action,
            WorldRuntimeStatusDto runtime,
            HostAssignmentDto assignment,
            String waiterSessionId
    ) {
    }

    public record ErrorDto(String error, String message, int status) {
    }
}
