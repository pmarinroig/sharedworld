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
