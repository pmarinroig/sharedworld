package link.sharedworld.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The last stop before full account deletion. The copy is deliberately blunt:
 * it enumerates exactly what is destroyed, including the local playable world
 * copies, and the red-flag button needs a second click to fire.
 */
public final class DeleteAccountConfirmScreen extends link.sharedworld.versioned.VersionedScreen {
    private final SettingsScreen parent;
    private boolean armed;
    private Button confirmButton;

    public DeleteAccountConfirmScreen(SettingsScreen parent) {
        super(Component.translatable("screen.sharedworld.account_delete_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.armed = false;
        this.confirmButton = this.addRenderableWidget(Button.builder(this.confirmLabel(), button -> this.onConfirmPressed())
                .bounds(this.width / 2 - 155, this.height / 6 + 150, 150, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.cancel"),
                        button -> link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, this.parent))
                .bounds(this.width / 2 + 5, this.height / 6 + 150, 150, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int y = this.height / 6 - 6;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, y, 0xFFFF5555);
        y += 26;
        String[] bullets = {
                "screen.sharedworld.account_delete_bullet_worlds",
                "screen.sharedworld.account_delete_bullet_drive",
                "screen.sharedworld.account_delete_bullet_memberships",
                "screen.sharedworld.account_delete_bullet_local"
        };
        for (String key : bullets) {
            guiGraphics.drawCenteredString(this.font, Component.translatable(key), centerX, y, 0xFFFFFFFF);
            y += 14;
        }
        y += 10;
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("screen.sharedworld.account_delete_export_hint"), centerX, y, 0xFFFFD37A);
    }

    private Component confirmLabel() {
        return Component.translatable(this.armed
                ? "screen.sharedworld.account_delete_confirm_armed"
                : "screen.sharedworld.account_delete_confirm");
    }

    private void onConfirmPressed() {
        if (!this.armed) {
            this.armed = true;
            this.confirmButton.setMessage(this.confirmLabel());
            return;
        }
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new DeleteAccountProgressScreen(this.parent));
    }
}
