package link.sharedworld.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ArtifactDeltaEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void deltaRoundTripReconstructsTargetExactly() throws Exception {
        Path baseFile = this.tempDir.resolve("base.bin");
        Path targetFile = this.tempDir.resolve("target.bin");
        Path deltaFile = this.tempDir.resolve("delta.bin");
        Path outputFile = this.tempDir.resolve("output.bin");

        Files.write(baseFile, "AAAAABBBBBCCCCCDDDDDEEEEE".getBytes());
        Files.write(targetFile, "AAAAABBBBBXXXXXYYYYYEEEEE".getBytes());

        ArtifactDeltaEngine.writeDelta(baseFile, targetFile, deltaFile, 5);
        ArtifactDeltaEngine.applyDelta(baseFile, deltaFile, outputFile);

        assertArrayEquals(Files.readAllBytes(targetFile), Files.readAllBytes(outputFile));
    }

    @Test
    void invalidDeltaHeaderFailsWithIOException() throws Exception {
        Path baseFile = this.tempDir.resolve("base.bin");
        Path deltaFile = this.tempDir.resolve("delta-invalid.bin");
        Path outputFile = this.tempDir.resolve("output.bin");

        Files.write(baseFile, "base".getBytes());
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(deltaFile))) {
            output.writeInt(0x12345678);
            output.writeInt(1);
            output.writeInt(4);
            output.writeInt(4);
            output.writeInt(1);
            output.writeByte(2);
            output.writeInt(4);
            output.write("data".getBytes());
        }

        IOException error = assertThrows(IOException.class, () -> ArtifactDeltaEngine.applyDelta(baseFile, deltaFile, outputFile));
        assertEquals("SharedWorld delta artifact header was invalid.", error.getMessage());
    }

    @Test
    void copyBaseFailsClosedWhenBaseIsTooShort() throws Exception {
        Path baseFile = this.tempDir.resolve("base-short.bin");
        Path deltaFile = this.tempDir.resolve("delta-copy.bin");
        Path outputFile = this.tempDir.resolve("output.bin");

        Files.write(baseFile, new byte[] {1, 2});
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(deltaFile))) {
            output.writeInt(0x53574441);
            output.writeInt(1);
            output.writeInt(4);
            output.writeInt(4);
            output.writeInt(1);
            output.writeByte(1);
        }

        IOException error = assertThrows(IOException.class, () -> ArtifactDeltaEngine.applyDelta(baseFile, deltaFile, outputFile));
        assertEquals("SharedWorld delta expected base block 0 to exist.", error.getMessage());
    }

    @Test
    void truncatedLiteralPayloadFailsClosed() throws Exception {
        Path baseFile = this.tempDir.resolve("base.bin");
        Path deltaFile = this.tempDir.resolve("delta-truncated.bin");
        Path outputFile = this.tempDir.resolve("output.bin");

        Files.write(baseFile, "base".getBytes());
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(deltaFile))) {
            output.writeInt(0x53574441);
            output.writeInt(1);
            output.writeInt(4);
            output.writeInt(4);
            output.writeInt(1);
            output.writeByte(2);
            output.writeInt(4);
            output.write(new byte[] {9, 8});
        }

        IOException error = assertThrows(IOException.class, () -> ArtifactDeltaEngine.applyDelta(baseFile, deltaFile, outputFile));
        assertEquals("SharedWorld delta ended before reading the full block.", error.getMessage());
    }
}
