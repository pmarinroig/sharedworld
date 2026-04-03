package link.sharedworld.screen;

import link.sharedworld.SharedWorldCustomIconStore;
import link.sharedworld.SharedWorldCustomIconStore.SelectedIcon;
import net.minecraft.client.gui.screens.FaviconTexture;

import java.nio.file.Path;
import java.util.function.Supplier;

final class SharedWorldMetadataIcons {
    private SharedWorldMetadataIcons() {
    }

    static void uploadPreview(
            SharedWorldCustomIconStore iconStore,
            FaviconTexture previewTexture,
            SelectedIcon selectedIcon,
            Supplier<Path> fallbackPreviewPath
    ) {
        Path previewPath = selectedIcon == null ? fallbackPreviewPath.get() : selectedIcon.path();
        iconStore.uploadPreview(previewTexture, previewPath);
    }

    static String encodeSelectedIcon(SharedWorldCustomIconStore iconStore, SelectedIcon selectedIcon) throws Exception {
        if (selectedIcon == null) {
            return null;
        }
        return iconStore.encodePngBase64(selectedIcon.path());
    }
}
