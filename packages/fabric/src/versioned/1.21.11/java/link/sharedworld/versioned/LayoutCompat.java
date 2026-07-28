package link.sharedworld.versioned;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Version-specific layout helpers (title header, contents slot, LinearLayout factories). */
public final class LayoutCompat {
    private LayoutCompat() {
    }

    public static void addTitleHeader(HeaderAndFooterLayout layout, Component title, Font font) {
        layout.addTitleHeader(title, font);
    }

    public static int contentHeight(HeaderAndFooterLayout layout) {
        return layout.getContentHeight();
    }

    /**
     * Registers the central selection list with the layout where lists are layout elements,
     * or adds it straight to the screen where they are not (the screenAdder is used there).
     */
    public static <L extends VersionedSelectionList<?>> L addContentsList(
            HeaderAndFooterLayout layout,
            L list,
            Consumer<? super L> screenAdder
    ) {
        layout.addToContents(list);
        return list;
    }

    public static LinearLayout horizontalLayout(int spacing) {
        return LinearLayout.horizontal().spacing(spacing);
    }

    public static LinearLayout verticalLayout(int spacing) {
        return LinearLayout.vertical().spacing(spacing);
    }

    public static LayoutSettings defaultCellSetting(LinearLayout layout) {
        return layout.defaultCellSetting();
    }
}
