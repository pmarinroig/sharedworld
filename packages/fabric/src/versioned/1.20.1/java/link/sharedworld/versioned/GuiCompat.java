package link.sharedworld.versioned;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingDotsText;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Version-specific GUI helpers whose home or shape moved across Minecraft versions. */
public final class GuiCompat {
    private GuiCompat() {
    }

    public static int argb(int alpha, int red, int green, int blue) {
        return FastColor.ARGB32.color(alpha, red, green, blue);
    }

    /** Defers a tooltip to the end of the frame (position args are ignored where vanilla auto-places). */
    public static void deferTooltip(GuiGraphics guiGraphics, Minecraft minecraft, Component tooltip, int x, int y) {
        if (minecraft.screen != null) {
            minecraft.screen.setTooltipForNextRenderPass(List.of(tooltip.getVisualOrderText()));
        }
    }

    public static void deferTooltip(GuiGraphics guiGraphics, Minecraft minecraft, List<FormattedCharSequence> lines, int x, int y) {
        if (minecraft.screen != null) {
            minecraft.screen.setTooltipForNextRenderPass(lines);
        }
    }

    /** Renders the vanilla animated loading dots centered at the given point. */
    public static void renderLoadingDots(GuiGraphics guiGraphics, Font font, int centerX, int centerY, float partialTick) {
        String dots = LoadingDotsText.get(net.minecraft.Util.getMillis());
        guiGraphics.drawString(font, dots, centerX - font.width(dots) / 2, centerY - font.lineHeight / 2, 0xFF808080, false);
    }

    public static void clearFocus(Screen screen) {
        screen.setFocused(null);
    }
}
