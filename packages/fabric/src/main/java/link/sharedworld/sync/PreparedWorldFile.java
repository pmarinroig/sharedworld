package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldModels.LocalFileDescriptorDto;
import link.sharedworld.api.SharedWorldModels.ManifestFileDto;

import java.nio.file.Path;

public record PreparedWorldFile(
        Path sourcePath,
        String relativePath,
        String hash,
        long size,
        long compressedSize,
        String contentType,
        boolean deltaCapable,
        byte[] overrideBytes
) {
    public LocalFileDescriptorDto toDescriptor() {
        return new LocalFileDescriptorDto(this.relativePath, this.hash, this.size, this.compressedSize, this.contentType, this.deltaCapable);
    }

    public ManifestFileDto toManifestFile(String storageKey, String transferMode, String baseSnapshotId, String baseHash, Integer chainDepth) {
        return new ManifestFileDto(this.relativePath, this.hash, this.size, this.compressedSize, storageKey, this.contentType, transferMode, baseSnapshotId, baseHash, chainDepth);
    }
}
