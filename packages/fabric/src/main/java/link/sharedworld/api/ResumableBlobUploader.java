package link.sharedworld.api;

import link.sharedworld.api.SharedWorldApiClient.UploadProgressListener;
import link.sharedworld.util.RetryPolicy;
import link.sharedworld.util.TransferWatchdog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Chunked resumable upload against a provider session URI (Google Drive
 * shape): PUT chunks with {@code Content-Range: bytes s-e/total}, a 308
 * answer's {@code Range} header is the server's high-water mark, the final
 * chunk answers 200/201. No SharedWorld credential is ever attached; the
 * session URI is the credential.
 *
 * <p>Owns its retry classification: Drive 429/5xx and transport breaks
 * (including stall aborts) back off and then <em>probe</em> the session
 * ({@code Content-Range: bytes *&#47;total}) to resume from the provider's
 * offset instead of restarting. A 404/410 means the session died; the caller
 * requests a fresh session and restarts this artifact only.</p>
 */
public final class ResumableBlobUploader {
    private static final RetryPolicy CHUNK_RETRY_POLICY = new RetryPolicy(5, 750L, 8_000L);

    private final HttpClient httpClient;
    private final URI sessionUri;
    private final long chunkSizeBytes;

    public ResumableBlobUploader(HttpClient httpClient, String sessionUrl, long chunkSizeBytes) {
        this.httpClient = httpClient;
        this.sessionUri = URI.create(sessionUrl);
        this.chunkSizeBytes = Math.max(256L * 1024L, chunkSizeBytes);
    }

    /** Thrown when the provider no longer knows the session (expired/cancelled). */
    public static final class SessionGoneException extends IOException {
        public SessionGoneException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when Google refuses the bytes because the Drive is FULL; a
     * terminal user condition. Retrying cannot help (pre-0.4.2 this burned
     * the whole 5-attempt ladder against it), so it aborts the transfer
     * immediately and carries a code the autosave classifier recognizes.
     */
    public static final class DriveStorageFullException extends IOException {
        public DriveStorageFullException() {
            super("Google Drive is full (storageQuotaExceeded).");
        }
    }

    public void upload(Path bodyFile, String contentType, UploadProgressListener progressListener) throws IOException, InterruptedException {
        long total = Files.size(bodyFile);
        long offset = 0L;
        int failures = 0;
        try (FileChannel channel = FileChannel.open(bodyFile, StandardOpenOption.READ)) {
            while (offset < total) {
                long end = Math.min(offset + this.chunkSizeBytes, total);
                try {
                    Long serverOffset = putChunk(channel, offset, end, total, contentType, progressListener);
                    offset = serverOffset != null ? serverOffset : end;
                    failures = 0;
                } catch (SessionGoneException gone) {
                    throw gone;
                } catch (DriveStorageFullException full) {
                    throw full;
                } catch (IOException failure) {
                    failures += 1;
                    if (!CHUNK_RETRY_POLICY.shouldRetry(failures)) {
                        throw failure;
                    }
                    Thread.sleep(CHUNK_RETRY_POLICY.delayBeforeAttemptMs(failures + 1));
                    offset = probeReceivedBytes(total);
                }
            }
        }
        if (progressListener != null) {
            progressListener.onBytesTransferred(total, total);
        }
    }

    /**
     * Returns the server's next-byte offset when it answered 308 with a Range
     * high-water mark, null when the chunk landed exactly as sent (or the
     * upload completed).
     */
    private Long putChunk(FileChannel channel, long offset, long end, long total, String contentType, UploadProgressListener progressListener)
            throws IOException, InterruptedException {
        long chunkLength = end - offset;
        // The JDK client may invoke the body supplier more than once (its own
        // low-level resend), so every invocation gets a fresh positional
        // stream; the watchdog aborts whichever stream is currently live.
        java.util.concurrent.atomic.AtomicReference<InputStream> active = new java.util.concurrent.atomic.AtomicReference<>();
        TransferWatchdog watchdog = TransferWatchdog.watching(() -> {
            InputStream current = active.get();
            if (current != null) {
                current.close();
            }
        });
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(this.sessionUri)
                    .timeout(Duration.ofHours(6))
                    .header("content-range", "bytes " + offset + "-" + (end - 1) + "/" + total)
                    .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> {
                        BoundedChannelInputStream chunkStream = new BoundedChannelInputStream(channel, offset, chunkLength);
                        active.set(chunkStream);
                        return progressWrapped(chunkStream, offset, total, watchdog, progressListener);
                    }));
            if (contentType != null && !contentType.isBlank()) {
                builder.header("content-type", contentType);
            }
            CompletableFuture<HttpResponse<String>> pending =
                    this.httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = await(pending);
            int status = response.statusCode();
            if (status == 308) {
                return nextOffsetFromRange(response.headers().firstValue("range").orElse(null));
            }
            if (status == 200 || status == 201) {
                return null;
            }
            if (status == 404 || status == 410) {
                throw new SessionGoneException("Upload session is gone (HTTP " + status + ").");
            }
            if (status == 403 && response.body() != null && response.body().toLowerCase(java.util.Locale.ROOT).contains("storagequotaexceeded")) {
                throw new DriveStorageFullException();
            }
            // 429/5xx and everything else: an IOException here re-enters the
            // chunk retry loop, which probes and resumes.
            throw new IOException("Resumable chunk PUT failed (HTTP " + status + ").");
        } finally {
            watchdog.close();
        }
    }

    private long probeReceivedBytes(long total) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(this.sessionUri)
                .timeout(Duration.ofSeconds(30))
                .header("content-range", "bytes */" + total)
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 308) {
            Long next = nextOffsetFromRange(response.headers().firstValue("range").orElse(null));
            return next == null ? 0L : next;
        }
        if (status == 200 || status == 201) {
            return total;
        }
        if (status == 404 || status == 410) {
            throw new SessionGoneException("Upload session is gone (HTTP " + status + ").");
        }
        throw new IOException("Resumable status probe failed (HTTP " + status + ").");
    }

    private static Long nextOffsetFromRange(String range) {
        if (range == null) {
            return 0L;
        }
        // "bytes=0-<lastReceived>"
        int dash = range.lastIndexOf('-');
        if (dash < 0) {
            return 0L;
        }
        try {
            return Long.parseLong(range.substring(dash + 1)) + 1L;
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static HttpResponse<String> await(CompletableFuture<HttpResponse<String>> pending) throws IOException, InterruptedException {
        try {
            return pending.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Resumable chunk transfer failed.", cause);
        } catch (CancellationException cancelled) {
            throw new IOException("Resumable chunk transfer was cancelled.", cancelled);
        }
    }

    private static InputStream progressWrapped(InputStream chunkStream, long chunkOffset, long total, TransferWatchdog watchdog, UploadProgressListener listener) {
        return new SharedWorldApiClient.ProgressInputStream(chunkStream, total, (transferred, ignoredTotal) -> {
            watchdog.pulse();
            if (listener != null) {
                listener.onBytesTransferred(chunkOffset + transferred, total);
            }
        });
    }

    /** Reads exactly [offset, offset+length) of the channel, positionally (safe under retries). */
    private static final class BoundedChannelInputStream extends InputStream {
        private final FileChannel channel;
        private long position;
        private long remaining;
        private volatile boolean closed;

        BoundedChannelInputStream(FileChannel channel, long offset, long length) {
            this.channel = channel;
            this.position = offset;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int bufferOffset, int length) throws IOException {
            if (this.closed) {
                throw new IOException("Chunk stream aborted.");
            }
            if (this.remaining <= 0L) {
                return -1;
            }
            int toRead = (int) Math.min(length, this.remaining);
            int read = this.channel.read(java.nio.ByteBuffer.wrap(buffer, bufferOffset, toRead), this.position);
            if (read < 0) {
                throw new IOException("Upload body ended before its declared length.");
            }
            this.position += read;
            this.remaining -= read;
            return read;
        }

        @Override
        public void close() {
            // Marks the stream aborted (stall watchdog) without closing the
            // shared channel; later chunks and retries still need it.
            this.closed = true;
        }
    }
}
