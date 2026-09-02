package link.sharedworld.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public final class SharedWorldErrorScreen extends link.sharedworld.versioned.VersionedScreen {
    private final Screen parent;
    private final Component body;
    private final Component buttonLabel;
    private final Runnable onBack;
    private final Runnable onCloseAction;
    private final Component secondaryLabel;
    private final Runnable onSecondary;

    public SharedWorldErrorScreen(Screen parent, Component title, Component body) {
        this(parent, title, body, Component.translatable("screen.sharedworld.return_to_sharedworld"));
    }

    public SharedWorldErrorScreen(Screen parent, Component title, Component body, Component buttonLabel) {
        this(parent, title, body, buttonLabel, null, null);
    }

    public SharedWorldErrorScreen(Screen parent, Component title, Component body, Component buttonLabel, Runnable onBack) {
        this(parent, title, body, buttonLabel, onBack, null);
    }

    public SharedWorldErrorScreen(Screen parent, Component title, Component body, Component buttonLabel, Runnable onBack, Runnable onCloseAction) {
        this(parent, title, body, buttonLabel, onBack, onCloseAction, null, null);
    }

    /** With a second, less prominent action drawn above the main button. */
    public SharedWorldErrorScreen(Screen parent, Component title, Component body, Component buttonLabel, Runnable onBack, Runnable onCloseAction, Component secondaryLabel, Runnable onSecondary) {
        super(title);
        this.parent = parent;
        this.body = body;
        this.buttonLabel = buttonLabel;
        this.onBack = onBack;
        this.onCloseAction = onCloseAction;
        this.secondaryLabel = secondaryLabel;
        this.onSecondary = onSecondary;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.addRenderableWidget(Button.builder(this.buttonLabel, button -> {
                    if (this.onBack != null) {
                        this.onBack.run();
                    } else {
                        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, this.parent);
                    }
                })
                .bounds(centerX - 100, this.height - 28, 200, 20)
                .build());
        if (this.secondaryLabel != null && this.onSecondary != null) {
            this.addRenderableWidget(Button.builder(this.secondaryLabel, button -> this.onSecondary.run())
                    .bounds(centerX - 100, this.height - 54, 200, 20)
                    .build());
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        List<FormattedCharSequence> lines = this.font.split(this.body, Math.min(this.width - 60, 320));
        // Centered when there is room; a long body over a two-button stack at
        // the minimum window height slides up so it never runs into a button.
        int firstButtonTop = this.height - (this.secondaryLabel != null && this.onSecondary != null ? 54 : 28);
        int top = Math.max(8, Math.min(this.height / 2 - 35, firstButtonTop - 8 - (24 + lines.size() * 12)));
        guiGraphics.drawCenteredString(this.font, this.title, centerX, top, 0xFFFFFFFF);
        int y = top + 24;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawCenteredString(this.font, line, centerX, y, 0xFFFF8080);
            y += 12;
        }
    }

    @Override
    public void onClose() {
        if (this.onCloseAction != null) {
            this.onCloseAction.run();
            return;
        }
        if (this.parent != null) {
            link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, this.parent);
        }
    }
}
