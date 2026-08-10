package link.sharedworld.devhelper.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * World-file operations for the big-world e2e scenario: deterministic hashing
 * of the synthetic modded data (so host and guest runs can be compared by
 * marker equality alone) and the mid-session mutation of the giant file that
 * forces the next snapshot into a delta-v2 upload.
 */
final class BigWorldOps {
    private static final int IO_BUFFER_BYTES = 1 << 20;

    private BigWorldOps() {
    }

    /**
     * One line describing the synthetic modded state: a combined digest over
     * every file under dimensions/ (relative path + bytes, sorted), plus the
     * giant file's own digest for precise diagnosis. Host-side and guest-side
     * details must be string-equal for the same world content.
     */
    static String hashArtifacts(Path worldRoot, String bigFileRelative) throws IOException {
        Path dimensions = worldRoot.resolve("dimensions");
        MessageDigest combined = sha256();
        int fileCount = 0;
        if (Files.isDirectory(dimensions)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dimensions)) {
                files = walk.filter(Files::isRegularFile).sorted(Comparator.comparing(path -> worldRoot.relativize(path).toString().replace('\\', '/'))).toList();
            }
            for (Path file : files) {
                combined.update(worldRoot.relativize(file).toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digestFile(file, combined);
                fileCount += 1;
            }
        }
        Path bigFile = worldRoot.resolve(bigFileRelative);
        MessageDigest big = sha256();
        long bigSize = 0L;
        if (Files.isRegularFile(bigFile)) {
            bigSize = Files.size(bigFile);
            digestFile(bigFile, big);
        }
        return "dims=" + HexFormat.of().formatHex(combined.digest())
                + " big=" + HexFormat.of().formatHex(big.digest())
                + " bigSize=" + bigSize
                + " files=" + fileCount;
    }

    /**
     * Deterministic mutation of the giant file, shaped to exercise every delta
     * interesting case at once: an in-place overwrite (same-offset copy runs
     * around it), an unaligned mid-file insertion (everything after it shifts,
     * which only rolling-hash matching can absorb), and an appended tail of
     * fresh data (pure literals). Returns the new file size.
     */
    static long mutateBigFile(Path worldRoot, String bigFileRelative) throws IOException {
        Path bigFile = worldRoot.resolve(bigFileRelative);
        long size = Files.size(bigFile);
        long overwriteAt = (size / 4L) & ~4095L;
        int overwriteLength = 4 << 20;
        long insertAt = size * 3L / 5L + 137L;
        int insertLength = 64 << 10;
        int appendLength = 32 << 20;

        // The temp lives OUTSIDE the world dir (next to it in saves/): a
        // concurrent autosave sync scan must never see a giant stray file.
        Path rewritten = worldRoot.resolveSibling(worldRoot.getFileName() + ".mutate-tmp");
        try (InputStream input = Files.newInputStream(bigFile);
             OutputStream output = new java.io.BufferedOutputStream(Files.newOutputStream(rewritten), IO_BUFFER_BYTES)) {
            long position = 0L;
            boolean overwritten = false;
            boolean inserted = false;
            byte[] buffer = new byte[IO_BUFFER_BYTES];
            for (;;) {
                if (position == overwriteAt && !overwritten) {
                    overwritten = true;
                    output.write(deterministicBytes(0x50484E58L, overwriteLength));
                    skipFully(input, overwriteLength);
                    position += overwriteLength;
                    continue;
                }
                if (position == insertAt && !inserted) {
                    inserted = true;
                    // Insertion: nothing is consumed from the source.
                    output.write(deterministicBytes(0x494E5352L, insertLength));
                }
                long nextStop = position < overwriteAt ? overwriteAt : position < insertAt ? insertAt : size;
                int want = (int) Math.min(buffer.length, nextStop - position);
                if (want == 0 && position >= size) {
                    break;
                }
                int read = input.read(buffer, 0, want);
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                position += read;
            }
            output.write(deterministicBytes(0x41505044L, appendLength));
        }
        Files.move(rewritten, bigFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return Files.size(bigFile);
    }

    private static byte[] deterministicBytes(long seed, int length) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) {
                    return;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void digestFile(Path file, MessageDigest digest) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[IO_BUFFER_BYTES];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
