package link.sharedworld.versioned;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LoadingDotsWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Version-specific GUI helpers whose home or shape moved across Minecraft versions. */
public final class GuiCompat {
    private GuiCompat() {
    }

    public static int argb(int alpha, int red, int green, int blue) {
        return ARGB.color(alpha, red, green, blue);
    }

    /** Defers a tooltip to the end of the frame (position args are ignored where vanilla auto-places). */
    public static void deferTooltip(GuiGraphics guiGraphics, Minecraft minecraft, Component tooltip, int x, int y) {
        guiGraphics.setTooltipForNextFrame(tooltip, x, y);
    }

    public static void deferTooltip(GuiGraphics guiGraphics, Minecraft minecraft, List<FormattedCharSequence> lines, int x, int y) {
        guiGraphics.setTooltipForNextFrame(lines, x, y);
    }

    /** Renders the vanilla animated loading dots centered at the given point. */
    public static void renderLoadingDots(GuiGraphics guiGraphics, Font font, int centerX, int centerY, float partialTick) {
        LoadingDotsWidget dotsWidget = new LoadingDotsWidget(font, Component.empty());
        dotsWidget.setX(centerX - (dotsWidget.getWidth() / 2));
        dotsWidget.setY(centerY - (dotsWidget.getHeight() / 2));
        dotsWidget.render(guiGraphics, 0, 0, partialTick);
    }

    public static void clearFocus(Screen screen) {
        screen.clearFocus();
    }
}
