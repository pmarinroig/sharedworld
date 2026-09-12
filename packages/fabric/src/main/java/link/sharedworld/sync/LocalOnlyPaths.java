package link.sharedworld.sync;

import java.nio.file.Path;

/**
 * World files that never leave the machine. They are skipped by the upload
 * scan and the capture mirror, and the download apply neither deletes nor
 * overwrites them, so a file matched here behaves like a per-machine cache
 * that happens to live inside the world folder.
 *
 * <p>Distant Horizons keeps its LOD database in every dimension's {@code data}
 * folder and rewrites it constantly; synced, it dirtied a superpack shard on
 * every autosave, pushed gigabytes to every member and tripped the single-file
 * relay limit. Xaero's {@code xaeromap.txt} is deliberately NOT here: it holds
 * the world id Xaero's server side hands to guests, and syncing it is what keeps
 * every guest in the same map instance.
 */
public final class LocalOnlyPaths {
    private static final String DISTANT_HORIZONS_DATABASE = "DistantHorizons.sqlite";

    private LocalOnlyPaths() {
    }

    /** {@code relativePath} is world-relative with {@code /} separators. */
    public static boolean isLocalOnly(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        String fileName = slash < 0 ? relativePath : relativePath.substring(slash + 1);
        if (isLocalOnlyFileName(fileName)) {
            return true;
        }
        String parent = slash < 0 ? "" : relativePath.substring(0, slash);
        return isDistantHorizonsDatabase(parent, fileName);
    }

    public static boolean isLocalOnly(Path worldDirectory, Path file) {
        return isLocalOnly(worldDirectory.relativize(file).toString().replace('\\', '/'));
    }

    /** The filename-only half of the rule, also used when importing or exporting vanilla saves. */
    public static boolean isLocalOnlyFileName(String fileName) {
        return "session.lock".equals(fileName) || fileName.endsWith(".dat_old");
    }

    private static boolean isDistantHorizonsDatabase(String parent, String fileName) {
        if (!fileName.startsWith(DISTANT_HORIZONS_DATABASE)) {
            return false;
        }
        String suffix = fileName.substring(DISTANT_HORIZONS_DATABASE.length());
        boolean sqliteFamily = suffix.isEmpty() || "-wal".equals(suffix) || "-shm".equals(suffix) || "-journal".equals(suffix);
        return sqliteFamily && ("data".equals(parent) || parent.endsWith("/data"));
    }
}
