package link.sharedworld.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Word-wrapped text at the font's 9px line pitch. */
final class WrappedText {
    private static final int LINE_HEIGHT = 9;

    private WrappedText() {
    }

    /** Draws text wrapped to width and returns the Y just below the last line. */
    static int draw(GuiGraphics guiGraphics, Font font, Component text, int x, int y, int width, int color) {
        List<FormattedCharSequence> lines = font.split(text, width);
        for (int index = 0; index < lines.size(); index++) {
            guiGraphics.drawString(font, lines.get(index), x, y + index * LINE_HEIGHT, color);
        }
        return y + lines.size() * LINE_HEIGHT;
    }
}
