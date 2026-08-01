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
        return tag.getCompoundOrEmpty(key);
    }

    public static String getStringOr(CompoundTag tag, String key, String defaultValue) {
        return tag.getStringOr(key, defaultValue);
    }


    public static int getIntOr(CompoundTag tag, String key, int defaultValue) {
        return tag.getIntOr(key, defaultValue);
    }

    public static byte getByteOr(CompoundTag tag, String key, byte defaultValue) {
        return tag.getByteOr(key, defaultValue);
    }

    public static long getLongOr(CompoundTag tag, String key, long defaultValue) {
        return tag.getLongOr(key, defaultValue);
    }
}
