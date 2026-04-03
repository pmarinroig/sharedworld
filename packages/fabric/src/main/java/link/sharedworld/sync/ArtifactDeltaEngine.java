package link.sharedworld.sync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class ArtifactDeltaEngine {
    private static final int MAGIC = 0x53574441; // SWDA
    private static final int VERSION = 1;
    private static final byte OP_COPY_BASE = 1;
    private static final byte OP_LITERAL = 2;

    private ArtifactDeltaEngine() {
    }

    public static DeltaStats writeDelta(Path baseFile, Path targetFile, Path artifactFile, int blockSize) throws IOException {
        byte[] baseBytes = baseFile != null && Files.exists(baseFile) ? Files.readAllBytes(baseFile) : new byte[0];
        byte[] targetBytes = Files.readAllBytes(targetFile);
        int blockCount = (int) Math.ceil((double) targetBytes.length / (double) blockSize);
        int copiedBlocks = 0;
        int literalBlocks = 0;
        long literalBytes = 0L;

        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(artifactFile)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(blockSize);
            output.writeInt(targetBytes.length);
            output.writeInt(blockCount);

            for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
                int offset = blockIndex * blockSize;
                int blockLength = Math.min(blockSize, targetBytes.length - offset);
                boolean copyBase = baseBytes.length >= offset + blockLength
                        && Arrays.equals(
                        Arrays.copyOfRange(baseBytes, offset, offset + blockLength),
                        Arrays.copyOfRange(targetBytes, offset, offset + blockLength)
                );

                if (copyBase) {
                    output.writeByte(OP_COPY_BASE);
                    copiedBlocks++;
                    continue;
                }

                output.writeByte(OP_LITERAL);
                output.writeInt(blockLength);
                output.write(targetBytes, offset, blockLength);
                literalBlocks++;
                literalBytes += blockLength;
            }
        }

        return new DeltaStats(Files.size(artifactFile), copiedBlocks, literalBlocks, literalBytes);
    }

    public static void applyDelta(Path baseFile, Path deltaFile, Path outputFile) throws IOException {
        byte[] baseBytes = baseFile != null && Files.exists(baseFile) ? Files.readAllBytes(baseFile) : new byte[0];

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(deltaFile)));
             BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(outputFile))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IOException("SharedWorld delta artifact header was invalid.");
            }

            int blockSize = input.readInt();
            int targetLength = input.readInt();
            int blockCount = input.readInt();
            for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
                int offset = blockIndex * blockSize;
                int blockLength = Math.min(blockSize, targetLength - offset);
                byte op = input.readByte();
                switch (op) {
                    case OP_COPY_BASE -> {
                        if (baseBytes.length < offset + blockLength) {
                            throw new IOException("SharedWorld delta expected base block " + blockIndex + " to exist.");
                        }
                        output.write(baseBytes, offset, blockLength);
                    }
                    case OP_LITERAL -> {
                        int literalLength = input.readInt();
                        if (literalLength != blockLength) {
                            throw new IOException("SharedWorld delta literal length mismatch.");
                        }
                        byte[] bytes = input.readNBytes(literalLength);
                        if (bytes.length != literalLength) {
                            throw new IOException("SharedWorld delta ended before reading the full block.");
                        }
                        output.write(bytes);
                    }
                    default -> throw new IOException("SharedWorld delta contained unknown op code " + op + ".");
                }
            }
        }
    }

    public record DeltaStats(long artifactSize, int copiedBlocks, int literalBlocks, long literalBytes) {
    }
}
