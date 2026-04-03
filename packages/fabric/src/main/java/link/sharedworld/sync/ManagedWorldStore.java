package link.sharedworld.sync;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

public final class ManagedWorldStore {
    public static final String LEVEL_ID = "current";
    private final Path sharedWorldRoot;

    public ManagedWorldStore() {
        this(Minecraft.getInstance().gameDirectory.toPath().resolve("sharedworld").resolve("worlds"));
    }

    public ManagedWorldStore(Path sharedWorldRoot) {
        this.sharedWorldRoot = sharedWorldRoot;
    }

    public Path worldContainer(String worldId) {
        return this.sharedWorldRoot.resolve(worldId);
    }

    public Path workingCopy(String worldId) {
        return this.worldContainer(worldId).resolve(LEVEL_ID);
    }

    public Path stagingRoot(String worldId) {
        return this.worldContainer(worldId).resolve("staging");
    }

    public Path regionBaselineRoot(String worldId) {
        return this.worldContainer(worldId).resolve("region-baseline");
    }

    public Path regionBundleBaselineFile(String worldId, String bundleId) {
        return this.regionBaselineRoot(worldId).resolve(sanitizeBundleId(bundleId) + ".bundle");
    }

    public Path packBaselineFile(String worldId) {
        return this.worldContainer(worldId).resolve("non-region-pack-baseline.pack");
    }

    public Path regionBaselineSnapshotFile(String worldId) {
        return this.worldContainer(worldId).resolve("region-baseline-snapshot.txt");
    }

    public Path packBaselineSnapshotFile(String worldId) {
        return this.worldContainer(worldId).resolve("non-region-pack-baseline-snapshot.txt");
    }

    public LevelStorageSource levelSource(String worldId) {
        return LevelStorageSource.createDefault(this.worldContainer(worldId));
    }

    public void ensureWorldContainer(String worldId) throws IOException {
        Files.createDirectories(this.worldContainer(worldId));
    }

    public void resetWorkingCopy(String worldId) throws IOException {
        Path workingCopy = this.workingCopy(worldId);
        if (Files.exists(workingCopy)) {
            deleteRecursively(workingCopy);
        }
        clearRegionBaseline(worldId);
        clearPackBaseline(worldId);
        Files.createDirectories(this.worldContainer(worldId));
    }

    public Path createSnapshotStagingCopy(String worldId) throws IOException {
        Path workingCopy = this.workingCopy(worldId);
        Path stagingDirectory = this.stagingRoot(worldId).resolve("snapshot-" + System.currentTimeMillis());
        return createSnapshotStagingCopy(
                workingCopy,
                stagingDirectory,
                (sourceRoot, targetRoot) -> copyTree(sourceRoot, targetRoot, true),
                ManagedWorldStore::deleteRecursivelyIfExists
        );
    }

    public void deleteSnapshotStagingCopy(Path stagingDirectory) throws IOException {
        if (!Files.exists(stagingDirectory)) {
            return;
        }
        deleteRecursively(stagingDirectory);
    }

    public void replaceWorkingCopyWithSnapshot(String worldId, Path snapshotDirectory) throws IOException {
        Path workingCopy = this.workingCopy(worldId);
        if (Files.exists(workingCopy)) {
            deleteRecursively(workingCopy);
        }
        Files.createDirectories(workingCopy);
        copyTree(snapshotDirectory, workingCopy, false);
    }

    public String regionBaselineSnapshotId(String worldId) throws IOException {
        Path marker = this.regionBaselineSnapshotFile(worldId);
        if (!Files.exists(marker)) {
            return null;
        }
        String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
        return value.isBlank() ? null : value;
    }

    public void replaceRegionBaselines(String worldId, java.util.Map<String, Path> bundleFiles, String snapshotId) throws IOException {
        Path baselineRoot = this.regionBaselineRoot(worldId);
        if (Files.exists(baselineRoot)) {
            deleteRecursively(baselineRoot);
        }
        updateRegionBaselines(worldId, bundleFiles, snapshotId);
    }

    public void updateRegionBaselines(String worldId, java.util.Map<String, Path> bundleFiles, String snapshotId) throws IOException {
        Path baselineRoot = this.regionBaselineRoot(worldId);
        Files.createDirectories(baselineRoot);
        for (var entry : bundleFiles.entrySet()) {
            Path target = regionBundleBaselineFile(worldId, entry.getKey());
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(entry.getValue(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Files.writeString(this.regionBaselineSnapshotFile(worldId), snapshotId == null ? "" : snapshotId, StandardCharsets.UTF_8);
    }

    public String packBaselineSnapshotId(String worldId) throws IOException {
        Path marker = this.packBaselineSnapshotFile(worldId);
        if (!Files.exists(marker)) {
            return null;
        }
        String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
        return value.isBlank() ? null : value;
    }

    public void refreshPackBaseline(String worldId, Path packFile, String snapshotId) throws IOException {
        Path baselineFile = this.packBaselineFile(worldId);
        if (baselineFile.getParent() != null) {
            Files.createDirectories(baselineFile.getParent());
        }
        Files.copy(packFile, baselineFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.writeString(this.packBaselineSnapshotFile(worldId), snapshotId == null ? "" : snapshotId, StandardCharsets.UTF_8);
    }

    public void clearRegionBaseline(String worldId) throws IOException {
        Path baselineRoot = this.regionBaselineRoot(worldId);
        if (Files.exists(baselineRoot)) {
            deleteRecursively(baselineRoot);
        }
        Files.deleteIfExists(this.regionBaselineSnapshotFile(worldId));
    }

    private static String sanitizeBundleId(String bundleId) {
        return bundleId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public void clearPackBaseline(String worldId) throws IOException {
        Files.deleteIfExists(this.packBaselineFile(worldId));
        Files.deleteIfExists(this.packBaselineSnapshotFile(worldId));
    }

    static Path createSnapshotStagingCopy(
            Path workingCopy,
            Path stagingDirectory,
            SnapshotTreeCopyOperation copyOperation,
            SnapshotCleanupOperation cleanupOperation
    ) throws IOException {
        Files.createDirectories(stagingDirectory);
        try {
            copyOperation.copy(workingCopy, stagingDirectory);
            return stagingDirectory;
        } catch (IOException copyException) {
            cleanupFailedSnapshotCopy(stagingDirectory, cleanupOperation, copyException);
            throw copyException;
        } catch (RuntimeException | Error copyFailure) {
            cleanupFailedSnapshotCopy(stagingDirectory, cleanupOperation, copyFailure);
            throw copyFailure;
        }
    }

    private static void cleanupFailedSnapshotCopy(
            Path stagingDirectory,
            SnapshotCleanupOperation cleanupOperation,
            Throwable copyFailure
    ) {
        try {
            cleanupOperation.cleanup(stagingDirectory);
        } catch (IOException cleanupException) {
            copyFailure.addSuppressed(cleanupException);
        }
    }

    private static void copyTree(Path sourceRoot, Path targetRoot, boolean skipSessionLock) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            for (Path source : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = sourceRoot.relativize(source);
                if (relative.toString().isBlank()) {
                    continue;
                }
                if (skipSessionLock && "session.lock".equals(source.getFileName().toString())) {
                    continue;
                }
                Path target = targetRoot.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                    continue;
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void deleteRecursivelyIfExists(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        deleteRecursively(root);
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    interface SnapshotTreeCopyOperation {
        void copy(Path sourceRoot, Path targetRoot) throws IOException;
    }

    @FunctionalInterface
    interface SnapshotCleanupOperation {
        void cleanup(Path stagingDirectory) throws IOException;
    }
}
