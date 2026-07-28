package link.sharedworld.versioned;

import net.minecraft.client.gui.components.AbstractWidget;

/** Version-specific widget geometry helpers (setPosition/getBottom do not exist on 1.20.x). */
public final class WidgetCompat {
    private WidgetCompat() {
    }

    public static void setPosition(AbstractWidget widget, int x, int y) {
        widget.setX(x);
        widget.setY(y);
    }

    public static int bottom(AbstractWidget widget) {
        return widget.getY() + widget.getHeight();
    }
}
