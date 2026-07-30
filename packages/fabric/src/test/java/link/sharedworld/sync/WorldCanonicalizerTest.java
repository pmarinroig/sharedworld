package link.sharedworld.sync;

import link.sharedworld.versioned.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldCanonicalizerTest {
    private static final String HOST_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String GUEST_UUID = "22222222-2222-2222-2222-222222222222";

    @TempDir
    Path tempDir;

    @Test
    void materializeHostPlayerPreservesWorldDataAcrossHandoff() throws Exception {
        Path source = this.tempDir.resolve("source");
        Files.createDirectories(source.resolve("playerdata"));

        CompoundTag hostPlayer = new CompoundTag();
        hostPlayer.putString("SharedWorldPlayerMarker", "host-a");

        CompoundTag guestPlayer = new CompoundTag();
        guestPlayer.putString("SharedWorldPlayerMarker", "guest-b");

        CompoundTag data = new CompoundTag();
        data.putString("LevelName", "Handoff Regression");
        data.putLong("RandomSeed", 424242L);
        data.putString("SharedWorldStableMarker", "stone-arch");
        data.put("Player", hostPlayer);

        CompoundTag level = new CompoundTag();
        level.put("Data", data);
        NbtCompat.writeCompressed(level, source.resolve("level.dat"));
        NbtCompat.writeCompressed(guestPlayer, source.resolve("playerdata").resolve(GUEST_UUID + ".dat"));

        List<PreparedWorldFile> canonicalFiles = WorldCanonicalizer.scanCanonical(source, HOST_UUID);
        Path canonical = this.tempDir.resolve("canonical");
        writePreparedFiles(canonicalFiles, canonical);

        CompoundTag canonicalLevel = NbtCompat.readCompressed(canonical.resolve("level.dat"));
        assertFalse(NbtCompat.getCompoundOrEmpty(canonicalLevel, "Data").contains("Player"));

        WorldCanonicalizer.materializeHostPlayer(canonical, GUEST_UUID);

        CompoundTag materialized = NbtCompat.readCompressed(canonical.resolve("level.dat"));
        CompoundTag materializedData = NbtCompat.getCompoundOrEmpty(materialized, "Data");

        assertEquals("Handoff Regression", NbtCompat.getStringOr(materializedData, "LevelName", ""));
        assertEquals(424242L, NbtCompat.getLongOr(materializedData, "RandomSeed", 0L));
        assertEquals("stone-arch", NbtCompat.getStringOr(materializedData, "SharedWorldStableMarker", ""));
        assertEquals("guest-b", NbtCompat.getStringOr(NbtCompat.getCompoundOrEmpty(materializedData, "Player"), "SharedWorldPlayerMarker", ""));
        assertFalse(Files.exists(canonical.resolve("playerdata").resolve(GUEST_UUID + ".dat")));
        assertTrue(Files.exists(canonical.resolve("playerdata").resolve(HOST_UUID + ".dat")));
    }

    @Test
    void uploadStripsModernOwnerUuidAndPassesPlayerFilesThrough() throws Exception {
        Path source = this.tempDir.resolve("source");
        Files.createDirectories(source.resolve("players").resolve("data"));

        CompoundTag hostPlayer = new CompoundTag();
        hostPlayer.putString("SharedWorldPlayerMarker", "host-a");

        CompoundTag data = new CompoundTag();
        data.putString("LevelName", "Modern Handoff");
        data.putIntArray(WorldCanonicalizer.MODERN_OWNER_UUID_KEY, new int[]{1, 2, 3, 4});

        CompoundTag level = new CompoundTag();
        level.put("Data", data);
        NbtCompat.writeCompressed(level, source.resolve("level.dat"));
        NbtCompat.writeCompressed(hostPlayer, source.resolve("players").resolve("data").resolve(HOST_UUID + ".dat"));

        List<PreparedWorldFile> canonicalFiles = WorldCanonicalizer.scanCanonical(source, HOST_UUID);
        Path canonical = this.tempDir.resolve("canonical");
        writePreparedFiles(canonicalFiles, canonical);

        CompoundTag canonicalData = NbtCompat.getCompoundOrEmpty(NbtCompat.readCompressed(canonical.resolve("level.dat")), "Data");
        assertFalse(canonicalData.contains(WorldCanonicalizer.MODERN_OWNER_UUID_KEY));
        assertEquals("Modern Handoff", NbtCompat.getStringOr(canonicalData, "LevelName", ""));
        assertTrue(Files.exists(canonical.resolve("players").resolve("data").resolve(HOST_UUID + ".dat")));
        assertFalse(Files.exists(canonical.resolve("playerdata")));
    }

    @Test
    void materializeStripsStaleModernOwnerUuidWithoutPlayerFile() throws Exception {
        Path world = this.tempDir.resolve("world");
        Files.createDirectories(world);

        CompoundTag data = new CompoundTag();
        data.putString("LevelName", "Stale Snapshot");
        data.putIntArray(WorldCanonicalizer.MODERN_OWNER_UUID_KEY, new int[]{1, 2, 3, 4});
        CompoundTag level = new CompoundTag();
        level.put("Data", data);
        NbtCompat.writeCompressed(level, world.resolve("level.dat"));

        WorldCanonicalizer.materializeHostPlayer(world, GUEST_UUID);

        CompoundTag materializedData = NbtCompat.getCompoundOrEmpty(NbtCompat.readCompressed(world.resolve("level.dat")), "Data");
        assertFalse(materializedData.contains(WorldCanonicalizer.MODERN_OWNER_UUID_KEY));
        assertFalse(materializedData.contains("Player"));
        assertEquals("Stale Snapshot", NbtCompat.getStringOr(materializedData, "LevelName", ""));
    }

    @Test
    void materializeLeavesLevelDatUntouchedWhenNothingToDo() throws Exception {
        Path world = this.tempDir.resolve("world");
        Files.createDirectories(world);

        CompoundTag data = new CompoundTag();
        data.putString("LevelName", "Already Canonical");
        CompoundTag level = new CompoundTag();
        level.put("Data", data);
        NbtCompat.writeCompressed(level, world.resolve("level.dat"));
        byte[] before = Files.readAllBytes(world.resolve("level.dat"));

        WorldCanonicalizer.materializeHostPlayer(world, GUEST_UUID);

        assertArrayEquals(before, Files.readAllBytes(world.resolve("level.dat")));
    }

    private static void writePreparedFiles(List<PreparedWorldFile> files, Path targetRoot) throws Exception {
        for (PreparedWorldFile file : files) {
            Path target = targetRoot.resolve(file.relativePath().replace('/', java.io.File.separatorChar));
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            if (file.overrideBytes() != null) {
                Files.write(target, file.overrideBytes());
            } else {
                Files.copy(file.sourcePath(), target);
            }
        }
    }
}
