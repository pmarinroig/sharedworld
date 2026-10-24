package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;
import link.sharedworld.host.HostEnvironmentCheck;
import link.sharedworld.versioned.VersionedSelectionEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-start environment warning: this client differs from the one that wrote the latest
 * snapshot in ways that change the world for good once it opens (another Minecraft version,
 * missing mods). Shown after the backend assigned the host lease and before any download or world
 * open, so declining is a clean hand-back. The change list scrolls, so even a whole wrong
 * modpack fits while the buttons stay put. Closing the screen counts as declining, and an
 * unanswered screen declines on its own before the lease it holds (no heartbeats yet) runs out.
 */
public final class HostEnvironmentWarningScreen extends link.sharedworld.versioned.VersionedScreen {
    private static final int AUTO_DECLINE_TICKS = 60 * 20;
    private static final int ROW_HEIGHT = 12;
    private static final int SIDE_MARGIN = 30;
    private static final int HEADER_COLOR = 0xFFFFD37A;
    private static final int NOTE_COLOR = 0xFFC0C0C0;
    private static final int ITEM_COLOR = 0xFFFFFFFF;

    private final Screen parent;
    private final String worldName;
    private final HostEnvironmentCheck.Changes changes;
    private final Runnable accept;
    private final Runnable decline;
    private List<FormattedCharSequence> introLines = List.of();
    private boolean decided;
    private int ticksShown;

    public HostEnvironmentWarningScreen(Screen parent, String worldName, HostEnvironmentCheck.Changes changes, Runnable accept, Runnable decline) {
        super(Component.translatable("screen.sharedworld.env_title"));
        this.parent = parent;
        this.worldName = worldName;
        this.changes = changes;
        this.accept = accept;
        this.decline = decline;
    }

    @Override
    public void tick() {
        super.tick();
        this.ticksShown += 1;
        if (this.ticksShown >= AUTO_DECLINE_TICKS) {
            decide(this.decline);
        }
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.introLines = this.font.split(
                Component.translatable("screen.sharedworld.env_intro",
                        this.worldName == null ? "?" : SharedWorldText.truncate(this.font, this.worldName, this.width / 2)),
                this.width - 2 * SIDE_MARGIN
        );
        int listTop = 34 + this.introLines.size() * 12 + 6;
        int listHeight = Math.max(ROW_HEIGHT * 3, this.height - 60 - listTop);
        ChangeList list = new ChangeList(this.minecraft, this.width - 2 * SIDE_MARGIN, listHeight, listTop);
        list.sharedworldSetBounds(SIDE_MARGIN, listTop, this.width - 2 * SIDE_MARGIN, listHeight);
        list.setRows(buildRows(list.getRowWidth() - 16));
        this.addRenderableWidget(list);
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.env_host"), button -> decide(this.accept))
                .bounds(centerX - 154, this.height - 52, 150, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.env_cancel"), button -> decide(this.decline))
                .bounds(centerX + 4, this.height - 52, 150, 20)
                .build());
    }

    /** One line per row: headers, wrapped explanations, then one row per missing mod. */
    private List<Row> buildRows(int textWidth) {
        List<Row> rows = new ArrayList<>();
        if (this.changes.versionFrom() != null) {
            rows.add(Row.of(SharedWorldText.string("screen.sharedworld.env_version_header", this.changes.versionFrom(), this.changes.versionTo()), HEADER_COLOR, 0));
            addWrapped(rows, this.changes.downgrade()
                    ? SharedWorldText.string("screen.sharedworld.env_downgrade_note", this.changes.versionTo())
                    : SharedWorldText.string("screen.sharedworld.env_upgrade_note", this.changes.versionTo(), this.changes.versionFrom()), textWidth);
        }
        if (!this.changes.missingMods().isEmpty()) {
            if (!rows.isEmpty()) {
                rows.add(Row.of("", NOTE_COLOR, 0));
            }
            rows.add(Row.of(SharedWorldText.string("screen.sharedworld.env_missing_header", this.changes.missingMods().size()), HEADER_COLOR, 0));
            addWrapped(rows, SharedWorldText.string("screen.sharedworld.env_missing_note"), textWidth);
            for (String mod : this.changes.missingMods()) {
                rows.add(Row.of(SharedWorldText.truncate(this.font, "- " + mod, textWidth - 8), ITEM_COLOR, 8));
            }
        }
        rows.add(Row.of("", NOTE_COLOR, 0));
        addWrapped(rows, SharedWorldText.string("screen.sharedworld.env_backup_note"), textWidth + 8);
        return rows;
    }

    private void addWrapped(List<Row> rows, String text, int width) {
        for (FormattedCharSequence line : this.font.split(Component.literal(text), width - 8)) {
            rows.add(new Row(line, text, NOTE_COLOR, 8));
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private void decide(Runnable action) {
        if (this.decided) {
            return;
        }
        this.decided = true;
        action.run();
    }

    @Override
    public void onClose() {
        decide(this.decline);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.getTitle(), this.width / 2, 16, 0xFFFFFFFF);
        int y = 34;
        for (FormattedCharSequence line : this.introLines) {
            graphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFFC0C0C0);
            y += 12;
        }
        graphics.drawCenteredString(
                this.font,
                Component.translatable("screen.sharedworld.env_countdown", Math.max(0, (AUTO_DECLINE_TICKS - this.ticksShown) / 20)),
                this.width / 2,
                this.height - 24,
                0xFF808080
        );
    }

    Screen parent() {
        return this.parent;
    }

    /** {@code narration} is the full source text (a wrapped line narrates its whole paragraph). */
    private record Row(FormattedCharSequence text, String narration, int color, int indent) {
        static Row of(String text, int color, int indent) {
            return new Row(Component.literal(text).getVisualOrderText(), text, color, indent);
        }
    }

    /** Read-only, scrollable rows: nothing is selectable. */
    private static final class ChangeList extends link.sharedworld.versioned.VersionedSelectionList<ChangeList.Entry> {
        ChangeList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, ROW_HEIGHT);
        }

        void setRows(List<Row> rows) {
            this.clearEntries();
            for (Row row : rows) {
                this.addEntry(new Entry(row));
            }
        }

        @Override
        public int getRowWidth() {
            return this.width - 12;
        }

        final class Entry extends VersionedSelectionEntry<Entry> {
            private final Row row;

            Entry(Row row) {
                this.row = row;
            }

            @Override
            public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
                guiGraphics.drawString(ChangeList.this.minecraft.font, this.row.text(), this.getContentX() + this.row.indent(), this.getContentY() + 2, this.row.color());
            }

            @Override
            protected boolean sharedworldMouseClicked(double mouseX, double mouseY, boolean doubleClick) {
                return false;
            }

            @Override
            public Component getNarration() {
                return Component.literal(this.row.narration());
            }
        }
    }
}
