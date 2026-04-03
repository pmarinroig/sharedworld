package link.sharedworld.screen;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Util;

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

final class LocalSaveSelectionList extends ObjectSelectionList<LocalSaveSelectionList.Entry> {
    private static final DateTimeFormatter DATE_FORMAT = Util.localizedDateFormatter(FormatStyle.SHORT);
    private final CreateSharedWorldScreen owner;

    LocalSaveSelectionList(Minecraft minecraft, int width, int height, int y, int itemHeight, CreateSharedWorldScreen owner) {
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
    }

    LocalSaveCatalog.LocalSaveOption selectedSave() {
        Entry selected = this.getSelected();
        return selected == null ? null : selected.save;
    }

    @Override
    public int getRowWidth() {
        return 270;
    }

    final class Entry extends ObjectSelectionList.Entry<Entry> {
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
            guiGraphics.blit(RenderPipelines.GUI_TEXTURED, this.iconTexture.textureLocation(), x, y, 0.0F, 0.0F, 32, 32, 32, 32);
            guiGraphics.drawString(LocalSaveSelectionList.this.minecraft.font, Component.literal(this.save.displayName()), x + 36, y + 1, 0xFFFFFFFF);
            guiGraphics.drawString(
                    LocalSaveSelectionList.this.minecraft.font,
                    Component.literal(this.idAndLastPlayed()),
                    x + 36,
                    y + 12,
                    0xFF808080
            );
            guiGraphics.drawString(LocalSaveSelectionList.this.minecraft.font, this.save.metadata(), x + 36, y + 21, 0xFF808080);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            LocalSaveSelectionList.this.setSelected(this);
            LocalSaveSelectionList.this.owner.onSaveSelected(this.save);
            if (doubleClick) {
                LocalSaveSelectionList.this.owner.openDetailsTab();
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
