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
            String storageAccountEmail
    ) {
    }

    public record WorldMembershipDto(
            String worldId,
            String playerUuid,
            String playerName,
            String role,
            String joinedAt,
            String deletedAt
    ) {
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
            UncleanShutdownWarningDto uncleanShutdownWarning
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
                    uncleanShutdownWarning
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
