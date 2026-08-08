package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldModels.LocalPackDescriptorDto;
import link.sharedworld.api.SharedWorldModels.PackedManifestFileDto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class SharedWorldPack {
    public static final String PACK_ID = "non-region";
    /** Salted into cached pack-hash fingerprints so a format bump invalidates them. */
    static final int FORMAT_VERSION = 1;
    private static final int MAGIC = 0x5357504B; // SWPK
    private static final int VERSION = FORMAT_VERSION;
    private static final int ALIGNMENT = 4096;

    private SharedWorldPack() {
    }

    public static LocalPackDescriptorDto buildPack(List<PreparedWorldFile> files, Path target) throws IOException {
        return buildPack(PACK_ID, files, target);
    }

    /**
     * Describes the pack these files would build — same id, hash, size, and
     * manifest as {@link #buildPack} — without writing any bytes. The size
     * comes from the deterministic layout math (sorted entries, declared
     * sizes, fixed alignment); the hash must be supplied by a caller that
     * already knows it (the scan cache keyed by member fingerprint).
     */
    public static LocalPackDescriptorDto describePack(String packId, List<PreparedWorldFile> files, String packHash) {
        PackLayout layout = computeLayout(files);
        return new LocalPackDescriptorDto(
                packId,
                packHash,
                layout.packSize(),
                layout.headers().size(),
                packedManifest(layout.headers())
        );
    }

    private static PackLayout computeLayout(List<PreparedWorldFile> files) {
        List<PackEntryData> entries = files.stream()
                .sorted(Comparator.comparing(PreparedWorldFile::relativePath))
                .map(SharedWorldPack::toEntryData)
                .toList();
        long metadataSize = Integer.BYTES * 3L;
        for (PackEntryData entry : entries) {
            metadataSize += Integer.BYTES + entry.pathBytes().length;
            metadataSize += Long.BYTES;
            metadataSize += Integer.BYTES + entry.contentTypeBytes().length;
            metadataSize += Integer.BYTES + entry.hashBytes().length;
            metadataSize += Long.BYTES;
        }

        long nextOffset = align(metadataSize, ALIGNMENT);
        List<PackEntryHeader> headers = new ArrayList<>(entries.size());
        for (PackEntryData entry : entries) {
            headers.add(new PackEntryHeader(entry, nextOffset));
            nextOffset = align(nextOffset + entry.size(), ALIGNMENT);
        }
        // The last entry is not padded, so the file ends exactly at its final
        // body byte; an empty pack is just the (unaligned) metadata block.
        long packSize = headers.isEmpty()
                ? metadataSize
                : headers.get(headers.size() - 1).offset() + entries.get(entries.size() - 1).size();
        return new PackLayout(headers, metadataSize, packSize);
    }

    private static PackedManifestFileDto[] packedManifest(List<PackEntryHeader> headers) {
        return headers.stream().map(header -> new PackedManifestFileDto(
                header.entry().relativePath(),
                header.entry().hash(),
                header.entry().size(),
                header.entry().contentType()
        )).toArray(PackedManifestFileDto[]::new);
    }

    public static LocalPackDescriptorDto buildPack(String packId, List<PreparedWorldFile> files, Path target) throws IOException {
        PackLayout layout = computeLayout(files);
        long metadataSize = layout.metadataSize();
        List<PackEntryHeader> headers = layout.headers();

        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(headers.size());
            for (PackEntryHeader header : headers) {
                output.writeInt(header.entry().pathBytes().length);
                output.write(header.entry().pathBytes());
                output.writeLong(header.entry().size());
                output.writeInt(header.entry().contentTypeBytes().length);
                output.write(header.entry().contentTypeBytes());
                output.writeInt(header.entry().hashBytes().length);
                output.write(header.entry().hashBytes());
                output.writeLong(header.offset());
            }
            padToOffset(output, metadataSize, headers.isEmpty() ? metadataSize : headers.get(0).offset());
            long currentOffset = headers.isEmpty() ? metadataSize : headers.get(0).offset();
            for (PackEntryHeader header : headers) {
                padToOffset(output, currentOffset, header.offset());
                writeEntryBody(output, header.entry());
                currentOffset = header.offset() + header.entry().size();
            }
        }

        return new LocalPackDescriptorDto(
                packId,
                LocalWorldHasher.hashFile(target),
                Files.size(target),
                headers.size(),
                packedManifest(headers)
        );
    }

    /**
     * Header offsets were computed from the scanned size before any bytes were
     * copied, so a file that changes size mid-pack would silently corrupt every
     * later entry's offset — that mismatch must abort the pack.
     */
    private static void writeEntryBody(DataOutputStream output, PackEntryData entry) throws IOException {
        if (entry.overrideBytes() != null) {
            output.write(entry.overrideBytes());
            return;
        }
        long copied = 0L;
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(entry.sourcePath()))) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (copied + read > entry.size()) {
                    throw new IOException("SharedWorld pack entry " + entry.relativePath() + " grew while packing (expected " + entry.size() + " bytes).");
                }
                output.write(buffer, 0, read);
                copied += read;
            }
        }
        if (copied != entry.size()) {
            throw new IOException("SharedWorld pack entry " + entry.relativePath() + " changed size while packing (expected " + entry.size() + " bytes, read " + copied + ").");
        }
    }

    /**
     * Extracts every entry and returns each entry's content hash, computed
     * while the bytes are written so callers verify extractions without a
     * second full read of everything extracted.
     */
    public static Map<String, String> extract(Path packFile, Path outputDirectory) throws IOException {
        List<PackEntryMetadata> entries = readMetadata(packFile);
        Map<String, String> extractedHashes = new HashMap<>(entries.size());
        for (PackEntryMetadata entry : entries) {
            Path target = outputDirectory.resolve(entry.relativePath().replace('/', java.io.File.separatorChar));
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            MessageDigest digest = newSha256();
            try (SeekableByteChannel channel = Files.newByteChannel(packFile);
                 var output = Files.newOutputStream(target)) {
                channel.position(entry.offset());
                long remaining = entry.size();
                byte[] buffer = new byte[16 * 1024];
                while (remaining > 0L) {
                    int read = channel.read(ByteBuffer.wrap(buffer, 0, (int) Math.min(buffer.length, remaining)));
                    if (read <= 0) {
                        throw new IOException("SharedWorld pack ended early while extracting " + entry.relativePath() + ".");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
            extractedHashes.put(entry.relativePath(), HexFormat.of().formatHex(digest.digest()));
        }
        return extractedHashes;
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("Missing SHA-256 implementation.", exception);
        }
    }

    public static PackedManifestFileDto[] describe(Path packFile) throws IOException {
        return readMetadata(packFile).stream()
                .map(entry -> new PackedManifestFileDto(entry.relativePath(), entry.hash(), entry.size(), entry.contentType()))
                .toArray(PackedManifestFileDto[]::new);
    }

    private static List<PackEntryMetadata> readMetadata(Path packFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(packFile)))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IOException("SharedWorld pack header was invalid.");
            }
            int entryCount = input.readInt();
            List<PackEntryMetadata> entries = new ArrayList<>(entryCount);
            for (int i = 0; i < entryCount; i++) {
                byte[] pathBytes = input.readNBytes(input.readInt());
                long size = input.readLong();
                byte[] contentTypeBytes = input.readNBytes(input.readInt());
                byte[] hashBytes = input.readNBytes(input.readInt());
                long offset = input.readLong();
                entries.add(new PackEntryMetadata(
                        new String(pathBytes, StandardCharsets.UTF_8),
                        size,
                        new String(contentTypeBytes, StandardCharsets.UTF_8),
                        new String(hashBytes, StandardCharsets.UTF_8),
                        offset
                ));
            }
            return entries;
        }
    }

    private static PackEntryData toEntryData(PreparedWorldFile file) {
        return new PackEntryData(
                file.relativePath(),
                file.hash(),
                file.contentType(),
                file.relativePath().getBytes(StandardCharsets.UTF_8),
                file.contentType().getBytes(StandardCharsets.UTF_8),
                file.hash().getBytes(StandardCharsets.UTF_8),
                file.sourcePath(),
                file.overrideBytes(),
                file.overrideBytes() != null ? file.overrideBytes().length : file.size()
        );
    }

    private static long align(long value, long alignment) {
        long remainder = value % alignment;
        return remainder == 0L ? value : value + (alignment - remainder);
    }

    private static void padToOffset(DataOutputStream output, long currentOffset, long targetOffset) throws IOException {
        long remaining = Math.max(0L, targetOffset - currentOffset);
        if (remaining <= 0L) {
            return;
        }
        byte[] zeroes = new byte[(int) Math.min(ALIGNMENT, remaining)];
        while (remaining > 0L) {
            int chunk = (int) Math.min(zeroes.length, remaining);
            output.write(zeroes, 0, chunk);
            remaining -= chunk;
        }
    }

    private record PackEntryData(
            String relativePath,
            String hash,
            String contentType,
            byte[] pathBytes,
            byte[] contentTypeBytes,
            byte[] hashBytes,
            Path sourcePath,
            byte[] overrideBytes,
            long size
    ) {
    }

    private record PackEntryHeader(PackEntryData entry, long offset) {
    }

    private record PackLayout(List<PackEntryHeader> headers, long metadataSize, long packSize) {
    }

    private record PackEntryMetadata(String relativePath, long size, String contentType, String hash, long offset) {
    }
}
