package link.sharedworld.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.api.SharedWorldApiClient.SharedWorldApiException;
import link.sharedworld.api.SharedWorldModels.SessionTokenDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session lifecycle: persisted tokens skip certificate auth entirely,
 * rejected tokens trigger exactly one automatic re-auth, and transient
 * backend identity verification failures get one full fresh attempt.
 */
final class SharedWorldApiClientSessionTest {
    private static final String PLAYER_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String SESSION_JSON = """
            {"token":"session-fresh","playerUuid":"11111111111111111111111111111111","playerName":"HostA","expiresAt":"2099-01-01T00:00:00.000Z"}
            """;
    private static final KeyPair KEY_PAIR = generateKeyPair();

    /** In-memory SessionPersistence recording interactions. */
    private static final class FakePersistence implements SharedWorldApiClient.SessionPersistence {
        private final Map<String, SessionTokenDto> entries = new HashMap<>();
        int loads;
        int saves;
        int clears;

        @Override
        public synchronized SessionTokenDto load(String baseUrl, String playerUuid) {
            this.loads += 1;
            return this.entries.get(baseUrl + "|" + playerUuid);
        }

        @Override
        public synchronized void save(String baseUrl, String playerUuid, SessionTokenDto session) {
            this.saves += 1;
            this.entries.put(baseUrl + "|" + playerUuid, session);
        }

