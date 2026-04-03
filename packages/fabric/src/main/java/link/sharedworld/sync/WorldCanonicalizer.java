package link.sharedworld.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

public final class WorldCanonicalizer {
    private static final String CONTENT_TYPE = "application/octet-stream";

    private WorldCanonicalizer() {
    }

    public static List<PreparedWorldFile> scanCanonical(Path worldDirectory, String hostPlayerUuid) throws IOException {
        List<PreparedWorldFile> files = new ArrayList<>();
        String hostPlayerRelativePath = "playerdata/" + hostPlayerUuid + ".dat";
        byte[] extractedHostPlayer = null;
        boolean hostPlayerSeen = false;
        Set<String> seenPaths = new HashSet<>();

        try (Stream<Path> stream = Files.walk(worldDirectory)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(WorldCanonicalizer::shouldSyncPath)
                    .sorted(Comparator.naturalOrder())
                    .toList()) {
                String relativePath = worldDirectory.relativize(path).toString().replace('\\', '/');
                seenPaths.add(relativePath);

                if ("level.dat".equals(relativePath)) {
                    CanonicalLevelResult result = canonicalizeLevelDat(path);
                    extractedHostPlayer = result.hostPlayerBytes();
                    files.add(prepareOverride(path, relativePath, result.levelBytes()));
                    continue;
                }

                if (relativePath.equals(hostPlayerRelativePath) && extractedHostPlayer != null) {
                    hostPlayerSeen = true;
                    files.add(prepareOverride(path, relativePath, extractedHostPlayer));
                    continue;
                }

                files.add(preparePassthrough(path, relativePath));
            }
        }

        if (extractedHostPlayer != null && !hostPlayerSeen && !seenPaths.contains(hostPlayerRelativePath)) {
            files.add(prepareOverride(null, hostPlayerRelativePath, extractedHostPlayer));
        }

        return files;
    }

    public static void materializeHostPlayer(Path worldDirectory, String hostPlayerUuid) throws IOException {
        Path levelDat = worldDirectory.resolve("level.dat");
        Path playerDataPath = worldDirectory.resolve("playerdata").resolve(hostPlayerUuid + ".dat");
        if (!Files.exists(levelDat) || !Files.exists(playerDataPath)) {
            return;
        }

        CompoundTag levelTag = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
        CompoundTag dataTag = levelTag.getCompoundOrEmpty("Data").copy();
        CompoundTag playerTag = NbtIo.readCompressed(playerDataPath, NbtAccounter.unlimitedHeap());
        dataTag.put("Player", playerTag.copy());
        levelTag.put("Data", dataTag);
        NbtIo.writeCompressed(levelTag, levelDat);
        Files.deleteIfExists(playerDataPath);
    }

    public static void writeGzipFile(PreparedWorldFile file, Path target) throws IOException {
        try (OutputStream rawOutput = Files.newOutputStream(target);
             GZIPOutputStream gzip = new GZIPOutputStream(rawOutput)) {
            if (file.overrideBytes() != null) {
                gzip.write(file.overrideBytes());
            } else if (file.sourcePath() != null) {
                try (InputStream input = Files.newInputStream(file.sourcePath())) {
                    input.transferTo(gzip);
                }
            } else {
                throw new IOException("SharedWorld file " + file.relativePath() + " has no source bytes.");
            }
            gzip.finish();
        }
    }

    private static CanonicalLevelResult canonicalizeLevelDat(Path levelDat) throws IOException {
        CompoundTag levelTag = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
        CompoundTag canonicalLevel = levelTag.copy();
        CompoundTag dataTag = canonicalLevel.getCompoundOrEmpty("Data").copy();
        byte[] hostPlayerBytes = null;

        if (dataTag.contains("Player")) {
            CompoundTag playerTag = dataTag.getCompoundOrEmpty("Player").copy();
            dataTag.remove("Player");
            hostPlayerBytes = writeCompressed(playerTag);
        }

        canonicalLevel.put("Data", dataTag);
        return new CanonicalLevelResult(writeCompressed(canonicalLevel), hostPlayerBytes);
    }

    private static PreparedWorldFile preparePassthrough(Path sourcePath, String relativePath) throws IOException {
        StreamedFileInfo info = describeFile(sourcePath);
        boolean deltaCapable = SyncPathRules.isTerrainRegionFile(relativePath);
        return new PreparedWorldFile(
                sourcePath,
                relativePath,
                info.hash(),
                info.size(),
                deltaCapable ? info.size() : info.compressedSize(),
                CONTENT_TYPE,
                deltaCapable,
                null
        );
    }

    private static PreparedWorldFile prepareOverride(Path sourcePath, String relativePath, byte[] bytes) {
        byte[] gzippedBytes = gzipBytes(bytes);
        return new PreparedWorldFile(
                sourcePath,
                relativePath,
                hashBytes(bytes),
                bytes.length,
                gzippedBytes.length,
                CONTENT_TYPE,
                false,
                bytes
        );
    }

    private static byte[] writeCompressed(CompoundTag tag) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(tag, output);
            return output.toByteArray();
        }
    }

    private static String hashBytes(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new RuntimeException("Missing SHA-256 implementation.", exception);
        }
    }

    private static boolean shouldSyncPath(Path path) {
        String fileName = path.getFileName().toString();
        return !"session.lock".equals(fileName) && !fileName.endsWith(".dat_old");
    }

    private static byte[] gzipBytes(byte[] rawBytes) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(rawBytes);
            gzip.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new RuntimeException("Failed to gzip SharedWorld blob.", exception);
        }
    }

    private static StreamedFileInfo describeFile(Path sourcePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CountingOutputStream countingOutput = new CountingOutputStream();
            long size = 0L;
            try (InputStream input = Files.newInputStream(sourcePath);
                 GZIPOutputStream gzip = new GZIPOutputStream(countingOutput)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read <= 0) {
                        continue;
                    }
                    digest.update(buffer, 0, read);
                    gzip.write(buffer, 0, read);
                    size += read;
                }
                gzip.finish();
            }
            return new StreamedFileInfo(HexFormat.of().formatHex(digest.digest()), size, countingOutput.count());
        } catch (NoSuchAlgorithmException exception) {
            throw new RuntimeException("Missing SHA-256 implementation.", exception);
        }
    }

    private record CanonicalLevelResult(byte[] levelBytes, byte[] hostPlayerBytes) {
    }

    private record StreamedFileInfo(String hash, long size, long compressedSize) {
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

        public long count() {
            return this.count;
        }
    }
}
