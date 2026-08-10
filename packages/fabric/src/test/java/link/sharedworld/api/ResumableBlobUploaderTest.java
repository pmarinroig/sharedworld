package link.sharedworld.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the uploader against a second-origin fake Drive session endpoint:
 * SharedWorld's backend is not involved at all, exactly like production.
 */
class ResumableBlobUploaderTest {
    private HttpServer server;
    private final ByteArrayOutputStream received = new ByteArrayOutputStream();
    private final List<String> contentRanges = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private long expectedTotal;
    private int reject429Remaining;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startFakeDrive() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/session", this::handleSession);
        this.server.start();
    }

    @AfterEach
    void stopFakeDrive() {
        this.server.stop(0);
    }

    private void handleSession(HttpExchange exchange) throws IOException {
        String contentRange = exchange.getRequestHeaders().getFirst("Content-range");
        this.contentRanges.add(contentRange);
        this.authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (contentRange != null && contentRange.startsWith("bytes */")) {
            // Status probe: report the high-water mark.
            respond(exchange, this.received.size() >= this.expectedTotal ? 200 : 308,
                    this.received.size() > 0 ? "bytes=0-" + (this.received.size() - 1) : null,
                    this.received.size() >= this.expectedTotal ? "{\"id\":\"file-1\",\"size\":\"" + this.received.size() + "\"}" : null);
            return;
        }
        if (this.reject429Remaining > 0) {
            this.reject429Remaining -= 1;
            respond(exchange, 429, null, "{\"error\":{\"message\":\"rate limited\"}}");
            return;
        }
        this.received.write(body);
        boolean complete = this.received.size() >= this.expectedTotal;
        respond(exchange, complete ? 200 : 308,
                "bytes=0-" + (this.received.size() - 1),
                complete ? "{\"id\":\"file-1\",\"size\":\"" + this.received.size() + "\"}" : null);
    }

    private static void respond(HttpExchange exchange, int status, String range, String body) throws IOException {
        if (range != null) {
            exchange.getResponseHeaders().add("Range", range);
        }
        byte[] bytes = body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1L : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private String sessionUrl() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/session";
    }

    private static byte[] patterned(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (index * 17);
        }
        return bytes;
    }

    @Test
    void uploadsInChunksWithContentRangesAndNoAuthHeader() throws Exception {
        byte[] payload = patterned(700_000);
        this.expectedTotal = payload.length;
        Path bodyFile = this.tempDir.resolve("body.bin");
        Files.write(bodyFile, payload);

        new ResumableBlobUploader(HttpClient.newHttpClient(), sessionUrl(), 256 * 1024)
                .upload(bodyFile, "application/octet-stream", null);

        assertArrayEquals(payload, this.received.toByteArray());
        assertEquals(3, this.contentRanges.size());
        assertEquals("bytes 0-262143/700000", this.contentRanges.get(0));
        assertEquals("bytes 262144-524287/700000", this.contentRanges.get(1));
        assertEquals("bytes 524288-699999/700000", this.contentRanges.get(2));
        for (String auth : this.authHeaders) {
            assertNull(auth, "no SharedWorld credential may reach the provider origin");
        }
    }

    @Test
    void a429ChunkBacksOffProbesAndResumesFromTheServerOffset() throws Exception {
        byte[] payload = patterned(600_000);
        this.expectedTotal = payload.length;
        this.reject429Remaining = 1;
        Path bodyFile = this.tempDir.resolve("body429.bin");
        Files.write(bodyFile, payload);

        new ResumableBlobUploader(HttpClient.newHttpClient(), sessionUrl(), 256 * 1024)
                .upload(bodyFile, "application/octet-stream", null);

        assertArrayEquals(payload, this.received.toByteArray());
        // chunk1 429'd, probe (bytes */total) says nothing landed, chunk1
        // replays, then chunks 2 and 3 land once each.
        assertTrue(this.contentRanges.contains("bytes */600000"), String.valueOf(this.contentRanges));
        long chunk1Attempts = this.contentRanges.stream().filter("bytes 0-262143/600000"::equals).count();
        assertEquals(2, chunk1Attempts);
    }
}
