package link.sharedworld.screen;

import net.minecraft.client.gui.components.events.GuiEventListener;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedWorldScreenCloseTest {
    private static final class StubListener implements GuiEventListener {
        @Override
        public void setFocused(boolean focused) {
        }

        @Override
        public boolean isFocused() {
            return false;
        }
    }

    @Test
    void neverShownParentIsNotClosedThrough() {
        assertFalse(SharedWorldScreen.canCloseThroughParent(List.of()));
    }

    @Test
    void initializedParentIsClosedThrough() {
        assertTrue(SharedWorldScreen.canCloseThroughParent(List.of(new StubListener())));
    }
}
