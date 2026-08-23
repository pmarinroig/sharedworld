package link.sharedworld.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import link.sharedworld.CanonicalPlayerIdentity;
import link.sharedworld.RuntimePlayerIdentity;
import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.api.SharedWorldModels.AuthChallengeDto;
import link.sharedworld.api.SharedWorldModels.CreateWorldResultDto;
import link.sharedworld.api.SharedWorldModels.DeleteSnapshotsResultDto;
import link.sharedworld.api.SharedWorldModels.DevSessionTokenDto;
import link.sharedworld.api.SharedWorldModels.DownloadPlanDto;
import link.sharedworld.api.SharedWorldModels.ErrorDto;
import link.sharedworld.api.SharedWorldModels.EnterSessionResponseDto;
import link.sharedworld.api.SharedWorldModels.FinalizationActionResultDto;
import link.sharedworld.api.SharedWorldModels.HostAssignmentDto;
import link.sharedworld.api.SharedWorldModels.InviteCodeDto;
import link.sharedworld.api.SharedWorldModels.ImportedWorldSourceDto;
import link.sharedworld.api.SharedWorldModels.LocalFileDescriptorDto;
import link.sharedworld.api.SharedWorldModels.LocalPackDescriptorDto;
import link.sharedworld.api.SharedWorldModels.ManifestFileDto;
import link.sharedworld.api.SharedWorldModels.ObserveWaitingResponseDto;
import link.sharedworld.api.SharedWorldModels.SnapshotPackDto;
import link.sharedworld.api.SharedWorldModels.ResetInviteResponseDto;
import link.sharedworld.api.SharedWorldModels.SessionTokenDto;
import link.sharedworld.api.SharedWorldModels.SignedBlobUrlDto;
import link.sharedworld.api.SharedWorldModels.SnapshotActionResultDto;
import link.sharedworld.api.SharedWorldModels.SnapshotManifestDto;
import link.sharedworld.api.SharedWorldModels.StorageLinkSessionDto;
import link.sharedworld.api.SharedWorldModels.UploadPlanDto;
import link.sharedworld.api.SharedWorldModels.WorldDetailsDto;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import link.sharedworld.api.SharedWorldModels.WorldSnapshotSummaryDto;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.util.TransferWatchdog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

public final class SharedWorldApiClient {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("sharedworld");

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Gson gson;
    private final SessionIdentityProvider sessionIdentityProvider;
    private final ProfileCertificateProvider certificateProvider;
    private SessionPersistence sessionPersistence;
    private SessionTokenDto cachedSession;
    private boolean cachedSessionIsDev;
    private boolean cachedAllowInsecureE4mc;

    public SharedWorldApiClient(String baseUrl) {
        this(
                baseUrl,
                defaultHttpClient(),
                SharedWorldApiClient::resolveSessionIdentity,
                SharedWorldApiClient::resolveProfileCertificate
        );
    }

    public SharedWorldApiClient(String baseUrl, SessionIdentityProvider sessionIdentityProvider) {
        this(
                baseUrl,
                defaultHttpClient(),
                sessionIdentityProvider,
                SharedWorldApiClient::resolveProfileCertificate
        );
    }

    public SharedWorldApiClient(String baseUrl, HttpClient httpClient) {
        this(
                baseUrl,
                httpClient,
                SharedWorldApiClient::resolveSessionIdentity,
                SharedWorldApiClient::resolveProfileCertificate
        );
    }

    public SharedWorldApiClient(String baseUrl, HttpClient httpClient, SessionIdentityProvider sessionIdentityProvider) {
        this(
                baseUrl,
                httpClient,
                sessionIdentityProvider,
                SharedWorldApiClient::resolveProfileCertificate
        );
    }

