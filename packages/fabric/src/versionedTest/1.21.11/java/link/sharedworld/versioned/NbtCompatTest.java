package link.sharedworld.versioned;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtCompatTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripsCompressedCompound() throws IOException {
        CompoundTag data = new CompoundTag();
        data.putString("LevelName", "SharedWorld Test");
        CompoundTag root = new CompoundTag();
        root.put("Data", data);

        Path file = this.tempDir.resolve("level.dat");
        NbtCompat.writeCompressed(root, file);
        CompoundTag read = NbtCompat.readCompressed(file);

        assertEquals("SharedWorld Test", NbtCompat.getCompoundOrEmpty(read, "Data").getStringOr("LevelName", ""));
    }

    @Test
    void missingCompoundReadsAsEmpty() {
        CompoundTag root = new CompoundTag();
        assertTrue(NbtCompat.getCompoundOrEmpty(root, "Absent").isEmpty());
    }
}
