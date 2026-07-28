package link.sharedworld.versioned;

import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LayoutCompatTest {
    private static final class StubElement implements LayoutElement {
        private int x;
        private int y;
        private final int width;
        private final int height;

        private StubElement(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public void setX(int x) {
            this.x = x;
        }

        @Override
        public void setY(int y) {
            this.y = y;
        }

        @Override
        public int getX() {
            return this.x;
        }

        @Override
        public int getY() {
            return this.y;
        }

        @Override
        public int getWidth() {
            return this.width;
        }

        @Override
        public int getHeight() {
            return this.height;
        }

        @Override
        public void visitWidgets(Consumer<net.minecraft.client.gui.components.AbstractWidget> consumer) {
        }
    }

    @Test
    void horizontalLayoutPacksChildrenWithFixedSpacing() {
        LinearLayout row = LayoutCompat.horizontalLayout(4);
        StubElement first = row.addChild(new StubElement(74, 20));
        StubElement second = row.addChild(new StubElement(74, 20));
        StubElement third = row.addChild(new StubElement(74, 20));
        row.arrangeElements();

        assertEquals(0, first.getX());
        assertEquals(78, second.getX());
        assertEquals(156, third.getX());
        assertEquals(74 * 3 + 4 * 2, row.getWidth());
    }

    @Test
    void nestedRowsInsideVerticalLayoutStackInOrderWithSpacing() {
        LinearLayout footer = LayoutCompat.verticalLayout(4);
        LinearLayout topRow = footer.addChild(LayoutCompat.horizontalLayout(4));
        StubElement topButton = topRow.addChild(new StubElement(74, 20));
        LinearLayout bottomRow = footer.addChild(LayoutCompat.horizontalLayout(4));
        StubElement bottomButton = bottomRow.addChild(new StubElement(74, 20));

        footer.arrangeElements();

        // The rows report zero height until arranged; the packed vertical layout
        // must arrange them first, or the second row lands on top of the first.
        assertEquals(0, topButton.getY());
        assertEquals(24, bottomButton.getY());
        assertEquals(44, footer.getHeight());
    }
}
