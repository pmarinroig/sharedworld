package link.sharedworld.progress;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LoadingDotsWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;

public final class SharedWorldProgressRenderer {
    private static final int TITLE_Y_OFFSET = -36;
    private static final int LABEL_Y_OFFSET = -8;
    private static final int INDICATOR_Y_OFFSET = 12;
    private static final int PROGRESS_BAR_HEIGHT = 10;
    private static final int DOTS_Y_NUDGE = -4;

    private SharedWorldProgressRenderer() {
    }

    public static void renderCentered(GuiGraphics guiGraphics, Font font, int width, int height, SharedWorldProgressState state, float partialTick) {
        int centerX = width / 2;
        int centerY = height / 2;
        boolean hasTitle = state.title() != null && !state.title().getString().isBlank();
        int titleY = centerY + TITLE_Y_OFFSET;
        int labelY = centerY + LABEL_Y_OFFSET;
        int indicatorTop = centerY + INDICATOR_Y_OFFSET;

        if (hasTitle) {
            guiGraphics.drawCenteredString(font, state.title(), centerX, titleY, 0xFFFFFFFF);
        }
        guiGraphics.drawCenteredString(font, state.label(), centerX, labelY, 0xFFFFFFFF);

        if (state.mode() == SharedWorldProgressState.ProgressMode.DETERMINATE && state.displayedFraction() != null) {
            int halfWidth = Math.min((int) (width * 0.75D), height) / 4;
            int left = centerX - halfWidth;
            int top = indicatorTop;
            int right = centerX + halfWidth;
            int bottom = top + PROGRESS_BAR_HEIGHT;
            drawProgressBar(guiGraphics, left, top, right, bottom, state.displayedFraction().floatValue());
            return;
        }

        LoadingDotsWidget dotsWidget = new LoadingDotsWidget(font, Component.empty());
        dotsWidget.setX(centerX - (dotsWidget.getWidth() / 2));
        int indicatorCenterY = indicatorTop + (PROGRESS_BAR_HEIGHT / 2);
        dotsWidget.setY(indicatorCenterY - (dotsWidget.getHeight() / 2) + DOTS_Y_NUDGE);
        dotsWidget.render(guiGraphics, 0, 0, partialTick);
    }

    public static void renderCenteredBar(
            GuiGraphics guiGraphics,
            Font font,
            int width,
            int height,
            Component title,
            Component label,
            float progress,
            Float activityStart,
            Float activityEnd,
            float partialTick
    ) {
        int centerX = width / 2;
        int centerY = height / 2;
        boolean hasTitle = title != null && !title.getString().isBlank();
        int titleY = centerY + TITLE_Y_OFFSET;
        int labelY = centerY + LABEL_Y_OFFSET;
        int indicatorTop = centerY + INDICATOR_Y_OFFSET;

        if (hasTitle) {
            guiGraphics.drawCenteredString(font, title, centerX, titleY, 0xFFFFFFFF);
        }
        guiGraphics.drawCenteredString(font, label, centerX, labelY, 0xFFFFFFFF);

        int halfWidth = Math.min((int) (width * 0.75D), height) / 4;
        int left = centerX - halfWidth;
        int top = indicatorTop;
        int right = centerX + halfWidth;
        int bottom = top + PROGRESS_BAR_HEIGHT;
        drawProgressBar(guiGraphics, left, top, right, bottom, Mth.clamp(progress, 0.0F, 1.0F));

        if (activityStart == null || activityEnd == null) {
            return;
        }

        float clampedStart = Mth.clamp(activityStart, 0.0F, 1.0F);
        float clampedEnd = Mth.clamp(activityEnd, clampedStart, 1.0F);
        if (clampedEnd <= clampedStart) {
            return;
        }

        float segmentWidth = Math.max(0.025F, (clampedEnd - clampedStart) * 0.28F);
        float travel = Math.max(0.0F, (clampedEnd - clampedStart) - segmentWidth);
        float cycle = (float) ((Util.getMillis() % 1400L) / 1400.0D);
        float pingPong = cycle <= 0.5F ? cycle * 2.0F : (1.0F - cycle) * 2.0F;
        float highlightStart = clampedStart + (travel * pingPong);
        float highlightEnd = Math.min(clampedEnd, highlightStart + segmentWidth);
        drawProgressSegment(guiGraphics, left, top, right, bottom, highlightStart, highlightEnd, ARGB.color(200, 255, 255, 255));
    }

    private static void drawProgressBar(GuiGraphics guiGraphics, int left, int top, int right, int bottom, float progress) {
        guiGraphics.fill(left + 1, top + 1, right - 1, bottom - 1, ARGB.color(80, 255, 255, 255));
        int filledWidth = (int) Math.ceil((right - left - 2) * Math.max(0.0F, Math.min(1.0F, progress)));
        int color = ARGB.color(255, 255, 255, 255);
        guiGraphics.fill(left + 2, top + 2, left + filledWidth, bottom - 2, color);
        guiGraphics.fill(left + 1, top, right - 1, top + 1, color);
        guiGraphics.fill(left + 1, bottom, right - 1, bottom - 1, color);
        guiGraphics.fill(left, top, left + 1, bottom, color);
        guiGraphics.fill(right, top, right - 1, bottom, color);
    }

    private static void drawProgressSegment(
            GuiGraphics guiGraphics,
            int left,
            int top,
            int right,
            int bottom,
            float startFraction,
            float endFraction,
            int color
    ) {
        int innerLeft = left + 2;
        int innerRight = right - 2;
        int width = Math.max(0, innerRight - innerLeft);
        int segmentLeft = innerLeft + Math.round(width * startFraction);
        int segmentRight = innerLeft + Math.round(width * endFraction);
        if (segmentRight <= segmentLeft) {
            return;
        }
        guiGraphics.fill(segmentLeft, top + 2, segmentRight, bottom - 2, color);
    }
}
