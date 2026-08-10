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
        int version;
        try (DataInputStream header = new DataInputStream(new BufferedInputStream(Files.newInputStream(deltaFile)))) {
            if (header.readInt() != MAGIC) {
                throw new IOException("SharedWorld delta artifact header was invalid.");
            }
            version = header.readInt();
        }
        if (version == VERSION) {
            applyDeltaV1(baseFile, deltaFile, outputFile);
            return;
        }
        if (version == VERSION_2) {
            applyDeltaV2(baseFile, deltaFile, outputFile);
            return;
        }
        throw new IOException("SharedWorld delta artifact header was invalid.");
    }

    private static void applyDeltaV1(Path baseFile, Path deltaFile, Path outputFile) throws IOException {
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

    // ---------------------------------------------------------------- v2

    /**
     * v2 wire format: MAGIC, VERSION_2, int blockSize, long baseLength,
     * long targetLength, then ops until OP_END:
     *   OP_COPY_RANGE: long baseOffset + int length  (bytes from the base)
     *   OP_LITERAL:    int length + bytes            (raw target bytes, ≤1MiB)
     * Unlike v1's same-offset lockstep, copies may reference any base offset
     * (rsync-style rolling match), so shifted/appended data still deduplicates,
     * and long lengths lift v1's 2GiB ceiling.
     */
    static final int VERSION_2 = 2;
    private static final byte OP_END = 0;
    private static final byte OP_COPY_RANGE = 3;
    private static final int LITERAL_CHUNK_MAX = 1024 * 1024;
    private static final int V2_MIN_BLOCK_SIZE = 64 * 1024;
    private static final int V2_MAX_BLOCK_SIZE = 8 * 1024 * 1024;
    private static final int V2_TARGET_BLOCK_COUNT = 16384;

    /** Smallest power of two giving ≤ V2_TARGET_BLOCK_COUNT base blocks, clamped. */
    static int v2BlockSizeFor(long baseLength) {
        int blockSize = V2_MIN_BLOCK_SIZE;
        while (blockSize < V2_MAX_BLOCK_SIZE && (baseLength + blockSize - 1) / blockSize > V2_TARGET_BLOCK_COUNT) {
            blockSize <<= 1;
        }
        return blockSize;
    }

    public static DeltaStats writeDeltaV2(Path baseFile, Path targetFile, Path artifactFile) throws IOException {
        long baseLength = baseFile != null && Files.exists(baseFile) ? Files.size(baseFile) : 0L;
        long targetLength = Files.size(targetFile);
        int blockSize = v2BlockSizeFor(Math.max(baseLength, 1L));

        // Index the base: weak rolling checksum -> candidate offsets, verified
        // by a strong hash. Non-overlapping blocks keep the index tiny
        // (≤ V2_TARGET_BLOCK_COUNT entries) regardless of file size.
        java.util.HashMap<Integer, java.util.List<long[]>> index = new java.util.HashMap<>();
        java.util.HashMap<Long, byte[]> strongByOffset = new java.util.HashMap<>();
        java.security.MessageDigest digest = newSha256();
        if (baseLength > 0L) {
            try (BufferedInputStream base = new BufferedInputStream(Files.newInputStream(baseFile))) {
                byte[] block = new byte[blockSize];
                long offset = 0L;
                while (offset + blockSize <= baseLength) {
                    if (base.readNBytes(block, 0, blockSize) != blockSize) {
                        throw new IOException("SharedWorld delta base changed size while indexing.");
                    }
                    int weak = weakChecksum(block, blockSize);
                    index.computeIfAbsent(weak, ignored -> new java.util.ArrayList<>()).add(new long[]{offset});
                    strongByOffset.put(offset, digest.digest(java.util.Arrays.copyOf(block, blockSize)));
                    offset += blockSize;
                }
            }
        }

        long copiedBytes = 0L;
        long literalBytes = 0L;
        int copiedBlocks = 0;
        int literalBlocks = 0;
        try (BufferedInputStream target = new BufferedInputStream(Files.newInputStream(targetFile), Math.max(blockSize * 2, 1 << 16));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(artifactFile)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION_2);
            output.writeInt(blockSize);
            output.writeLong(baseLength);
            output.writeLong(targetLength);

            byte[] window = new byte[blockSize];
            int windowFill = 0;
            int windowStart = 0;
            java.io.ByteArrayOutputStream pendingLiteral = new java.io.ByteArrayOutputStream();
            long pendingCopyOffset = -1L;
            long pendingCopyLength = 0L;
            int weak = 0;

            int next;
            while (true) {
                if (windowFill < blockSize) {
                    next = target.read();
                    if (next < 0) {
                        break;
                    }
                    window[(windowStart + windowFill) % blockSize] = (byte) next;
                    windowFill += 1;
                    if (windowFill < blockSize) {
                        continue;
                    }
                    weak = weakChecksumRing(window, windowStart, blockSize);
                }

                java.util.List<long[]> candidates = index.get(weak);
                long matchOffset = -1L;
                if (candidates != null) {
                    byte[] linear = ringToLinear(window, windowStart, blockSize);
                    byte[] strong = digest.digest(linear);
                    for (long[] candidate : candidates) {
                        if (java.util.Arrays.equals(strong, strongByOffset.get(candidate[0]))) {
                            matchOffset = candidate[0];
                            break;
                        }
                    }
                }

                if (matchOffset >= 0L) {
                    literalBlocks += flushLiteral(output, pendingLiteral, literalBlocks);
                    literalBytes += pendingLiteral.size();
                    pendingLiteral.reset();
                    if (pendingCopyOffset >= 0L && pendingCopyOffset + pendingCopyLength == matchOffset) {
                        pendingCopyLength += blockSize;
                    } else {
                        writePendingCopy(output, pendingCopyOffset, pendingCopyLength);
                        pendingCopyOffset = matchOffset;
                        pendingCopyLength = blockSize;
                    }
                    copiedBlocks += 1;
                    copiedBytes += blockSize;
                    windowFill = 0;
                    windowStart = 0;
                    continue;
                }

                // No match: the window's oldest byte becomes literal and the
                // window slides one byte forward (rolling checksum update).
                writePendingCopy(output, pendingCopyOffset, pendingCopyLength);
                pendingCopyOffset = -1L;
                pendingCopyLength = 0L;
                byte oldest = window[windowStart];
                pendingLiteral.write(oldest);
                if (pendingLiteral.size() >= LITERAL_CHUNK_MAX) {
                    literalBlocks += flushLiteral(output, pendingLiteral, literalBlocks);
                    literalBytes += pendingLiteral.size();
                    pendingLiteral.reset();
                }
                next = target.read();
                if (next < 0) {
                    windowStart = (windowStart + 1) % blockSize;
                    windowFill -= 1;
                    break;
                }
                weak = rollWeak(weak, oldest, (byte) next, blockSize);
                window[windowStart] = (byte) next;
                windowStart = (windowStart + 1) % blockSize;
            }

            // Tail: whatever is left in the window joins the pending literal.
            for (int i = 0; i < windowFill; i++) {
                pendingLiteral.write(window[(windowStart + i) % blockSize]);
                if (pendingLiteral.size() >= LITERAL_CHUNK_MAX) {
                    flushLiteral(output, pendingLiteral, 0);
                    literalBytes += pendingLiteral.size();
                    literalBlocks += 1;
                    pendingLiteral.reset();
                }
            }
            writePendingCopy(output, pendingCopyOffset, pendingCopyLength);
            if (pendingLiteral.size() > 0) {
                literalBytes += pendingLiteral.size();
                literalBlocks += 1;
                flushLiteral(output, pendingLiteral, 0);
            }
            output.writeByte(OP_END);
        }
        if (copiedBytes + literalBytes != targetLength) {
            throw new IOException("SharedWorld delta writer covered " + (copiedBytes + literalBytes) + " of " + targetLength + " bytes.");
        }
        return new DeltaStats(Files.size(artifactFile), copiedBlocks, literalBlocks, literalBytes);
    }

    private static int flushLiteral(DataOutputStream output, java.io.ByteArrayOutputStream pending, int ignored) throws IOException {
        if (pending.size() == 0) {
            return 0;
        }
        byte[] bytes = pending.toByteArray();
        output.writeByte(OP_LITERAL);
        output.writeInt(bytes.length);
        output.write(bytes);
        return 1;
    }

    private static void writePendingCopy(DataOutputStream output, long offset, long length) throws IOException {
        long remaining = length;
        long cursor = offset;
        while (remaining > 0L) {
            int emit = (int) Math.min(remaining, Integer.MAX_VALUE);
            output.writeByte(OP_COPY_RANGE);
            output.writeLong(cursor);
            output.writeInt(emit);
            cursor += emit;
            remaining -= emit;
        }
    }

    private static void applyDeltaV2(Path baseFile, Path deltaFile, Path outputFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(deltaFile)));
             java.nio.channels.SeekableByteChannel base = baseFile != null && Files.exists(baseFile)
                     ? Files.newByteChannel(baseFile, java.nio.file.StandardOpenOption.READ)
                     : null;
             BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(outputFile))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION_2) {
                throw new IOException("SharedWorld delta artifact header was invalid.");
            }
            int blockSize = input.readInt();
            long baseLength = input.readLong();
            long targetLength = input.readLong();
            if (blockSize <= 0 || baseLength < 0L || targetLength < 0L) {
                throw new IOException("SharedWorld delta artifact header was invalid.");
            }
            long written = 0L;
            byte[] copyBuffer = new byte[1 << 16];
            while (true) {
                byte op = input.readByte();
                if (op == OP_END) {
                    break;
                }
                if (op == OP_COPY_RANGE) {
                    if (base == null) {
                        throw new IOException("SharedWorld delta expected a base artifact that is missing.");
                    }
                    long copyOffset = input.readLong();
                    int copyLength = input.readInt();
                    if (copyOffset < 0L || copyLength < 0 || copyOffset + copyLength > baseLength) {
                        throw new IOException("SharedWorld delta copy range is outside the base artifact.");
                    }
                    base.position(copyOffset);
                    long remaining = copyLength;
                    while (remaining > 0L) {
                        int toRead = (int) Math.min(remaining, copyBuffer.length);
                        int read = base.read(java.nio.ByteBuffer.wrap(copyBuffer, 0, toRead));
                        if (read <= 0) {
                            throw new IOException("SharedWorld delta base ended inside a copy range.");
                        }
                        output.write(copyBuffer, 0, read);
                        remaining -= read;
                    }
                    written += copyLength;
                } else if (op == OP_LITERAL) {
                    int literalLength = input.readInt();
                    if (literalLength < 0) {
                        throw new IOException("SharedWorld delta literal length was invalid.");
                    }
                    long remaining = literalLength;
                    while (remaining > 0L) {
                        int toRead = (int) Math.min(remaining, copyBuffer.length);
                        int read = input.read(copyBuffer, 0, toRead);
                        if (read <= 0) {
                            throw new IOException("SharedWorld delta ended inside a literal.");
                        }
                        output.write(copyBuffer, 0, read);
                        remaining -= read;
                    }
                    written += literalLength;
                } else {
                    throw new IOException("SharedWorld delta contained unknown op code " + op + ".");
                }
            }
            if (written != targetLength) {
                throw new IOException("SharedWorld delta reconstructed " + written + " of " + targetLength + " bytes.");
            }
        }
    }

    /** Adler-style weak checksum over a linear buffer. */
    private static int weakChecksum(byte[] bytes, int length) {
        int s1 = 0;
        int s2 = 0;
        for (int i = 0; i < length; i++) {
            s1 = (s1 + (bytes[i] & 0xFF)) & 0xFFFF;
            s2 = (s2 + s1) & 0xFFFF;
        }
        return (s2 << 16) | s1;
    }

    private static int weakChecksumRing(byte[] ring, int start, int length) {
        int s1 = 0;
        int s2 = 0;
        for (int i = 0; i < length; i++) {
            s1 = (s1 + (ring[(start + i) % length] & 0xFF)) & 0xFFFF;
            s2 = (s2 + s1) & 0xFFFF;
        }
        return (s2 << 16) | s1;
    }

    /** O(1) roll: drop {@code outgoing}, append {@code incoming}. */
    private static int rollWeak(int weak, byte outgoing, byte incoming, int blockSize) {
        int s1 = weak & 0xFFFF;
        int s2 = (weak >>> 16) & 0xFFFF;
        int out = outgoing & 0xFF;
        int in = incoming & 0xFF;
        s1 = (s1 - out + in) & 0xFFFF;
        s2 = (s2 - blockSize * out + s1) & 0xFFFF;
        return (s2 << 16) | s1;
    }

    private static byte[] ringToLinear(byte[] ring, int start, int length) {
        byte[] linear = new byte[length];
        for (int i = 0; i < length; i++) {
            linear[i] = ring[(start + i) % length];
        }
        return linear;
    }

    private static java.security.MessageDigest newSha256() throws IOException {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 unavailable.", exception);
        }
    }

    public record DeltaStats(long artifactSize, int copiedBlocks, int literalBlocks, long literalBytes) {
    }
}
