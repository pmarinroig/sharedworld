package link.sharedworld.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import link.sharedworld.SharedWorldDevSessionBridge;
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
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Certificate-first auth: a client holding a Mojang-signed profile keypair
 * signs the challenge nonce and never touches the sessionserver join flow;
 * cert-less clients and pre-cert backends fall back to it automatically.
 */
final class SharedWorldApiClientCertAuthTest {
    private static final String PLAYER_UUID = "11111111-1111-1111-1111-111111111111";
    private static final Gson GSON = new Gson();
    private static final String SESSION_JSON = """
            {"token":"session-cert","playerUuid":"11111111111111111111111111111111","playerName":"HostA","expiresAt":"2099-01-01T00:00:00.000Z"}
            """;

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

    private static ProfileCertificateData certificate(KeyPair keyPair) {
        return new ProfileCertificateData(
                keyPair.getPrivate(),
                keyPair.getPublic().getEncoded(),
                System.currentTimeMillis() + 48L * 60L * 60_000L,
                "mojang-signature-bytes".getBytes(StandardCharsets.UTF_8)
        );
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private SharedWorldApiClient client(String baseUrl, AtomicInteger joinCalls, Optional<ProfileCertificateData> certificate) {
        SharedWorldDevSessionBridge.clear();
        return new SharedWorldApiClient(
                baseUrl,
                HttpClient.newHttpClient(),
                () -> new SharedWorldApiClient.SessionIdentity(PLAYER_UUID, "HostA", "premium-access-token"),
                (identity, serverId) -> joinCalls.incrementAndGet(),
                () -> certificate
        );
    }

    private void serveChallenge() {
        this.server.createContext("/auth/challenge", exchange ->
                writeJson(exchange, 200, "{\"serverId\":\"server-1\",\"expiresAt\":\"2099-01-01T00:00:00.000Z\"}"));
    }

    @Test
    void aCertificateSignsTheChallengeAndSkipsTheJoinFlow() throws Exception {
        String baseUrl = startServer();
        serveChallenge();
        KeyPair keyPair = generateKeyPair();
        AtomicReference<JsonObject> received = new AtomicReference<>();
        this.server.createContext("/auth/complete-cert", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.set(GSON.fromJson(body, JsonObject.class));
            writeJson(exchange, 200, SESSION_JSON);
        });
        AtomicInteger joinCalls = new AtomicInteger();

        SessionTokenDto session = client(baseUrl, joinCalls, Optional.of(certificate(keyPair))).ensureSession();

        assertEquals("session-cert", session.token());
        assertEquals(0, joinCalls.get(), "certificate auth must never call Mojang joinServer");
        JsonObject request = received.get();
        assertNotNull(request);
        assertEquals("server-1", request.get("serverId").getAsString());
        assertEquals("11111111111111111111111111111111", request.get("playerUuid").getAsString());
        assertEquals("HostA", request.get("playerName").getAsString());
        assertEquals(
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                request.get("publicKey").getAsString());
        assertTrue(request.get("publicKeyExpiresAtMs").getAsLong() > System.currentTimeMillis());

        // The nonce signature must verify under the very key the request carries.
        byte[] publicKeyDer = Base64.getDecoder().decode(request.get("publicKey").getAsString());
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyDer)));
        verifier.update("server-1".getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(request.get("nonceSignature").getAsString())),
                "nonceSignature must be SHA256withRSA over the serverId");
    }

    @Test
    void aCertLessClientUsesTheJoinFlow() throws Exception {
        String baseUrl = startServer();
        serveChallenge();
        AtomicInteger completeCalls = new AtomicInteger();
        this.server.createContext("/auth/complete", exchange -> {
            completeCalls.incrementAndGet();
            writeJson(exchange, 200, SESSION_JSON);
        });
        AtomicInteger joinCalls = new AtomicInteger();

        SessionTokenDto session = client(baseUrl, joinCalls, Optional.empty()).ensureSession();

        assertEquals("session-cert", session.token());
        assertEquals(1, joinCalls.get());
        assertEquals(1, completeCalls.get());
    }

    @Test
    void aPreCertBackendFallsBackToTheJoinFlow() throws Exception {
        String baseUrl = startServer();
        serveChallenge();
        this.server.createContext("/auth/complete-cert", exchange ->
                writeJson(exchange, 404, "{\"error\":\"not_found\",\"message\":\"Route not found.\",\"status\":404}"));
        AtomicInteger completeCalls = new AtomicInteger();
        this.server.createContext("/auth/complete", exchange -> {
            completeCalls.incrementAndGet();
            writeJson(exchange, 200, SESSION_JSON);
        });
        AtomicInteger joinCalls = new AtomicInteger();

        SessionTokenDto session = client(baseUrl, joinCalls, Optional.of(certificate(generateKeyPair()))).ensureSession();

        assertEquals("session-cert", session.token());
        assertEquals(1, joinCalls.get(), "the fallback path proves the session via joinServer");
        assertEquals(1, completeCalls.get());
    }

    @Test
    void aRejectedCertificateFallsBackToTheJoinFlow() throws Exception {
        String baseUrl = startServer();
        serveChallenge();
        this.server.createContext("/auth/complete-cert", exchange ->
                writeJson(exchange, 403,
                        "{\"error\":\"certificate_invalid\",\"message\":\"Minecraft profile certificate is not validly signed for this player.\",\"status\":403}"));
        this.server.createContext("/auth/complete", exchange -> writeJson(exchange, 200, SESSION_JSON));
        AtomicInteger joinCalls = new AtomicInteger();

        SessionTokenDto session = client(baseUrl, joinCalls, Optional.of(certificate(generateKeyPair()))).ensureSession();

        assertEquals("session-cert", session.token());
        assertEquals(1, joinCalls.get());
    }
}
