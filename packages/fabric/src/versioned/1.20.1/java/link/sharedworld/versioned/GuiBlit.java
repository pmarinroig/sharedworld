package link.sharedworld.versioned;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Version-specific GUI draw calls for Minecraft 1.20/1.20.1, which predate the gui-sprite
 * atlas: vanilla sprite ids map onto the classic icons/server_selection texture coordinates,
 * SharedWorld's own sprites are drawn as standalone textures, and the scroller/separator
 * sprites are reproduced with plain fills (matching the era's scrollbar look).
 */
public final class GuiBlit {
    private static final ResourceLocation GUI_ICONS = new ResourceLocation("textures/gui/icons.png");
    private static final ResourceLocation SERVER_SELECTION = new ResourceLocation("textures/gui/server_selection.png");

    private GuiBlit() {
    }

    public static void sprite(GuiGraphics guiGraphics, String spriteId, int x, int y, int width, int height) {
        switch (spriteId) {
            case "minecraft:server_list/join" -> icon(guiGraphics, SERVER_SELECTION, x, y, 0, 0, width, height);
            case "minecraft:server_list/join_highlighted" -> icon(guiGraphics, SERVER_SELECTION, x, y, 0, 32, width, height);
            case "minecraft:server_list/move_up" -> icon(guiGraphics, SERVER_SELECTION, x, y, 96, 0, width, height);
            case "minecraft:server_list/move_up_highlighted" -> icon(guiGraphics, SERVER_SELECTION, x, y, 96, 32, width, height);
            case "minecraft:server_list/move_down" -> icon(guiGraphics, SERVER_SELECTION, x, y, 64, 0, width, height);
            case "minecraft:server_list/move_down_highlighted" -> icon(guiGraphics, SERVER_SELECTION, x, y, 64, 32, width, height);
            case "minecraft:server_list/unreachable" -> ping(guiGraphics, x, y, 0, 5, width, height);
            case "minecraft:server_list/ping_5" -> ping(guiGraphics, x, y, 0, 0, width, height);
            case "minecraft:server_list/ping_4" -> ping(guiGraphics, x, y, 0, 1, width, height);
            case "minecraft:server_list/ping_3" -> ping(guiGraphics, x, y, 0, 2, width, height);
            case "minecraft:server_list/ping_2" -> ping(guiGraphics, x, y, 0, 3, width, height);
            case "minecraft:server_list/ping_1" -> ping(guiGraphics, x, y, 0, 4, width, height);
            case "minecraft:server_list/pinging_1" -> ping(guiGraphics, x, y, 1, 0, width, height);
            case "minecraft:server_list/pinging_2" -> ping(guiGraphics, x, y, 1, 1, width, height);
            case "minecraft:server_list/pinging_3" -> ping(guiGraphics, x, y, 1, 2, width, height);
            case "minecraft:server_list/pinging_4" -> ping(guiGraphics, x, y, 1, 3, width, height);
            case "minecraft:server_list/pinging_5" -> ping(guiGraphics, x, y, 1, 4, width, height);
            case "minecraft:widget/scroller_background" -> guiGraphics.fill(x, y, x + width, y + height, 0xFF000000);
            case "minecraft:widget/scroller" -> {
                guiGraphics.fill(x, y, x + width, y + height, 0xFF808080);
                guiGraphics.fill(x, y, x + width - 1, y + height - 1, 0xFFC0C0C0);
            }
            default -> {
                // SharedWorld's own atlas sprites double as standalone textures pre-atlas.
                ResourceLocation texture = spriteTexture(spriteId);
                guiGraphics.blit(texture, x, y, 0.0F, 0.0F, width, height, width, height);
            }
        }
    }

    public static void favicon(GuiGraphics guiGraphics, FaviconTexture texture, int x, int y, int size) {
        guiGraphics.blit(texture.textureLocation(), x, y, 0.0F, 0.0F, size, size, size, size);
    }

    public static void footerSeparator(GuiGraphics guiGraphics, int y, int width) {
        guiGraphics.fill(0, y, width, y + 1, 0xFF000000);
        guiGraphics.fill(0, y + 1, width, y + 2, 0xFF303030);
    }

    private static void icon(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int u, int v, int width, int height) {
        guiGraphics.blit(texture, x, y, u, v, width, height, 256, 256);
    }

    private static void ping(GuiGraphics guiGraphics, int x, int y, int column, int row, int width, int height) {
        guiGraphics.blit(GUI_ICONS, x, y, column * 10, 176 + row * 8, width, height, 256, 256);
    }

    private static ResourceLocation spriteTexture(String spriteId) {
        int colon = spriteId.indexOf(':');
        String namespace = colon > 0 ? spriteId.substring(0, colon) : "minecraft";
        String path = colon > 0 ? spriteId.substring(colon + 1) : spriteId;
        return new ResourceLocation(namespace, "textures/gui/sprites/" + path + ".png");
    }
}
