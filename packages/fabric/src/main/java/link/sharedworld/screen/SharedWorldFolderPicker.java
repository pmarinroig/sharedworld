package link.sharedworld.screen;

import link.sharedworld.versioned.FileDialogCompat;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Native pick-a-folder dialog. Up to 26.2 the dialog blocks the render thread until
 * it closes (acceptable for an explicit user action on a menu screen); from 26.3 it
 * answers asynchronously, always on the client thread.
 */
final class SharedWorldFolderPicker {
    /**
     * Folder dialog plus validation as a local save. {@code onChosen} runs only for a
     * usable save; a cancel does nothing and an unusable folder shows its reason on
     * the banner.
     */
    static void chooseSaveFolder(Minecraft minecraft, SharedWorldStatusBanner banner, Consumer<LocalSaveCatalog.LocalSaveOption> onChosen) {
        String title = link.sharedworld.SharedWorldText.string("screen.sharedworld.select_folder_title");
        String defaultPath = minecraft.gameDirectory.toPath().resolve("saves").toAbsolutePath() + java.io.File.separator;
        FileDialogCompat.openFolder(title, defaultPath, selected -> {
            if (selected == null) {
                return;
            }
            try {
                onChosen.accept(LocalSaveFolderValidator.validate(
                        Path.of(selected),
                        minecraft.gameDirectory.toPath().resolve("sharedworld").resolve("worlds"),
                        link.sharedworld.versioned.ClientCompat.currentDataVersion()
                ));
            } catch (LocalSaveFolderValidator.InvalidSaveFolderException exception) {
                banner.set(SharedWorldStatusBanner.Kind.ERROR, net.minecraft.network.chat.Component.literal(exception.getMessage()));
            }
        });
    }

    private SharedWorldFolderPicker() {
    }
}
