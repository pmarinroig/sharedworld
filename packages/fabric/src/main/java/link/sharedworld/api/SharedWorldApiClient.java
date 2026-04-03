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
import link.sharedworld.api.SharedWorldModels.StorageUsageSummaryDto;
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
    private final String baseUrl;
    private final HttpClient httpClient;
    private final Gson gson;
    private final SessionIdentityProvider sessionIdentityProvider;
    private final SessionJoiner sessionJoiner;
    private SessionTokenDto cachedSession;
    private boolean cachedSessionIsDev;
    private boolean cachedAllowInsecureE4mc;

    public SharedWorldApiClient(String baseUrl) {
        this(
                baseUrl,
                defaultHttpClient(),
                SharedWorldApiClient::resolveSessionIdentity,
                SharedWorldApiClient::joinMinecraftSessionServer
        );
    }

    public SharedWorldApiClient(String baseUrl, SessionIdentityProvider sessionIdentityProvider) {
        this(baseUrl, defaultHttpClient(), sessionIdentityProvider, SharedWorldApiClient::joinMinecraftSessionServer);
    }

    public SharedWorldApiClient(String baseUrl, HttpClient httpClient) {
        this(
                baseUrl,
                httpClient,
                SharedWorldApiClient::resolveSessionIdentity,
                SharedWorldApiClient::joinMinecraftSessionServer
        );
    }

    public SharedWorldApiClient(String baseUrl, HttpClient httpClient, SessionIdentityProvider sessionIdentityProvider) {
        this(baseUrl, httpClient, sessionIdentityProvider, SharedWorldApiClient::joinMinecraftSessionServer);
    }

    public SharedWorldApiClient(
            String baseUrl,
            HttpClient httpClient,
            SessionIdentityProvider sessionIdentityProvider,
            SessionJoiner sessionJoiner
    ) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.sessionIdentityProvider = Objects.requireNonNull(sessionIdentityProvider, "sessionIdentityProvider");
        this.sessionJoiner = Objects.requireNonNull(sessionJoiner, "sessionJoiner");
        this.gson = new Gson();
    }

    public List<WorldSummaryDto> listWorlds() throws IOException, InterruptedException {
        ensureSession();
        return Arrays.asList(request("GET", "/worlds", null, WorldSummaryDto[].class, true));
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
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("motdLine1", blankToNull(motdLine1));
        body.put("motdLine2", blankToNull(motdLine2));
        body.put("customIconPngBase64", blankToNull(customIconPngBase64));
        body.put("importSource", importSource);
        body.put("storageLinkSessionId", storageLinkSessionId);
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

    public StorageLinkSessionDto createStorageLink() throws IOException, InterruptedException {
        ensureSession();
        return request(
                "POST",
                "/storage/link-sessions",
                Map.of(),
                StorageLinkSessionDto.class,
                true
        );
    }

    public StorageLinkSessionDto getStorageLink(String sessionId) throws IOException, InterruptedException {
        ensureSession();
        return request("GET", "/storage/link-sessions/" + sessionId, null, StorageLinkSessionDto.class, true);
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
        return request("GET", "/worlds/" + worldId + "/runtime", null, WorldRuntimeStatusDto.class, true);
    }

    public void heartbeatHost(String worldId, long runtimeEpoch, String hostToken, String joinTarget) throws IOException, InterruptedException {
        ensureSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtimeEpoch", runtimeEpoch);
        body.put("hostToken", hostToken);
        if (joinTarget != null) {
            body.put("joinTarget", joinTarget);
        }
        request("POST", "/worlds/" + worldId + "/heartbeat", body, null, true);
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

    public void setPresence(String worldId, boolean present, long guestSessionEpoch, long presenceSequence) throws IOException, InterruptedException {
        ensureSession();
        request(
                "POST",
                "/worlds/" + worldId + "/presence",
                Map.of(
                        "present", present,
                        "guestSessionEpoch", guestSessionEpoch,
                        "presenceSequence", presenceSequence
                ),
                null,
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

    public WorldRuntimeStatusDto refreshWaiting(String worldId, String waiterSessionId) throws IOException, InterruptedException {
        ensureSession();
        return request("POST", "/worlds/" + worldId + "/session/waiting/refresh", Map.of("waiterSessionId", waiterSessionId), WorldRuntimeStatusDto.class, true);
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
        return request("GET", "/worlds/" + worldId + "/snapshots/latest-manifest", null, SnapshotManifestDto.class, true);
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

    public StorageUsageSummaryDto getStorageUsage(String worldId) throws IOException, InterruptedException {
        ensureSession();
        return request("GET", "/worlds/" + worldId + "/storage/usage", null, StorageUsageSummaryDto.class, true);
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
                .header("x-sharedworld-files", this.gson.toJson(files))
                .header("x-sharedworld-pack", this.gson.toJson(nonRegionPack))
                .header("x-sharedworld-region-bundles", this.gson.toJson(regionBundles))
                .GET();

        HttpResponse<String> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            ErrorDto error = tryParseError(response.body(), response.statusCode());
            throw new IOException(error.message());
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

        HttpResponse<Void> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 400) {
            throw new IOException("SharedWorld blob upload failed (" + response.statusCode() + ").");
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
            throw new IOException("SharedWorld blob download failed (" + response.statusCode() + ").");
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
            throw new IOException("SharedWorld blob download failed (" + response.statusCode() + ").");
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

    public SessionTokenDto ensureSession() throws IOException, InterruptedException {
        if (cachedSession != null && Instant.parse(cachedSession.expiresAt()).isAfter(Instant.now().plusSeconds(30))) {
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

        AuthChallengeDto challenge = request("POST", "/auth/challenge", Map.of(), AuthChallengeDto.class, false);
        this.sessionJoiner.joinServer(identity, challenge.serverId());
        SessionTokenDto session = request(
                "POST",
                "/auth/complete",
                Map.of(
                        "serverId", challenge.serverId(),
                        "playerName", identity.playerName()
                ),
                SessionTokenDto.class,
                false
        );
        cacheSession(session, false, false);
        return cachedSession;
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

    private static void joinMinecraftSessionServer(SessionIdentity identity, String serverId) throws IOException {
        try {
            UUID profileUuid = UUID.fromString(
                    CanonicalPlayerIdentity.normalizeUuidWithHyphens(identity.playerUuid(), "current backend player UUID")
            );
            Minecraft.getInstance().services().sessionService().joinServer(profileUuid, identity.accessToken(), serverId);
        } catch (com.mojang.authlib.exceptions.AuthenticationException exception) {
            throw new IOException("Failed to prove Minecraft session to SharedWorld.", exception);
        }
    }

    public static String currentPlayerUuidWithHyphens() {
        return currentBackendPlayerUuidWithHyphens();
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

    public static String canonicalPlayerUuidWithHyphens(String backendAssignedPlayerUuid) {
        return CanonicalPlayerIdentity.canonicalUuidForAssignment(
                backendAssignedPlayerUuid,
                currentBackendPlayerUuidWithHyphens()
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

    public static String friendlyErrorMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
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
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("accept", "application/json");

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
            throw new SharedWorldApiException(error.error(), error.message(), error.status());
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

        public SharedWorldApiException(String error, String message, int status) {
            super(message);
            this.error = error;
            this.status = status;
        }

        public String error() {
            return this.error;
        }

        public int status() {
            return this.status;
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

    @FunctionalInterface
    public interface SessionJoiner {
        void joinServer(SessionIdentity identity, String serverId) throws IOException;
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