        @Override
        public synchronized void clear(String baseUrl, String playerUuid) {
            this.clears += 1;
            this.entries.remove(baseUrl + "|" + playerUuid);
        }
    }

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.stop(0);
        }
        SharedWorldDevSessionBridge.clear();
    }

    private String startServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.start();
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ProfileCertificateData certificate() {
        return new ProfileCertificateData(
                KEY_PAIR.getPrivate(),
                KEY_PAIR.getPublic().getEncoded(),
                System.currentTimeMillis() + 48L * 60L * 60_000L,
                "mojang-signature-bytes".getBytes(StandardCharsets.UTF_8)
        );
    }

    /** certAttempts counts full certificate-auth handshakes (the provider is consulted once per attempt). */
    private SharedWorldApiClient client(String baseUrl, AtomicInteger certAttempts, FakePersistence persistence) {
        SharedWorldDevSessionBridge.clear();
        SharedWorldApiClient client = new SharedWorldApiClient(
                baseUrl,
                HttpClient.newHttpClient(),
                () -> new SharedWorldApiClient.SessionIdentity(PLAYER_UUID, "HostA", "premium-access-token"),
                () -> {
                    certAttempts.incrementAndGet();
                    return Optional.of(certificate());
                }
        );
        client.setSessionPersistence(persistence);
        return client;
    }

    @Test
    void aValidPersistedTokenSkipsCertificateAuthEntirely() throws Exception {
        String baseUrl = startServer();
        AtomicInteger certAttempts = new AtomicInteger();
        FakePersistence persistence = new FakePersistence();
        persistence.save(baseUrl, PLAYER_UUID,
                new SessionTokenDto("token-persisted", "1111", "HostA", "2099-01-01T00:00:00.000Z"));
        persistence.saves = 0;

        SessionTokenDto session = client(baseUrl, certAttempts, persistence).ensureSession();

        assertEquals("token-persisted", session.token());
        assertEquals(0, certAttempts.get(), "no certificate handshake for a persisted session");
        assertEquals(0, persistence.saves);
    }

    @Test
    void freshSessionsArePersistedForTheNextLaunch() throws Exception {
        String baseUrl = startServer();
        this.server.createContext("/auth/challenge", exchange ->
                writeJson(exchange, 200, "{\"serverId\":\"server-1\",\"expiresAt\":\"2099-01-01T00:00:00.000Z\"}"));
        this.server.createContext("/auth/complete-cert", exchange -> writeJson(exchange, 200, SESSION_JSON));
        AtomicInteger certAttempts = new AtomicInteger();
        FakePersistence persistence = new FakePersistence();

        SessionTokenDto session = client(baseUrl, certAttempts, persistence).ensureSession();

        assertEquals("session-fresh", session.token());
        assertEquals(1, certAttempts.get());
        assertEquals(1, persistence.saves);
        assertEquals("session-fresh", persistence.entries.get(baseUrl + "|" + PLAYER_UUID).token());
    }

    @Test
    void aRejectedSessionTokenTriggersExactlyOneReauth() throws Exception {
        String baseUrl = startServer();
        AtomicInteger worldsCalls = new AtomicInteger();
        this.server.createContext("/worlds", exchange -> {
            if (worldsCalls.incrementAndGet() == 1) {
                writeJson(exchange, 401, "{\"error\":\"invalid_session\",\"message\":\"Session token is invalid.\",\"status\":401}");
                return;
            }
            writeJson(exchange, 200, "[]");
        });
        this.server.createContext("/auth/challenge", exchange ->
                writeJson(exchange, 200, "{\"serverId\":\"server-1\",\"expiresAt\":\"2099-01-01T00:00:00.000Z\"}"));
        this.server.createContext("/auth/complete-cert", exchange -> writeJson(exchange, 200, SESSION_JSON));
        AtomicInteger certAttempts = new AtomicInteger();
        FakePersistence persistence = new FakePersistence();
        persistence.save(baseUrl, PLAYER_UUID,
                new SessionTokenDto("token-stale", "1111", "HostA", "2099-01-01T00:00:00.000Z"));

        var worlds = client(baseUrl, certAttempts, persistence).listWorlds();

        assertTrue(worlds.isEmpty());
        assertEquals(2, worldsCalls.get(), "the request is replayed once after re-auth");
        assertEquals(1, certAttempts.get(), "re-auth goes through the full certificate flow");
        assertTrue(persistence.clears >= 1, "the stale persisted token is dropped");
        assertEquals("session-fresh", persistence.entries.get(baseUrl + "|" + PLAYER_UUID).token());
    }

    @Test
    void aSecondSessionRejectionSurfacesTheError() throws Exception {
        String baseUrl = startServer();
        this.server.createContext("/worlds", exchange ->
                writeJson(exchange, 401, "{\"error\":\"expired_session\",\"message\":\"Session token has expired.\",\"status\":401}"));
        this.server.createContext("/auth/challenge", exchange ->
                writeJson(exchange, 200, "{\"serverId\":\"server-1\",\"expiresAt\":\"2099-01-01T00:00:00.000Z\"}"));
        this.server.createContext("/auth/complete-cert", exchange -> writeJson(exchange, 200, SESSION_JSON));
        AtomicInteger certAttempts = new AtomicInteger();

        SharedWorldApiException error = assertThrows(SharedWorldApiException.class,
                () -> client(baseUrl, certAttempts, new FakePersistence()).listWorlds());

        assertEquals("expired_session", error.error());
        assertEquals(2, certAttempts.get(), "the initial auth plus exactly one automatic re-auth, never more");
    }

    @Test
    void transientIdentityVerificationGetsOneFullFreshAttempt() throws Exception {
        String baseUrl = startServer();
        AtomicInteger challengeCalls = new AtomicInteger();
        this.server.createContext("/auth/challenge", exchange ->
                writeJson(exchange, 200, "{\"serverId\":\"server-" + challengeCalls.incrementAndGet() + "\",\"expiresAt\":\"2099-01-01T00:00:00.000Z\"}"));
        AtomicInteger completeCalls = new AtomicInteger();
        this.server.createContext("/auth/complete-cert", exchange -> {
            if (completeCalls.incrementAndGet() == 1) {
                writeJson(exchange, 503, "{\"error\":\"identity_verification_unavailable\",\"message\":\"Minecraft's key registry is unreachable right now.\",\"status\":503}");
                return;
            }
            writeJson(exchange, 200, SESSION_JSON);
        });
        AtomicInteger certAttempts = new AtomicInteger();

        SessionTokenDto session = client(baseUrl, certAttempts, new FakePersistence()).ensureSession();

        assertEquals("session-fresh", session.token());
        assertEquals(2, challengeCalls.get(), "the re-attempt starts a fresh challenge");
        assertEquals(2, certAttempts.get(), "the re-attempt re-signs with a fresh certificate lookup");
    }

    @Test
    void anExpiredLookingPersistedTokenIsStillTriedAgainstTheServer() throws Exception {
        // The server is the authority on token lifetime. Discarding sessions
        // by the LOCAL clock meant a skewed clock re-ran the full auth
        // handshake on every call — self-inflicted rate limiting. A genuinely
        // expired token surfaces as a 401 that request() recovers from.
        String baseUrl = startServer();
        this.server.createContext("/worlds", exchange -> writeJson(exchange, 200, "[]"));
        AtomicInteger certAttempts = new AtomicInteger();
        FakePersistence persistence = new FakePersistence();
        persistence.save(baseUrl, PLAYER_UUID,
                new SessionTokenDto("token-old-clock", "1111", "HostA", "2020-01-01T00:00:00.000Z"));

        var worlds = client(baseUrl, certAttempts, persistence).listWorlds();

        assertTrue(worlds.isEmpty());
        assertEquals(0, certAttempts.get(), "no auth handshake while the server still accepts the token");
    }

    @Test
    void theVerificationRetryWaitsForTheBackendsRetryAfter() throws Exception {
        String baseUrl = startServer();
        this.server.createContext("/auth/challenge", exchange ->
                writeJson(exchange, 200, "{\"serverId\":\"server-1\",\"expiresAt\":\"2099-01-01T00:00:00.000Z\"}"));
        AtomicInteger completeCalls = new AtomicInteger();
        this.server.createContext("/auth/complete-cert", exchange -> {
            completeCalls.incrementAndGet();
            exchange.getResponseHeaders().set("retry-after", "1");
            writeJson(exchange, 503,
                    "{\"error\":\"identity_verification_unavailable\",\"message\":\"Minecraft's key registry is unreachable right now.\",\"status\":503}");
        });
        AtomicInteger certAttempts = new AtomicInteger();

        long startedAt = System.nanoTime();
        SharedWorldApiException error = assertThrows(SharedWorldApiException.class,
                () -> client(baseUrl, certAttempts, new FakePersistence()).ensureSession());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals("identity_verification_unavailable", error.error());
        assertEquals(1, (int) error.retryAfterSeconds(), "the retry-after header rides on the exception");
        assertEquals(2, completeCalls.get(), "exactly one full fresh re-attempt");
        assertTrue(elapsedMillis >= 900L, "the re-attempt honors the backend's retry-after pause");
    }

    @Test
    void verificationRetryDelayHonorsRetryAfterWithACap() {
        assertEquals(2_000L, SharedWorldApiClient.verificationRetryDelayMillis(null), "no header: short default");
        assertEquals(2_000L, SharedWorldApiClient.verificationRetryDelayMillis(0), "nonsense header: short default");
        assertEquals(10_000L, SharedWorldApiClient.verificationRetryDelayMillis(10));
        assertEquals(15_000L, SharedWorldApiClient.verificationRetryDelayMillis(60), "capped so the UI never sits for a minute");
    }

    @Test
    void malformedPersistedExpiryFallsBackToAFreshAuth() throws Exception {
        String baseUrl = startServer();
        this.server.createContext("/auth/challenge", exchange ->
                writeJson(exchange, 200, "{\"serverId\":\"server-1\",\"expiresAt\":\"2099-01-01T00:00:00.000Z\"}"));
        this.server.createContext("/auth/complete-cert", exchange -> writeJson(exchange, 200, SESSION_JSON));
        AtomicInteger certAttempts = new AtomicInteger();
        FakePersistence persistence = new FakePersistence();
        persistence.entries.put(baseUrl + "|" + PLAYER_UUID,
                new SessionTokenDto("token-bad", "1111", "HostA", "garbage-timestamp"));

        SessionTokenDto session = client(baseUrl, certAttempts, persistence).ensureSession();

        assertEquals("session-fresh", session.token());
        assertEquals(1, certAttempts.get());
        assertEquals("session-fresh", persistence.entries.get(baseUrl + "|" + PLAYER_UUID).token(),
                "the malformed entry is replaced by the fresh session");
    }
}
