package link.sharedworld.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        NbtIo.writeCompressed(level, source.resolve("level.dat"));
        NbtIo.writeCompressed(guestPlayer, source.resolve("playerdata").resolve(GUEST_UUID + ".dat"));

        List<PreparedWorldFile> canonicalFiles = WorldCanonicalizer.scanCanonical(source, HOST_UUID);
        Path canonical = this.tempDir.resolve("canonical");
        writePreparedFiles(canonicalFiles, canonical);

        CompoundTag canonicalLevel = NbtIo.readCompressed(canonical.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        assertFalse(canonicalLevel.getCompoundOrEmpty("Data").contains("Player"));

        WorldCanonicalizer.materializeHostPlayer(canonical, GUEST_UUID);

        CompoundTag materialized = NbtIo.readCompressed(canonical.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        CompoundTag materializedData = materialized.getCompoundOrEmpty("Data");

        assertEquals("Handoff Regression", materializedData.getString("LevelName").orElse(""));
        assertEquals(424242L, materializedData.getLong("RandomSeed").orElse(0L));
        assertEquals("stone-arch", materializedData.getString("SharedWorldStableMarker").orElse(""));
        assertEquals("guest-b", materializedData.getCompoundOrEmpty("Player").getString("SharedWorldPlayerMarker").orElse(""));
        assertFalse(Files.exists(canonical.resolve("playerdata").resolve(GUEST_UUID + ".dat")));
        assertTrue(Files.exists(canonical.resolve("playerdata").resolve(HOST_UUID + ".dat")));
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
