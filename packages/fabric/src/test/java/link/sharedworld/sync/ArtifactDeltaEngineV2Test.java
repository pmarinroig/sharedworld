package link.sharedworld.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactDeltaEngineV2Test {
    @TempDir
    Path tempDir;

    private Path write(String name, byte[] bytes) throws IOException {
        Path path = this.tempDir.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    private byte[] roundtrip(byte[] base, byte[] target) throws IOException {
        Path basePath = base == null ? null : write("base.bin", base);
        Path targetPath = write("target.bin", target);
        Path delta = this.tempDir.resolve("delta.bin");
        Path rebuilt = this.tempDir.resolve("rebuilt.bin");
        ArtifactDeltaEngine.DeltaStats stats = ArtifactDeltaEngine.writeDeltaV2(basePath, targetPath, delta);
        assertEquals(Files.size(delta), stats.artifactSize());
        ArtifactDeltaEngine.applyDelta(basePath, delta, rebuilt);
        return Files.readAllBytes(rebuilt);
    }

    private static byte[] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    @Test
    void identicalInputRoundtripsAsAlmostAllCopies() throws IOException {
        byte[] base = randomBytes(400_000, 1);
        Path basePath = write("b.bin", base);
        Path targetPath = write("t.bin", base.clone());
        Path delta = this.tempDir.resolve("d.bin");
        ArtifactDeltaEngine.DeltaStats stats = ArtifactDeltaEngine.writeDeltaV2(basePath, targetPath, delta);
        assertTrue(stats.artifactSize() < base.length / 4, "identical content must compress: " + stats.artifactSize());
        Path rebuilt = this.tempDir.resolve("r.bin");
        ArtifactDeltaEngine.applyDelta(basePath, delta, rebuilt);
        assertArrayEquals(base, Files.readAllBytes(rebuilt));
    }

    @Test
    void aShiftedTargetStillDeduplicates() throws IOException {
        // v1's same-offset comparison degrades to all-literals here; v2's
        // rolling match must keep the delta far smaller than the target.
        byte[] base = randomBytes(600_000, 2);
        byte[] inserted = randomBytes(1_000, 3);
        byte[] target = new byte[base.length + inserted.length];
        System.arraycopy(inserted, 0, target, 0, inserted.length);
        System.arraycopy(base, 0, target, inserted.length, base.length);

        Path basePath = write("b.bin", base);
        Path targetPath = write("t.bin", target);
        Path delta = this.tempDir.resolve("d.bin");
        ArtifactDeltaEngine.DeltaStats stats = ArtifactDeltaEngine.writeDeltaV2(basePath, targetPath, delta);
        assertTrue(stats.artifactSize() < target.length / 3,
                "shifted content must still deduplicate, delta was " + stats.artifactSize());
        Path rebuilt = this.tempDir.resolve("r.bin");
        ArtifactDeltaEngine.applyDelta(basePath, delta, rebuilt);
        assertArrayEquals(target, Files.readAllBytes(rebuilt));
    }

    @Test
    void editsAppendsAndUnrelatedContentRoundtrip() throws IOException {
        byte[] base = randomBytes(300_000, 4);
        byte[] edited = base.clone();
        Arrays.fill(edited, 1000, 2000, (byte) 7);
        byte[] appended = Arrays.copyOf(base, base.length + 50_000);
        System.arraycopy(randomBytes(50_000, 5), 0, appended, base.length, 50_000);
        byte[] unrelated = randomBytes(150_000, 6);

        assertArrayEquals(edited, roundtrip(base, edited));
        assertArrayEquals(appended, roundtrip(base, appended));
        assertArrayEquals(unrelated, roundtrip(base, unrelated));
        assertArrayEquals(new byte[0], roundtrip(base, new byte[0]));
        assertArrayEquals(unrelated, roundtrip(null, unrelated));
    }

    @Test
    void truncatedArtifactIsRejected() throws IOException {
        byte[] base = randomBytes(200_000, 7);
        Path basePath = write("b.bin", base);
        Path targetPath = write("t.bin", base.clone());
        Path delta = this.tempDir.resolve("d.bin");
        ArtifactDeltaEngine.writeDeltaV2(basePath, targetPath, delta);
        byte[] deltaBytes = Files.readAllBytes(delta);
        Path truncated = write("cut.bin", Arrays.copyOf(deltaBytes, deltaBytes.length - 1));
        assertThrows(IOException.class,
                () -> ArtifactDeltaEngine.applyDelta(basePath, truncated, this.tempDir.resolve("out.bin")));
    }

    @Test
    void v1ArtifactsStillApplyThroughTheDispatcher() throws IOException {
        byte[] base = randomBytes(50_000, 8);
        byte[] target = base.clone();
        target[100] = (byte) (target[100] + 1);
        Path basePath = write("b.bin", base);
        Path targetPath = write("t.bin", target);
        Path delta = this.tempDir.resolve("v1.bin");
        ArtifactDeltaEngine.writeDelta(basePath, targetPath, delta, 4096);
        Path rebuilt = this.tempDir.resolve("r.bin");
        ArtifactDeltaEngine.applyDelta(basePath, delta, rebuilt);
        assertArrayEquals(target, Files.readAllBytes(rebuilt));
    }

    @Test
    void blockSizeScalesWithBaseLengthAndStaysClamped() {
        assertEquals(64 * 1024, ArtifactDeltaEngine.v2BlockSizeFor(1));
        assertEquals(64 * 1024, ArtifactDeltaEngine.v2BlockSizeFor(16_384L * 64 * 1024));
        assertEquals(128 * 1024, ArtifactDeltaEngine.v2BlockSizeFor(16_384L * 64 * 1024 + 1));
        assertEquals(8 * 1024 * 1024, ArtifactDeltaEngine.v2BlockSizeFor(Long.MAX_VALUE / 4));
    }

    /**
     * Sparse-file roundtrip past the 2GiB int boundary (v1 throws there).
     * Gated: run with -Dsharedworld.test.hugeDelta=true; APFS keeps the
     * sparse fixtures nearly free on disk.
     */
    @Test
    @EnabledIfSystemProperty(named = "sharedworld.test.hugeDelta", matches = "true")
    void pastTwoGiBRoundtripsViaSparseFiles() throws IOException {
        Path basePath = this.tempDir.resolve("huge-base.bin");
        Path targetPath = this.tempDir.resolve("huge-target.bin");
        long length = 2_200_000_000L;
        byte[] stripe = randomBytes(1 << 20, 9);
        try (RandomAccessFile base = new RandomAccessFile(basePath.toFile(), "rw");
             RandomAccessFile target = new RandomAccessFile(targetPath.toFile(), "rw")) {
            base.setLength(length);
            target.setLength(length);
            for (long offset : new long[]{0L, 1_000_000_000L, 2_100_000_000L}) {
                base.seek(offset);
                base.write(stripe);
                target.seek(offset);
                target.write(stripe);
            }
            // One divergent stripe so the delta is not a pure copy.
            target.seek(1_500_000_000L);
            target.write(randomBytes(1 << 20, 10));
        }
        Path delta = this.tempDir.resolve("huge-delta.bin");
        ArtifactDeltaEngine.DeltaStats stats = ArtifactDeltaEngine.writeDeltaV2(basePath, targetPath, delta);
        assertTrue(stats.artifactSize() < 100_000_000L, "delta must stay small: " + stats.artifactSize());
        Path rebuilt = this.tempDir.resolve("huge-rebuilt.bin");
        ArtifactDeltaEngine.applyDelta(basePath, delta, rebuilt);
        assertEquals(length, Files.size(rebuilt));
    }
}
