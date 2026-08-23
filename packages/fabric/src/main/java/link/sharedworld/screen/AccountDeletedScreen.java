package link.sharedworld.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

/**
 * Terminal screen after a full account deletion. Every exit lands on the
 * vanilla multiplayer screen; never back into SharedWorld UI, which would
 * re-authenticate and recreate an account.
 */
public final class AccountDeletedScreen extends link.sharedworld.versioned.VersionedScreen {
    public AccountDeletedScreen() {
        super(Component.translatable("screen.sharedworld.account_deleted_title"));
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height / 6 + 96, 200, 20)
                .build());
    }

    @Override
    public void onClose() {
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new JoinMultiplayerScreen(new TitleScreen()));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 6 + 20, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("screen.sharedworld.account_deleted_body"),
                this.width / 2,
                this.height / 6 + 48,
                0xFFB0B0B0
        );
    }
}
