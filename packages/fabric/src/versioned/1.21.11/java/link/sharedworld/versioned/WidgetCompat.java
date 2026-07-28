package link.sharedworld.versioned;

import net.minecraft.client.gui.components.AbstractWidget;

/** Version-specific widget geometry helpers (setPosition/getBottom are recent additions). */
public final class WidgetCompat {
    private WidgetCompat() {
    }

    public static void setPosition(AbstractWidget widget, int x, int y) {
        widget.setPosition(x, y);
    }

    public static int bottom(AbstractWidget widget) {
        return widget.getBottom();
    }
}
