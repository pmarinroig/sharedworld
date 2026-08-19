package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.WorldSnapshotSummaryDto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import link.sharedworld.versioned.VersionedSelectionEntry;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Backups tab list. Besides the single highlighted row (details panel,
 * restore), the owner can tick any number of non-latest rows through the
 * checkbox at the right edge and delete them in one request (0.4.5) —
 * deleting a day of autosaves one confirm at a time was the complaint that
 * motivated it. Ticks survive reloads only for rows that still exist.
 */
final class SnapshotBrowserList extends link.sharedworld.versioned.VersionedSelectionList<SnapshotBrowserList.Entry> {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int CHECKBOX_SIZE = 10;
    private static final int CHECKBOX_RIGHT_INSET = 8;
    private static final int CHECKBOX_HIT_PADDING = 5;
    private final EditSharedWorldScreen owner;
    private final Set<String> marked = new LinkedHashSet<>();

    SnapshotBrowserList(Minecraft minecraft, int width, int height, int y, int itemHeight, EditSharedWorldScreen owner) {
        super(minecraft, width, height, y, itemHeight);
        this.owner = owner;
    }

    void setSnapshots(List<WorldSnapshotSummaryDto> snapshots, String selectedId) {
        this.clearEntries();
        Entry selected = null;
        Set<String> present = new LinkedHashSet<>();
        for (WorldSnapshotSummaryDto snapshot : snapshots) {
            Entry entry = new Entry(snapshot);
            this.addEntry(entry);
            if (!snapshot.isLatest()) {
                present.add(snapshot.snapshotId());
            }
            if (selectedId != null && selectedId.equals(snapshot.snapshotId())) {
                selected = entry;
            }
        }
        this.marked.retainAll(present);
        Entry resolved = selected == null && !this.children().isEmpty() ? this.children().get(0) : selected;
        this.setSelected(resolved);
        this.owner.onSnapshotSelected(resolved == null ? null : resolved.snapshot);
    }

    WorldSnapshotSummaryDto selectedSnapshot() {
        Entry entry = this.getSelected();
        return entry == null ? null : entry.snapshot;
    }

    /** Ids ticked for bulk delete, in tick order; never contains the latest snapshot. */
    List<String> markedSnapshotIds() {
        return List.copyOf(this.marked);
    }

    void clearMarks() {
        if (!this.marked.isEmpty()) {
            this.marked.clear();
            this.owner.onSnapshotMarksChanged();
        }
    }

    /** Ticks every non-latest row (or clears all when every one is already ticked). */
    void toggleMarkAll() {
        List<String> all = this.children().stream()
                .map(entry -> entry.snapshot)
                .filter(snapshot -> !snapshot.isLatest())
                .map(WorldSnapshotSummaryDto::snapshotId)
                .toList();
        if (!all.isEmpty() && this.marked.containsAll(all)) {
            this.marked.clear();
        } else {
            this.marked.addAll(all);
        }
        this.owner.onSnapshotMarksChanged();
    }

    private void toggleMark(WorldSnapshotSummaryDto snapshot) {
        if (snapshot.isLatest()) {
            return;
        }
        if (!this.marked.remove(snapshot.snapshotId())) {
            this.marked.add(snapshot.snapshotId());
        }
        this.owner.onSnapshotMarksChanged();
    }

    @Override
    public int getRowWidth() {
        return this.width - 12;
    }

    final class Entry extends VersionedSelectionEntry<Entry> {
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
            if (!this.snapshot.isLatest() && SnapshotBrowserList.this.owner.canManageBackups()) {
                int boxX = this.checkboxLeft();
                int boxY = y + 11;
                boolean ticked = SnapshotBrowserList.this.marked.contains(this.snapshot.snapshotId());
                boolean overBox = mouseX >= boxX - CHECKBOX_HIT_PADDING && mouseX <= boxX + CHECKBOX_SIZE + CHECKBOX_HIT_PADDING
                        && mouseY >= boxY - CHECKBOX_HIT_PADDING && mouseY <= boxY + CHECKBOX_SIZE + CHECKBOX_HIT_PADDING;
                int border = ticked ? 0xFFF2C25B : (overBox ? 0xFFDDDDDD : 0xFF8A94A3);
                guiGraphics.fill(boxX, boxY, boxX + CHECKBOX_SIZE, boxY + CHECKBOX_SIZE, border);
                guiGraphics.fill(boxX + 1, boxY + 1, boxX + CHECKBOX_SIZE - 1, boxY + CHECKBOX_SIZE - 1, ticked ? 0xFF3A3222 : 0xFF14181F);
                if (ticked) {
                    guiGraphics.fill(boxX + 3, boxY + 3, boxX + CHECKBOX_SIZE - 3, boxY + CHECKBOX_SIZE - 3, 0xFFF2C25B);
                }
            }
        }

        private int checkboxLeft() {
            return this.getContentX() + SnapshotBrowserList.this.getRowWidth() - CHECKBOX_RIGHT_INSET - CHECKBOX_SIZE;
        }

        @Override
        protected boolean sharedworldMouseClicked(double mouseX, double mouseY, boolean doubleClick) {
            if (!this.snapshot.isLatest() && SnapshotBrowserList.this.owner.canManageBackups()) {
                int boxX = this.checkboxLeft();
                if (mouseX >= boxX - CHECKBOX_HIT_PADDING && mouseX <= boxX + CHECKBOX_SIZE + CHECKBOX_HIT_PADDING) {
                    SnapshotBrowserList.this.toggleMark(this.snapshot);
                    return true;
                }
            }
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
