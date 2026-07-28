package link.sharedworld.versioned;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Version-specific layout helpers for 1.20.x. The layout classes exist but predate
 * addTitleHeader/getContentHeight and the spacing-based LinearLayout factories; selection
 * lists are not layout elements yet, so the contents slot adds them to the screen directly.
 */
public final class LayoutCompat {
    private LayoutCompat() {
    }

    public static void addTitleHeader(HeaderAndFooterLayout layout, Component title, Font font) {
        layout.addToHeader(new StringWidget(title, font));
    }

    public static int contentHeight(HeaderAndFooterLayout layout) {
        return layout.getHeight() - layout.getHeaderHeight() - layout.getFooterHeight();
    }

    public static <L extends VersionedSelectionList<?>> L addContentsList(
            HeaderAndFooterLayout layout,
            L list,
            Consumer<? super L> screenAdder
    ) {
        screenAdder.accept(list);
        return list;
    }

    public static LinearLayout horizontalLayout(int spacing) {
        return new PackedLinearLayout(LinearLayout.Orientation.HORIZONTAL, spacing);
    }

    public static LinearLayout verticalLayout(int spacing) {
        return new PackedLinearLayout(LinearLayout.Orientation.VERTICAL, spacing);
    }

    public static LayoutSettings defaultCellSetting(LinearLayout layout) {
        return layout.defaultChildLayoutSetting();
    }

    /**
     * The 1.20.x LinearLayout justifies children across its fixed primary length; setting
     * that length to the packed size (children + fixed gaps) right before arranging makes
     * the leftover distribute as exactly one spacing per gap, matching the newer
     * LinearLayout.spacing() behavior.
     */
    private static final class PackedLinearLayout extends LinearLayout {
        private final Orientation packOrientation;
        private final int spacing;

        private PackedLinearLayout(Orientation orientation, int spacing) {
            super(0, 0, orientation);
            this.packOrientation = orientation;
            this.spacing = spacing;
        }

        @Override
        public void arrangeElements() {
            // Nested child layouts (e.g. the button rows inside a vertical footer)
            // report zero size until arranged; arrange them first so the packed
            // length is computed from real sizes. The later super call re-arranges
            // them idempotently before positioning.
            this.visitChildren((Consumer<LayoutElement>) child -> {
                if (child instanceof net.minecraft.client.gui.layouts.Layout childLayout) {
                    childLayout.arrangeElements();
                }
            });
            int[] childCount = {0};
            int[] primarySum = {0};
            this.visitChildren((Consumer<LayoutElement>) child -> {
                childCount[0]++;
                primarySum[0] += this.packOrientation == Orientation.HORIZONTAL ? child.getWidth() : child.getHeight();
            });
            int packed = primarySum[0] + this.spacing * Math.max(0, childCount[0] - 1);
            if (this.packOrientation == Orientation.HORIZONTAL) {
                this.width = packed;
            } else {
                this.height = packed;
            }
            super.arrangeElements();
        }
    }

    /** 1.20.x lists cannot ride the TabManager; add them to the screen once at init. */
    public static <L extends VersionedSelectionList<?>> L registerTabList(L list, Consumer<? super L> screenAdder) {
        screenAdder.accept(list);
        return list;
    }
}
