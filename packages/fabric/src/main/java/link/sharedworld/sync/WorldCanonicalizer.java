package link.sharedworld.sync;

import link.sharedworld.versioned.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

public final class WorldCanonicalizer {
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final int MAX_HASHING_THREADS = 8;

    /**
     * Minecraft 26.x owner marker in level.dat (Data.singleplayer_uuid). Modern versions store
     * per-player data in players/data/&lt;uuid&gt;.dat and this key tells the integrated server which
     * player file its owner should load; the canonical snapshot must be owner-neutral or the next
     * host inherits the previous host's inventory.
     */
    public static final String MODERN_OWNER_UUID_KEY = "singleplayer_uuid";

    private WorldCanonicalizer() {
    }

    public static List<PreparedWorldFile> scanCanonical(Path worldDirectory, String hostPlayerUuid) throws IOException, InterruptedException {
        return scanCanonical(worldDirectory, hostPlayerUuid, null);
    }

    public static List<PreparedWorldFile> scanCanonical(Path worldDirectory, String hostPlayerUuid, WorldScanCache cache) throws IOException, InterruptedException {
        List<PreparedWorldFile> files = new ArrayList<>();
        String hostPlayerRelativePath = "playerdata/" + hostPlayerUuid + ".dat";
        byte[] extractedHostPlayer = null;
        boolean hostPlayerSeen = false;
        Set<String> seenPaths = new HashSet<>();
        List<PendingHash> pendingHashes = new ArrayList<>();

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

                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                long size = attributes.size();
                long mtimeMillis = attributes.lastModifiedTime().toMillis();
                String cachedHash = cache == null ? null : cache.cachedFileHash(relativePath, size, mtimeMillis);
                if (cachedHash != null) {
                    files.add(preparePassthrough(path, relativePath, cachedHash, size));
                    continue;
                }
                pendingHashes.add(new PendingHash(files.size(), path, relativePath, size, mtimeMillis));
                files.add(null);
            }
        }

        hashPendingFiles(files, pendingHashes, cache);

        if (extractedHostPlayer != null && !hostPlayerSeen && !seenPaths.contains(hostPlayerRelativePath)) {
            files.add(prepareOverride(null, hostPlayerRelativePath, extractedHostPlayer));
        }

        return files;
    }

    /**
     * Hashes every cache miss, on a bounded pool when there are several: a cold
     * scan (first create, fresh download) is CPU-bound on SHA-256 and
     * parallelizes cleanly. Results are set into the pre-reserved slots from
     * this thread so the list itself is never touched concurrently.
     */
    private static void hashPendingFiles(List<PreparedWorldFile> files, List<PendingHash> pendingHashes, WorldScanCache cache) throws IOException, InterruptedException {
        if (pendingHashes.isEmpty()) {
            return;
        }
        if (pendingHashes.size() == 1) {
            PendingHash pending = pendingHashes.get(0);
            files.set(pending.slot(), hashPendingFile(pending, cache));
            return;
        }

        int threads = Math.min(pendingHashes.size(), Math.min(Runtime.getRuntime().availableProcessors(), MAX_HASHING_THREADS));
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<PreparedWorldFile>> futures = new ArrayList<>(pendingHashes.size());
            for (PendingHash pending : pendingHashes) {
                futures.add(executor.submit(() -> hashPendingFile(pending, cache)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    files.set(pendingHashes.get(i).slot(), futures.get(i).get());
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw new IOException("SharedWorld failed to hash a world file.", cause);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static PreparedWorldFile hashPendingFile(PendingHash pending, WorldScanCache cache) throws IOException {
        String hash = LocalWorldHasher.hashFile(pending.path());
        if (cache != null) {
            cache.recordFileHash(pending.relativePath(), pending.size(), pending.mtimeMillis(), hash);
        }
        return preparePassthrough(pending.path(), pending.relativePath(), hash, pending.size());
    }

    public static void materializeHostPlayer(Path worldDirectory, String hostPlayerUuid) throws IOException {
        Path levelDat = worldDirectory.resolve("level.dat");
        if (!Files.exists(levelDat)) {
            return;
        }
        Path playerDataPath = worldDirectory.resolve("playerdata").resolve(hostPlayerUuid + ".dat");
        boolean materializePlayer = Files.exists(playerDataPath);

        CompoundTag levelTag = NbtCompat.readCompressed(levelDat);
        CompoundTag dataTag = NbtCompat.getCompoundOrEmpty(levelTag, "Data").copy();
        boolean changed = dataTag.contains(MODERN_OWNER_UUID_KEY);
        dataTag.remove(MODERN_OWNER_UUID_KEY);
        if (materializePlayer) {
            CompoundTag playerTag = NbtCompat.readCompressed(playerDataPath);
            dataTag.put("Player", playerTag.copy());
            changed = true;
        }
        if (!changed) {
            return;
        }

        levelTag.put("Data", dataTag);
        NbtCompat.writeCompressed(levelTag, levelDat);
        if (materializePlayer) {
            Files.deleteIfExists(playerDataPath);
        }
    }

    private static CanonicalLevelResult canonicalizeLevelDat(Path levelDat) throws IOException {
        CompoundTag levelTag = NbtCompat.readCompressed(levelDat);
        CompoundTag canonicalLevel = levelTag.copy();
        CompoundTag dataTag = NbtCompat.getCompoundOrEmpty(canonicalLevel, "Data").copy();
        byte[] hostPlayerBytes = null;

        if (dataTag.contains("Player")) {
            CompoundTag playerTag = NbtCompat.getCompoundOrEmpty(dataTag, "Player").copy();
            dataTag.remove("Player");
            hostPlayerBytes = writeCompressed(playerTag);
        }
        dataTag.remove(MODERN_OWNER_UUID_KEY);

        canonicalLevel.put("Data", dataTag);
        return new CanonicalLevelResult(writeCompressed(canonicalLevel), hostPlayerBytes);
    }

    // compressedSize is reported as the raw size everywhere: the field is
    // retained for wire shape only, unread since per-file whole-gzip transfers
    // were retired (the backend always answers uploads: [] / downloads: []).
    private static PreparedWorldFile preparePassthrough(Path sourcePath, String relativePath, String hash, long size) {
        return new PreparedWorldFile(
                sourcePath,
                relativePath,
                hash,
                size,
                size,
                CONTENT_TYPE,
                SyncPathRules.isTerrainRegionFile(relativePath),
                null
        );
    }

    private static PreparedWorldFile prepareOverride(Path sourcePath, String relativePath, byte[] bytes) {
        return new PreparedWorldFile(
                sourcePath,
                relativePath,
                hashBytes(bytes),
                bytes.length,
                bytes.length,
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
        return !isLocalOnlyFileName(path.getFileName().toString());
    }

    /**
     * Files that never leave the machine: excluded from sync uploads and from
     * exports to the vanilla saves folder alike.
     */
    public static boolean isLocalOnlyFileName(String fileName) {
        return "session.lock".equals(fileName) || fileName.endsWith(".dat_old");
    }

    private record CanonicalLevelResult(byte[] levelBytes, byte[] hostPlayerBytes) {
    }

    private record PendingHash(int slot, Path path, String relativePath, long size, long mtimeMillis) {
    }
}
