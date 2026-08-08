package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldModels.PackedManifestFileDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class WorldScanCacheTest {
    @TempDir
    Path tempDir;

    // Old enough that the racy-mtime rule never questions the entry.
    private static final long OLD_MTIME = System.currentTimeMillis() - 60_000L;

    @Test
    void hitRequiresSameSizeAndSameMtime() {
        WorldScanCache cache = WorldScanCache.load(this.tempDir.resolve("cache.json"));
        cache.recordFileHash("data/foo.dat", 100L, OLD_MTIME, "hash-1");

        assertEquals("hash-1", cache.cachedFileHash("data/foo.dat", 100L, OLD_MTIME));
        assertNull(cache.cachedFileHash("data/foo.dat", 101L, OLD_MTIME));
        assertNull(cache.cachedFileHash("data/foo.dat", 100L, OLD_MTIME + 1L));
        assertNull(cache.cachedFileHash("data/other.dat", 100L, OLD_MTIME));
    }

    @Test
    void entryRecordedInTheSameInstantAsTheWriteIsNeverTrusted() {
        WorldScanCache cache = WorldScanCache.load(this.tempDir.resolve("cache.json"));
        long freshMtime = System.currentTimeMillis();
        cache.recordFileHash("region/r.0.0.mca", 100L, freshMtime, "hash-racy");

        // The file could have been rewritten again without the mtime ticking;
        // git's racy-stat rule says re-hash it.
        assertNull(cache.cachedFileHash("region/r.0.0.mca", 100L, freshMtime));
    }

    @Test
    void verifiedEntriesSkipTheRacyRule() {
        WorldScanCache cache = WorldScanCache.load(this.tempDir.resolve("cache.json"));
        long freshMtime = System.currentTimeMillis();
        // A hash-checked download this client just moved into place: nothing
        // else can have written the file in the same tick.
        cache.recordVerifiedFileHash("region/r.0.0.mca", 100L, freshMtime, "hash-verified");

        assertEquals("hash-verified", cache.cachedFileHash("region/r.0.0.mca", 100L, freshMtime));
        assertNull(cache.cachedFileHash("region/r.0.0.mca", 101L, freshMtime));
    }

    @Test
    void saveAndLoadRoundTripsEntries() {
        Path cacheFile = this.tempDir.resolve("cache.json");
        WorldScanCache cache = WorldScanCache.load(cacheFile);
        cache.recordFileHash("data/foo.dat", 100L, OLD_MTIME, "hash-1");
        cache.recordPackHash("non-region", "fingerprint-1", "pack-hash-1");
        cache.save();

        WorldScanCache reloaded = WorldScanCache.load(cacheFile);
        assertEquals("hash-1", reloaded.cachedFileHash("data/foo.dat", 100L, OLD_MTIME));
        assertEquals("pack-hash-1", reloaded.cachedPackHash("non-region", "fingerprint-1"));
        assertNull(reloaded.cachedPackHash("non-region", "fingerprint-2"));
    }

    @Test
    void corruptCacheFileStartsCold() throws Exception {
        Path cacheFile = this.tempDir.resolve("cache.json");
        Files.writeString(cacheFile, "{\"formatVersion\": 1, \"files\": {truncated");

        WorldScanCache cache = WorldScanCache.load(cacheFile);
        assertNull(cache.cachedFileHash("data/foo.dat", 100L, OLD_MTIME));
        // And it can still record + save over the corrupt file.
        cache.recordFileHash("data/foo.dat", 100L, OLD_MTIME, "hash-1");
        cache.save();
        assertEquals("hash-1", WorldScanCache.load(cacheFile).cachedFileHash("data/foo.dat", 100L, OLD_MTIME));
    }

    @Test
    void retainOnlyDropsEntriesForVanishedFilesAndPacks() {
        WorldScanCache cache = WorldScanCache.load(this.tempDir.resolve("cache.json"));
        cache.recordFileHash("data/keep.dat", 1L, OLD_MTIME, "hash-keep");
        cache.recordFileHash("data/gone.dat", 1L, OLD_MTIME, "hash-gone");
        cache.recordPackHash("non-region", "fp", "hash-pack");
        cache.recordPackHash("region-bundle:gone/region:0:0", "fp", "hash-bundle");

        cache.retainOnly(Set.of("data/keep.dat"), Set.of("non-region"));

        assertEquals("hash-keep", cache.cachedFileHash("data/keep.dat", 1L, OLD_MTIME));
        assertNull(cache.cachedFileHash("data/gone.dat", 1L, OLD_MTIME));
        assertEquals("hash-pack", cache.cachedPackHash("non-region", "fp"));
        assertNull(cache.cachedPackHash("region-bundle:gone/region:0:0", "fp"));
    }

    @Test
    void packFingerprintIsOrderInsensitiveAndContentSensitive() {
        PreparedWorldFile fileA = passthrough("data/a.dat", "hash-a", 10L);
        PreparedWorldFile fileB = passthrough("data/b.dat", "hash-b", 20L);

        String fingerprint = WorldScanCache.packFingerprint("non-region", List.of(fileA, fileB));
        assertEquals(fingerprint, WorldScanCache.packFingerprint("non-region", List.of(fileB, fileA)));
        assertNotEquals(fingerprint, WorldScanCache.packFingerprint("other-pack", List.of(fileA, fileB)));
        assertNotEquals(fingerprint, WorldScanCache.packFingerprint("non-region", List.of(passthrough("data/a.dat", "hash-changed", 10L), fileB)));
    }

    @Test
    void manifestFingerprintMatchesLocalFingerprintForTheSameMembers() {
        // The download path seeds the pack cache from snapshot manifests; the
        // next local scan must reproduce the same fingerprint or the seed is
        // useless.
        PreparedWorldFile fileA = passthrough("data/a.dat", "hash-a", 10L);
        PreparedWorldFile fileB = passthrough("playerdata/p.dat", "hash-p", 20L);
        PackedManifestFileDto[] manifest = new PackedManifestFileDto[]{
                new PackedManifestFileDto("playerdata/p.dat", "hash-p", 20L, "application/octet-stream"),
                new PackedManifestFileDto("data/a.dat", "hash-a", 10L, "application/octet-stream")
        };

        assertEquals(
                WorldScanCache.packFingerprint("non-region", List.of(fileA, fileB)),
                WorldScanCache.packFingerprintFromManifest("non-region", manifest)
        );
    }

    private static PreparedWorldFile passthrough(String relativePath, String hash, long size) {
        return new PreparedWorldFile(null, relativePath, hash, size, size, "application/octet-stream", false, null);
    }
}
