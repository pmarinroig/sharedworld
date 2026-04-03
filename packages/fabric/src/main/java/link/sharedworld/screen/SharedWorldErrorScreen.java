package link.sharedworld.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public final class SharedWorldErrorScreen extends Screen {
    private final Screen parent;
    private final Component body;
    private final Component buttonLabel;
    private final Runnable onBack;
    private final Runnable onCloseAction;

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
        super(title);
        this.parent = parent;
        this.body = body;
        this.buttonLabel = buttonLabel;
        this.onBack = onBack;
        this.onCloseAction = onCloseAction;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.addRenderableWidget(Button.builder(this.buttonLabel, button -> {
                    if (this.onBack != null) {
                        this.onBack.run();
                    } else {
                        this.minecraft.setScreen(this.parent);
                    }
                })
                .bounds(centerX - 100, this.height - 28, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int top = this.height / 2 - 35;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, top, 0xFFFFFFFF);
        List<FormattedCharSequence> lines = this.font.split(this.body, Math.min(this.width - 60, 320));
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
            this.minecraft.setScreen(this.parent);
        }
    }
}
