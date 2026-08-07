package link.sharedworld.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import link.sharedworld.CanonicalPlayerIdentity;
import link.sharedworld.RuntimePlayerIdentity;
import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.api.SharedWorldModels.AuthChallengeDto;
import link.sharedworld.api.SharedWorldModels.CreateWorldResultDto;
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
        return Arrays.asList(requestWithTransportRetry("GET", "/worlds", WorldSummaryDto[].class));
    }

    public WorldDetailsDto getWorld(String worldId) throws IOException, InterruptedException {
        ensureSession();
        return request("GET", "/worlds/" + worldId, null, WorldDetailsDto.class, true);
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
        ensureSession();
        return request(
                "POST",
                "/storage/link-sessions",
                forceConsent ? Map.of("forceConsent", true) : Map.of(),
                StorageLinkSessionDto.class,
                true
        );
    }

    public SharedWorldModels.StorageAccountSummaryDto getStorageAccount() throws IOException, InterruptedException {
        ensureSession();
        return request("GET", "/storage/account", null, SharedWorldModels.StorageAccountSummaryDto.class, true);
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

    /** Persist the managed server's current gamerule values (host runtime authority, not ownership). */
    public SharedWorldModels.HostGameRulesReportResponseDto reportHostGameRules(String worldId, long runtimeEpoch, String hostToken, Map<String, Boolean> gamerules) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        body.put("gamerules", gamerules);
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

    public SharedWorldModels.PresenceHeartbeatResponseDto setPresence(String worldId, boolean present, long guestSessionEpoch, long presenceSequence) throws IOException, InterruptedException {
        ensureSession();
        return request(
                "POST",
                "/worlds/" + worldId + "/presence",
                Map.of(
                        "present", present,
                        "guestSessionEpoch", guestSessionEpoch,
                        "presenceSequence", presenceSequence
                ),
                SharedWorldModels.PresenceHeartbeatResponseDto.class,
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

    public UploadPlanDto prepareUploads(String worldId, long runtimeEpoch, String hostToken, LocalFileDescriptorDto[] files, LocalPackDescriptorDto nonRegionPack, LocalPackDescriptorDto[] regionBundles) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        body.put("files", files);
        body.put("nonRegionPack", nonRegionPack);
        body.put("regionBundles", regionBundles);
        return request("POST", "/worlds/" + worldId + "/uploads/prepare", body, UploadPlanDto.class, true);
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
                true
        );
    }

    public SnapshotManifestDto finalizeSnapshot(String worldId, String baseSnapshotId, ManifestFileDto[] files, SnapshotPackDto[] packs) throws IOException, InterruptedException {
        return finalizeSnapshot(worldId, -1L, null, baseSnapshotId, files, packs);
    }

    public DownloadPlanDto downloadPlan(String worldId, LocalFileDescriptorDto[] files, LocalPackDescriptorDto nonRegionPack, LocalPackDescriptorDto[] regionBundles) throws IOException, InterruptedException {
        ensureSession();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(this.baseUrl + "/worlds/" + worldId + "/downloads/plan"))
                .timeout(Duration.ofSeconds(20))
                .header("accept", "application/json")
                .header("authorization", "Bearer " + ensureSession().token())
                .header("x-sharedworld-version", modVersion())
                .header("x-sharedworld-files", this.gson.toJson(files))
                .header("x-sharedworld-pack", this.gson.toJson(nonRegionPack))
                .header("x-sharedworld-region-bundles", this.gson.toJson(regionBundles))
                .GET();

        HttpResponse<String> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            ErrorDto error = tryParseError(response.body(), response.statusCode());
            throw new SharedWorldApiException(error.error(), error.message(), error.status());
        }

        try {
            return this.gson.fromJson(response.body(), DownloadPlanDto.class);
        } catch (JsonSyntaxException exception) {
            throw new IOException("Failed to parse SharedWorld response.", exception);
        }
    }

    public void uploadBlob(SignedBlobUrlDto signedUrl, Path bodyFile, String contentType) throws IOException, InterruptedException {
        this.uploadBlob(signedUrl, bodyFile, contentType, null);
    }

    public void uploadBlob(SignedBlobUrlDto signedUrl, Path bodyFile, String contentType, UploadProgressListener progressListener) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher bodyPublisher = progressListener == null
                ? HttpRequest.BodyPublishers.ofFile(bodyFile)
                : HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return new ProgressInputStream(Files.newInputStream(bodyFile), Files.size(bodyFile), progressListener);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(signedUrl.url()))
                .timeout(Duration.ofSeconds(60))
                .method(signedUrl.method(), bodyPublisher);
        builder.header("authorization", "Bearer " + ensureSession().token());

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
            throw blobTransferError("upload", response.body(), response.statusCode());
        }
    }

    public void downloadBlobToFile(SignedBlobUrlDto signedUrl, Path target) throws IOException, InterruptedException {
        this.downloadBlobToFile(signedUrl, target, null);
    }

    public void downloadBlobToFile(SignedBlobUrlDto signedUrl, Path target, DownloadProgressListener progressListener) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(signedUrl.url()))
                .timeout(Duration.ofSeconds(60))
                .method(signedUrl.method(), HttpRequest.BodyPublishers.noBody());
        builder.header("authorization", "Bearer " + ensureSession().token());

        if (signedUrl.headers() != null) {
            signedUrl.headers().forEach(builder::header);
        }

        HttpResponse<InputStream> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw blobTransferError("download", readErrorBody(response.body()), response.statusCode());
        }

        long compressedLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
        InputStream body = progressListener == null
                ? response.body()
                : new ProgressInputStream(response.body(), compressedLength, progressListener::onBytesTransferred);
        try (InputStream input = new GZIPInputStream(body);
             OutputStream output = Files.newOutputStream(target)) {
            input.transferTo(output);
        }
    }

    public void downloadRawBlobToFile(SignedBlobUrlDto signedUrl, Path target) throws IOException, InterruptedException {
        this.downloadRawBlobToFile(signedUrl, target, null);
    }

    public void downloadRawBlobToFile(SignedBlobUrlDto signedUrl, Path target, DownloadProgressListener progressListener) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(signedUrl.url()))
                .timeout(Duration.ofSeconds(60))
                .method(signedUrl.method(), HttpRequest.BodyPublishers.noBody());
        builder.header("authorization", "Bearer " + ensureSession().token());

        if (signedUrl.headers() != null) {
            signedUrl.headers().forEach(builder::header);
        }

        HttpResponse<InputStream> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw blobTransferError("download", readErrorBody(response.body()), response.statusCode());
        }

        long length = response.headers().firstValueAsLong("content-length").orElse(-1L);
        InputStream body = progressListener == null
                ? response.body()
                : new ProgressInputStream(response.body(), length, progressListener::onBytesTransferred);
        try (InputStream input = body;
             OutputStream output = Files.newOutputStream(target)) {
            input.transferTo(output);
        }
    }


    private SharedWorldApiException blobTransferError(String operation, String errorBody, int statusCode) {
        ErrorDto error = tryParseError(errorBody, statusCode);
        String message = "http_error".equals(error.error())
                ? "SharedWorld blob " + operation + " failed (" + statusCode + ")."
                : error.message();
        return new SharedWorldApiException(error.error(), message, error.status());
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
     * headless unit tests construct this client without a running Fabric
     * loader, and a header value must never be the reason a request fails.
     */
    private static String modVersion() {
        String version = cachedModVersion;
        if (version == null) {
            try {
                version = net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getModContainer(link.sharedworld.SharedWorldClient.MOD_ID)
                        .map(container -> container.getMetadata().getVersion().getFriendlyString())
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
     * turned a skewed clock into a full Mojang handshake on every API call —
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

    private synchronized void invalidateSession() {
        SessionTokenDto invalid = this.cachedSession;
        this.cachedSession = null;
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

    public static boolean isHostNotActiveError(Throwable error) {
        SharedWorldApiException apiError = findApiError(error);
        return apiError != null
                && apiError.status() == 409
                && "host_not_active".equals(apiError.error());
    }

    public static String errorCode(Throwable error) {
        SharedWorldApiException apiError = findApiError(error);
        return apiError == null ? null : apiError.error();
    }

    /**
     * The single user-facing rendering of any SharedWorld failure: the
     * backend's human message when one exists, a friendly offline/unreachable
     * string for connectivity failures, otherwise the deepest non-blank cause
     * message — never null and never a bare JDK socket string for the
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
        try {
            return requestOnce(method, path, body, responseType, authenticated);
        } catch (SharedWorldApiException exception) {
            // A rejected session token (expired server-side, wiped backend,
            // stale persisted token) is recoverable: re-authenticate once and
            // replay the request. Anything else propagates.
            if (!authenticated || exception.status() != 401
                    || !("invalid_session".equals(exception.error()) || "expired_session".equals(exception.error()))) {
                throw exception;
            }
            invalidateSession();
            return requestOnce(method, path, body, responseType, true);
        }
    }

    private <T> T requestOnce(String method, String path, Object body, Class<T> responseType, boolean authenticated) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
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
                    parseRetryAfterSeconds(response.headers().firstValue("retry-after").orElse(null))
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

    /**
     * Bounded transport retry for safe idempotent reads only. Mutating calls
     * are never replayed here; their coordinators own retry semantics.
     */
    private <T> T requestWithTransportRetry(String method, String path, Class<T> responseType) throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= READ_RETRY_POLICY.maxAttempts(); attempt++) {
            long delayMs = READ_RETRY_POLICY.delayBeforeAttemptMs(attempt);
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            try {
                return request(method, path, null, responseType, true);
            } catch (IOException exception) {
                if (!isRetryableTransportError(exception) || !READ_RETRY_POLICY.shouldRetry(attempt)) {
                    throw exception;
                }
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    /**
     * Retriable: connection-level failures and 5xx responses without a
     * meaningful protocol code. Auth unavailability is excluded — the session
     * layer already performs its own full re-attempt — as is every 4xx
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
                    || cause instanceof java.net.UnknownHostException) {
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

        public SharedWorldApiException(String error, String message, int status) {
            this(error, message, status, null);
        }

        public SharedWorldApiException(String error, String message, int status, Integer retryAfterSeconds) {
            super(message);
            this.error = error;
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
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

    private static final class ProgressInputStream extends InputStream {
        private final InputStream delegate;
        private final long totalBytes;
        private final UploadProgressListener listener;
        private long transferredBytes;

        private ProgressInputStream(InputStream delegate, long totalBytes, UploadProgressListener listener) {
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
