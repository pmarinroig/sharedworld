package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;
import link.sharedworld.versioned.GuiBlit;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.FaviconTexture;

/**
 * The world-icon well plus the server-list card preview shared by the create
 * and edit screens. Each screen keeps its own card Y (the edit screen centers
 * the card under its Replace World row, the create screen bottom-anchors it);
 * everything else about the two is identical and lives here.
 */
final class WorldPreviewCard {
    static final int ICON_SIZE = 48;
    private static final String EDIT_ICON_SPRITE = "sharedworld:edit_icon";
    private static final String EDIT_ICON_HIGHLIGHTED_SPRITE = "sharedworld:edit_icon_highlighted";
    private static final String DELETE_ICON_HIGHLIGHTED_SPRITE = "sharedworld:delete_icon_highlighted";
    private static final String PING_5_SPRITE = "minecraft:server_list/ping_5";

    private WorldPreviewCard() {
    }

    static int cardX(ScreenRectangle contentArea) {
        return contentArea.left() + (contentArea.width() - SharedWorldServerList.ROW_WIDTH) / 2;
    }

    /** The icon well sits centered between the right edge of the text fields and the card's right edge. */
    static int iconX(ScreenRectangle contentArea) {
        int fieldsRight = contentArea.left() + 38 + Math.min(190, contentArea.width() - 140);
        int previewRight = cardX(contentArea) + SharedWorldServerList.ROW_WIDTH;
        return fieldsRight + ((previewRight - fieldsRight) - ICON_SIZE) / 2;
    }

    /** The icon well sits centered between the content top and the card. */
    static int iconY(ScreenRectangle contentArea, int cardY) {
        int top = contentArea.top();
        return top + ((cardY - top) - ICON_SIZE) / 2;
    }

    static boolean iconHovered(int iconX, int iconY, int mouseX, int mouseY) {
        return mouseX >= iconX && mouseX <= iconX + ICON_SIZE && mouseY >= iconY && mouseY <= iconY + ICON_SIZE;
    }

    /**
     * The icon well: the world icon, an always-visible pencil badge when it is
     * editable (the well is a button, say so silently) and, while hovered, a
     * dim overlay with the action that a click performs.
     */
    static void renderIconWell(GuiGraphics guiGraphics, FaviconTexture texture, int iconX, int iconY, boolean editable, boolean hovered, boolean clickDeletes) {
        GuiBlit.favicon(guiGraphics, texture, iconX, iconY, ICON_SIZE);
        if (!editable) {
            return;
        }
        // The dark chip keeps the pencil readable over any world screenshot.
        guiGraphics.fill(iconX + ICON_SIZE - 16, iconY + ICON_SIZE - 16, iconX + ICON_SIZE, iconY + ICON_SIZE, 0xB0000000);
        GuiBlit.sprite(guiGraphics, EDIT_ICON_SPRITE, iconX + ICON_SIZE - 14, iconY + ICON_SIZE - 14, 12, 12);
        if (hovered) {
            guiGraphics.fill(iconX, iconY, iconX + ICON_SIZE, iconY + ICON_SIZE, 0x80000000);
            GuiBlit.sprite(guiGraphics, clickDeletes ? DELETE_ICON_HIGHLIGHTED_SPRITE : EDIT_ICON_HIGHLIGHTED_SPRITE, iconX + 12, iconY + 12, 24, 24);
        }
    }

    /** The card exactly as the world list would draw it, selected, with a full ping. */
    static void renderCard(GuiGraphics guiGraphics, Font font, FaviconTexture texture, int rowX, int rowY, String worldName, String motd) {
        int contentX = rowX + SharedWorldServerList.CONTENT_PADDING;
        int contentY = rowY + SharedWorldServerList.CONTENT_PADDING;
        SharedWorldServerList.renderSelectedOutline(guiGraphics, rowX, rowY, true);
        GuiBlit.favicon(guiGraphics, texture, contentX, contentY, 32);
        SharedWorldServerList.renderRowContents(guiGraphics, font, rowX, rowY, worldName, motd, SharedWorldText.playerCount(0, 8), PING_5_SPRITE);
    }
}
