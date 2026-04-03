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

    private SyncPathRules() {
    }

    public static boolean isMcaFile(String relativePath) {
        return relativePath.toLowerCase(Locale.ROOT).endsWith(".mca");
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
