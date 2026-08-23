package link.sharedworld.screen;

import net.minecraft.client.Minecraft;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;

/**
 * Native pick-a-folder dialog. Like the icon picker, tinyfd blocks the calling
 * (render) thread until the dialog closes; acceptable for an explicit user
 * action on a menu screen.
 */
final class SharedWorldFolderPicker {
    private SharedWorldFolderPicker() {
    }

    static Path chooseFolder(String title) {
        String defaultPath = Minecraft.getInstance().gameDirectory.toPath().resolve("saves").toAbsolutePath() + java.io.File.separator;
        String selected = TinyFileDialogs.tinyfd_selectFolderDialog(title, defaultPath);
        return selected == null || selected.isBlank() ? null : Path.of(selected);
    }
}
