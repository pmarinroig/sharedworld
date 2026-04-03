package link.sharedworld.sync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import link.sharedworld.api.SharedWorldModels.LocalFileDescriptorDto;

public final class LocalWorldHasher {
    private static final String CONTENT_TYPE = "application/octet-stream";

    private LocalWorldHasher() {
    }

    public static List<LocalWorldFile> scan(Path worldDirectory) throws IOException {
        List<LocalWorldFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(worldDirectory)) {
            stream.filter(Files::isRegularFile)
                    .filter(LocalWorldHasher::shouldSyncPath)
                    .sorted(Comparator.naturalOrder())
                    .forEach(path -> files.add(hashFile(worldDirectory, path)));
        }
        return files;
    }

    private static LocalWorldFile hashFile(Path worldDirectory, Path file) {
        try {
            String hash = hashSha256(file);
            return new LocalWorldFile(
                    file,
                    worldDirectory.relativize(file).toString().replace('\\', '/'),
                    hash,
                    Files.size(file),
                    gzipSize(file),
                    CONTENT_TYPE
            );
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new RuntimeException("Failed to hash world file " + file, exception);
        }
    }

    public static String hashFile(Path file) throws IOException {
        try {
            return hashSha256(file);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("Missing SHA-256 implementation.", exception);
        }
    }

    private static String hashSha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long gzipSize(Path file) throws IOException {
        CountingOutputStream output = new CountingOutputStream();
        try (InputStream input = Files.newInputStream(file);
             GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    gzip.write(buffer, 0, read);
                }
            }
            gzip.finish();
            return output.count;
        }
    }

    public static LocalFileDescriptorDto toDescriptor(LocalWorldFile file) {
        return new LocalFileDescriptorDto(
                file.relativePath(),
                file.hash(),
                file.size(),
                file.compressedSize(),
                file.contentType(),
                SyncPathRules.isTerrainRegionFile(file.relativePath())
        );
    }

    private static boolean shouldSyncPath(Path path) {
        String fileName = path.getFileName().toString();
        return !"session.lock".equals(fileName) && !fileName.endsWith(".dat_old");
    }

    private static final class CountingOutputStream extends OutputStream {
        private long count;

        @Override
        public void write(int value) {
            this.count++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            this.count += length;
        }
    }
}
