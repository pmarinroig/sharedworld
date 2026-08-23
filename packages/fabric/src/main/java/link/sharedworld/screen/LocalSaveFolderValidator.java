package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;
import link.sharedworld.host.WorldVersionGatePolicy;
import link.sharedworld.versioned.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Validates an arbitrary folder chosen as a world source (create-from-folder
 * and replace-world flows): it must be a readable vanilla save, not saved by a
 * NEWER Minecraft (opening it would corrupt it; same guardrail as hosting),
 * and not one of SharedWorld's own managed world directories (self-import).
 */
final class LocalSaveFolderValidator {
    private LocalSaveFolderValidator() {
    }

    static LocalSaveCatalog.LocalSaveOption validate(Path directory, Path sharedWorldsRoot, int localDataVersion) throws InvalidSaveFolderException {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new InvalidSaveFolderException(SharedWorldText.string("screen.sharedworld.folder_not_a_world"));
        }
        Path levelDat = directory.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            throw new InvalidSaveFolderException(SharedWorldText.string("screen.sharedworld.folder_not_a_world"));
        }
        if (isUnderManagedRoot(directory, sharedWorldsRoot)) {
            throw new InvalidSaveFolderException(SharedWorldText.string("screen.sharedworld.folder_is_managed"));
        }

        CompoundTag data;
        try {
            data = NbtCompat.getCompoundOrEmpty(NbtCompat.readCompressed(levelDat), "Data");
        } catch (IOException | RuntimeException exception) {
            throw new InvalidSaveFolderException(SharedWorldText.string("screen.sharedworld.folder_not_a_world"));
        }
        int dataVersion = NbtCompat.getIntOr(data, "DataVersion", 0);
        if (WorldVersionGatePolicy.decideHost(dataVersion == 0 ? null : dataVersion, localDataVersion)
                == WorldVersionGatePolicy.HostDecision.BLOCK_SNAPSHOT_NEWER) {
            throw new InvalidSaveFolderException(SharedWorldText.string("screen.sharedworld.folder_newer_minecraft"));
        }

        String folderName = directory.getFileName() == null ? "world" : directory.getFileName().toString();
        String levelName = NbtCompat.getStringOr(data, "LevelName", "");
        String displayName = levelName.isBlank() ? folderName : levelName;
        Path iconPath = Files.isRegularFile(directory.resolve("icon.png")) ? directory.resolve("icon.png") : null;
        long lastModified;
        try {
            lastModified = Files.getLastModifiedTime(levelDat).toMillis();
        } catch (IOException exception) {
            lastModified = -1L;
        }
        return new LocalSaveCatalog.LocalSaveOption(
                folderName,
                displayName,
                directory,
                lastModified,
                iconPath,
                Component.literal(directory.toString())
        );
    }

    private static boolean isUnderManagedRoot(Path directory, Path sharedWorldsRoot) {
        if (sharedWorldsRoot == null) {
            return false;
        }
        try {
            Path realDirectory = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realRoot = Files.exists(sharedWorldsRoot) ? sharedWorldsRoot.toRealPath(LinkOption.NOFOLLOW_LINKS) : sharedWorldsRoot.toAbsolutePath().normalize();
            return realDirectory.startsWith(realRoot);
        } catch (IOException exception) {
            return directory.toAbsolutePath().normalize().startsWith(sharedWorldsRoot.toAbsolutePath().normalize());
        }
    }

    static final class InvalidSaveFolderException extends Exception {
        InvalidSaveFolderException(String message) {
            super(message);
        }
    }
}
