package link.sharedworld.devhelper.e2e;

import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic screen driving for the e2e driver: find buttons by the translation
 * key of their label and press them, exactly as a player would. Buttons are
 * addressed by translation key because keys are the stable contract
 * (SharedWorldLocalizationParityTest pins them against the lang files).
 */
final class WidgetAutomation {
    private WidgetAutomation() {
    }

    /** A plain Enter press, as if the button were focused and activated. */
    private static final InputWithModifiers ENTER_PRESS = new InputWithModifiers() {
        @Override
        public int input() {
            return 257;
        }

        @Override
        public int modifiers() {
            return 0;
        }
    };

    static boolean pressButton(Screen screen, String translationKey) {
        AbstractButton button = findButton(screen, translationKey);
        if (button == null || !button.active || !button.visible) {
            return false;
        }
        button.onPress(ENTER_PRESS);
        return true;
    }

    static AbstractButton findButton(Screen screen, String translationKey) {
        for (AbstractButton button : collectButtons(screen)) {
            if (translationKey.equals(translationKey(button.getMessage()))) {
                return button;
            }
        }
        return null;
    }

    static boolean hasActiveButton(Screen screen, String translationKey) {
        AbstractButton button = findButton(screen, translationKey);
        return button != null && button.active && button.visible;
    }

    /** Visible regardless of enabled state — step detection on wizard screens whose widgets toggle visibility. */
    static boolean hasVisibleButton(Screen screen, String translationKey) {
        AbstractButton button = findButton(screen, translationKey);
        return button != null && button.visible;
    }

    private static List<AbstractButton> collectButtons(ContainerEventHandler container) {
        List<AbstractButton> buttons = new ArrayList<>();
        collectButtons(container, buttons);
        return buttons;
    }

    private static void collectButtons(ContainerEventHandler container, List<AbstractButton> sink) {
        for (GuiEventListener child : container.children()) {
            if (child instanceof AbstractButton button) {
                sink.add(button);
            }
            if (child instanceof ContainerEventHandler nested) {
                collectButtons(nested, sink);
            }
        }
    }

    private static String translationKey(Component message) {
        if (message.getContents() instanceof TranslatableContents translatable) {
            return translatable.getKey();
        }
        return null;
    }
}
