package link.sharedworld.screen;

import com.mojang.blaze3d.platform.NativeImage;
import link.sharedworld.LegacyMotdFormatter;
import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.sync.ManagedWorldStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SharedWorldServerList extends ObjectSelectionList<SharedWorldServerList.Entry> {
    static final int ROW_WIDTH = 305;
    static final int ROW_HEIGHT = 36;
    static final int CONTENT_PADDING = 2;
    static final int CONTENT_WIDTH = ROW_WIDTH - CONTENT_PADDING * 2;
    static final int TEXT_LEFT_OFFSET = 35;
    static final int TITLE_Y_OFFSET = 1;
    static final int DETAIL_Y_OFFSET = 12;
    static final int DETAIL_LINE_SPACING = 9;
    static final int STATUS_ICON_RIGHT_INSET = 15;
    static final int PLAYER_COUNT_GAP = 15;
    static final int DETAILS_GAP = 5;

    private static final Identifier JOIN_SPRITE = Identifier.withDefaultNamespace("server_list/join");
    private static final Identifier JOIN_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("server_list/join_highlighted");
    private static final Identifier MOVE_UP_SPRITE = Identifier.withDefaultNamespace("server_list/move_up");
    private static final Identifier MOVE_UP_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("server_list/move_up_highlighted");
    private static final Identifier MOVE_DOWN_SPRITE = Identifier.withDefaultNamespace("server_list/move_down");
    private static final Identifier MOVE_DOWN_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("server_list/move_down_highlighted");
    private static final Identifier UNREACHABLE_SPRITE = Identifier.withDefaultNamespace("server_list/unreachable");
    private static final Identifier PING_5_SPRITE = Identifier.withDefaultNamespace("server_list/ping_5");
    private static final Identifier PINGING_1_SPRITE = Identifier.withDefaultNamespace("server_list/pinging_1");
    private static final Identifier PINGING_2_SPRITE = Identifier.withDefaultNamespace("server_list/pinging_2");
    private static final Identifier PINGING_3_SPRITE = Identifier.withDefaultNamespace("server_list/pinging_3");
    private static final Identifier PINGING_4_SPRITE = Identifier.withDefaultNamespace("server_list/pinging_4");
    private static final Identifier PINGING_5_SPRITE = Identifier.withDefaultNamespace("server_list/pinging_5");

    private final SharedWorldScreen screen;
    private final ManagedWorldStore worldStore = new ManagedWorldStore();

    public SharedWorldServerList(Minecraft minecraft, int width, int height, int y, int itemHeight, SharedWorldScreen screen) {
        super(minecraft, width, height, y, itemHeight);
        this.screen = screen;
    }

    public void setWorlds(List<WorldSummaryDto> worlds, String preferredWorldId) {
        this.clearEntries();

        Entry preferredEntry = null;
        for (WorldSummaryDto world : worlds) {
            Entry entry = new Entry(world);
            this.addEntry(entry);
            if (preferredWorldId != null && preferredWorldId.equals(world.id())) {
                preferredEntry = entry;
            }
        }

        if (preferredEntry != null) {
            this.setSelected(preferredEntry);
            this.centerScrollOn(preferredEntry);
        } else {
            this.setSelected(null);
        }

        this.screen.onEntrySelected(this.selectedWorld());
    }

    public WorldSummaryDto selectedWorld() {
        Entry selected = this.getSelected();
        return selected == null ? null : selected.world;
    }

    @Override
    public int getRowWidth() {
        return ROW_WIDTH;
    }

    public final class Entry extends ObjectSelectionList.Entry<Entry> {
        private final WorldSummaryDto world;
        private final FaviconTexture iconTexture;
        private long lastIconSignature = Long.MIN_VALUE;
        private Component statusIconTooltip;
        private List<Component> onlinePlayersTooltip;
        private Identifier statusIcon = PING_5_SPRITE;

        private Entry(WorldSummaryDto world) {
            this.world = world;
            this.iconTexture = FaviconTexture.forWorld(SharedWorldServerList.this.minecraft.getTextureManager(), "sharedworld/" + world.id());
        }

        @Override
        public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int left = this.getContentX();
            int top = this.getContentY();
            this.refreshIconIfNeeded();
            this.refreshStatusPresentation();
            this.drawIcon(guiGraphics, left, top, this.iconTexture.textureLocation());
            this.drawHoverControls(guiGraphics, left, top, mouseX, mouseY, hovered);
            renderRowContents(
                    guiGraphics,
                    SharedWorldServerList.this.minecraft.font,
                    this.getX(),
                    this.getY(),
                    displayName(this.world),
                    this.world.motd(),
                    playerCount(this.world),
                    this.statusIcon
            );

            int iconRight = left + ROW_WIDTH - STATUS_ICON_RIGHT_INSET;
            int playerCountWidth = SharedWorldServerList.this.minecraft.font.width(playerCount(this.world));
            int playerCountX = iconRight - playerCountWidth - PLAYER_COUNT_GAP;

            if (this.statusIconTooltip != null && mouseX >= iconRight && mouseX <= iconRight + 10 && mouseY >= top && mouseY <= top + 8) {
                guiGraphics.setTooltipForNextFrame(this.statusIconTooltip, mouseX, mouseY);
            } else if (this.onlinePlayersTooltip != null
                    && mouseX >= playerCountX
                    && mouseX <= playerCountX + playerCountWidth
                    && mouseY >= top
                    && mouseY <= top + 9) {
                java.util.List<FormattedCharSequence> tooltipLines = new java.util.ArrayList<>(this.onlinePlayersTooltip.size());
                for (Component component : this.onlinePlayersTooltip) {
                    tooltipLines.add(component.getVisualOrderText());
                }
                guiGraphics.setTooltipForNextFrame(tooltipLines, mouseX, mouseY);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            SharedWorldServerList.this.setSelected(this);
            SharedWorldServerList.this.screen.onEntrySelected(this.world);
            int left = this.getContentX();
            int top = this.getContentY();
            double mouseX = this.normalizeIconCoordinate(event.x(), left);
            double mouseY = this.normalizeIconCoordinate(event.y(), top);
            if (this.isJoinButton(mouseX, mouseY)) {
                SharedWorldServerList.this.screen.joinSelected();
                return true;
            }
            if (this.isMoveUpButton(mouseX, mouseY) && SharedWorldServerList.this.screen.canMoveWorld(this.world, -1)) {
                SharedWorldServerList.this.screen.moveWorld(this.world, -1);
                return true;
            }
            if (this.isMoveDownButton(mouseX, mouseY) && SharedWorldServerList.this.screen.canMoveWorld(this.world, 1)) {
                SharedWorldServerList.this.screen.moveWorld(this.world, 1);
                return true;
            }
            if (doubleClick) {
                SharedWorldServerList.this.screen.joinSelected();
            }
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(displayName(this.world));
        }

        private void refreshIconIfNeeded() {
            Path iconPath = SharedWorldClient.customIconStore().resolveCachedIcon(this.world);
            if (iconPath == null) {
                iconPath = SharedWorldServerList.this.worldStore.workingCopy(this.world.id()).resolve("icon.png");
            }
            long signature = iconSignature(iconPath);
            if (signature == this.lastIconSignature) {
                return;
            }

            this.lastIconSignature = signature;
            if (!Files.isRegularFile(iconPath)) {
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

        private void drawIcon(GuiGraphics guiGraphics, int x, int y, Identifier textureLocation) {
            guiGraphics.blit(RenderPipelines.GUI_TEXTURED, textureLocation, x, y, 0.0F, 0.0F, 32, 32, 32, 32);
        }

        private void drawHoverControls(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, boolean hovered) {
            if (!hovered || mouseX < x || mouseX > x + 32 || mouseY < y || mouseY > y + 32) {
                return;
            }

            double localX = mouseX - x;
            double localY = mouseY - y;
            guiGraphics.fill(x, y, x + 32, y + 32, 0xA0000000);
            guiGraphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    this.isJoinButton(localX, localY) ? JOIN_HIGHLIGHTED_SPRITE : JOIN_SPRITE,
                    x,
                    y,
                    32,
                    32
            );

            if (SharedWorldServerList.this.screen.canMoveWorld(this.world, -1)) {
                guiGraphics.blitSprite(
                        RenderPipelines.GUI_TEXTURED,
                        this.isMoveUpButton(localX, localY) ? MOVE_UP_HIGHLIGHTED_SPRITE : MOVE_UP_SPRITE,
                        x,
                        y,
                        32,
                        32
                );
            }

            if (SharedWorldServerList.this.screen.canMoveWorld(this.world, 1)) {
                guiGraphics.blitSprite(
                        RenderPipelines.GUI_TEXTURED,
                        this.isMoveDownButton(localX, localY) ? MOVE_DOWN_HIGHLIGHTED_SPRITE : MOVE_DOWN_SPRITE,
                        x,
                        y,
                        32,
                        32
                );
            }
        }

        private void refreshStatusPresentation() {
            this.onlinePlayersTooltip = onlinePlayersTooltip(this.world);
            switch (this.world.status()) {
                case "finalizing" -> {
                    this.statusIcon = animatedPingingSprite(SharedWorldServerList.this.children().indexOf(this));
                    if (this.world.activeHostPlayerName() != null && !this.world.activeHostPlayerName().isBlank()) {
                        this.statusIconTooltip = Component.translatable("screen.sharedworld.status_finalizing_with", this.world.activeHostPlayerName());
                    } else {
                        this.statusIconTooltip = Component.translatable("screen.sharedworld.status_finalizing");
                    }
                }
                case "handoff" -> {
                    this.statusIcon = animatedPingingSprite(SharedWorldServerList.this.children().indexOf(this));
                    if (this.world.activeHostPlayerName() != null && !this.world.activeHostPlayerName().isBlank()) {
                        this.statusIconTooltip = Component.translatable("screen.sharedworld.status_starting_with", this.world.activeHostPlayerName());
                    } else {
                        this.statusIconTooltip = Component.translatable("screen.sharedworld.status_starting");
                    }
                }
                case "hosting" -> {
                    if (this.world.activeJoinTarget() != null && !this.world.activeJoinTarget().isBlank()) {
                        this.statusIcon = PING_5_SPRITE;
                        if (this.world.activeHostPlayerName() != null && !this.world.activeHostPlayerName().isBlank()) {
                            this.statusIconTooltip = Component.translatable("screen.sharedworld.status_hosted_by", this.world.activeHostPlayerName());
                        } else {
                            this.statusIconTooltip = Component.translatable("screen.sharedworld.status_running");
                        }
                    } else {
                        this.statusIcon = animatedPingingSprite(SharedWorldServerList.this.children().indexOf(this));
                        this.statusIconTooltip = Component.translatable("screen.sharedworld.status_starting");
                    }
                }
                case "idle" -> {
                    if (SharedWorldServerList.this.screen.backendReachable()) {
                        this.statusIcon = PING_5_SPRITE;
                        this.statusIconTooltip = Component.translatable("screen.sharedworld.status_ready");
                    } else {
                        this.statusIcon = UNREACHABLE_SPRITE;
                        this.statusIconTooltip = Component.translatable("screen.sharedworld.status_backend_unreachable");
                    }
                }
                default -> {
                    this.statusIcon = UNREACHABLE_SPRITE;
                    this.statusIconTooltip = Component.translatable("screen.sharedworld.status_unavailable");
                }
            }
        }

        private double normalizeIconCoordinate(double value, int origin) {
            if (value >= origin && value < origin + 32) {
                return value - origin;
            }
            return value;
        }

        private boolean isJoinButton(double mouseX, double mouseY) {
            return mouseX >= 16 && mouseX < 32 && mouseY >= 0 && mouseY < 32;
        }

        private boolean isMoveUpButton(double mouseX, double mouseY) {
            return mouseX >= 0 && mouseX < 16 && mouseY >= 0 && mouseY < 16;
        }

        private boolean isMoveDownButton(double mouseX, double mouseY) {
            return mouseX >= 0 && mouseX < 16 && mouseY >= 16 && mouseY < 32;
        }
    }

    private static String displayName(WorldSummaryDto world) {
        return SharedWorldText.displayWorldName(world.name());
    }

    private List<FormattedCharSequence> detailLines(WorldSummaryDto world, int width) {
        return detailLines(this.minecraft.font, world.motd(), width);
    }

    private static Component playerCount(WorldSummaryDto world) {
        return SharedWorldText.playerCount(world.onlinePlayerCount(), 8);
    }

    private static List<Component> onlinePlayersTooltip(WorldSummaryDto world) {
        if (world.onlinePlayerNames() == null || world.onlinePlayerNames().length == 0) {
            return null;
        }
        java.util.List<Component> players = new java.util.ArrayList<>(world.onlinePlayerNames().length);
        for (String playerName : world.onlinePlayerNames()) {
            players.add(Component.literal(playerName));
        }
        return players;
    }

    private static Identifier animatedPingingSprite(int rowIndex) {
        int frame = (int) ((Util.getMillis() / 100L + (long) rowIndex * 2L) & 7L);
        if (frame > 4) {
            frame = 8 - frame;
        }
        return switch (frame) {
            case 1 -> PINGING_2_SPRITE;
            case 2 -> PINGING_3_SPRITE;
            case 3 -> PINGING_4_SPRITE;
            case 4 -> PINGING_5_SPRITE;
            default -> PINGING_1_SPRITE;
        };
    }

    private static long iconSignature(Path iconPath) {
        try {
            if (!Files.isRegularFile(iconPath)) {
                return -1L;
            }
            return Files.size(iconPath) ^ Files.getLastModifiedTime(iconPath).toMillis();
        } catch (IOException exception) {
            return -1L;
        }
    }

    static void renderSelectedOutline(GuiGraphics guiGraphics, int x, int y, boolean focused) {
        int outlineColor = focused ? -1 : -8355712;
        guiGraphics.fill(x, y, x + ROW_WIDTH, y + ROW_HEIGHT, outlineColor);
        guiGraphics.fill(x + 1, y + 1, x + ROW_WIDTH - 1, y + ROW_HEIGHT - 1, 0xFF000000);
    }

    static void renderRowContents(
            GuiGraphics guiGraphics,
            Font font,
            int rowX,
            int rowY,
            String name,
            String motd,
            Component playerCount,
            Identifier statusIcon
    ) {
        int left = rowX + CONTENT_PADDING;
        int top = rowY + CONTENT_PADDING;
        int textLeft = left + TEXT_LEFT_OFFSET;
        int iconRight = left + CONTENT_WIDTH - STATUS_ICON_RIGHT_INSET;
        int playerCountWidth = font.width(playerCount);
        int playerCountX = iconRight - playerCountWidth - PLAYER_COUNT_GAP;
        int detailsWidth = Math.max(90, playerCountX - textLeft - DETAILS_GAP);

        guiGraphics.drawString(font, Component.literal(name), textLeft, top + TITLE_Y_OFFSET, 0xFFFFFFFF);
        List<FormattedCharSequence> lines = detailLines(font, motd, detailsWidth);
        for (int index = 0; index < Math.min(2, lines.size()); index++) {
            guiGraphics.drawString(font, lines.get(index), textLeft, top + DETAIL_Y_OFFSET + (index * DETAIL_LINE_SPACING), 0xFF808080);
        }
        guiGraphics.drawString(font, playerCount, playerCountX, top + TITLE_Y_OFFSET, 0xFF808080);
        guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, statusIcon, iconRight, top, 10, 8);
    }

    static List<FormattedCharSequence> detailLines(Font font, String motd, int width) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : LegacyMotdFormatter.lines(motd)) {
            lines.addAll(font.split(line, width));
            if (lines.size() >= 2) {
                break;
            }
        }
        return lines;
    }
}
