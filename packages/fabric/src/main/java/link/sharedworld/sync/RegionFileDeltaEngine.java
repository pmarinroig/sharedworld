package link.sharedworld.sync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class RegionFileDeltaEngine {
    private static final int MAGIC = 0x53575244; // SWRD
    private static final int VERSION = 2;
    private static final int SECTOR_BYTES = 4096;
    private static final byte OP_COPY_BASE = 1;
    private static final byte OP_LITERAL = 2;

    private RegionFileDeltaEngine() {
    }

    public static DeltaStats writeDelta(Path baseFile, Path targetFile, Path artifactFile) throws IOException {
        byte[] baseBytes = baseFile != null && Files.exists(baseFile) ? Files.readAllBytes(baseFile) : new byte[0];
        byte[] targetBytes = Files.readAllBytes(targetFile);
        int sectorCount = (int) Math.ceil((double) targetBytes.length / (double) SECTOR_BYTES);
        int copiedSectors = 0;
        int literalSectors = 0;
        long literalBytes = 0L;

        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(artifactFile)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(targetBytes.length);
            output.writeInt(sectorCount);

            for (int sectorIndex = 0; sectorIndex < sectorCount; sectorIndex++) {
                int offset = sectorIndex * SECTOR_BYTES;
                int sectorLength = Math.min(SECTOR_BYTES, targetBytes.length - offset);
                boolean copyBase = baseBytes.length >= offset + sectorLength
                        && Arrays.equals(
                        Arrays.copyOfRange(baseBytes, offset, offset + sectorLength),
                        Arrays.copyOfRange(targetBytes, offset, offset + sectorLength)
                );

                if (copyBase) {
                    output.writeByte(OP_COPY_BASE);
                    copiedSectors++;
                    continue;
                }

                output.writeByte(OP_LITERAL);
                output.writeInt(sectorLength);
                output.write(targetBytes, offset, sectorLength);
                literalSectors++;
                literalBytes += sectorLength;
            }
        }

        return new DeltaStats(Files.size(artifactFile), copiedSectors, literalSectors, literalBytes);
    }

    public static void applyDelta(Path baseFile, Path deltaFile, Path outputFile) throws IOException {
        byte[] baseBytes = baseFile != null && Files.exists(baseFile) ? Files.readAllBytes(baseFile) : new byte[0];

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(deltaFile)));
             BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(outputFile))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IOException("SharedWorld region delta artifact header was invalid.");
            }

            int targetLength = input.readInt();
            int sectorCount = input.readInt();
            for (int sectorIndex = 0; sectorIndex < sectorCount; sectorIndex++) {
                int offset = sectorIndex * SECTOR_BYTES;
                int sectorLength = Math.min(SECTOR_BYTES, targetLength - offset);
                byte op = input.readByte();
                switch (op) {
                    case OP_COPY_BASE -> {
                        if (baseBytes.length < offset + sectorLength) {
                            throw new IOException("SharedWorld region delta expected base sector " + sectorIndex + " to exist.");
                        }
                        output.write(baseBytes, offset, sectorLength);
                    }
                    case OP_LITERAL -> {
                        int literalLength = input.readInt();
                        if (literalLength != sectorLength) {
                            throw new IOException("SharedWorld region delta literal length mismatch.");
                        }
                        byte[] bytes = input.readNBytes(literalLength);
                        if (bytes.length != literalLength) {
                            throw new IOException("SharedWorld region delta ended before reading the full sector.");
                        }
                        output.write(bytes);
                    }
                    default -> throw new IOException("SharedWorld region delta contained unknown op code " + op + ".");
                }
            }
        }
    }

    public record DeltaStats(long artifactSize, int copiedSectors, int literalSectors, long literalBytes) {
    }
}
