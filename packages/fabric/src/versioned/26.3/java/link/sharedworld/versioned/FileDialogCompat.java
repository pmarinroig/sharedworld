package link.sharedworld.versioned;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Native file/folder dialogs through SDL3, which replaced GLFW (and with it LWJGL's
 * tinyfd) in 26.3. SDL dialogs are asynchronous: the result arrives in a native
 * callback, on some platforms from another thread, so it is handed back to the
 * client thread. Only one dialog is open at a time; a second request while one is
 * showing is ignored.
 *
 * The filter list and the callback stub must outlive the call until SDL invokes the
 * callback. They are freed when the next dialog opens rather than inside the
 * callback, so a stub is never freed while it is still executing.
 */
public final class FileDialogCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-file-dialog");
    private static final AtomicBoolean OPEN = new AtomicBoolean();
    private static SDL_DialogFileCallback previousCallback;
    private static SDL_DialogFileFilter.Buffer previousFilters;
    private static ByteBuffer previousFilterName;
    private static ByteBuffer previousFilterPattern;

    private FileDialogCompat() {
    }

    /** Asks for one PNG file; {@code onPicked} gets its path, or null when cancelled. */
    public static void openPngFile(String title, Consumer<String> onPicked) {
        if (!OPEN.compareAndSet(false, true)) {
            return;
        }
        freePrevious();
        previousFilterName = MemoryUtil.memUTF8("PNG image");
        previousFilterPattern = MemoryUtil.memUTF8("png");
        previousFilters = SDL_DialogFileFilter.malloc(1);
        previousFilters.get(0).name(previousFilterName).pattern(previousFilterPattern);
        previousCallback = callback(onPicked);
        SDLDialog.SDL_ShowOpenFileDialog(previousCallback, 0L, windowHandle(), previousFilters, (CharSequence) null, false);
    }

    /** Asks for one folder; {@code onPicked} gets its path, or null when cancelled. */
    public static void openFolder(String title, String defaultPath, Consumer<String> onPicked) {
        if (!OPEN.compareAndSet(false, true)) {
            return;
        }
        freePrevious();
        previousCallback = callback(onPicked);
        SDLDialog.SDL_ShowOpenFolderDialog(previousCallback, 0L, windowHandle(), defaultPath, false);
    }

    private static SDL_DialogFileCallback callback(Consumer<String> onPicked) {
        return SDL_DialogFileCallback.create((userdata, filelist, filter) -> {
            String picked = null;
            if (filelist == 0L) {
                LOGGER.warn("SharedWorld file dialog failed: {}", SDLError.SDL_GetError());
            } else {
                long first = MemoryUtil.memGetAddress(filelist);
                if (first != 0L) {
                    picked = MemoryUtil.memUTF8(first);
                }
            }
            String result = picked == null || picked.isBlank() ? null : picked;
            Minecraft.getInstance().execute(() -> {
                OPEN.set(false);
                onPicked.accept(result);
            });
        });
    }

    private static long windowHandle() {
        return Minecraft.getInstance().getWindow().handle();
    }

    private static void freePrevious() {
        if (previousCallback != null) {
            previousCallback.free();
            previousCallback = null;
        }
        if (previousFilters != null) {
            previousFilters.free();
            previousFilters = null;
        }
        if (previousFilterName != null) {
            MemoryUtil.memFree(previousFilterName);
            previousFilterName = null;
        }
        if (previousFilterPattern != null) {
            MemoryUtil.memFree(previousFilterPattern);
            previousFilterPattern = null;
        }
    }
}
