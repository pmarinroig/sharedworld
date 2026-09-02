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
    /**
     * Folder dialog plus validation as a local save: null when the player cancelled
     * or the folder is not a usable save (the reason is shown on the banner).
     */
    static LocalSaveCatalog.LocalSaveOption chooseSaveFolder(net.minecraft.client.Minecraft minecraft, SharedWorldStatusBanner banner) {
        Path chosen = chooseFolder(link.sharedworld.SharedWorldText.string("screen.sharedworld.select_folder_title"));
        if (chosen == null) {
            return null;
        }
        try {
            return LocalSaveFolderValidator.validate(
                    chosen,
                    minecraft.gameDirectory.toPath().resolve("sharedworld").resolve("worlds"),
                    link.sharedworld.versioned.ClientCompat.currentDataVersion()
            );
        } catch (LocalSaveFolderValidator.InvalidSaveFolderException exception) {
            banner.set(SharedWorldStatusBanner.Kind.ERROR, net.minecraft.network.chat.Component.literal(exception.getMessage()));
            return null;
        }
    }

    private SharedWorldFolderPicker() {
    }

    static Path chooseFolder(String title) {
        String defaultPath = Minecraft.getInstance().gameDirectory.toPath().resolve("saves").toAbsolutePath() + java.io.File.separator;
        String selected = TinyFileDialogs.tinyfd_selectFolderDialog(title, defaultPath);
        return selected == null || selected.isBlank() ? null : Path.of(selected);
    }
}
