package link.sharedworld.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SyncPathRules {
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^(?:(.*?)/)?region/r\\.(-?\\d+)\\.(-?\\d+)\\.mca$", Pattern.CASE_INSENSITIVE);
    public static final int REGION_BUNDLE_MAX_MEMBERS = 4;
    public static final long REGION_BUNDLE_MAX_BYTES = 40L * 1024L * 1024L;
    public static final long SUPERPACK_SHARD_MAX_BYTES = 40L * 1024L * 1024L;
    /**
     * Shard ids live inside the region-bundle wire namespace on purpose: 0.3.0
     * clients apply any "region-bundle:*" pack generically (download, extract,
     * baseline by id), so sharded snapshots stay joinable by them. Collision
     * with terrain ids is impossible: a terrain id's segment before the tile
     * coordinates always ends in "region", and this literal does not.
     */
    public static final String SUPERPACK_SHARD_ID_PREFIX = "region-bundle:superpack:";
    private static final String SUPERPACK_SHARD_MAX_BYTES_PROPERTY = "sharedworld.dev.superpackShardMaxBytes";

    private SyncPathRules() {
    }

    public static long superpackShardMaxBytes() {
        String override = System.getProperty(SUPERPACK_SHARD_MAX_BYTES_PROPERTY, "").trim();
        if (!override.isEmpty()) {
            try {
                long parsed = Long.parseLong(override);
                if (parsed > 0L) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the default; a broken dev override must not
                // change production sharding behavior.
            }
        }
        return SUPERPACK_SHARD_MAX_BYTES;
    }

    /**
     * Splits the non-region file set into capped shard groups, or returns an
     * empty list when the whole set fits in one pack; the single "non-region"
     * superpack then stays wire-identical to pre-0.3.1 clients. Grouping is by
     * top-level directory (root files form one group) with deterministic
     * midpoint splits, so unchanged shards keep their ids and hashes across
     * snapshots and dedupe via alreadyPresent.
     */
    public static List<RegionBundleGroup> groupSuperpackFiles(List<PreparedWorldFile> files) {
        long shardMaxBytes = superpackShardMaxBytes();
        long totalSize = files.stream().mapToLong(PreparedWorldFile::size).sum();
        if (totalSize <= shardMaxBytes) {
            return List.of();
        }
        List<PreparedWorldFile> sorted = files.stream().sorted(Comparator.comparing(PreparedWorldFile::relativePath)).toList();
        java.util.Map<String, List<PreparedWorldFile>> byGroupId = new java.util.TreeMap<>();
        for (PreparedWorldFile file : sorted) {
            byGroupId.computeIfAbsent(SUPERPACK_SHARD_ID_PREFIX + superpackGroupToken(file.relativePath()), ignored -> new ArrayList<>()).add(file);
        }
        List<RegionBundleGroup> groups = new ArrayList<>();
        for (var entry : byGroupId.entrySet()) {
            splitBySize(entry.getKey(), entry.getValue(), shardMaxBytes, groups);
        }
        return groups;
    }

    /**
     * "." names the root-files group; a directory can never relativize to that
     * segment. Sanitized ":" keeps the id grammar unambiguous (grouping happens
     * on the sanitized token, so two directories that sanitize alike simply
     * share a shard).
     */
    private static String superpackGroupToken(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        int slash = normalized.indexOf('/');
        return slash < 0 ? "." : normalized.substring(0, slash).replace(':', '_');
    }

    private static void splitBySize(String groupId, List<PreparedWorldFile> files, long maxBytes, List<RegionBundleGroup> output) {
        long totalSize = files.stream().mapToLong(PreparedWorldFile::size).sum();
        if (files.size() <= 1 || totalSize <= maxBytes) {
            output.add(new RegionBundleGroup(groupId, List.copyOf(files)));
            return;
        }
        int midpoint = (int) Math.ceil(files.size() / 2.0D);
        splitBySize(groupId + ":a", files.subList(0, midpoint), maxBytes, output);
        splitBySize(groupId + ":b", files.subList(midpoint, files.size()), maxBytes, output);
    }

    public static boolean isTerrainRegionFile(String relativePath) {
        return regionFileMatcher(relativePath).matches();
    }

    public static boolean belongsInSuperpack(String relativePath) {
        return !isTerrainRegionFile(relativePath);
    }

    public static String regionBundleId(String relativePath) {
        Matcher matcher = regionFileMatcher(relativePath);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Path " + relativePath + " is not a terrain region file.");
        }
        String prefix = matcher.group(1);
        String directory = prefix == null || prefix.isBlank() ? "region" : prefix + "/region";
        int x = Integer.parseInt(matcher.group(2));
        int z = Integer.parseInt(matcher.group(3));
        int tileX = Math.floorDiv(x - 1, 2) * 2 + 1;
        int tileZ = Math.floorDiv(z - 1, 2) * 2 + 1;
        return "region-bundle:" + directory + ":" + tileX + ":" + tileZ;
    }

    public static List<RegionBundleGroup> groupTerrainFiles(List<PreparedWorldFile> files) {
        files = files.stream().sorted(Comparator.comparing(PreparedWorldFile::relativePath)).toList();
        java.util.Map<String, List<PreparedWorldFile>> byBase = new java.util.TreeMap<>();
        for (PreparedWorldFile file : files) {
            byBase.computeIfAbsent(regionBundleId(file.relativePath()), ignored -> new ArrayList<>()).add(file);
        }
        List<RegionBundleGroup> groups = new ArrayList<>();
        for (var entry : byBase.entrySet()) {
            splitGroup(entry.getKey(), entry.getValue(), groups);
        }
        return groups;
    }

    private static void splitGroup(String bundleId, List<PreparedWorldFile> files, List<RegionBundleGroup> output) {
        long totalSize = files.stream().mapToLong(PreparedWorldFile::size).sum();
        if (files.size() <= 1 || (files.size() <= REGION_BUNDLE_MAX_MEMBERS && totalSize <= REGION_BUNDLE_MAX_BYTES)) {
            output.add(new RegionBundleGroup(bundleId, List.copyOf(files)));
            return;
        }
        if (files.size() == 2) {
            for (PreparedWorldFile file : files) {
                output.add(new RegionBundleGroup(bundleId + ":" + basenameWithoutExtension(file.relativePath()), List.of(file)));
            }
            return;
        }
        int midpoint = (int) Math.ceil(files.size() / 2.0D);
        splitGroup(bundleId + ":a", files.subList(0, midpoint), output);
        splitGroup(bundleId + ":b", files.subList(midpoint, files.size()), output);
    }

    private static String basenameWithoutExtension(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return fileName.endsWith(".mca") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    private static Matcher regionFileMatcher(String relativePath) {
        return REGION_FILE_PATTERN.matcher(relativePath.replace('\\', '/'));
    }

    public record RegionBundleGroup(String bundleId, List<PreparedWorldFile> files) {
    }
}
