package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldModels.LocalPackDescriptorDto;
import link.sharedworld.api.SharedWorldModels.PackedManifestFileDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SharedWorldPackTest {
    @TempDir
    Path tempDir;

    @Test
    void buildDescribeAndExtractRoundTripPreservesBytesAndMetadata() throws Exception {
        PreparedWorldFile foo = preparedFile("data/foo.txt", "alpha".getBytes());
        PreparedWorldFile bar = preparedFile("nested/dir/bar.bin", new byte[] {1, 2, 3, 4});
        Path packFile = this.tempDir.resolve("round-trip.pack");
        Path extractRoot = this.tempDir.resolve("extract");

        LocalPackDescriptorDto descriptor = SharedWorldPack.buildPack(List.of(bar, foo), packFile);
        PackedManifestFileDto[] described = SharedWorldPack.describe(packFile);
        SharedWorldPack.extract(packFile, extractRoot);

        assertEquals(2, descriptor.fileCount());
        assertEquals(
                List.of("data/foo.txt", "nested/dir/bar.bin"),
                Arrays.stream(described).map(PackedManifestFileDto::path).toList()
        );
        assertArrayEquals("alpha".getBytes(), Files.readAllBytes(extractRoot.resolve("data").resolve("foo.txt")));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(extractRoot.resolve("nested").resolve("dir").resolve("bar.bin")));
    }

    @Test
    void invalidPackHeaderFailsWithIOException() throws Exception {
        Path invalidPack = this.tempDir.resolve("invalid.pack");
        Files.writeString(invalidPack, "not-a-pack");

        IOException error = assertThrows(IOException.class, () -> SharedWorldPack.describe(invalidPack));
        assertEquals("SharedWorld pack header was invalid.", error.getMessage());
    }

    @Test
    void truncatedPackBodyFailsDuringExtract() throws Exception {
        PreparedWorldFile file = preparedFile("data/foo.txt", "payload".getBytes());
        Path validPack = this.tempDir.resolve("valid.pack");
        Path truncatedPack = this.tempDir.resolve("truncated.pack");

        SharedWorldPack.buildPack(List.of(file), validPack);
        byte[] packBytes = Files.readAllBytes(validPack);
        Files.write(truncatedPack, Arrays.copyOf(packBytes, packBytes.length - 2));

        IOException error = assertThrows(IOException.class, () -> SharedWorldPack.extract(truncatedPack, this.tempDir.resolve("extract")));
        assertEquals("SharedWorld pack ended early while extracting data/foo.txt.", error.getMessage());
    }

    @Test
    void fileOrderingIsDeterministicRegardlessOfInputOrder() throws Exception {
        PreparedWorldFile alpha = preparedFile("b.txt", "bbb".getBytes());
        PreparedWorldFile beta = preparedFile("a.txt", "aaa".getBytes());
        Path firstPack = this.tempDir.resolve("first.pack");
        Path secondPack = this.tempDir.resolve("second.pack");

        SharedWorldPack.buildPack(List.of(alpha, beta), firstPack);
        SharedWorldPack.buildPack(List.of(beta, alpha), secondPack);

        assertArrayEquals(Files.readAllBytes(firstPack), Files.readAllBytes(secondPack));
    }

    @Test
    void overrideBytesEntriesPackWithoutTouchingTheSourceFile() throws Exception {
        // level.dat and the extracted host player file are packed from canonicalized
        // in-memory bytes; the on-disk file (if any) must not leak into the pack.
        PreparedWorldFile override = new PreparedWorldFile(
                null,
                "level.dat",
                "override-hash",
                "canonical".getBytes().length,
                "canonical".getBytes().length,
                "application/octet-stream",
                false,
                "canonical".getBytes()
        );
        Path packFile = this.tempDir.resolve("override.pack");
        Path extractRoot = this.tempDir.resolve("override-extract");

        SharedWorldPack.buildPack(List.of(override), packFile);
        SharedWorldPack.extract(packFile, extractRoot);

        assertArrayEquals("canonical".getBytes(), Files.readAllBytes(extractRoot.resolve("level.dat")));
    }

    @Test
    void entryThatShrankBetweenScanAndPackFailsNamingTheFile() throws Exception {
        PreparedWorldFile file = preparedFile("data/foo.txt", "original-payload".getBytes());
        Files.write(file.sourcePath(), "short".getBytes());

        IOException error = assertThrows(IOException.class, () -> SharedWorldPack.buildPack(List.of(file), this.tempDir.resolve("drift.pack")));
        assertEquals("SharedWorld pack entry data/foo.txt changed size while packing (expected 16 bytes, read 5).", error.getMessage());
    }

    @Test
    void entryThatGrewBetweenScanAndPackFailsNamingTheFile() throws Exception {
        PreparedWorldFile file = preparedFile("data/foo.txt", "orig".getBytes());
        Files.write(file.sourcePath(), "much-longer-content".getBytes());

        IOException error = assertThrows(IOException.class, () -> SharedWorldPack.buildPack(List.of(file), this.tempDir.resolve("drift.pack")));
        assertEquals("SharedWorld pack entry data/foo.txt grew while packing (expected 4 bytes).", error.getMessage());
    }

    @Test
    void describePackMatchesTheBuiltPackDescriptorExactly() throws Exception {
        // The lazy sync path answers plan requests from describePack + a cached
        // hash instead of building the pack; its size, ordering, and manifest
        // must be indistinguishable from a real build.
        PreparedWorldFile foo = preparedFile("data/foo.txt", "alpha-beta-gamma".getBytes());
        PreparedWorldFile bar = preparedFile("nested/dir/bar.bin", new byte[]{1, 2, 3, 4, 5});
        PreparedWorldFile override = new PreparedWorldFile(null, "level.dat", "override-hash", 9L, 9L, "application/octet-stream", false, "canonical".getBytes());
        Path packFile = this.tempDir.resolve("golden.pack");

        LocalPackDescriptorDto built = SharedWorldPack.buildPack("non-region", List.of(bar, override, foo), packFile);
        LocalPackDescriptorDto described = SharedWorldPack.describePack("non-region", List.of(foo, bar, override), built.hash());

        assertEquals(built.packId(), described.packId());
        assertEquals(built.hash(), described.hash());
        assertEquals(built.size(), described.size());
        assertEquals(Files.size(packFile), described.size());
        assertEquals(built.fileCount(), described.fileCount());
        assertArrayEquals(built.files(), described.files());
    }

    @Test
    void describePackOfAnEmptyFileListMatchesTheEmptyBuiltPack() throws Exception {
        Path packFile = this.tempDir.resolve("empty.pack");
        LocalPackDescriptorDto built = SharedWorldPack.buildPack("non-region", List.of(), packFile);
        LocalPackDescriptorDto described = SharedWorldPack.describePack("non-region", List.of(), built.hash());

        assertEquals(built.size(), described.size());
        assertEquals(Files.size(packFile), described.size());
        assertEquals(0, described.fileCount());
    }

    @Test
    void extractReportsTheContentHashOfEveryEntry() throws Exception {
        PreparedWorldFile foo = preparedFile("data/foo.txt", "alpha".getBytes());
        PreparedWorldFile bar = preparedFile("nested/dir/bar.bin", new byte[]{1, 2, 3, 4});
        Path packFile = this.tempDir.resolve("hashes.pack");
        Path extractRoot = this.tempDir.resolve("hashes-extract");
        SharedWorldPack.buildPack(List.of(foo, bar), packFile);

        var extractedHashes = SharedWorldPack.extract(packFile, extractRoot);

        assertEquals(foo.hash(), extractedHashes.get("data/foo.txt"));
        assertEquals(bar.hash(), extractedHashes.get("nested/dir/bar.bin"));
        assertEquals(LocalWorldHasher.hashFile(extractRoot.resolve("data").resolve("foo.txt")), extractedHashes.get("data/foo.txt"));
    }

    private PreparedWorldFile preparedFile(String relativePath, byte[] bytes) throws Exception {
        Path file = this.tempDir.resolve("source").resolve(relativePath.replace('/', java.io.File.separatorChar));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, bytes);
        return new PreparedWorldFile(
                file,
                relativePath,
                LocalWorldHasher.hashFile(file),
                bytes.length,
                bytes.length,
                "application/octet-stream",
                false,
                null
        );
    }
}
