package link.sharedworld.versioned;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Path;

/** Version-specific NBT file IO (Path overloads and getCompoundOrEmpty are recent). */
public final class NbtCompat {
    private NbtCompat() {
    }

    public static CompoundTag readCompressed(Path path) throws IOException {
        return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
    }

    public static void writeCompressed(CompoundTag tag, Path path) throws IOException {
        NbtIo.writeCompressed(tag, path);
    }

    public static CompoundTag getCompoundOrEmpty(CompoundTag tag, String key) {
        return tag.getCompound(key);
    }

    public static String getStringOr(CompoundTag tag, String key, String defaultValue) {
        return tag.contains(key) ? tag.getString(key) : defaultValue;
    }


    public static int getIntOr(CompoundTag tag, String key, int defaultValue) {
        return tag.contains(key) ? tag.getInt(key) : defaultValue;
    }

    public static long getLongOr(CompoundTag tag, String key, long defaultValue) {
        return tag.contains(key) ? tag.getLong(key) : defaultValue;
    }
}
