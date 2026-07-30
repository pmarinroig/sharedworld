package link.sharedworld.screen;

import com.mojang.blaze3d.platform.NativeImage;
import link.sharedworld.versioned.GuiBlit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import link.sharedworld.versioned.VersionedSelectionEntry;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

final class LocalSaveSelectionList extends link.sharedworld.versioned.VersionedSelectionList<LocalSaveSelectionList.Entry> {
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault());

    /** Owner screen callbacks; activation is a double-click on an entry. */
    interface Host {
        void onSaveSelected(LocalSaveCatalog.LocalSaveOption save);

        default void onSaveActivated(LocalSaveCatalog.LocalSaveOption save) {
        }
    }

    private final Host owner;

    LocalSaveSelectionList(Minecraft minecraft, int width, int height, int y, int itemHeight, Host owner) {
        super(minecraft, width, height, y, itemHeight);
        this.owner = owner;
    }

    void setSaves(List<LocalSaveCatalog.LocalSaveOption> saves, String selectedId) {
        this.clearEntries();
        Entry selected = null;
        for (LocalSaveCatalog.LocalSaveOption save : saves) {
            Entry entry = new Entry(save);
            this.addEntry(entry);
            if (selectedId != null && selectedId.equals(save.id())) {
                selected = entry;
            }
        }
        this.setSelected(selected == null && !this.children().isEmpty() ? this.children().get(0) : selected);
        // Selecting can auto-scroll while the list is still zero-sized (before
        // the screen lays it out), leaving a stale scroll that pushes every row
        // outside the clip. Start from the top deterministically.
        this.setScrollAmount(0);
    }

    LocalSaveCatalog.LocalSaveOption selectedSave() {
        Entry selected = this.getSelected();
        return selected == null ? null : selected.save;
    }

    @Override
    public int getRowWidth() {
        return 270;
    }

    final class Entry extends VersionedSelectionEntry<Entry> {
        private final LocalSaveCatalog.LocalSaveOption save;
        private final FaviconTexture iconTexture;
        private long loadedSignature = Long.MIN_VALUE;

        Entry(LocalSaveCatalog.LocalSaveOption save) {
            this.save = save;
            this.iconTexture = FaviconTexture.forWorld(LocalSaveSelectionList.this.minecraft.getTextureManager(), "sharedworld/save/" + save.id());
        }

        @Override
        public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            this.refreshIconIfNeeded();
            int x = this.getContentX();
            int y = this.getContentY();
            GuiBlit.favicon(guiGraphics, this.iconTexture, x, y, 32);
            var font = LocalSaveSelectionList.this.minecraft.font;
            int textWidth = LocalSaveSelectionList.this.getRowWidth() - 36;
            guiGraphics.drawString(font, Component.literal(link.sharedworld.SharedWorldText.truncate(font, this.save.displayName(), textWidth)), x + 36, y + 1, 0xFFFFFFFF);
            guiGraphics.drawString(
                    font,
                    Component.literal(link.sharedworld.SharedWorldText.truncate(font, this.idAndLastPlayed(), textWidth)),
                    x + 36,
                    y + 12,
                    0xFF808080
            );
            // Folder-picked entries carry the full folder path here; keep the informative tail.
            String metadata = link.sharedworld.SharedWorldText.truncateLeading(font, this.save.metadata().getString(), textWidth);
            guiGraphics.drawString(font, Component.literal(metadata), x + 36, y + 21, 0xFF808080);
        }

        @Override
        protected boolean sharedworldMouseClicked(double mouseX, double mouseY, boolean doubleClick) {
            LocalSaveSelectionList.this.setSelected(this);
            LocalSaveSelectionList.this.owner.onSaveSelected(this.save);
            if (doubleClick) {
                LocalSaveSelectionList.this.owner.onSaveActivated(this.save);
            }
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.save.displayName());
        }

        private void refreshIconIfNeeded() {
            Path iconPath = this.save.iconPath();
            long signature = iconPath == null ? -1L : iconSignature(iconPath);
            if (signature == this.loadedSignature) {
                return;
            }
            this.loadedSignature = signature;
            if (iconPath == null || !Files.isRegularFile(iconPath)) {
                this.iconTexture.clear();
                return;
            }
            try (InputStream input = Files.newInputStream(iconPath);
                 NativeImage image = NativeImage.read(input)) {
                this.iconTexture.upload(image);
            } catch (IOException | IllegalArgumentException exception) {
                this.iconTexture.clear();
            }
        }

        private long iconSignature(Path iconPath) {
            try {
                return Files.getLastModifiedTime(iconPath).toMillis();
            } catch (IOException exception) {
                return -1L;
            }
        }

        private String idAndLastPlayed() {
            String levelId = this.save.id();
            long lastPlayed = this.save.lastModifiedMillis();
            if (lastPlayed == -1L) {
                return levelId;
            }
            ZonedDateTime zoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastPlayed), ZoneId.systemDefault());
            return levelId + " (" + DATE_FORMAT.format(zoned) + ")";
        }
    }
}
