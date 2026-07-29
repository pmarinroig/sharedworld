package link.sharedworld.screen;

import java.nio.file.Files;
import java.nio.file.Path;

import link.sharedworld.versioned.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LocalSaveFolderValidatorTest {
    private static final int LOCAL_DATA_VERSION = 4000;

    @TempDir
    Path tempDir;

    @Test
    void aValidSaveResolvesLevelNameAndIcon() throws Exception {
        Path world = worldDir("My World", 3900);
        Files.writeString(world.resolve("icon.png"), "png");

        LocalSaveCatalog.LocalSaveOption option =
                LocalSaveFolderValidator.validate(world, this.tempDir.resolve("sharedworld/worlds"), LOCAL_DATA_VERSION);

        assertEquals("My World", option.displayName());
        assertEquals(world, option.directory());
        assertEquals(world.resolve("icon.png"), option.iconPath());
    }

    @Test
    void aSaveWithoutLevelNameFallsBackToTheFolderName() throws Exception {
        Path world = worldDir(null, 3900);

        LocalSaveCatalog.LocalSaveOption option =
                LocalSaveFolderValidator.validate(world, this.tempDir.resolve("sharedworld/worlds"), LOCAL_DATA_VERSION);

        assertEquals(world.getFileName().toString(), option.displayName());
        assertNull(option.iconPath());
    }

    @Test
    void foldersWithoutLevelDatAreRejected() throws Exception {
        Path notAWorld = Files.createDirectories(this.tempDir.resolve("downloads"));
        assertThrows(LocalSaveFolderValidator.InvalidSaveFolderException.class,
                () -> LocalSaveFolderValidator.validate(notAWorld, this.tempDir.resolve("sharedworld/worlds"), LOCAL_DATA_VERSION));
        assertThrows(LocalSaveFolderValidator.InvalidSaveFolderException.class,
                () -> LocalSaveFolderValidator.validate(this.tempDir.resolve("missing"), this.tempDir.resolve("sharedworld/worlds"), LOCAL_DATA_VERSION));
    }

    @Test
    void savesFromNewerMinecraftAreRejected() throws Exception {
        Path world = worldDir("Future World", LOCAL_DATA_VERSION + 1);
        assertThrows(LocalSaveFolderValidator.InvalidSaveFolderException.class,
                () -> LocalSaveFolderValidator.validate(world, this.tempDir.resolve("sharedworld/worlds"), LOCAL_DATA_VERSION));
    }

    @Test
    void savesWithoutDataVersionAreAllowed() throws Exception {
        Path world = worldDir("Ancient World", 0);
        LocalSaveCatalog.LocalSaveOption option =
                LocalSaveFolderValidator.validate(world, this.tempDir.resolve("sharedworld/worlds"), LOCAL_DATA_VERSION);
        assertEquals("Ancient World", option.displayName());
    }

    @Test
    void sharedWorldManagedDirectoriesAreRejectedAsSelfImport() throws Exception {
        Path managedRoot = this.tempDir.resolve("sharedworld/worlds");
        Path managedCurrent = Files.createDirectories(managedRoot.resolve("world-1/current"));
        CompoundTag data = new CompoundTag();
        data.putString("LevelName", "Managed");
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtCompat.writeCompressed(root, managedCurrent.resolve("level.dat"));

        assertThrows(LocalSaveFolderValidator.InvalidSaveFolderException.class,
                () -> LocalSaveFolderValidator.validate(managedCurrent, managedRoot, LOCAL_DATA_VERSION));
    }

    @Test
    void corruptLevelDatIsRejected() throws Exception {
        Path world = Files.createDirectories(this.tempDir.resolve("corrupt"));
        Files.writeString(world.resolve("level.dat"), "not nbt");
        assertThrows(LocalSaveFolderValidator.InvalidSaveFolderException.class,
                () -> LocalSaveFolderValidator.validate(world, this.tempDir.resolve("sharedworld/worlds"), LOCAL_DATA_VERSION));
    }

    private Path worldDir(String levelName, int dataVersion) throws Exception {
        String folder = levelName == null ? "unnamed-" + dataVersion : levelName.replace(' ', '-').toLowerCase();
        Path world = Files.createDirectories(this.tempDir.resolve(folder));
        CompoundTag data = new CompoundTag();
        if (levelName != null) {
            data.putString("LevelName", levelName);
        }
        if (dataVersion > 0) {
            data.putInt("DataVersion", dataVersion);
        }
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtCompat.writeCompressed(root, world.resolve("level.dat"));
        return world;
    }
}
