package link.sharedworld.versioned;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version-specific GUI draw calls for Minecraft 1.21 and 1.21.1, which draw sprites without
 * a render-pipeline argument. Sprite and texture identifiers cross this
 * seam as plain "namespace:path" strings so shared screen code never names the class.
 */
public final class GuiBlit {
    private static final Map<String, ResourceLocation> IDENTIFIERS = new ConcurrentHashMap<>();

    private GuiBlit() {
    }

    public static void sprite(GuiGraphics guiGraphics, String spriteId, int x, int y, int width, int height) {
        guiGraphics.blitSprite(identifier(spriteId), x, y, width, height);
    }

    public static void favicon(GuiGraphics guiGraphics, FaviconTexture texture, int x, int y, int size) {
        guiGraphics.blit(texture.textureLocation(), x, y, 0, 0.0F, 0.0F, size, size, size, size);
    }

    public static void footerSeparator(GuiGraphics guiGraphics, int y, int width) {
        guiGraphics.blit(Screen.FOOTER_SEPARATOR, 0, y, 0, 0.0F, 0.0F, width, 2, 32, 2);
    }

    private static ResourceLocation identifier(String id) {
        return IDENTIFIERS.computeIfAbsent(id, ResourceLocation::parse);
    }
}
