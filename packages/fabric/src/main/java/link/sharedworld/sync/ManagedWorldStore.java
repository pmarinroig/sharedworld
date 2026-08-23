package link.sharedworld.sync;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class ManagedWorldStore {
    public static final String LEVEL_ID = "current";
    private static final Gson BASELINE_GSON = new Gson();
    private static final TypeToken<Map<String, String>> BASELINE_HASHES_TYPE = new TypeToken<>() {
    };
    private final Path sharedWorldRoot;

    public ManagedWorldStore() {
        this(Minecraft.getInstance().gameDirectory.toPath().resolve("sharedworld").resolve("worlds"));
    }

    public ManagedWorldStore(Path sharedWorldRoot) {
        this.sharedWorldRoot = sharedWorldRoot;
    }

    public Path root() {
        return this.sharedWorldRoot;
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

    public Path scanCacheFile(String worldId) {
        return this.worldContainer(worldId).resolve("sync-cache-v1.json");
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

    /**
     * Deletes transient sync artifacts a crashed or killed client left behind:
     * staging copies, extract directories, and partial download temps. Working
     * copies, baselines, and baseline markers are never touched.
     */
    public void pruneTransientArtifacts() {
        if (!Files.isDirectory(this.sharedWorldRoot)) {
            return;
        }
        try (Stream<Path> worlds = Files.list(this.sharedWorldRoot)) {
            for (Path worldContainer : worlds.filter(Files::isDirectory).toList()) {
                pruneWorldTransientArtifacts(worldContainer);
            }
        } catch (IOException exception) {
            // Best effort: pruning must never block startup.
        }
    }

    private static void pruneWorldTransientArtifacts(Path worldContainer) {
        deleteQuietly(worldContainer.resolve("staging"));
        try (Stream<Path> entries = Files.list(worldContainer)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith("pack-extract-")
                        || name.startsWith("region-bundle-extract-")
                        || (name.startsWith("pack-artifact-") && name.endsWith(".part"))
                        || (name.startsWith("pack-patched-") && name.endsWith(".pack"))
                        // Resumable-download partials; the temps they resume
                        // onto are per-attempt, so a crash orphans them.
                        || name.endsWith(".swpart")) {
                    deleteQuietly(entry);
                }
            }
        } catch (IOException exception) {
            // Best effort.
        }
        Path workingCopy = worldContainer.resolve(LEVEL_ID);
        if (Files.isDirectory(workingCopy)) {
            try (Stream<Path> stream = Files.walk(workingCopy)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    String name = path.getFileName().toString();
                    if ((name.contains(".artifact.") && name.endsWith(".part")) || name.endsWith(".swpart")) {
                        deleteQuietly(path);
                    }
                }
            } catch (IOException exception) {
                // Best effort.
            }
        }
    }

    private static void deleteQuietly(Path root) {
        try {
            deleteRecursivelyIfExists(root);
        } catch (IOException exception) {
            // Best effort.
        }
    }

    public void resetWorkingCopy(String worldId) throws IOException {
        Path workingCopy = this.workingCopy(worldId);
        if (Files.exists(workingCopy)) {
            deleteRecursively(workingCopy);
        }
        clearRegionBaseline(worldId);
        clearPackBaseline(worldId);
        Files.deleteIfExists(this.scanCacheFile(worldId));
        deleteRecursivelyIfExists(this.captureMirrorRoot(worldId));
        clearLocalChanges(worldId);
        Files.createDirectories(this.worldContainer(worldId));
    }

    // ---------------------------------------------------------- local changes

    /**
     * Sidecar saying "this working copy has been hosted since it last matched
     * a published snapshot": written when hosting opens the world, cleared
     * once the session's final upload lands. While it exists, the working
     * copy may hold progress that no backup has, so a later host start must
     * publish it (or ask) before the download sync is allowed to overwrite
     * it. Lives in the world container; never inside the working copy,
     * which is scanned and uploaded whole, and outside the mod's config
     * directory, so it survives reinstalls and "delete the config" advice.
     */
    public record LocalChangesMarker(String hostPlayerUuid, String since) {
    }

    private static final String LOCAL_CHANGES_FILE = "local-changes.json";

    public Path localChangesFile(String worldId) {
        return this.worldContainer(worldId).resolve(LOCAL_CHANGES_FILE);
    }

    /** The marker, or null when absent or unreadable (unreadable degrades to "no claim", never to a failure). */
    public LocalChangesMarker localChanges(String worldId) {
        Path file = this.localChangesFile(worldId);
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            LocalChangesMarker parsed = BASELINE_GSON.fromJson(reader, LocalChangesMarker.class);
            return parsed == null || parsed.hostPlayerUuid() == null ? null : parsed;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /** Idempotent: an existing marker keeps its original {@code since}. */
    public void markLocalChanges(String worldId, String hostPlayerUuid, String since) throws IOException {
        if (this.localChanges(worldId) != null) {
            return;
        }
        Path file = this.localChangesFile(worldId);
        Files.createDirectories(file.getParent());
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
            BASELINE_GSON.toJson(new LocalChangesMarker(hostPlayerUuid, since), LocalChangesMarker.class, writer);
        }
        moveAtomicallyOrReplace(tempFile, file);
    }

    public void clearLocalChanges(String worldId) throws IOException {
        Files.deleteIfExists(this.localChangesFile(worldId));
    }

    /**
     * The snapshot the working copy last converged on (upload or download):
     * the pack marker and the region marker are written together on every
     * sync, so either one answers; both null means never synced.
     */
    public String baselineSnapshotId(String worldId) throws IOException {
        String packSnapshot = this.packBaselineSnapshotId(worldId);
        return packSnapshot != null ? packSnapshot : this.regionBaselineSnapshotId(worldId);
    }

    /** Read-only view of the baseline sidecar: pack/bundle id → hash at the last sync. */
    public Map<String, String> baselineHashes(String worldId) {
        return Map.copyOf(loadBaselineHashes(worldId));
    }

    static final String CAPTURE_MIRROR_DIR = "capture-mirror";

    public Path captureMirrorRoot(String worldId) {
        return this.worldContainer(worldId).resolve(CAPTURE_MIRROR_DIR);
    }

    /**
     * Captures the working copy into a persistent per-world mirror refreshed
     * rsync-style: only files whose (size, mtime) differ from the mirror are
     * copied, files that vanished are deleted. A no-change capture is a stat
     * walk instead of a full world copy. Runs inside the autosave window (the
     * working copy is quiescent), and a crash mid-refresh just means the next
     * capture re-syncs by the same comparison before anything is uploaded.
     */
    public Path createSnapshotStagingCopy(String worldId) throws IOException {
        Path workingCopy = this.workingCopy(worldId);
        Path mirror = this.captureMirrorRoot(worldId);
        Files.createDirectories(mirror);

        Set<Path> desiredRelatives = new HashSet<>();
        try (Stream<Path> stream = Files.walk(workingCopy)) {
            for (Path source : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = workingCopy.relativize(source);
                if (relative.toString().isBlank() || "session.lock".equals(source.getFileName().toString())) {
                    continue;
                }
                Path target = mirror.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                    continue;
                }
                desiredRelatives.add(relative);
                if (mirrorEntryMatches(source, target)) {
                    continue;
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }

        try (Stream<Path> stream = Files.walk(mirror)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Path relative = mirror.relativize(path);
                if (relative.toString().isBlank()) {
                    continue;
                }
                if (Files.isDirectory(path)) {
                    try (Stream<Path> children = Files.list(path)) {
                        if (children.findAny().isEmpty()) {
                            Files.deleteIfExists(path);
                        }
                    }
                    continue;
                }
                if (!desiredRelatives.contains(relative)) {
                    Files.deleteIfExists(path);
                }
            }
        }
        return mirror;
    }

    private static boolean mirrorEntryMatches(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        return Files.size(source) == Files.size(target)
                && Files.getLastModifiedTime(source).toMillis() == Files.getLastModifiedTime(target).toMillis();
    }

    /**
     * The capture mirror is persistent by design; it is what makes the next
     * capture incremental, so the post-upload cleanup hook leaves it alone
     * while still disposing of any legacy one-shot staging directory.
     */
    public void deleteSnapshotStagingCopy(Path stagingDirectory) throws IOException {
        if (CAPTURE_MIRROR_DIR.equals(stagingDirectory.getFileName().toString())) {
            return;
        }
        if (!Files.exists(stagingDirectory)) {
            return;
        }
        deleteRecursively(stagingDirectory);
    }

    public String regionBaselineSnapshotId(String worldId) throws IOException {
        Path marker = this.regionBaselineSnapshotFile(worldId);
        if (!Files.exists(marker)) {
            return null;
        }
        String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
        return value.isBlank() ? null : value;
    }

    /**
     * Converges the baseline set on exactly {@code desiredHashesById}: files
     * whose sidecar hash already matches are kept untouched, changed or
     * missing ones are copied from {@code bodies} (invoked only for those),
     * and baselines for packs that no longer exist are deleted. A no-change
     * sync therefore performs zero baseline I/O beyond the marker write.
     *
     * <p>The sidecar is advisory: baseline consumers hash the real file before
     * trusting it as a delta base, so a stale sidecar entry can only cost a
     * needless copy or a fallback to a full transfer.
     */
    public void ensureRegionBaselines(String worldId, Map<String, String> desiredHashesById, BaselineBodySupplier bodies, String snapshotId) throws IOException {
        Path baselineRoot = this.regionBaselineRoot(worldId);
        Files.createDirectories(baselineRoot);
        Map<String, String> sidecar = loadBaselineHashes(worldId);
        Set<String> expectedFileNames = new HashSet<>();
        for (var entry : desiredHashesById.entrySet()) {
            Path target = regionBundleBaselineFile(worldId, entry.getKey());
            expectedFileNames.add(target.getFileName().toString());
            if (entry.getValue().equals(sidecar.get(entry.getKey())) && Files.exists(target)) {
                continue;
            }
            copyAtomically(bodies.body(entry.getKey()), target);
            sidecar.put(entry.getKey(), entry.getValue());
        }
        try (Stream<Path> entries = Files.list(baselineRoot)) {
            for (Path staleFile : entries.filter(Files::isRegularFile).toList()) {
                if (!expectedFileNames.contains(staleFile.getFileName().toString())) {
                    Files.deleteIfExists(staleFile);
                }
            }
        }
        sidecar.keySet().removeIf(packId -> packId.startsWith("region-bundle:") && !desiredHashesById.containsKey(packId));
        saveBaselineHashes(worldId, sidecar);
        Files.writeString(this.regionBaselineSnapshotFile(worldId), snapshotId == null ? "" : snapshotId, StandardCharsets.UTF_8);
    }

    /**
     * Partial refresh after a download apply: only the bundles that were
     * actually downloaded move forward; untouched baselines stay valid for the
     * packs the plan retained.
     */
    public void updateRegionBaselines(String worldId, Map<String, Path> bundleFiles, Map<String, String> hashesById, String snapshotId) throws IOException {
        Path baselineRoot = this.regionBaselineRoot(worldId);
        Files.createDirectories(baselineRoot);
        Map<String, String> sidecar = loadBaselineHashes(worldId);
        for (var entry : bundleFiles.entrySet()) {
            Path target = regionBundleBaselineFile(worldId, entry.getKey());
            copyAtomically(entry.getValue(), target);
            String hash = hashesById.get(entry.getKey());
            if (hash != null) {
                sidecar.put(entry.getKey(), hash);
            } else {
                sidecar.remove(entry.getKey());
            }
        }
        saveBaselineHashes(worldId, sidecar);
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

    /** Skip-if-unchanged counterpart of {@link #ensureRegionBaselines} for the single non-region pack. */
    public void ensurePackBaseline(String worldId, String desiredHash, BaselineBodySupplier body, String snapshotId) throws IOException {
        Path baselineFile = this.packBaselineFile(worldId);
        Map<String, String> sidecar = loadBaselineHashes(worldId);
        if (!desiredHash.equals(sidecar.get(SharedWorldPack.PACK_ID)) || !Files.exists(baselineFile)) {
            if (baselineFile.getParent() != null) {
                Files.createDirectories(baselineFile.getParent());
            }
            copyAtomically(body.body(SharedWorldPack.PACK_ID), baselineFile);
            sidecar.put(SharedWorldPack.PACK_ID, desiredHash);
            saveBaselineHashes(worldId, sidecar);
        }
        Files.writeString(this.packBaselineSnapshotFile(worldId), snapshotId == null ? "" : snapshotId, StandardCharsets.UTF_8);
    }

    public void refreshPackBaseline(String worldId, Path packFile, String packHash, String snapshotId) throws IOException {
        Path baselineFile = this.packBaselineFile(worldId);
        if (baselineFile.getParent() != null) {
            Files.createDirectories(baselineFile.getParent());
        }
        copyAtomically(packFile, baselineFile);
        Map<String, String> sidecar = loadBaselineHashes(worldId);
        if (packHash != null) {
            sidecar.put(SharedWorldPack.PACK_ID, packHash);
        } else {
            sidecar.remove(SharedWorldPack.PACK_ID);
        }
        saveBaselineHashes(worldId, sidecar);
        Files.writeString(this.packBaselineSnapshotFile(worldId), snapshotId == null ? "" : snapshotId, StandardCharsets.UTF_8);
    }

    public void clearRegionBaseline(String worldId) throws IOException {
        Path baselineRoot = this.regionBaselineRoot(worldId);
        if (Files.exists(baselineRoot)) {
            deleteRecursively(baselineRoot);
        }
        Files.deleteIfExists(this.regionBaselineSnapshotFile(worldId));
        Map<String, String> sidecar = loadBaselineHashes(worldId);
        if (sidecar.keySet().removeIf(packId -> packId.startsWith("region-bundle:"))) {
            saveBaselineHashes(worldId, sidecar);
        }
    }

    private static String sanitizeBundleId(String bundleId) {
        return bundleId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public void clearPackBaseline(String worldId) throws IOException {
        Files.deleteIfExists(this.packBaselineFile(worldId));
        Files.deleteIfExists(this.packBaselineSnapshotFile(worldId));
        Map<String, String> sidecar = loadBaselineHashes(worldId);
        if (sidecar.remove(SharedWorldPack.PACK_ID) != null) {
            saveBaselineHashes(worldId, sidecar);
        }
    }

    public Path baselineHashesFile(String worldId) {
        return this.worldContainer(worldId).resolve("baseline-hashes.json");
    }

    /** Corrupt or missing sidecar degrades to "copy everything again", never to a failed sync. */
    private Map<String, String> loadBaselineHashes(String worldId) {
        Path sidecarFile = this.baselineHashesFile(worldId);
        if (!Files.exists(sidecarFile)) {
            return new HashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(sidecarFile, StandardCharsets.UTF_8)) {
            Map<String, String> parsed = BASELINE_GSON.fromJson(reader, BASELINE_HASHES_TYPE);
            return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
        } catch (IOException | RuntimeException exception) {
            return new HashMap<>();
        }
    }

    private void saveBaselineHashes(String worldId, Map<String, String> hashes) throws IOException {
        Path sidecarFile = this.baselineHashesFile(worldId);
        if (sidecarFile.getParent() != null) {
            Files.createDirectories(sidecarFile.getParent());
        }
        Path tempFile = sidecarFile.resolveSibling(sidecarFile.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
            BASELINE_GSON.toJson(hashes, BASELINE_HASHES_TYPE.getType(), writer);
        }
        moveAtomicallyOrReplace(tempFile, sidecarFile);
    }

    /**
     * Baseline files feed delta uploads, so a torn copy must be impossible: a
     * crash leaves either the old baseline or the new one, never a mix.
     */
    private static void copyAtomically(Path source, Path target) throws IOException {
        Path tempFile = target.resolveSibling(target.getFileName() + ".tmp");
        Files.copy(source, tempFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        moveAtomicallyOrReplace(tempFile, target);
    }

    private static void moveAtomicallyOrReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @FunctionalInterface
    public interface BaselineBodySupplier {
        Path body(String packId) throws IOException;
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

}
