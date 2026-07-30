package link.sharedworld.screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import link.sharedworld.sync.WorldCanonicalizer;
import link.sharedworld.versioned.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldExportFlowTest {
    @TempDir
    Path tempDir;

    @Test
    void sanitizeFolderNameStripsIllegalCharactersAndNeverGoesBlank() {
        assertEquals("My_ World_", SharedWorldExportFlow.sanitizeFolderName("My/ World?"));
        assertEquals("SharedWorld Export", SharedWorldExportFlow.sanitizeFolderName("   "));
        assertEquals("SharedWorld Export", SharedWorldExportFlow.sanitizeFolderName(null));
        assertEquals("trailing", SharedWorldExportFlow.sanitizeFolderName("trailing..."));
    }

    @Test
    void uniqueFolderNameDedupesAgainstExistingSaves() throws Exception {
        Path saves = Files.createDirectories(this.tempDir.resolve("saves"));
        assertEquals("World", SharedWorldExportFlow.uniqueFolderName(saves, "World"));
        Files.createDirectories(saves.resolve("World"));
        assertEquals("World (2)", SharedWorldExportFlow.uniqueFolderName(saves, "World"));
        Files.createDirectories(saves.resolve("World (2)"));
        assertEquals("World (3)", SharedWorldExportFlow.uniqueFolderName(saves, "World"));
    }

    @Test
    void copyWorldSkipsLocalOnlyFilesAndPreservesTheRest() throws Exception {
        Path source = Files.createDirectories(this.tempDir.resolve("current"));
        Files.writeString(source.resolve("level.dat"), "level");
        Files.writeString(source.resolve("session.lock"), "lock");
        Files.writeString(source.resolve("level.dat_old"), "old");
        Files.createDirectories(source.resolve("region"));
        Files.writeString(source.resolve("region/r.0.0.mca"), "region");
        Files.createDirectories(source.resolve("playerdata"));
        Files.writeString(source.resolve("playerdata/00000000-0000-0000-0000-000000000002.dat"), "player");

        Path target = this.tempDir.resolve("saves/Exported");
        SharedWorldExportFlow.copyWorld(source, target);

        assertTrue(Files.exists(target.resolve("level.dat")));
        assertTrue(Files.exists(target.resolve("region/r.0.0.mca")));
        assertTrue(Files.exists(target.resolve("playerdata/00000000-0000-0000-0000-000000000002.dat")));
        assertFalse(Files.exists(target.resolve("session.lock")));
        assertFalse(Files.exists(target.resolve("level.dat_old")));
    }

    @Test
    void rewriteLevelNameRenamesTheLevelInPlace() throws Exception {
        Path levelDat = this.tempDir.resolve("level.dat");
        CompoundTag data = new CompoundTag();
        data.putString("LevelName", "Original Save");
        data.putString("generatorName", "default");
        // Hosting locked the managed world's difficulty; the export must unlock it.
        data.putBoolean("DifficultyLocked", true);
        // 26.x owner marker pointing at whoever hosted last; the export must drop it.
        data.putIntArray(WorldCanonicalizer.MODERN_OWNER_UUID_KEY, new int[]{1, 2, 3, 4});
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtCompat.writeCompressed(root, levelDat);

        SharedWorldExportFlow.rewriteLevelName(levelDat, "Exported (2)");

        CompoundTag reread = NbtCompat.readCompressed(levelDat);
        CompoundTag rereadData = NbtCompat.getCompoundOrEmpty(reread, "Data");
        assertEquals("Exported (2)", NbtCompat.getStringOr(rereadData, "LevelName", ""));
        assertEquals("default", NbtCompat.getStringOr(rereadData, "generatorName", ""));
        assertEquals((byte) 0, NbtCompat.getByteOr(rereadData, "DifficultyLocked", (byte) 1));
        assertFalse(rereadData.contains(WorldCanonicalizer.MODERN_OWNER_UUID_KEY));
    }

    @Test
    void exportSyncsBeforeCopyingAndReturnsTheDedupedName() throws Exception {
        Path managed = Files.createDirectories(this.tempDir.resolve("managed/world-1/current"));
        CompoundTag levelData = new CompoundTag();
        levelData.putString("LevelName", "Friends SMP");
        CompoundTag levelRoot = new CompoundTag();
        levelRoot.put("Data", levelData);
        NbtCompat.writeCompressed(levelRoot, managed.resolve("level.dat"));
        Files.writeString(managed.resolve("session.lock"), "lock");
        Path saves = Files.createDirectories(this.tempDir.resolve("saves"));
        Files.createDirectories(saves.resolve("Friends SMP"));

        List<String> calls = new ArrayList<>();
        SharedWorldExportFlow flow = new SharedWorldExportFlow((worldId, uuid, listener) -> {
            calls.add("sync:" + worldId + ":" + uuid);
            return managed;
        }, saves);

        SharedWorldExportFlow.ExportResult result = flow.export(
                "world-1",
                "Friends SMP",
                "00000000-0000-0000-0000-000000000001",
                (label, phase) -> calls.add("progress:" + phase)
        );

        assertEquals(List.of("progress:export_sync", "sync:world-1:00000000-0000-0000-0000-000000000001", "progress:export_copy"), calls);
        assertEquals("Friends SMP (2)", result.folderName());
        assertTrue(Files.exists(saves.resolve("Friends SMP (2)/level.dat")));
        assertFalse(Files.exists(saves.resolve("Friends SMP (2)/session.lock")));
    }
}
