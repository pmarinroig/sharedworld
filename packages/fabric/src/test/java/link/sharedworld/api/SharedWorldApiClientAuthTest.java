package link.sharedworld.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import link.sharedworld.SharedWorldDevSessionBridge;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldApiClientAuthTest {
    private static final String DEV_AUTH_SECRET = "test-dev-auth-secret";

    @Test
    void ensureSessionUsesJoinServerAndNeverSendsAccessTokenToBackend() throws Exception {
        SharedWorldDevSessionBridge.clear();
        AtomicReference<String> completeBody = new AtomicReference<>();
        AtomicReference<String> joinedServerId = new AtomicReference<>();
        AtomicInteger challengeCalls = new AtomicInteger();
        AtomicInteger completeCalls = new AtomicInteger();

        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/auth/challenge", exchange -> {
                challengeCalls.incrementAndGet();
                writeJson(exchange, 200, """
                        {"serverId":"server-proof-123","expiresAt":"2099-01-01T00:00:00.000Z"}
                        """);
            });
            server.handle("/auth/complete", exchange -> {
                completeCalls.incrementAndGet();
                completeBody.set(readBody(exchange));
                writeJson(exchange, 200, """
                        {"token":"session-123","playerUuid":"11111111111111111111111111111111","playerName":"HostA","expiresAt":"2099-01-01T00:00:00.000Z"}
                        """);
            });

            SharedWorldApiClient client = new SharedWorldApiClient(
                    server.baseUrl(),
                    HttpClient.newHttpClient(),
                    () -> new SharedWorldApiClient.SessionIdentity(
                            "11111111-1111-1111-1111-111111111111",
                            "HostA",
                            "premium-access-token"
                    ),
                    (identity, serverId) -> joinedServerId.set(serverId)
            );

            SharedWorldModels.SessionTokenDto first = client.ensureSession();
            SharedWorldModels.SessionTokenDto second = client.ensureSession();

            assertEquals("session-123", first.token());
            assertEquals(first, second);
            assertEquals("server-proof-123", joinedServerId.get());
            assertEquals(1, challengeCalls.get());
            assertEquals(1, completeCalls.get());
            assertNotNull(completeBody.get());
            assertTrue(completeBody.get().contains("\"serverId\":\"server-proof-123\""));
            assertTrue(completeBody.get().contains("\"playerName\":\"HostA\""));
            assertFalse(completeBody.get().contains("accessToken"));
            assertFalse(completeBody.get().contains("playerUuid"));
            assertFalse(SharedWorldDevSessionBridge.isCurrentSessionDev());
            assertFalse(SharedWorldDevSessionBridge.backendAllowsInsecureE4mc());
        }
    }

    @Test
    void ensureSessionUsesDedicatedDevEndpointForDevSessions() throws Exception {
        SharedWorldDevSessionBridge.clear();
        AtomicReference<String> devCompleteBody = new AtomicReference<>();
        AtomicInteger joinCalls = new AtomicInteger();

        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/auth/dev-complete", exchange -> {
                devCompleteBody.set(readBody(exchange));
                writeJson(exchange, 200, """
                        {"token":"session-dev","playerUuid":"22222222222222222222222222222222","playerName":"GuestB","expiresAt":"2099-01-01T00:00:00.000Z","allowInsecureE4mc":true}
                        """);
            });

            SharedWorldApiClient client = new SharedWorldApiClient(
                    server.baseUrl(),
                    HttpClient.newHttpClient(),
                    () -> new SharedWorldApiClient.SessionIdentity(
                            "22222222-2222-2222-2222-222222222222",
                            "GuestB",
                            "dev:" + DEV_AUTH_SECRET
                    ),
                    (identity, serverId) -> joinCalls.incrementAndGet()
            );

            SharedWorldModels.SessionTokenDto session = client.ensureSession();

            assertEquals("session-dev", session.token());
            assertEquals(0, joinCalls.get());
            assertNotNull(devCompleteBody.get());
            assertTrue(devCompleteBody.get().contains("\"playerUuid\":\"22222222222222222222222222222222\""));
            assertTrue(devCompleteBody.get().contains("\"playerName\":\"GuestB\""));
            assertTrue(devCompleteBody.get().contains("\"secret\":\"" + DEV_AUTH_SECRET + "\""));
            assertTrue(SharedWorldDevSessionBridge.isCurrentSessionDev());
            assertTrue(SharedWorldDevSessionBridge.backendAllowsInsecureE4mc());
        }
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody()) {
            byte[] bytes = stream.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static final class TestHttpServer implements AutoCloseable {
        private final HttpServer server;

        private TestHttpServer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.start();
        }

        private void handle(String path, HttpHandler handler) {
            this.server.createContext(path, exchange -> handler.handle(exchange));
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + this.server.getAddress().getPort();
        }

        @Override
        public void close() {
            this.server.stop(0);
        }
    }

    @FunctionalInterface
    private interface HttpHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
