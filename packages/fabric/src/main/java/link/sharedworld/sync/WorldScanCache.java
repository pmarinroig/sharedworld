package link.sharedworld.sync;

import com.google.gson.Gson;
import link.sharedworld.api.SharedWorldModels.PackedManifestFileDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent per-world cache of expensive scan results: file content hashes
 * keyed by (size, mtime) and pack hashes keyed by a fingerprint of the pack's
 * member entries.
 *
 * <p>The cache is strictly advisory. Every consumer whose correctness depends
 * on real bytes (delta base checks, post-download verification) hashes the
 * actual file regardless, so a stale entry can only cost a needless transfer,
 * never a corrupt sync. That is the same trust model rsync and git place in
 * their stat caches.
 */
public final class WorldScanCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-sync");
    private static final Gson GSON = new Gson();
    private static final int FORMAT_VERSION = 1;
    private static final String DISABLE_PROPERTY = "sharedworld.dev.disableScanCache";
    /**
     * A file hashed in the same instant it was last written could be rewritten
     * again without the mtime ticking; entries whose mtime is within this
     * window of when they were recorded are never trusted (git's racy-stat
     * rule).
     */
    private static final long RACY_MTIME_WINDOW_MS = 2_000L;

    private final Path cacheFile;
    private final ConcurrentHashMap<String, FileEntry> files;
    private final ConcurrentHashMap<String, PackEntry> packs;

    private WorldScanCache(Path cacheFile, Map<String, FileEntry> files, Map<String, PackEntry> packs) {
        this.cacheFile = cacheFile;
        this.files = new ConcurrentHashMap<>(files);
        this.packs = new ConcurrentHashMap<>(packs);
    }

    public static WorldScanCache load(Path cacheFile) {
        if (isDisabled() || !Files.exists(cacheFile)) {
            return new WorldScanCache(cacheFile, Map.of(), Map.of());
        }
        try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
            PersistedCache persisted = GSON.fromJson(reader, PersistedCache.class);
            if (persisted == null || persisted.formatVersion() != FORMAT_VERSION
                    || persisted.files() == null || persisted.packs() == null) {
                return new WorldScanCache(cacheFile, Map.of(), Map.of());
            }
            return new WorldScanCache(cacheFile, persisted.files(), persisted.packs());
        } catch (IOException | RuntimeException exception) {
            // A crash mid-write or hand-edited file must cost a cold scan, not
            // a failed sync.
            LOGGER.warn("SharedWorld scan cache at {} was unreadable; starting cold", cacheFile, exception);
            return new WorldScanCache(cacheFile, Map.of(), Map.of());
        }
    }

    /** Best effort: a cache that cannot be written only costs the next scan. */
    public void save() {
        if (isDisabled()) {
            return;
        }
        try {
            if (this.cacheFile.getParent() != null) {
                Files.createDirectories(this.cacheFile.getParent());
            }
            Path tempFile = this.cacheFile.resolveSibling(this.cacheFile.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                GSON.toJson(new PersistedCache(FORMAT_VERSION, Map.copyOf(this.files), Map.copyOf(this.packs)), writer);
            }
            try {
                Files.move(tempFile, this.cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(tempFile, this.cacheFile, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("SharedWorld failed to persist the scan cache at {}", this.cacheFile, exception);
        }
    }

    /** Returns the cached content hash, or null when the entry is absent, stale, or racy. */
    public String cachedFileHash(String relativePath, long size, long mtimeMillis) {
        if (isDisabled()) {
            return null;
        }
        FileEntry entry = this.files.get(relativePath);
        if (entry == null || entry.size() != size || entry.mtimeMillis() != mtimeMillis) {
            return null;
        }
        if (!entry.verified() && entry.mtimeMillis() >= entry.recordedAtMillis() - RACY_MTIME_WINDOW_MS) {
            return null;
        }
        return entry.hash();
    }

    public void recordFileHash(String relativePath, long size, long mtimeMillis, String hash) {
        this.files.put(relativePath, new FileEntry(size, mtimeMillis, System.currentTimeMillis(), hash, false));
    }

    /**
     * Records a hash that was verified against the very bytes at this path
     * (a hash-checked download this client just moved into place). Such
     * entries skip the racy-mtime rule: nothing else can have written the
     * file in the same timestamp tick, so the next scan may trust them
     * immediately instead of re-hashing the whole freshly downloaded world.
     */
    public void recordVerifiedFileHash(String relativePath, long size, long mtimeMillis, String hash) {
        this.files.put(relativePath, new FileEntry(size, mtimeMillis, System.currentTimeMillis(), hash, true));
    }

    public String cachedPackHash(String packId, String fingerprint) {
        if (isDisabled()) {
            return null;
        }
        PackEntry entry = this.packs.get(packId);
        if (entry == null || !entry.fingerprint().equals(fingerprint)) {
            return null;
        }
        return entry.hash();
    }

    public void recordPackHash(String packId, String fingerprint, String hash) {
        this.packs.put(packId, new PackEntry(fingerprint, hash));
    }

    /** Drops entries for files and packs that no longer exist in the world. */
    public void retainOnly(Set<String> relativePaths, Set<String> packIds) {
        this.files.keySet().retainAll(relativePaths);
        this.packs.keySet().retainAll(packIds);
    }

    /**
     * Fingerprint of everything that determines a pack's bytes besides the
     * member contents themselves (the member hashes stand in for those). The
     * pack format version is included so a future format bump invalidates
     * every cached pack hash by construction; entries are sorted the same way
     * the pack builder sorts them, so callers need not pre-sort.
     */
    public static String packFingerprint(String packId, List<PreparedWorldFile> files) {
        return fingerprint(packId, files.stream()
                .map(file -> new FingerprintEntry(file.relativePath(), file.hash(), file.size(), file.contentType()))
                .toList());
    }

    /**
     * Same digest as {@link #packFingerprint}, computed from a downloaded
     * pack's manifest: lets a client that just applied a snapshot seed the
     * pack cache so its next scan describes the pack without rebuilding it.
     */
    public static String packFingerprintFromManifest(String packId, PackedManifestFileDto[] files) {
        return fingerprint(packId, java.util.Arrays.stream(files)
                .map(file -> new FingerprintEntry(file.path(), file.hash(), file.size(), file.contentType()))
                .toList());
    }

    private static String fingerprint(String packId, List<FingerprintEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("sharedworld-pack-v" + SharedWorldPack.FORMAT_VERSION + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update((packId + "\n").getBytes(StandardCharsets.UTF_8));
            for (FingerprintEntry entry : entries.stream().sorted(java.util.Comparator.comparing(FingerprintEntry::path)).toList()) {
                String line = entry.path() + "|" + entry.hash() + "|" + entry.size() + "|" + entry.contentType() + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new RuntimeException("Missing SHA-256 implementation.", exception);
        }
    }

    private record FingerprintEntry(String path, String hash, long size, String contentType) {
    }

    private static boolean isDisabled() {
        return Boolean.getBoolean(DISABLE_PROPERTY);
    }

    private record PersistedCache(int formatVersion, Map<String, FileEntry> files, Map<String, PackEntry> packs) {
    }

    private record FileEntry(long size, long mtimeMillis, long recordedAtMillis, String hash, boolean verified) {
    }

    private record PackEntry(String fingerprint, String hash) {
    }
}
