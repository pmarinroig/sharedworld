package link.sharedworld.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.api.SharedWorldApiClient.SharedWorldApiException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Error-code fidelity: every HTTP path of the client, including the
 * hand-rolled downloadPlan and blob transfers, must surface backend error
 * codes as SharedWorldApiException so the terminal-state classifiers
 * (deleted world, membership revoked, host not active) see them.
 */
final class SharedWorldApiClientErrorTest {
    private static final String DEV_SESSION_JSON = """
            {"token":"session-err","playerUuid":"11111111111111111111111111111111","playerName":"HostA","expiresAt":"2099-01-01T00:00:00.000Z","allowInsecureE4mc":false}
            """;

    private static SharedWorldApiClient client(String baseUrl) {
        SharedWorldDevSessionBridge.clear();
        return new SharedWorldApiClient(
                baseUrl,
                HttpClient.newHttpClient(),
                () -> new SharedWorldApiClient.SessionIdentity(
                        "11111111-1111-1111-1111-111111111111",
                        "HostA",
                        "dev:test-secret"
                ),
                () -> {
                    throw new IllegalStateException("dev sessions never consult the certificate provider");
                }
        );
    }

    private static void write(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private interface ServerScript {
        void run(HttpServer server, String baseUrl) throws Exception;
    }

    private static void withServer(ServerScript script) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/dev-complete", exchange -> write(exchange, 200, DEV_SESSION_JSON, "application/json"));
        server.start();
        try {
            script.run(server, "http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
            SharedWorldDevSessionBridge.clear();
        }
    }

    @Test
    void downloadPlanSurfacesBackendErrorCodes() throws Exception {
        withServer((server, baseUrl) -> {
            server.createContext("/worlds/w1/downloads/plan", exchange ->
                    write(exchange, 404, "{\"error\":\"world_not_found\",\"message\":\"SharedWorld server not found.\",\"status\":404}", "application/json"));

            SharedWorldApiException error = assertThrows(SharedWorldApiException.class, () ->
                    client(baseUrl).downloadPlan("w1", new SharedWorldModels.LocalFileDescriptorDto[0], null, null));

            assertEquals("world_not_found", error.error());
            assertEquals(404, error.status());
            assertTrue(SharedWorldApiClient.isDeletedWorldError(error));
        });
    }

    @Test
    void uploadBlobSurfacesBackendErrorCodes() throws Exception {
        withServer((server, baseUrl) -> {
            server.createContext("/blob/up", exchange ->
                    write(exchange, 403, "{\"error\":\"membership_revoked\",\"message\":\"You are no longer a member.\",\"status\":403}", "application/json"));
            Path bodyFile = Files.createTempFile("sharedworld-blob", ".bin");
            Files.writeString(bodyFile, "payload");
            try {
                SharedWorldModels.SignedBlobUrlDto signedUrl =
                        new SharedWorldModels.SignedBlobUrlDto("PUT", baseUrl + "/blob/up", null, "2099-01-01T00:00:00.000Z");

                SharedWorldApiException error = assertThrows(SharedWorldApiException.class, () ->
                        client(baseUrl).uploadBlob(signedUrl, bodyFile, "application/octet-stream"));

                assertEquals("membership_revoked", error.error());
                assertTrue(SharedWorldApiClient.isMembershipRevokedError(error));
            } finally {
                Files.deleteIfExists(bodyFile);
            }
        });
    }

    @Test
    void uploadBlobFallsBackToHttpErrorForNonBackendBodies() throws Exception {
        withServer((server, baseUrl) -> {
            server.createContext("/blob/up", exchange ->
                    write(exchange, 503, "<Error><Code>SlowDown</Code></Error>", "application/xml"));
            Path bodyFile = Files.createTempFile("sharedworld-blob", ".bin");
            Files.writeString(bodyFile, "payload");
            try {
                SharedWorldModels.SignedBlobUrlDto signedUrl =
                        new SharedWorldModels.SignedBlobUrlDto("PUT", baseUrl + "/blob/up", null, "2099-01-01T00:00:00.000Z");

                SharedWorldApiException error = assertThrows(SharedWorldApiException.class, () ->
                        client(baseUrl).uploadBlob(signedUrl, bodyFile, "application/octet-stream"));

                assertEquals("http_error", error.error());
                assertEquals(503, error.status());
                assertTrue(error.getMessage().contains("upload failed (503)"));
            } finally {
                Files.deleteIfExists(bodyFile);
            }
        });
    }

    @Test
    void downloadBlobSurfacesBackendErrorCodes() throws Exception {
        withServer((server, baseUrl) -> {
            server.createContext("/blob/down", exchange ->
                    write(exchange, 409, "{\"error\":\"host_not_active\",\"message\":\"Host is not active.\",\"status\":409}", "application/json"));
            Path target = Files.createTempFile("sharedworld-download", ".bin");
            try {
                SharedWorldModels.SignedBlobUrlDto signedUrl =
                        new SharedWorldModels.SignedBlobUrlDto("GET", baseUrl + "/blob/down", null, "2099-01-01T00:00:00.000Z");

                SharedWorldApiException error = assertThrows(SharedWorldApiException.class, () ->
                        client(baseUrl).downloadBlobToFile(signedUrl, target));

                assertEquals("host_not_active", error.error());
                assertTrue(SharedWorldApiClient.isHostNotActiveError(error));
            } finally {
                Files.deleteIfExists(target);
            }
        });
    }

    @Test
    void classifiersSeeApiErrorsThroughWrapperExceptions() {
        SharedWorldApiException apiError = new SharedWorldApiException("world_not_found", "gone", 404);
        Throwable wrapped = new CompletionException(new IOException("SharedWorld sync task failed.", apiError));

        assertTrue(SharedWorldApiClient.isDeletedWorldError(wrapped));
        assertEquals("world_not_found", SharedWorldApiClient.errorCode(wrapped));
    }

    @Test
    void friendlyErrorMessagePrefersTheBackendMessage() {
        Throwable wrapped = new CompletionException(
                new SharedWorldApiException("host_not_active", "The host is no longer active.", 409));
        assertEquals("The host is no longer active.", SharedWorldApiClient.friendlyErrorMessage(wrapped));
    }

    @Test
    void friendlyErrorMessageMapsConnectivityFailuresToFriendlyStrings() {
        String offline = SharedWorldApiClient.friendlyErrorMessage(
                new IOException("boom", new java.net.ConnectException("Connection refused")));
        assertTrue(offline.contains("error_internet_unreachable") || !offline.contains("Connection refused"),
                "raw JDK socket text must not leak: " + offline);

        String unresolved = SharedWorldApiClient.friendlyErrorMessage(
                new IOException("java.nio.channels.UnresolvedAddressException"));
        assertTrue(unresolved.contains("error_backend_unreachable") || !unresolved.contains("UnresolvedAddress"),
                "raw JDK socket text must not leak: " + unresolved);
    }

    @Test
    void friendlyErrorMessageNeverReturnsNull() {
        String message = SharedWorldApiClient.friendlyErrorMessage(new IOException((String) null));
        assertInstanceOf(String.class, message);
        assertTrue(!message.isBlank());
    }
}
