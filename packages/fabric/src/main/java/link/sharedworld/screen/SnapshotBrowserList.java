package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.WorldSnapshotSummaryDto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

final class SnapshotBrowserList extends ObjectSelectionList<SnapshotBrowserList.Entry> {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final EditSharedWorldScreen owner;

    SnapshotBrowserList(Minecraft minecraft, int width, int height, int y, int itemHeight, EditSharedWorldScreen owner) {
        super(minecraft, width, height, y, itemHeight);
        this.owner = owner;
    }

    void setSnapshots(List<WorldSnapshotSummaryDto> snapshots, String selectedId) {
        this.clearEntries();
        Entry selected = null;
        for (WorldSnapshotSummaryDto snapshot : snapshots) {
            Entry entry = new Entry(snapshot);
            this.addEntry(entry);
            if (selectedId != null && selectedId.equals(snapshot.snapshotId())) {
                selected = entry;
            }
        }
        Entry resolved = selected == null && !this.children().isEmpty() ? this.children().get(0) : selected;
        this.setSelected(resolved);
        this.owner.onSnapshotSelected(resolved == null ? null : resolved.snapshot);
    }

    WorldSnapshotSummaryDto selectedSnapshot() {
        Entry entry = this.getSelected();
        return entry == null ? null : entry.snapshot;
    }

    @Override
    public int getRowWidth() {
        return this.width - 12;
    }

    final class Entry extends ObjectSelectionList.Entry<Entry> {
        private final WorldSnapshotSummaryDto snapshot;

        Entry(WorldSnapshotSummaryDto snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = this.getContentX();
            int y = this.getContentY();
            String stamp = DATE_FORMAT.format(Instant.parse(this.snapshot.createdAt()).atZone(ZoneId.systemDefault()));
            guiGraphics.drawString(SnapshotBrowserList.this.minecraft.font, Component.literal(stamp), x, y + 4, 0xFFFFFFFF);
            String meta = SharedWorldText.string(this.snapshot.isLatest()
                    ? "screen.sharedworld.snapshot_meta_latest"
                    : "screen.sharedworld.snapshot_meta", this.snapshot.fileCount(), bytes(this.snapshot.totalCompressedSize()));
            guiGraphics.drawString(SnapshotBrowserList.this.minecraft.font, Component.literal(meta), x, y + 16, 0xFF9AA8BA);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            SnapshotBrowserList.this.setSelected(this);
            SnapshotBrowserList.this.owner.onSnapshotSelected(this.snapshot);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.snapshot.snapshotId());
        }
    }

    private static String bytes(long value) {
        if (value >= 1024L * 1024L * 1024L) {
            return SharedWorldText.string("screen.sharedworld.size_gb", String.format(Locale.ROOT, "%.1f", value / (1024.0 * 1024.0 * 1024.0)));
        }
        if (value >= 1024L * 1024L) {
            return SharedWorldText.string("screen.sharedworld.size_mb", String.format(Locale.ROOT, "%.1f", value / (1024.0 * 1024.0)));
        }
        if (value >= 1024L) {
            return SharedWorldText.string("screen.sharedworld.size_kb", String.format(Locale.ROOT, "%.1f", value / 1024.0));
        }
        return SharedWorldText.string("screen.sharedworld.size_b", value);
    }
}