    public SharedWorldApiClient(
            String baseUrl,
            HttpClient httpClient,
            SessionIdentityProvider sessionIdentityProvider,
            ProfileCertificateProvider certificateProvider
    ) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.sessionIdentityProvider = Objects.requireNonNull(sessionIdentityProvider, "sessionIdentityProvider");
        this.certificateProvider = Objects.requireNonNull(certificateProvider, "certificateProvider");
        this.gson = new Gson();
    }

    public List<WorldSummaryDto> listWorlds() throws IOException, InterruptedException {
        ensureSession();
        return Arrays.asList(withTransportRetry(() -> conditionalGet("/worlds", WorldSummaryDto[].class)));
    }

    public WorldDetailsDto getWorld(String worldId) throws IOException, InterruptedException {
        ensureSession();
        return conditionalGet("/worlds/" + worldId, WorldDetailsDto.class);
    }

    /** On-demand storage usage (0.4.1+): world details no longer carry it inline. */
    public SharedWorldModels.StorageUsageSummaryDto getStorageUsage(String worldId) throws IOException, InterruptedException {
        ensureSession();
        return request("GET", "/worlds/" + worldId + "/storage-usage", null, SharedWorldModels.StorageUsageSummaryDto.class, true);
    }

    public CreateWorldResultDto createWorld(
            String name,
            String motdLine1,
            String motdLine2,
            String customIconPngBase64,
            ImportedWorldSourceDto importSource,
            String storageLinkSessionId
    ) throws IOException, InterruptedException {
        return createWorld(name, motdLine1, motdLine2, customIconPngBase64, importSource, storageLinkSessionId, false);
    }

    public CreateWorldResultDto createWorld(
            String name,
            String motdLine1,
            String motdLine2,
            String customIconPngBase64,
            ImportedWorldSourceDto importSource,
            String storageLinkSessionId,
            boolean useLinkedStorageAccount
    ) throws IOException, InterruptedException {
        return createWorld(name, motdLine1, motdLine2, customIconPngBase64, importSource, storageLinkSessionId, useLinkedStorageAccount, null);
    }

    public CreateWorldResultDto createWorld(
            String name,
            String motdLine1,
            String motdLine2,
            String customIconPngBase64,
            ImportedWorldSourceDto importSource,
            String storageLinkSessionId,
            boolean useLinkedStorageAccount,
            String linkedStorageProvider
    ) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("motdLine1", blankToNull(motdLine1));
        body.put("motdLine2", blankToNull(motdLine2));
        body.put("customIconPngBase64", blankToNull(customIconPngBase64));
        body.put("importSource", importSource);
        body.put("storageLinkSessionId", storageLinkSessionId);
        if (useLinkedStorageAccount) {
            body.put("useLinkedStorageAccount", true);
            if (linkedStorageProvider != null && !linkedStorageProvider.isBlank()) {
                body.put("linkedStorageProvider", linkedStorageProvider);
            }
        }
        return request("POST", "/worlds", body, CreateWorldResultDto.class, true);
    }

    public WorldDetailsDto updateWorld(
            String worldId,
            String name,
            String motdLine1,
            String motdLine2,
            String customIconPngBase64,
            boolean clearCustomIcon
    ) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("motdLine1", blankToNull(motdLine1));
        body.put("motdLine2", blankToNull(motdLine2));
        body.put("customIconPngBase64", blankToNull(customIconPngBase64));
        body.put("clearCustomIcon", clearCustomIcon);
        return request("PATCH", "/worlds/" + worldId, body, WorldDetailsDto.class, true);
    }

    public WorldDetailsDto putWorldSettings(String worldId, SharedWorldModels.WorldSettingsDto settings) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settings", settings);
        return request("PUT", "/worlds/" + worldId + "/settings", body, WorldDetailsDto.class, true);
    }

    public StorageLinkSessionDto createStorageLink() throws IOException, InterruptedException {
        return createStorageLink(false);
    }

    public StorageLinkSessionDto createStorageLink(boolean forceConsent) throws IOException, InterruptedException {
        return createStorageLink(forceConsent, null);
    }

    /** 0.5.0: provider "s3" starts a bring-your-own-bucket link (null = Google Drive). */
    public StorageLinkSessionDto createStorageLink(boolean forceConsent, String provider) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        if (forceConsent) {
            body.put("forceConsent", true);
        }
        if (provider != null && !provider.isBlank()) {
            body.put("provider", provider);
        }
        return request("POST", "/storage/link-sessions", body, StorageLinkSessionDto.class, true);
    }

    public SharedWorldModels.StorageAccountSummaryDto getStorageAccount() throws IOException, InterruptedException {
        return getStorageAccount(null);
    }

    /** 0.5.0: provider "s3" reads the S3 account summary (null = the server default, Google Drive). */
    public SharedWorldModels.StorageAccountSummaryDto getStorageAccount(String provider) throws IOException, InterruptedException {
        ensureSession();
        if (provider == null || provider.isBlank()) {
            return request("GET", "/storage/account", null, SharedWorldModels.StorageAccountSummaryDto.class, true);
        }
        return request("GET", "/storage/account?provider=" + provider, null, SharedWorldModels.StorageAccountSummaryDto.class, true);
    }

    /** Unlink every Google Drive account (409 storage_unlink_blocked while worlds still use it). */
    public void unlinkStorageAccount() throws IOException, InterruptedException {
        unlinkStorageAccount(null);
    }

    /** 0.5.0: provider "s3" unlinks the S3 account instead. */
    public void unlinkStorageAccount(String provider) throws IOException, InterruptedException {
        ensureSession();
        if (provider == null || provider.isBlank()) {
            request("DELETE", "/storage/account", null, null, true);
            return;
        }
        request("DELETE", "/storage/account?provider=" + provider, null, null, true);
    }

    /**
     * One bounded step of full account deletion; callers loop until done.
     * Generous timeout: a step's Drive sweep is time-budgeted server-side
     * (~8s of sequential Drive deletes) but still has real network tail.
     */
    public SharedWorldModels.AccountDeleteStepDto deleteAccountStep() throws IOException, InterruptedException {
        ensureSession();
        return request("DELETE", "/account", null, SharedWorldModels.AccountDeleteStepDto.class, true, Duration.ofSeconds(60));
    }

    public StorageLinkSessionDto getStorageLink(String sessionId) throws IOException, InterruptedException {
        ensureSession();
        return request("GET", "/storage/link-sessions/" + sessionId, null, StorageLinkSessionDto.class, true);
    }

    public StorageLinkSessionDto cancelStorageLink(String sessionId) throws IOException, InterruptedException {
        ensureSession();
        return request("POST", "/storage/link-sessions/" + sessionId + "/cancel", Map.of(), StorageLinkSessionDto.class, true);
    }

    public void deleteWorld(String worldId) throws IOException, InterruptedException {
        ensureSession();
        request("DELETE", "/worlds/" + worldId, null, null, true);
    }

    public InviteCodeDto createInvite(String worldId) throws IOException, InterruptedException {
        ensureSession();
        return request("POST", "/worlds/" + worldId + "/invites", Map.of(), InviteCodeDto.class, true);
    }

    public ResetInviteResponseDto resetInvite(String worldId) throws IOException, InterruptedException {
        ensureSession();
        return request("POST", "/worlds/" + worldId + "/invites/reset", Map.of(), ResetInviteResponseDto.class, true);
    }

    public void kickMember(String worldId, String playerUuid) throws IOException, InterruptedException {
        ensureSession();
        request("DELETE", "/worlds/" + worldId + "/members/" + playerUuid, null, null, true);
    }

    public SharedWorldModels.WorldMembershipDto setMemberCommandPermission(
            String worldId,
            String playerUuid,
            boolean canUseCommands
    ) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = Map.of("canUseCommands", canUseCommands);
        return request("PATCH", "/worlds/" + worldId + "/members/" + playerUuid, body,
                SharedWorldModels.WorldMembershipDto.class, true);
    }

    public WorldDetailsDto redeemInvite(String code) throws IOException, InterruptedException {
        ensureSession();
        return request("POST", "/invites/redeem", Map.of("code", code), WorldDetailsDto.class, true);
    }

    public EnterSessionResponseDto enterSession(String worldId) throws IOException, InterruptedException {
        return enterSession(worldId, null, false);
    }

    public EnterSessionResponseDto enterSession(String worldId, String waiterSessionId) throws IOException, InterruptedException {
        return enterSession(worldId, waiterSessionId, false);
    }

    public EnterSessionResponseDto enterSession(String worldId, String waiterSessionId, boolean acknowledgeUncleanShutdown) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("waiterSessionId", waiterSessionId);
        body.put("acknowledgeUncleanShutdown", acknowledgeUncleanShutdown);
        return request("POST", "/worlds/" + worldId + "/session/enter", body, EnterSessionResponseDto.class, true);
    }

    public WorldRuntimeStatusDto runtimeStatus(String worldId) throws IOException, InterruptedException {
        ensureSession();
        return requestWithTransportRetry("GET", "/worlds/" + worldId + "/runtime", WorldRuntimeStatusDto.class);
    }

    public SharedWorldModels.HostHeartbeatResponseDto heartbeatHost(String worldId, long runtimeEpoch, String hostToken, String joinTarget) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        if (joinTarget != null) {
            body.put("joinTarget", joinTarget);
        }
        String minecraftVersion = link.sharedworld.versioned.ClientCompat.currentMinecraftVersion();
        if (minecraftVersion != null) {
            body.put("minecraftVersion", minecraftVersion);
        }
        return request("POST", "/worlds/" + worldId + "/heartbeat", body, SharedWorldModels.HostHeartbeatResponseDto.class, true);
    }

    /** Persist the managed server's current gamerule/difficulty/game-mode values (host runtime authority, not ownership). */
    public SharedWorldModels.HostGameRulesReportResponseDto reportHostGameRules(String worldId, long runtimeEpoch, String hostToken, Map<String, Boolean> gamerules, String difficulty, String defaultGameMode) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        body.put("gamerules", gamerules);
        if (difficulty != null) {
            body.put("difficulty", difficulty);
        }
        if (defaultGameMode != null) {
            body.put("defaultGameMode", defaultGameMode);
        }
        return request("POST", "/worlds/" + worldId + "/host-gamerules", body, SharedWorldModels.HostGameRulesReportResponseDto.class, true);
    }

    public void setHostStartupProgress(String worldId, long runtimeEpoch, String hostToken, SharedWorldModels.StartupProgressDto progress) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        if (progress != null) {
            body.put("label", progress.label());
            body.put("mode", progress.mode());
            body.put("fraction", progress.fraction());
        } else {
            body.put("label", null);
            body.put("mode", null);
            body.put("fraction", null);
        }
        request("POST", "/worlds/" + worldId + "/host-startup-progress", body, Object.class, true);
    }

    public SharedWorldModels.GuestHeartbeatResponseDto setPresence(String worldId, boolean present, long guestSessionEpoch, long presenceSequence) throws IOException, InterruptedException {
        ensureSession();
        return request(
                "POST",
                "/worlds/" + worldId + "/presence",
                Map.of(
                        "present", present,
                        "guestSessionEpoch", guestSessionEpoch,
                        "presenceSequence", presenceSequence
                ),
                SharedWorldModels.GuestHeartbeatResponseDto.class,
                true
        );
    }

    public FinalizationActionResultDto beginFinalization(String worldId, long runtimeEpoch, String hostToken) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        return request("POST", "/worlds/" + worldId + "/begin-finalization", body, FinalizationActionResultDto.class, true);
    }

    public FinalizationActionResultDto completeFinalization(String worldId, long runtimeEpoch, String hostToken) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        return request("POST", "/worlds/" + worldId + "/complete-finalization", body, FinalizationActionResultDto.class, true);
    }

    public FinalizationActionResultDto abandonFinalization(String worldId) throws IOException, InterruptedException {
        ensureSession();
        return request("POST", "/worlds/" + worldId + "/abandon-finalization", Map.of(), FinalizationActionResultDto.class, true);
    }

    public ObserveWaitingResponseDto observeWaiting(String worldId, String waiterSessionId) throws IOException, InterruptedException {
        ensureSession();
        return request("POST", "/worlds/" + worldId + "/session/waiting/observe", Map.of("waiterSessionId", waiterSessionId), ObserveWaitingResponseDto.class, true);
    }

    public WorldRuntimeStatusDto cancelWaiting(String worldId, String waiterSessionId) throws IOException, InterruptedException {
        ensureSession();
        return request("POST", "/worlds/" + worldId + "/session/waiting/cancel", Map.of("waiterSessionId", waiterSessionId), WorldRuntimeStatusDto.class, true);
    }

    public SnapshotManifestDto latestManifest(String worldId) throws IOException, InterruptedException {
        ensureSession();
        return requestWithTransportRetry("GET", "/worlds/" + worldId + "/snapshots/latest-manifest", SnapshotManifestDto.class);
    }

    public WorldSnapshotSummaryDto[] listSnapshots(String worldId) throws IOException, InterruptedException {
        ensureSession();
        return request("GET", "/worlds/" + worldId + "/snapshots", null, WorldSnapshotSummaryDto[].class, true);
    }

    public SnapshotActionResultDto restoreSnapshot(String worldId, String snapshotId) throws IOException, InterruptedException {
        ensureSession();
        return request("POST", "/worlds/" + worldId + "/snapshots/" + snapshotId + "/restore", Map.of(), SnapshotActionResultDto.class, true);
    }

    public SnapshotActionResultDto deleteSnapshot(String worldId, String snapshotId) throws IOException, InterruptedException {
        ensureSession();
        return request("DELETE", "/worlds/" + worldId + "/snapshots/" + snapshotId, null, SnapshotActionResultDto.class, true);
    }

    /** 0.4.5: one request for any number of backups; the backend answers once the rows are gone. */
    public DeleteSnapshotsResultDto deleteSnapshots(String worldId, java.util.List<String> snapshotIds) throws IOException, InterruptedException {
        ensureSession();
        return request("POST", "/worlds/" + worldId + "/snapshots/delete", Map.of("snapshotIds", snapshotIds), DeleteSnapshotsResultDto.class, true);
    }

    public UploadPlanDto prepareUploads(String worldId, long runtimeEpoch, String hostToken, LocalFileDescriptorDto[] files, LocalPackDescriptorDto nonRegionPack, LocalPackDescriptorDto[] regionBundles) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        body.put("files", files);
        body.put("nonRegionPack", nonRegionPack);
        body.put("regionBundles", regionBundles);
        // Plan computation only, no server-side write, so a transport blip
        // is retried instead of aborting the whole create/sync.
        return requestWithTransportRetry("POST", "/worlds/" + worldId + "/uploads/prepare", body, UploadPlanDto.class, SNAPSHOT_REQUEST_TIMEOUT);
    }

    public UploadPlanDto prepareUploads(String worldId, LocalFileDescriptorDto[] files, LocalPackDescriptorDto nonRegionPack, LocalPackDescriptorDto[] regionBundles) throws IOException, InterruptedException {
        return prepareUploads(worldId, -1L, null, files, nonRegionPack, regionBundles);
    }

    public SnapshotManifestDto finalizeSnapshot(String worldId, long runtimeEpoch, String hostToken, String baseSnapshotId, ManifestFileDto[] files, SnapshotPackDto[] packs) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        body.put("baseSnapshotId", baseSnapshotId);
        String minecraftVersion = link.sharedworld.versioned.ClientCompat.currentMinecraftVersion();
        if (minecraftVersion != null) {
            body.put("dataVersion", link.sharedworld.versioned.ClientCompat.currentDataVersion());
            body.put("minecraftVersion", minecraftVersion);
        }
        body.put("files", files);
        body.put("packs", packs);
        return request(
                "POST",
                "/worlds/" + worldId + "/uploads/finalize-snapshot",
                body,
                SnapshotManifestDto.class,
                true,
                SNAPSHOT_REQUEST_TIMEOUT
        );
    }

    public SnapshotManifestDto finalizeSnapshot(String worldId, String baseSnapshotId, ManifestFileDto[] files, SnapshotPackDto[] packs) throws IOException, InterruptedException {
        return finalizeSnapshot(worldId, -1L, null, baseSnapshotId, files, packs);
    }

    /**
     * POST with the local state in the body: the pre-0.3.1 GET carried it in
     * x-sharedworld-* headers, which overflow edge header limits on worlds
     * with many files. Requires a backend new enough to route the POST;
     * the backend always deploys before the mod releases.
     */
    public DownloadPlanDto downloadPlan(String worldId, LocalFileDescriptorDto[] files, LocalPackDescriptorDto nonRegionPack, LocalPackDescriptorDto[] regionBundles) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", files);
        body.put("nonRegionPack", nonRegionPack);
        body.put("regionBundles", regionBundles);
        // Plan computation only, no server-side write, safe to retry.
        return requestWithTransportRetry("POST", "/worlds/" + worldId + "/downloads/plan", body, DownloadPlanDto.class);
    }

    public SharedWorldModels.CreateBlobSessionResponseDto createBlobSession(
            String worldId, String storageKey, long runtimeEpoch, String hostToken, String contentType, long contentLength, String blobStamp
    ) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storageKey", storageKey);
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        body.put("contentType", contentType);
        body.put("contentLength", contentLength);
        if (blobStamp != null && !blobStamp.isEmpty()) {
            // HMAC authority stamp from the plan's signed headers: lets the
            // backend authorize this artifact without a coordinator call.
            body.put("blobStamp", blobStamp);
        }
        return request("POST", "/worlds/" + worldId + "/uploads/blob-session", body, SharedWorldModels.CreateBlobSessionResponseDto.class, true);
    }

    public SharedWorldModels.CommitBlobSessionResponseDto commitBlobSession(
            String worldId, String uploadId, long runtimeEpoch, String hostToken, String blobStamp
    ) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uploadId", uploadId);
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        if (blobStamp != null && !blobStamp.isEmpty()) {
            body.put("blobStamp", blobStamp);
        }
        return request("POST", "/worlds/" + worldId + "/uploads/blob-commit", body, SharedWorldModels.CommitBlobSessionResponseDto.class, true);
    }

    /**
     * Direct-to-provider upload for one storage key: session init at the
     * backend, chunked resumable PUTs straight to the provider, then an
     * idempotent commit that has the backend verify the provider's own
     * account of the bytes. A dead session (provider forgot it) is recreated
     * once and only this artifact restarts.
     */
    public void uploadBlobDirect(
            String worldId,
            String storageKey,
            long runtimeEpoch,
            String hostToken,
            String blobStamp,
            Path bodyFile,
            String contentType,
            long fallbackChunkSizeBytes,
            UploadProgressListener progressListener
    ) throws IOException, InterruptedException {
        long contentLength = java.nio.file.Files.size(bodyFile);
        for (int sessionAttempt = 0; ; sessionAttempt++) {
            SharedWorldModels.CreateBlobSessionResponseDto session =
                    createBlobSession(worldId, storageKey, runtimeEpoch, hostToken, contentType, contentLength, blobStamp);
            long chunkSize = session.chunkSizeBytes() > 0 ? session.chunkSizeBytes() : fallbackChunkSizeBytes;
            try {
                new ResumableBlobUploader(this.httpClient, session.sessionUrl(), chunkSize)
                        .upload(bodyFile, contentType, progressListener);
                commitBlobSessionWithRetry(worldId, session.uploadId(), runtimeEpoch, hostToken, blobStamp);
                return;
            } catch (ResumableBlobUploader.SessionGoneException gone) {
                if (sessionAttempt >= 1) {
                    throw gone;
                }
                // Fresh session (same storage key reuses the provider file id
                // server-side), restart this artifact only.
            }
        }
    }

    private void commitBlobSessionWithRetry(String worldId, String uploadId, long runtimeEpoch, String hostToken, String blobStamp)
            throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                commitBlobSession(worldId, uploadId, runtimeEpoch, hostToken, blobStamp);
                return;
            } catch (IOException exception) {
                // Commit is idempotent server-side; only transport-level
                // failures are worth replaying.
                if (!isRetryableTransportError(exception) || attempt == 3) {
                    throw exception;
                }
                lastFailure = exception;
                Thread.sleep(500L * attempt);
            }
        }
        throw lastFailure;
    }

    public void uploadBlob(SignedBlobUrlDto signedUrl, Path bodyFile, String contentType) throws IOException, InterruptedException {
        this.uploadBlob(signedUrl, bodyFile, contentType, null);
    }

    public void uploadBlob(SignedBlobUrlDto signedUrl, Path bodyFile, String contentType, UploadProgressListener progressListener) throws IOException, InterruptedException {
        long bodySize = Files.size(bodyFile);
        // The progress wrapper must still declare the body length: a bare
        // ofInputStream publisher has unknown length, which makes the JDK
        // client send Transfer-Encoding: chunked with NO Content-Length,
        // and a known length is what lets the relay stream the body to
        // storage instead of buffering it.
        HttpRequest.BodyPublisher bodyPublisher = progressListener == null
                ? HttpRequest.BodyPublishers.ofFile(bodyFile)
                : HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofInputStream(() -> {
                            try {
                                return new ProgressInputStream(Files.newInputStream(bodyFile), bodySize, progressListener);
                            } catch (IOException exception) {
                                throw new RuntimeException(exception);
                            }
                        }),
                        bodySize
                );
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(signedUrl.url()))
                // The timeout covers the whole exchange including body
                // streaming: a 40 MB shard on a residential uplink takes
                // minutes, so a tight deadline turned every large-world
                // upload into "request timed out". Stall detection belongs
                // to the progress listener, not this deadline.
                .timeout(Duration.ofMinutes(10))
                .method(signedUrl.method(), bodyPublisher);
        applyBlobAuth(builder, signedUrl);

        if (contentType != null && !contentType.isBlank()) {
            builder.header("content-type", contentType);
        }
        if (signedUrl.headers() != null) {
            signedUrl.headers().forEach(builder::header);
        }

        HttpResponse<String> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            // Signed blob URLs may point at a non-backend store whose error
            // body is not our JSON shape; tryParseError falls back to a
            // generic http_error code with the real status either way.
            throw blobTransferError("upload", response.body(), response.statusCode(), response.headers());
        }
    }

    public void downloadBlobToFile(SignedBlobUrlDto signedUrl, Path target) throws IOException, InterruptedException {
        this.downloadBlobToFile(signedUrl, target, null);
    }

    public void downloadBlobToFile(SignedBlobUrlDto signedUrl, Path target, DownloadProgressListener progressListener) throws IOException, InterruptedException {
        downloadToFileResumable(signedUrl, target, progressListener, true);
    }

    public void downloadRawBlobToFile(SignedBlobUrlDto signedUrl, Path target) throws IOException, InterruptedException {
        this.downloadRawBlobToFile(signedUrl, target, null);
    }

    public void downloadRawBlobToFile(SignedBlobUrlDto signedUrl, Path target, DownloadProgressListener progressListener) throws IOException, InterruptedException {
        downloadToFileResumable(signedUrl, target, progressListener, false);
    }

    /**
     * Resumable download core. Raw bytes land in a sibling {@code .swpart}
     * file that survives failed attempts: a retry asks the backend to resume
     * with {@code Range: bytes=N-} (206 appends, a 200 from an older backend
     * truncates and restarts), so a flaky link pays for the bytes it lost,
     * not the whole blob. Progress is bounded by the stall watchdog instead
     * of a whole-exchange deadline; a healthy multi-GB transfer has no
     * upper duration, a stalled one is aborted and retried. The gunzip
     * variant decompresses only after the raw part completes, keeping resume
     * arithmetic in compressed bytes.
     */
    private void downloadToFileResumable(
            SignedBlobUrlDto signedUrl,
            Path target,
            DownloadProgressListener progressListener,
            boolean gunzip
    ) throws IOException, InterruptedException {
        Path partial = target.resolveSibling(target.getFileName() + ".swpart");
        long resumeOffset = Files.exists(partial) ? Files.size(partial) : 0L;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(signedUrl.url()))
                // Bounds time-to-response-headers only; body streaming is
                // governed by the stall watchdog (java.net.http has no read
                // timeout, and a fixed whole-exchange deadline is wrong for
                // transfers whose healthy duration is unbounded).
                .timeout(Duration.ofSeconds(30))
                .method(signedUrl.method(), HttpRequest.BodyPublishers.noBody());
        applyBlobAuth(builder, signedUrl);
        if (resumeOffset > 0L) {
            builder.header("range", "bytes=" + resumeOffset + "-");
        }
        if (signedUrl.headers() != null) {
            signedUrl.headers().forEach(builder::header);
        }

        HttpResponse<InputStream> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw blobTransferError("download", readErrorBody(response.body()), response.statusCode(), response.headers());
        }
        boolean resumed = response.statusCode() == 206 && resumeOffset > 0L;
        long baseOffset = resumed ? resumeOffset : 0L;
        long remaining = response.headers().firstValueAsLong("content-length").orElse(-1L);
        long totalBytes = totalFromContentRange(response.headers().firstValue("content-range").orElse(null));
        if (totalBytes < 0L) {
            totalBytes = remaining < 0L ? -1L : baseOffset + remaining;
        }

        InputStream rawBody = response.body();
        long reportedTotal = totalBytes;
        try (TransferWatchdog watchdog = TransferWatchdog.watching(rawBody)) {
            UploadProgressListener pulse = (transferred, ignoredTotal) -> {
                watchdog.pulse();
                if (progressListener != null) {
                    progressListener.onBytesTransferred(baseOffset + transferred, reportedTotal);
                }
            };
            try (InputStream body = new ProgressInputStream(rawBody, remaining, pulse);
                 OutputStream output = Files.newOutputStream(partial,
                         java.nio.file.StandardOpenOption.CREATE,
                         java.nio.file.StandardOpenOption.WRITE,
                         resumed ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                body.transferTo(output);
            } catch (IOException exception) {
                // The partial keeps the bytes it has; surface the break as
                // retryable so the next attempt resumes instead of failing
                // the sync closed.
                if (watchdog.stalled()) {
                    throw new BlobStreamInterruptedException(
                            "SharedWorld transfer stalled: no data received for " + TransferWatchdog.stallTimeoutMillis() / 1000 + "s.", exception);
                }
                throw new BlobStreamInterruptedException("SharedWorld transfer was interrupted mid-stream.", exception);
            }
        }
        if (totalBytes >= 0L && Files.size(partial) != totalBytes) {
            // The stream ended early without an error (server closed the
            // connection cleanly); treat like any other mid-stream break.
            throw new BlobStreamInterruptedException(
                    "SharedWorld transfer ended early (" + Files.size(partial) + " of " + totalBytes + " bytes).", null);
        }

        if (gunzip) {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(partial));
                 OutputStream output = Files.newOutputStream(target)) {
                input.transferTo(output);
            }
            Files.deleteIfExists(partial);
        } else {
            Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long totalFromContentRange(String contentRange) {
        if (contentRange == null) {
            return -1L;
        }
        // "bytes <start>-<end>/<total>"; "*" totals stay unknown.
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) {
            return -1L;
        }
        String total = contentRange.substring(slash + 1).trim();
        if (total.isEmpty() || "*".equals(total)) {
            return -1L;
        }
        try {
            return Long.parseLong(total);
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    /**
     * The session bearer belongs to the SharedWorld backend only: signed blob
     * URLs may point at third-party stores (a Google Drive resumable session
     * URI), and sending the token there both leaks it and can break the
     * store's own auth handling.
     */
    private void applyBlobAuth(HttpRequest.Builder builder, SignedBlobUrlDto signedUrl) throws IOException, InterruptedException {
        if (isBackendOrigin(signedUrl.url())) {
            builder.header("authorization", "Bearer " + ensureSession().token());
        }
    }

    private boolean isBackendOrigin(String url) {
        try {
            URI target = URI.create(url);
            URI base = URI.create(this.baseUrl);
            return java.util.Objects.equals(target.getScheme(), base.getScheme())
                    && java.util.Objects.equals(target.getHost(), base.getHost())
                    && target.getPort() == base.getPort();
        } catch (IllegalArgumentException exception) {
            // Unparseable URL: keep the pre-0.4.0 behavior of attaching auth.
            return true;
        }
    }

    private SharedWorldApiException blobTransferError(String operation, String errorBody, int statusCode, java.net.http.HttpHeaders headers) {
        Integer retryAfterSeconds = headers == null
                ? null
                : parseRetryAfterSeconds(headers.firstValue("retry-after").orElse(null));
        ErrorDto error = tryParseError(errorBody, statusCode);
        String message;
        String googleMessage = googleErrorMessage(errorBody);
        if (!"http_error".equals(error.error())) {
            message = error.message();
        } else if (googleMessage != null) {
            // A Drive-shaped {"error":{...}} body must not be branded as a
            // SharedWorld backend failure.
            message = "Google Drive rejected the blob " + operation + " (" + statusCode + "): " + googleMessage;
        } else if (statusCode == 413) {
            message = "SharedWorld blob " + operation + " was rejected: the file is larger than this transfer path accepts.";
        } else {
            message = "SharedWorld blob " + operation + " failed (" + statusCode + ").";
        }
        return new SharedWorldApiException(error.error(), message, error.status(), retryAfterSeconds);
    }

    private static String googleErrorMessage(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(errorBody);
            if (!parsed.isJsonObject()) {
                return null;
            }
            com.google.gson.JsonElement error = parsed.getAsJsonObject().get("error");
            if (error == null || !error.isJsonObject()) {
                return null;
            }
            com.google.gson.JsonElement message = error.getAsJsonObject().get("message");
            return message != null && message.isJsonPrimitive() ? message.getAsString() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * A blob transfer that broke mid-stream (stall abort, connection reset,
     * clean-but-early EOF). Always retryable: the .swpart partial keeps the
     * received bytes and the next attempt resumes from its size.
     */
    public static final class BlobStreamInterruptedException extends IOException {
        public BlobStreamInterruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String readErrorBody(InputStream body) {
        try (InputStream input = body) {
            return new String(input.readNBytes(65_536), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    public void releaseHost(String worldId, boolean graceful, long runtimeEpoch, String hostToken) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graceful", graceful);
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        request("POST", "/worlds/" + worldId + "/release-host", body, null, true);
    }

    public void releaseHost(String worldId, boolean graceful) throws IOException, InterruptedException {
        releaseHost(worldId, graceful, -1L, null);
    }

    /**
     * Persists non-dev session tokens across restarts. Optional: nothing is
     * persisted until a store is attached (production wires the shared
     * SharedWorldSessionStore at client init).
     */
    public interface SessionPersistence {
        SessionTokenDto load(String baseUrl, String playerUuid);

        void save(String baseUrl, String playerUuid, SessionTokenDto session);

        void clear(String baseUrl, String playerUuid);
    }

    public synchronized void setSessionPersistence(SessionPersistence persistence) {
        this.sessionPersistence = persistence;
    }

    public synchronized SessionTokenDto ensureSession() throws IOException, InterruptedException {
        if (isUsableSession(cachedSession)) {
            SharedWorldDevSessionBridge.updateAuthenticatedSession(this.cachedSessionIsDev, this.cachedAllowInsecureE4mc);
            return cachedSession;
        }

        SharedWorldDevSessionBridge.clear();

        SessionIdentity identity = this.sessionIdentityProvider.currentIdentity();
        if (identity.isDevSession()) {
            DevSessionTokenDto devSession = request(
                    "POST",
                    "/auth/dev-complete",
                    Map.of(
                            "playerUuid", identity.playerUuid().replace("-", ""),
                            "playerName", identity.playerName(),
                            "secret", identity.devAuthSecret()
                    ),
                    DevSessionTokenDto.class,
                    false
            );
            cacheSession(devSession.sessionToken(), true, devSession.allowInsecureE4mc());
            return cachedSession;
        }

        if (this.sessionPersistence != null) {
            SessionTokenDto persisted = this.sessionPersistence.load(this.baseUrl, identity.playerUuid());
            if (isUsableSession(persisted)) {
                cacheSession(persisted, false, false);
                return cachedSession;
            }
        }

        SessionTokenDto session;
        try {
            session = establishMojangSession(identity);
        } catch (SharedWorldApiException exception) {
            if (!"identity_verification_unavailable".equals(exception.error())) {
                LOGGER.warn("SharedWorld authentication failed: {} (HTTP {}) - {}",
                        exception.error(), exception.status(), exception.getMessage());
                throw exception;
            }
            // The backend already retried Mojang inside its own window; one
            // full fresh attempt (new challenge + newly signed nonce) after
            // the pause the backend asked for covers blips and rate-limit
            // windows that outlast it.
            long delayMillis = verificationRetryDelayMillis(exception.retryAfterSeconds());
            LOGGER.warn("SharedWorld identity verification unavailable (HTTP {}); retrying once in {} ms - {}",
                    exception.status(), delayMillis, exception.getMessage());
            Thread.sleep(delayMillis);
            try {
                session = establishMojangSession(identity);
            } catch (SharedWorldApiException retryFailure) {
                LOGGER.warn("SharedWorld authentication failed after retry: {} (HTTP {}) - {}",
                        retryFailure.error(), retryFailure.status(), retryFailure.getMessage());
                throw retryFailure;
            }
        }
        cacheSession(session, false, false);
        if (this.sessionPersistence != null) {
            this.sessionPersistence.save(this.baseUrl, identity.playerUuid(), session);
        }
        return cachedSession;
    }

    /**
     * Certificate auth is the only sign-in path since 0.2.2: the
     * Mojang-signed profile keypair proves the account offline on the
     * backend. The historical joinServer/hasJoined flow is gone because
     * Mojang blocks the backend's Cloudflare egress for it deterministically;
     * a client that cannot produce profile keys is told exactly that instead
     * of silently running a flow that always ends in a misleading error.
     */
    private SessionTokenDto establishMojangSession(SessionIdentity identity) throws IOException, InterruptedException {
        java.util.Optional<ProfileCertificateData> certificate = this.certificateProvider.currentCertificate();
        if (certificate.isEmpty()) {
            throw new SharedWorldApiException(
                    "profile_keys_unavailable",
                    "SharedWorld could not get your Minecraft profile keys, which it needs to sign in. "
                            + "This usually means the game is not signed in to a Minecraft account, "
                            + "or a mod that blocks chat signing is installed. "
                            + "Restart the game and try again; if it keeps happening, check your installed mods.",
                    0
            );
        }
        return establishCertificateSession(identity, certificate.get());
    }

    private SessionTokenDto establishCertificateSession(SessionIdentity identity, ProfileCertificateData certificate)
            throws IOException, InterruptedException {
        AuthChallengeDto challenge = request("POST", "/auth/challenge", Map.of(), AuthChallengeDto.class, false);
        java.util.Base64.Encoder base64 = java.util.Base64.getEncoder();
        return request(
                "POST",
                "/auth/complete-cert",
                Map.of(
                        "serverId", challenge.serverId(),
                        "playerUuid", identity.playerUuid().replace("-", "").toLowerCase(java.util.Locale.ROOT),
                        "playerName", identity.playerName(),
                        "publicKey", base64.encodeToString(certificate.publicKeyDer()),
                        "publicKeyExpiresAtMs", certificate.expiresAtEpochMillis(),
                        "keySignature", base64.encodeToString(certificate.keySignature()),
                        "nonceSignature", base64.encodeToString(signNonce(certificate.privateKey(), challenge.serverId()))
                ),
                SessionTokenDto.class,
                false
        );
    }

    private static byte[] signNonce(java.security.PrivateKey privateKey, String serverId) throws IOException {
        try {
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(serverId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return signature.sign();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IOException("Failed to sign the SharedWorld challenge with the Minecraft profile key.", exception);
        }
    }

    private static volatile String cachedModVersion;

    /**
     * Mod version for the x-sharedworld-version header, so backend logs can
     * attribute failures to a release. Resolved lazily and defensively:
     * headless unit tests construct this client without a running mod
     * loader, and a header value must never be the reason a request fails.
     */
    private static String modVersion() {
        String version = cachedModVersion;
        if (version == null) {
            try {
                version = link.sharedworld.platform.SharedWorldPlatform.get()
                        .modVersion(link.sharedworld.SharedWorldClient.MOD_ID)
                        .orElse("unknown");
            } catch (Throwable ignored) {
                version = "unknown";
            }
            cachedModVersion = version;
        }
        return version;
    }

    /**
     * There is no fallback auth flow anymore, so every distinct way of ending
     * up without profile keys gets its own WARN: these lines are the only
     * production record of why a sign-in failed before the backend was ever
     * reached.
     */
    private static java.util.Optional<ProfileCertificateData> resolveProfileCertificate() {
        try {
            java.util.Optional<ProfileCertificateData> certificate =
                    link.sharedworld.versioned.ClientCompat.profileCertificate(Minecraft.getInstance());
            if (certificate.isEmpty()) {
                LOGGER.warn("SharedWorld found no Minecraft profile keys (offline profile, or a mod blocks chat signing)");
            }
            return certificate;
        } catch (java.util.concurrent.TimeoutException exception) {
            LOGGER.warn("SharedWorld timed out waiting for Minecraft profile keys (10s); is the Minecraft services API reachable?");
            return java.util.Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("SharedWorld was interrupted while fetching Minecraft profile keys");
            return java.util.Optional.empty();
        } catch (Exception exception) {
            LOGGER.warn("SharedWorld could not fetch Minecraft profile keys", exception);
            return java.util.Optional.empty();
        }
    }

    /**
     * A session is usable when it is structurally sound; wall-clock expiry is
     * deliberately NOT checked. The server is the authority on token lifetime:
     * a genuinely expired token comes back as a 401 that request() already
     * recovers from with one automatic re-auth. Trusting the local clock here
     * turned a skewed clock into a full Mojang handshake on every API call,
     * and from there into self-inflicted session-server rate limiting. An
     * unparseable expiry still marks the record as corrupt.
     */
    private static boolean isUsableSession(SessionTokenDto session) {
        if (session == null || session.token() == null || session.expiresAt() == null) {
            return false;
        }
        try {
            Instant.parse(session.expiresAt());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * How long to pause before the single fresh verification re-attempt: the
     * backend's Retry-After when present (capped so a UI spinner never sits
     * for a minute), a short default otherwise.
     */
    static long verificationRetryDelayMillis(Integer retryAfterSeconds) {
        if (retryAfterSeconds == null || retryAfterSeconds <= 0) {
            return 2_000L;
        }
        return Math.min(retryAfterSeconds, 15L) * 1_000L;
    }

    private static Integer parseRetryAfterSeconds(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        try {
            return Integer.valueOf(headerValue.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Account deletion: drop the cached session so nothing can silently
     * re-authenticate afterwards (a fresh handshake would recreate the
     * just-deleted account on the backend).
     */
    public void forgetSessionForAccountDeletion() {
        invalidateSession();
    }

    private synchronized void invalidateSession() {
        SessionTokenDto invalid = this.cachedSession;
        this.cachedSession = null;
        // ETag tokens are per-user (the backend hashes the caller in), so a
        // session change invalidates every cached conditional-GET body.
        this.conditionalGetCache.clear();
        SharedWorldDevSessionBridge.clear();
        if (this.sessionPersistence != null && invalid != null && !this.cachedSessionIsDev) {
            try {
                SessionIdentity identity = this.sessionIdentityProvider.currentIdentity();
                this.sessionPersistence.clear(this.baseUrl, identity.playerUuid());
            } catch (IOException exception) {
                // Identity unavailable; the in-memory invalidation is enough.
            }
        }
    }

    private void cacheSession(SessionTokenDto session, boolean isDevSession, boolean allowInsecureE4mc) {
        this.cachedSession = session;
        this.cachedSessionIsDev = isDevSession;
        this.cachedAllowInsecureE4mc = allowInsecureE4mc;
        SharedWorldDevSessionBridge.updateAuthenticatedSession(isDevSession, allowInsecureE4mc);
    }

    public String authenticatedBackendPlayerUuidWithHyphens() {
        try {
            return this.sessionIdentityProvider.currentIdentity().playerUuid();
        } catch (IOException exception) {
            throw new IllegalStateException("SharedWorld couldn't resolve the current authenticated backend player identity.", exception);
        }
    }

    public String authenticatedWorldPlayerUuidWithHyphens() {
        return CanonicalPlayerIdentity.normalizeUuidWithHyphens(
                authenticatedBackendPlayerUuidWithHyphens(),
                "current backend player UUID"
        );
    }

    public String canonicalAssignedPlayerUuidWithHyphens(String backendAssignedPlayerUuid) {
        return CanonicalPlayerIdentity.canonicalUuidForAssignment(
                backendAssignedPlayerUuid,
                authenticatedBackendPlayerUuidWithHyphens()
        );
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static SessionIdentity resolveSessionIdentity() {
        User user = Minecraft.getInstance().getUser();
        String playerUuid = RuntimePlayerIdentity.resolveBackendPlayerUuidWithHyphens(user);
        String playerName = System.getProperty("sharedworld.devPlayerName", user.getName());
        String accessToken = System.getProperty("sharedworld.devAuthSecret") != null
                ? "dev:" + System.getProperty("sharedworld.devAuthSecret")
                : user.getAccessToken();
        return new SessionIdentity(playerUuid, playerName, accessToken);
    }

    public static String currentBackendPlayerUuidWithHyphens() {
        User user = Minecraft.getInstance().getUser();
        return RuntimePlayerIdentity.resolveBackendPlayerUuidWithHyphens(user);
    }

    public static String currentPlayerUuid() {
        return currentBackendPlayerUuidWithHyphens().replace("-", "").toLowerCase();
    }

    public static String currentWorldPlayerUuidWithHyphens() {
        return CanonicalPlayerIdentity.normalizeUuidWithHyphens(
                currentBackendPlayerUuidWithHyphens(),
                "current backend player UUID"
        );
    }

    public static String currentPlayerName() {
        User user = Minecraft.getInstance().getUser();
        return System.getProperty("sharedworld.devPlayerName", user.getName());
    }

    public static boolean isDeletedWorldError(Throwable error) {
        SharedWorldApiException apiError = findApiError(error);
        return apiError != null
                && apiError.status() == 404
                && "world_not_found".equals(apiError.error());
    }

    public static boolean isMembershipRevokedError(Throwable error) {
        SharedWorldApiException apiError = findApiError(error);
        return apiError != null
                && apiError.status() == 403
                && "membership_revoked".equals(apiError.error());
    }

    /**
     * The host's Google Drive is out of space. Covers both the backend's
     * rejection and the direct-to-Drive uploader's own failure, so callers see
     * one answer regardless of which leg of an upload hit the quota.
     */
    public static boolean isDriveStorageFullError(Throwable error) {
        SharedWorldApiException apiError = findApiError(error);
        if (apiError != null && "drive_storage_full".equals(apiError.error())) {
            return true;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ResumableBlobUploader.DriveStorageFullException) {
                return true;
            }
        }
        return false;
    }

    /** The host's Google Drive authorization is dead; only re-linking Drive fixes it. */
    public static boolean isDriveReauthRequiredError(Throwable error) {
        SharedWorldApiException apiError = findApiError(error);
        return apiError != null && "drive_reauth_required".equals(apiError.error());
    }

    public static boolean isHostNotActiveError(Throwable error) {
        SharedWorldApiException apiError = findApiError(error);
        return apiError != null
                && apiError.status() == 409
                && "host_not_active".equals(apiError.error());
    }

    /**
     * host_not_active refined by the backend: this host's own lease lapsed;
     * nobody took over. False for older backends (no reason field) and for
     * genuine replacements, so callers can keep the takeover copy for those.
     */
    public static boolean isHostLeaseExpiredError(Throwable error) {
        SharedWorldApiException apiError = findApiError(error);
        return apiError != null
                && apiError.status() == 409
                && "host_not_active".equals(apiError.error())
                && "lease_expired".equals(apiError.reason());
    }

    public static String errorCode(Throwable error) {
        SharedWorldApiException apiError = findApiError(error);
        return apiError == null ? null : apiError.error();
    }

    /**
     * The single user-facing rendering of any SharedWorld failure: the
     * backend's human message when one exists, a friendly offline/unreachable
     * string for connectivity failures, otherwise the deepest non-blank cause
     * message; never null and never a bare JDK socket string for the
     * connectivity cases players actually hit.
     */
    public static String friendlyErrorMessage(Throwable error) {
        if (error == null) {
            return link.sharedworld.SharedWorldText.errorMessageOrDefault(null);
        }
        SharedWorldApiException apiError = findApiError(error);
        if (apiError != null && apiError.getMessage() != null && !apiError.getMessage().isBlank()) {
            return apiError.getMessage();
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof java.net.UnknownHostException || cause instanceof java.net.ConnectException) {
                return link.sharedworld.SharedWorldText.string("screen.sharedworld.error_internet_unreachable");
            }
            if (cause instanceof java.net.http.HttpTimeoutException) {
                // The JDK's message is the bare "request timed out"; players
                // saw it verbatim on create/sync screens.
                return link.sharedworld.SharedWorldText.string("screen.sharedworld.error_request_timed_out");
            }
            String message = cause.getMessage();
            if (message != null && (message.contains("UnresolvedAddressException") || message.contains("Connection refused"))) {
                return link.sharedworld.SharedWorldText.string("screen.sharedworld.error_backend_unreachable");
            }
        }
        String best = error.getMessage();
        for (Throwable cause = error; cause.getCause() != null && cause.getCause() != cause; ) {
            cause = cause.getCause();
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                best = cause.getMessage();
            }
        }
        return link.sharedworld.SharedWorldText.errorMessageOrDefault(best);
    }

    private static SharedWorldApiException findApiError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SharedWorldApiException apiException) {
                return apiException;
            }
            current = current.getCause();
        }
        return null;
    }

    private <T> T request(String method, String path, Object body, Class<T> responseType, boolean authenticated) throws IOException, InterruptedException {
        return request(method, path, body, responseType, authenticated, DEFAULT_REQUEST_TIMEOUT);
    }

    private <T> T request(String method, String path, Object body, Class<T> responseType, boolean authenticated, Duration timeout) throws IOException, InterruptedException {
        try {
            return requestOnce(method, path, body, responseType, authenticated, timeout);
        } catch (SharedWorldApiException exception) {
            // A rejected session token (expired server-side, wiped backend,
            // stale persisted token) is recoverable: re-authenticate once and
            // replay the request. Anything else propagates.
            if (!authenticated || exception.status() != 401
                    || !("invalid_session".equals(exception.error()) || "expired_session".equals(exception.error()))) {
                throw exception;
            }
            invalidateSession();
            return requestOnce(method, path, body, responseType, true, timeout);
        }
    }

    private <T> T requestOnce(String method, String path, Object body, Class<T> responseType, boolean authenticated, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("accept", "application/json")
                .header("x-sharedworld-version", modVersion());

        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .header("content-type", "application/json");
        }

        if (authenticated) {
            builder.header("authorization", "Bearer " + ensureSession().token());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            ErrorDto error = tryParseError(response.body(), response.statusCode());
            throw new SharedWorldApiException(
                    error.error(),
                    error.message(),
                    error.status(),
                    parseRetryAfterSeconds(response.headers().firstValue("retry-after").orElse(null)),
                    error.reason()
            );
        }

        if (responseType == null) {
            return null;
        }

        try {
            return gson.fromJson(response.body(), responseType);
        } catch (JsonSyntaxException exception) {
            throw new IOException("Failed to parse SharedWorld response.", exception);
        }
    }


    private static final link.sharedworld.util.RetryPolicy READ_RETRY_POLICY =
            new link.sharedworld.util.RetryPolicy(3, 500L, 4_000L);

    /** Ordinary JSON calls: fast or failed. */
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    /**
     * Snapshot planning and finalization scale with the world (hundreds of
     * packs, delta-chain validation, and, before the backend moved it off
     * the response path, hourly retention). A finalize that outlives a
     * flat 20s budget has almost always SUCCEEDED server-side; giving up
     * early made the release lane report a transient failure, retry, and
     * on the next attempt discover "no changes since" the snapshot it had
     * just written. Two minutes covers the tail; a dead backend still fails
     * fast on connect.
     */
    static final Duration SNAPSHOT_REQUEST_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Bounded transport retry for safe idempotent calls only: reads, plus the
     * plan computations (uploads/prepare, downloads/plan) that write nothing
     * server-side. Mutating calls are never replayed here; their coordinators
     * own retry semantics.
     */
    private <T> T requestWithTransportRetry(String method, String path, Class<T> responseType) throws IOException, InterruptedException {
        return requestWithTransportRetry(method, path, null, responseType);
    }

    private <T> T requestWithTransportRetry(String method, String path, Object body, Class<T> responseType) throws IOException, InterruptedException {
        return requestWithTransportRetry(method, path, body, responseType, DEFAULT_REQUEST_TIMEOUT);
    }

    private <T> T requestWithTransportRetry(String method, String path, Object body, Class<T> responseType, Duration timeout) throws IOException, InterruptedException {
        return withTransportRetry(() -> request(method, path, body, responseType, true, timeout));
    }

    private interface IoCall<T> {
        T call() throws IOException, InterruptedException;
    }

    private <T> T withTransportRetry(IoCall<T> call) throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= READ_RETRY_POLICY.maxAttempts(); attempt++) {
            long delayMs = READ_RETRY_POLICY.delayBeforeAttemptMs(attempt);
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            try {
                return call.call();
            } catch (IOException exception) {
                if (!isRetryableTransportError(exception) || !READ_RETRY_POLICY.shouldRetry(attempt)) {
                    throw exception;
                }
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    private record CachedGet(String etag, String body) {
    }

    private final java.util.concurrent.ConcurrentHashMap<String, CachedGet> conditionalGetCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Conditional GET for the two world read endpoints: sends If-None-Match
     * from the per-path cache; a 304 answers from the cached body (never
     * parsing the empty 304 body), a 200 refreshes the cache from the ETag
     * header. Only worth it for endpoints the backend hands weak ETags to.
     */
    private <T> T conditionalGet(String path, Class<T> responseType) throws IOException, InterruptedException {
        try {
            return conditionalGetOnce(path, responseType);
        } catch (SharedWorldApiException exception) {
            if (exception.status() != 401
                    || !("invalid_session".equals(exception.error()) || "expired_session".equals(exception.error()))) {
                throw exception;
            }
            invalidateSession();
            return conditionalGetOnce(path, responseType);
        }
    }

    private <T> T conditionalGetOnce(String path, Class<T> responseType) throws IOException, InterruptedException {
        CachedGet cached = conditionalGetCache.get(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("accept", "application/json")
                .header("x-sharedworld-version", modVersion())
                .header("authorization", "Bearer " + ensureSession().token())
                .GET();
        if (cached != null) {
            builder.header("if-none-match", cached.etag());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 304) {
            if (cached != null) {
                return gson.fromJson(cached.body(), responseType);
            }
            // A 304 without a cached body (evicted mid-flight): retry plain.
            conditionalGetCache.remove(path);
            return request("GET", path, null, responseType, true);
        }
        if (response.statusCode() >= 400) {
            ErrorDto error = tryParseError(response.body(), response.statusCode());
            throw new SharedWorldApiException(
                    error.error(),
                    error.message(),
                    error.status(),
                    parseRetryAfterSeconds(response.headers().firstValue("retry-after").orElse(null)),
                    error.reason()
            );
        }
        String etag = response.headers().firstValue("etag").orElse(null);
        if (etag != null && !etag.isEmpty()) {
            conditionalGetCache.put(path, new CachedGet(etag, response.body()));
        } else {
            conditionalGetCache.remove(path);
        }
        try {
            return gson.fromJson(response.body(), responseType);
        } catch (JsonSyntaxException exception) {
            throw new IOException("Failed to parse SharedWorld response.", exception);
        }
    }

    /**
     * Retriable: connection-level failures and 5xx responses without a
     * meaningful protocol code. Auth unavailability is excluded; the session
     * layer already performs its own full re-attempt; as is every 4xx
     * protocol outcome.
     */
    public static boolean isRetryableTransportError(Throwable error) {
        SharedWorldApiException apiError = findApiError(error);
        if (apiError != null) {
            if ("identity_verification_unavailable".equals(apiError.error())) {
                return false;
            }
            return apiError.status() >= 500;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.UnknownHostException
                    // TLS record corruption (e.g. "bad record MAC") on a
                    // long-lived transfer is a transient link fault, not a
                    // protocol outcome; without a retry it killed multi-minute
                    // release uploads on their first blip.
                    || cause instanceof javax.net.ssl.SSLException
                    // Mid-stream breaks (stall abort, reset, early EOF) leave
                    // a resumable .swpart behind; the retry picks it up.
                    || cause instanceof BlobStreamInterruptedException) {
                return true;
            }
        }
        return false;
    }

    private ErrorDto tryParseError(String body, int fallbackStatus) {
        try {
            ErrorDto parsed = gson.fromJson(body, ErrorDto.class);
            if (parsed != null && parsed.message() != null) {
                return parsed;
            }
        } catch (JsonSyntaxException ignored) {
        }
        return new ErrorDto("http_error", "SharedWorld backend request failed (" + fallbackStatus + ").", fallbackStatus);
    }

    public static final class SharedWorldApiException extends IOException {
        private final String error;
        private final int status;
        private final Integer retryAfterSeconds;
        private final String reason;

        public SharedWorldApiException(String error, String message, int status) {
            this(error, message, status, null, null);
        }

        public SharedWorldApiException(String error, String message, int status, Integer retryAfterSeconds) {
            this(error, message, status, retryAfterSeconds, null);
        }

        public SharedWorldApiException(String error, String message, int status, Integer retryAfterSeconds, String reason) {
            super(message);
            this.error = error;
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
            this.reason = reason;
        }

        public String error() {
            return this.error;
        }

        public int status() {
            return this.status;
        }

        /** The backend's Retry-After header in seconds, or null when absent. */
        public Integer retryAfterSeconds() {
            return this.retryAfterSeconds;
        }

        /**
         * Refinement of error() for codes covering more than one situation
         * (host_not_active: "lease_expired" vs "replaced"); null from older
         * backends.
         */
        public String reason() {
            return this.reason;
        }
    }

    public record SessionIdentity(String playerUuid, String playerName, String accessToken) {
        public boolean isDevSession() {
            return this.accessToken != null && this.accessToken.startsWith("dev:");
        }

        public String devAuthSecret() {
            if (!this.isDevSession()) {
                throw new IllegalStateException("SharedWorld dev auth secret requested for a production session.");
            }
            return this.accessToken.substring("dev:".length());
        }
    }

    @FunctionalInterface
    public interface SessionIdentityProvider {
        SessionIdentity currentIdentity() throws IOException;
    }

    /**
     * Supplies the Mojang-signed profile certificate for certificate auth.
     * Empty means the account cannot sign in at all (no fallback flow exists);
     * the caller surfaces a profile_keys_unavailable error explaining why.
     */
    public interface ProfileCertificateProvider {
        java.util.Optional<ProfileCertificateData> currentCertificate();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @FunctionalInterface
    public interface UploadProgressListener {
        void onBytesTransferred(long bytesTransferred, long totalBytes);
    }

    @FunctionalInterface
    public interface DownloadProgressListener {
        void onBytesTransferred(long bytesTransferred, long totalBytes);
    }

    static final class ProgressInputStream extends InputStream {
        private final InputStream delegate;
        private final long totalBytes;
        private final UploadProgressListener listener;
        private long transferredBytes;

        ProgressInputStream(InputStream delegate, long totalBytes, UploadProgressListener listener) {
            this.delegate = delegate;
            this.totalBytes = totalBytes;
            this.listener = listener;
            this.listener.onBytesTransferred(0L, totalBytes);
        }

        @Override
        public int read() throws IOException {
            int read = this.delegate.read();
            if (read >= 0) {
                this.reportProgress(1L);
            }
            return read;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = this.delegate.read(b, off, len);
            if (read > 0) {
                this.reportProgress(read);
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            this.delegate.close();
        }

        private void reportProgress(long delta) {
            this.transferredBytes = Math.min(this.totalBytes, this.transferredBytes + delta);
            this.listener.onBytesTransferred(this.transferredBytes, this.totalBytes);
        }
    }
}
