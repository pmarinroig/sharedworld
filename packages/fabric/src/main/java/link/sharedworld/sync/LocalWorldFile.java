package link.sharedworld.sync;

import java.nio.file.Path;

public record LocalWorldFile(
        Path path,
        String relativePath,
        String hash,
        long size,
        long compressedSize,
        String contentType
) {
}
