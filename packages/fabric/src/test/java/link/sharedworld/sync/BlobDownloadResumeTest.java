package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobDownloadResumeTest {
    private static final String STALL_PROPERTY = "sharedworld.transferStallTimeoutMs";

    @TempDir
    Path tempDir;

    private String previousStallTimeout;

    @BeforeEach
    void shrinkStallTimeout() {
        this.previousStallTimeout = System.getProperty(STALL_PROPERTY);
        System.setProperty(STALL_PROPERTY, "300");
    }

    @AfterEach
    void restoreStallTimeout() {
        if (this.previousStallTimeout == null) {
            System.clearProperty(STALL_PROPERTY);
        } else {
            System.setProperty(STALL_PROPERTY, this.previousStallTimeout);
        }
    }

    private static byte[] patternedBytes(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (index * 31);
        }
        return bytes;
    }

    @Test
    void truncatedDownloadKeepsThePartialAndResumesWithRange() throws Exception {
        byte[] blob = patternedBytes(64 * 1024);
        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.seedBlob("big", blob);
            server.truncateBlobOnce("big", 10_000);
            SharedWorldApiClient client = server.apiClient();
            Path target = this.tempDir.resolve("artifact.bin");
            Path partial = this.tempDir.resolve("artifact.bin.swpart");

            assertThrows(SharedWorldApiClient.BlobStreamInterruptedException.class,
                    () -> client.downloadRawBlobToFile(server.downloadUrl("big"), target));
            assertTrue(Files.exists(partial), "partial must survive the failed attempt");
            long partialSize = Files.size(partial);
            assertTrue(partialSize > 0 && partialSize < blob.length, "partial holds a strict prefix");

            client.downloadRawBlobToFile(server.downloadUrl("big"), target);

            assertArrayEquals(blob, Files.readAllBytes(target));
            assertFalse(Files.exists(partial), "partial is consumed by the successful attempt");
            List<String> ranges = server.blobRangeHeaders("big");
            assertEquals(2, ranges.size());
            assertNull(ranges.get(0), "first attempt starts from scratch");
            assertEquals("bytes=" + partialSize + "-", ranges.get(1), "second attempt resumes from the partial");
        }
    }

    @Test
    void aServerIgnoringRangeRestartsCleanlyFromByteZero() throws Exception {
        byte[] blob = patternedBytes(4 * 1024);
        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.seedBlob("legacy", blob);
            server.ignoreRangeOnce("legacy");
            SharedWorldApiClient client = server.apiClient();
            Path target = this.tempDir.resolve("legacy.bin");
            Path partial = this.tempDir.resolve("legacy.bin.swpart");
            Files.write(partial, new byte[]{1, 2, 3, 4, 5});

            client.downloadRawBlobToFile(server.downloadUrl("legacy"), target);

            assertArrayEquals(blob, Files.readAllBytes(target), "a 200 after Range truncates the stale partial");
            assertEquals("bytes=5-", server.blobRangeHeaders("legacy").get(0));
        }
    }

    @Test
    void aStalledDownloadIsAbortedAsRetryable() throws Exception {
        byte[] blob = patternedBytes(8 * 1024);
        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.seedBlob("stalled", blob);
            server.stallBlobOnce("stalled");
            SharedWorldApiClient client = server.apiClient();
            Path target = this.tempDir.resolve("stalled.bin");

            long startedAt = System.nanoTime();
            SharedWorldApiClient.BlobStreamInterruptedException failure = assertThrows(
                    SharedWorldApiClient.BlobStreamInterruptedException.class,
                    () -> client.downloadRawBlobToFile(server.downloadUrl("stalled"), target));
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

            assertTrue(failure.getMessage().contains("stalled"), "stall abort names the stall: " + failure.getMessage());
            assertTrue(SharedWorldApiClient.isRetryableTransportError(failure), "stalls must be retryable");
            assertTrue(elapsedMillis < 2_500L, "watchdog aborted well before the server released the connection");
        }
    }

    @Test
    void gunzipDownloadsResumeOnCompressedBytesAndDecodeAtTheEnd() throws Exception {
        byte[] payload = patternedBytes(32 * 1024);
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(compressed)) {
            gzip.write(payload);
        }
        byte[] blob = compressed.toByteArray();
        try (SyncTestHttpServer server = new SyncTestHttpServer()) {
            server.seedBlob("gz", blob);
            server.truncateBlobOnce("gz", Math.max(1, blob.length / 2));
            SharedWorldApiClient client = server.apiClient();
            Path target = this.tempDir.resolve("payload.bin");

            assertThrows(SharedWorldApiClient.BlobStreamInterruptedException.class,
                    () -> client.downloadBlobToFile(server.downloadUrl("gz"), target));
            client.downloadBlobToFile(server.downloadUrl("gz"), target);

            assertArrayEquals(payload, Files.readAllBytes(target));
            assertFalse(Files.exists(this.tempDir.resolve("payload.bin.swpart")));
            assertEquals(2, server.blobRangeHeaders("gz").size());
        }
    }
}
