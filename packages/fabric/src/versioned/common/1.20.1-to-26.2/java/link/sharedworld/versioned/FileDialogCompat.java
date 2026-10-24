package link.sharedworld.versioned;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.util.function.Consumer;

/**
 * Native file/folder dialogs through LWJGL's tinyfd, which Minecraft ships up to
 * 26.2. tinyfd blocks the calling (render) thread until the dialog closes, which is
 * acceptable for an explicit user action on a menu screen, so the callback runs
 * before these methods return.
 */
public final class FileDialogCompat {
    private FileDialogCompat() {
    }

    /** Asks for one PNG file; {@code onPicked} gets its path, or null when cancelled. */
    public static void openPngFile(String title, Consumer<String> onPicked) {
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer patterns = stack.mallocPointer(1);
            patterns.put(stack.UTF8("*.png"));
            patterns.flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(title, "", patterns, null, false);
        }
        onPicked.accept(blankToNull(selected));
    }

    /** Asks for one folder; {@code onPicked} gets its path, or null when cancelled. */
    public static void openFolder(String title, String defaultPath, Consumer<String> onPicked) {
        onPicked.accept(blankToNull(TinyFileDialogs.tinyfd_selectFolderDialog(title, defaultPath)));
    }

    private static String blankToNull(String path) {
        return path == null || path.isBlank() ? null : path;
    }
}
