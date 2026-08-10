package link.sharedworld.sync;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.DownloadPlanDto;
import link.sharedworld.api.SharedWorldModels.ErrorDto;
import link.sharedworld.api.SharedWorldModels.SignedBlobUrlDto;
import link.sharedworld.api.SharedWorldModels.SnapshotManifestDto;
import link.sharedworld.api.SharedWorldModels.SyncPolicyDto;
import link.sharedworld.api.SharedWorldModels.UploadPlanDto;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SyncTestHttpServer implements AutoCloseable {
    private static final String DEV_AUTH_SECRET = "test-dev-auth-secret";
    private static final String HOST_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String EXPIRES_AT = "2099-01-01T00:00:00.000Z";

    private final HttpServer server;
    private final Gson gson = new Gson();
    private final Map<String, byte[]> downloadableBlobs = new LinkedHashMap<>();
    private final Map<String, int[]> scriptedBlobFailures = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> blobRequestCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, RecordedBlobUpload> uploadedBlobs = new LinkedHashMap<>();
    private final Map<String, List<String>> blobRangeHeaders = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> truncateBlobAfterBytes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> stallBlobsOnce = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> ignoreRangeOnce = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private UploadPlanDto uploadPlan;
    private DownloadPlanDto downloadPlan;
    private SnapshotManifestDto finalizeManifest;
    private ErrorDto finalizeError;
    private String lastPrepareUploadsBody;
    private String lastDownloadPlanBody;
    private String lastFinalizeSnapshotBody;

    SyncTestHttpServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    SharedWorldApiClient apiClient() {
        return new SharedWorldApiClient(
                baseUrl(),
                HttpClient.newHttpClient(),
                () -> new SharedWorldApiClient.SessionIdentity(HOST_UUID, "HostA", "dev:" + DEV_AUTH_SECRET)
        );
    }

    String baseUrl() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    void setUploadPlan(UploadPlanDto uploadPlan) {
        this.uploadPlan = uploadPlan;
    }

    void setDownloadPlan(DownloadPlanDto downloadPlan) {
        this.downloadPlan = downloadPlan;
    }

    void setFinalizeManifest(SnapshotManifestDto finalizeManifest) {
        this.finalizeManifest = finalizeManifest;
        this.finalizeError = null;
    }

    void failFinalize(String error, String message, int status) {
        this.finalizeError = new ErrorDto(error, message, status);
    }

    void seedBlob(String blobId, byte[] body) {
        this.downloadableBlobs.put(blobId, body);
    }

    /** The next {@code count} requests for this blob answer with {@code status}. */
    void failBlob(String blobId, int status, int count) {
        this.scriptedBlobFailures.put(blobId, new int[]{status, count});
    }

    /** The next GET of this blob declares the full length but writes only {@code bytes} before dropping the connection. */
    void truncateBlobOnce(String blobId, int bytes) {
        this.truncateBlobAfterBytes.put(blobId, bytes);
    }

    /** The next GET of this blob writes 2 bytes then holds the connection open (stall). */
    void stallBlobOnce(String blobId) {
        this.stallBlobsOnce.add(blobId);
    }

    /** The next GET of this blob ignores any Range header (old-backend behavior). */
    void ignoreRangeOnce(String blobId) {
        this.ignoreRangeOnce.add(blobId);
    }

    /** Range headers observed per GET of this blob, null entries for range-less requests. */
    List<String> blobRangeHeaders(String blobId) {
        return this.blobRangeHeaders.getOrDefault(blobId, List.of());
    }

    int blobRequestCount(String blobId) {
        return this.blobRequestCounts.getOrDefault(blobId, 0);
    }

    SignedBlobUrlDto downloadUrl(String blobId) {
        return new SignedBlobUrlDto("GET", this.baseUrl() + "/blobs/" + blobId, Map.of(), EXPIRES_AT);
    }

    SignedBlobUrlDto uploadUrl(String blobId) {
        return new SignedBlobUrlDto("PUT", this.baseUrl() + "/blobs/" + blobId, Map.of(), EXPIRES_AT);
    }

    byte[] uploadedBlobBody(String blobId) {
        RecordedBlobUpload upload = this.uploadedBlobs.get(blobId);
        return upload == null ? null : upload.body();
    }

    String lastPrepareUploadsBody() {
        return this.lastPrepareUploadsBody;
    }

    String lastDownloadPlanBody() {
        return this.lastDownloadPlanBody;
    }

    String lastFinalizeSnapshotBody() {
        return this.lastFinalizeSnapshotBody;
    }

    static SyncPolicyDto syncPolicy() {
        return new SyncPolicyDto(4, 2, 2, 10, 25, 250, 95_000_000L);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if ("/auth/dev-complete".equals(path)) {
                writeJson(exchange, 200, Map.of(
                        "token", "session-dev",
                        "playerUuid", HOST_UUID.replace("-", ""),
                        "playerName", "HostA",
                        "expiresAt", EXPIRES_AT,
                        "allowInsecureE4mc", true
                ));
                return;
            }
            if (path.endsWith("/uploads/prepare")) {
                this.lastPrepareUploadsBody = readBody(exchange);
                if (this.uploadPlan == null) {
                    writeError(exchange, "missing_upload_plan", "Upload plan was not configured.", 500);
                    return;
                }
                writeJson(exchange, 200, this.uploadPlan);
                return;
            }
            if (path.endsWith("/uploads/finalize-snapshot")) {
                this.lastFinalizeSnapshotBody = readBody(exchange);
                if (this.finalizeError != null) {
                    writeJson(exchange, this.finalizeError.status(), this.finalizeError);
                    return;
                }
                if (this.finalizeManifest == null) {
                    writeError(exchange, "missing_finalize_manifest", "Finalize manifest was not configured.", 500);
                    return;
                }
                writeJson(exchange, 200, this.finalizeManifest);
                return;
            }
            if (path.endsWith("/downloads/plan")) {
                this.lastDownloadPlanBody = readBody(exchange);
                if (this.downloadPlan == null) {
                    writeError(exchange, "missing_download_plan", "Download plan was not configured.", 500);
                    return;
                }
                writeJson(exchange, 200, this.downloadPlan);
                return;
            }
            if (path.startsWith("/blobs/")) {
                handleBlob(exchange, path.substring("/blobs/".length()));
                return;
            }

            writeError(exchange, "not_found", "No test route matched " + path + ".", 404);
        } finally {
            exchange.close();
        }
    }

    private void handleBlob(HttpExchange exchange, String blobId) throws IOException {
        this.blobRequestCounts.merge(blobId, 1, Integer::sum);
        int[] scripted = this.scriptedBlobFailures.get(blobId);
        if (scripted != null && scripted[1] > 0) {
            scripted[1] -= 1;
            writeError(exchange, "blob_transport_failure", "Scripted transient blob failure.", scripted[0]);
            return;
        }
        if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            byte[] body = readBodyBytes(exchange);
            this.uploadedBlobs.put(blobId, new RecordedBlobUpload(body, copyHeaders(exchange.getRequestHeaders())));
            exchange.sendResponseHeaders(200, -1L);
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, "unsupported_method", "Unsupported blob method.", 405);
            return;
        }

        byte[] body = this.downloadableBlobs.get(blobId);
        if (body == null) {
            writeError(exchange, "blob_not_found", "Blob " + blobId + " was not configured.", 404);
            return;
        }
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        this.blobRangeHeaders.computeIfAbsent(blobId, ignored -> java.util.Collections.synchronizedList(new java.util.ArrayList<>())).add(rangeHeader);
        int offset = 0;
        if (rangeHeader != null && !this.ignoreRangeOnce.remove(blobId)
                && rangeHeader.startsWith("bytes=") && rangeHeader.endsWith("-")) {
            offset = Integer.parseInt(rangeHeader.substring("bytes=".length(), rangeHeader.length() - 1));
        }
        byte[] slice = offset > 0 ? java.util.Arrays.copyOfRange(body, offset, body.length) : body;
        exchange.getResponseHeaders().add("content-type", "application/octet-stream");
        if (offset > 0) {
            exchange.getResponseHeaders().add("content-range", "bytes " + offset + "-" + (body.length - 1) + "/" + body.length);
            exchange.sendResponseHeaders(206, slice.length);
        } else {
            exchange.sendResponseHeaders(200, slice.length);
        }
        java.io.OutputStream output = exchange.getResponseBody();
        Integer truncateAt = this.truncateBlobAfterBytes.remove(blobId);
        if (truncateAt != null) {
            // Write fewer bytes than declared, then let the caller's close
            // drop the connection: the client sees an early EOF mid-body.
            output.write(slice, 0, Math.min(truncateAt, slice.length));
            output.flush();
            return;
        }
        if (this.stallBlobsOnce.remove(blobId)) {
            output.write(slice, 0, Math.min(2, slice.length));
            output.flush();
            try {
                Thread.sleep(3_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        output.write(slice);
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = this.gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void writeError(HttpExchange exchange, String error, String message, int status) throws IOException {
        writeJson(exchange, status, new ErrorDto(error, message, status));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(readBodyBytes(exchange), StandardCharsets.UTF_8);
    }

    private static byte[] readBodyBytes(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody()) {
            return stream.readAllBytes();
        }
    }

    private static Map<String, List<String>> copyHeaders(Headers headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return copy;
    }

    @Override
    public void close() {
        this.server.stop(0);
    }

    record RecordedBlobUpload(byte[] body, Map<String, List<String>> headers) {
    }
}
