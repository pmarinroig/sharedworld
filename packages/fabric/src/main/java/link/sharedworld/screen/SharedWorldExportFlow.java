package link.sharedworld.screen;

import link.sharedworld.sync.WorldCanonicalizer;
import link.sharedworld.sync.WorldSyncProgressListener;
import link.sharedworld.versioned.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Exports a shared world into the vanilla saves folder as a normal singleplayer
 * save: sync the working copy to the latest snapshot (host path, so the
 * exporting owner's playerdata is materialized back into level.dat), copy it
 * out minus local-only files, and rename the level to match its new folder.
 */
final class SharedWorldExportFlow {
    private final SyncAccess syncAccess;
    private final Path savesDirectory;

    SharedWorldExportFlow(SyncAccess syncAccess, Path savesDirectory) {
        this.syncAccess = syncAccess;
        this.savesDirectory = savesDirectory;
    }

    ExportResult export(String worldId, String worldName, String playerUuid, ProgressSink progressSink) throws Exception {
        progressSink.updateIndeterminate(Component.translatable("screen.sharedworld.export_progress_syncing"), "export_sync");
        Path worldDirectory = this.syncAccess.ensureSynchronizedWorkingCopy(worldId, playerUuid, progress -> {
        });

        String folderName = uniqueFolderName(this.savesDirectory, sanitizeFolderName(worldName));
        Path target = this.savesDirectory.resolve(folderName);
        try {
            progressSink.updateIndeterminate(Component.translatable("screen.sharedworld.export_progress_copying"), "export_copy");
            copyWorld(worldDirectory, target);
            rewriteLevelName(target.resolve("level.dat"), folderName);
        } catch (Exception exception) {
            deleteRecursivelyQuietly(target);
            throw exception;
        }
        return new ExportResult(folderName, target);
    }

    static String sanitizeFolderName(String worldName) {
        String cleaned = worldName == null ? "" : worldName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        // Windows also refuses names ending in dots.
        cleaned = cleaned.replaceAll("\\.+$", "").trim();
        return cleaned.isBlank() ? "SharedWorld Export" : cleaned;
    }

    static String uniqueFolderName(Path savesDirectory, String base) {
        if (!Files.exists(savesDirectory.resolve(base))) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + " (" + suffix + ")";
            if (!Files.exists(savesDirectory.resolve(candidate))) {
                return candidate;
            }
        }
    }

    static void copyWorld(Path source, Path target) throws IOException {
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(source)) {
            paths = stream.sorted(Comparator.naturalOrder()).toList();
        }
        Files.createDirectories(target);
        for (Path path : paths) {
            Path relative = source.relativize(path);
            if (relative.toString().isBlank()) {
                continue;
            }
            if (Files.isRegularFile(path) && WorldCanonicalizer.isLocalOnlyFileName(path.getFileName().toString())) {
                continue;
            }
            Path destination = target.resolve(relative.toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
                continue;
            }
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Vanilla's world list shows {@code Data.LevelName}, not the folder name;
     * without the rewrite the export would masquerade as the original save.
     */
    static void rewriteLevelName(Path levelDat, String levelName) throws IOException {
        if (!Files.exists(levelDat)) {
            return;
        }
        CompoundTag levelTag = NbtCompat.readCompressed(levelDat);
        CompoundTag dataTag = NbtCompat.getCompoundOrEmpty(levelTag, "Data").copy();
        dataTag.putString("LevelName", levelName);
        levelTag.put("Data", dataTag);
        NbtCompat.writeCompressed(levelTag, levelDat);
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    record ExportResult(String folderName, Path targetDirectory) {
    }

    interface SyncAccess {
        Path ensureSynchronizedWorkingCopy(String worldId, String hostPlayerUuid, WorldSyncProgressListener progressListener)
                throws IOException, InterruptedException;
    }

    interface ProgressSink {
        void updateIndeterminate(Component label, String phase);
    }
}
