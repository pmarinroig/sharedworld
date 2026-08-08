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
        long targetSize = Files.size(targetFile);
        if (targetSize > Integer.MAX_VALUE) {
            throw new IOException("SharedWorld delta target is too large for a delta artifact (" + targetSize + " bytes).");
        }
        int targetLength = (int) targetSize;
        long baseSize = baseFile != null && Files.exists(baseFile) ? Files.size(baseFile) : 0L;
        int blockCount = (int) Math.ceil((double) targetLength / (double) blockSize);
        int copiedBlocks = 0;
        int literalBlocks = 0;
        long literalBytes = 0L;

        // Both streams advance in lockstep block by block; once the base can no
        // longer cover a full block it can never cover a later one, so it is
        // simply not read past that point.
        try (BufferedInputStream target = new BufferedInputStream(Files.newInputStream(targetFile));
             BufferedInputStream base = baseSize > 0 ? new BufferedInputStream(Files.newInputStream(baseFile)) : null;
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(artifactFile)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(blockSize);
            output.writeInt(targetLength);
            output.writeInt(blockCount);

            byte[] targetBlock = new byte[blockSize];
            byte[] baseBlock = new byte[blockSize];
            for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
                int offset = blockIndex * blockSize;
                int blockLength = Math.min(blockSize, targetLength - offset);
                if (target.readNBytes(targetBlock, 0, blockLength) != blockLength) {
                    throw new IOException("SharedWorld delta target changed size while reading block " + blockIndex + ".");
                }
                boolean copyBase = false;
                if (base != null && baseSize >= (long) offset + blockLength) {
                    if (base.readNBytes(baseBlock, 0, blockLength) != blockLength) {
                        throw new IOException("SharedWorld delta base changed size while reading block " + blockIndex + ".");
                    }
                    copyBase = Arrays.equals(baseBlock, 0, blockLength, targetBlock, 0, blockLength);
                }

                if (copyBase) {
                    output.writeByte(OP_COPY_BASE);
                    copiedBlocks++;
                    continue;
                }

                output.writeByte(OP_LITERAL);
                output.writeInt(blockLength);
                output.write(targetBlock, 0, blockLength);
                literalBlocks++;
                literalBytes += blockLength;
            }
        }

        return new DeltaStats(Files.size(artifactFile), copiedBlocks, literalBlocks, literalBytes);
    }

    public static void applyDelta(Path baseFile, Path deltaFile, Path outputFile) throws IOException {
        boolean baseExists = baseFile != null && Files.exists(baseFile);

        // The base is consumed strictly forward: ops arrive in offset order, and
        // the skip before a copy op is lazy, so literal blocks past the base's
        // EOF never touch the base stream at all.
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(deltaFile)));
             BufferedInputStream base = baseExists ? new BufferedInputStream(Files.newInputStream(baseFile)) : null;
             BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(outputFile))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IOException("SharedWorld delta artifact header was invalid.");
            }

            int blockSize = input.readInt();
            int targetLength = input.readInt();
            int blockCount = input.readInt();
            if (blockSize <= 0 || targetLength < 0 || blockCount < 0) {
                throw new IOException("SharedWorld delta artifact header was invalid.");
            }
            byte[] baseBlock = new byte[(int) Math.min(blockSize, Math.max(targetLength, 1))];
            long basePosition = 0L;
            for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
                int offset = blockIndex * blockSize;
                int blockLength = Math.min(blockSize, targetLength - offset);
                byte op = input.readByte();
                switch (op) {
                    case OP_COPY_BASE -> {
                        if (base == null) {
                            throw new IOException("SharedWorld delta expected base block " + blockIndex + " to exist.");
                        }
                        try {
                            base.skipNBytes(offset - basePosition);
                        } catch (IOException exception) {
                            throw new IOException("SharedWorld delta expected base block " + blockIndex + " to exist.", exception);
                        }
                        basePosition = offset;
                        if (base.readNBytes(baseBlock, 0, blockLength) != blockLength) {
                            throw new IOException("SharedWorld delta expected base block " + blockIndex + " to exist.");
                        }
                        basePosition += blockLength;
                        output.write(baseBlock, 0, blockLength);
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
