package link.sharedworld.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 as lowercase hex, the hash every sync manifest and baseline records. */
public final class LocalWorldHasher {
    private LocalWorldHasher() {
    }

    /** SHA-256 is mandatory in every JRE, so its absence is a broken runtime, not an IO condition. */
    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing SHA-256 implementation.", exception);
        }
    }

    public static String hashBytes(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    public static String hashFile(Path file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
