package link.sharedworld.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Consent gate for AUTOMATIC host takeovers: the previous host left and the
 * backend elected this player, but they never pressed anything; hosting must
 * not start (world download, port opening, lease ownership) without an
 * explicit yes. Declining hands the lease back gracefully so the next waiter
 * is elected instead. Closing the screen counts as declining.
 */
public final class ConfirmHostTakeoverScreen extends link.sharedworld.versioned.VersionedScreen {
    /**
     * Unanswered dialogs must never hold the assigned lease hostage (the
     * backend already claimed it for this player): after the countdown the
     * screen declines on its own, handing the lease to the next waiter.
     */
    private static final int AUTO_DECLINE_TICKS = 30 * 20;

    private final Screen parent;
    private final String worldName;
    private final Runnable accept;
    private final Runnable decline;
    private boolean decided;
    private int ticksShown;

    public ConfirmHostTakeoverScreen(Screen parent, String worldName, Runnable accept, Runnable decline) {
        super(Component.translatable("screen.sharedworld.takeover_title"));
        this.parent = parent;
        this.worldName = worldName;
        this.accept = accept;
        this.decline = decline;
    }

    @Override
    public void tick() {
        super.tick();
        this.ticksShown += 1;
        if (this.ticksShown >= AUTO_DECLINE_TICKS) {
            decide(this.decline);
        }
    }

    int secondsUntilAutoDecline() {
        return Math.max(0, (AUTO_DECLINE_TICKS - this.ticksShown) / 20);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.takeover_host"), button -> decide(this.accept))
                .bounds(centerX - 100, this.height - 76, 200, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.takeover_decline"), button -> decide(this.decline))
                .bounds(centerX - 100, this.height - 52, 200, 20)
                .build());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private void decide(Runnable action) {
        if (this.decided) {
            return;
        }
        this.decided = true;
        action.run();
    }

    @Override
    public void onClose() {
        decide(this.decline);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.getTitle(), this.width / 2, 40, 0xFFFFFF);
        List<FormattedCharSequence> lines = this.font.split(
                Component.translatable("screen.sharedworld.takeover_body", this.worldName == null ? "?" : this.worldName),
                this.width - 80
        );
        int y = 70;
        for (FormattedCharSequence line : lines) {
            graphics.drawCenteredString(this.font, line, this.width / 2, y, 0xC0C0C0);
            y += 12;
        }
        graphics.drawCenteredString(
                this.font,
                Component.translatable("screen.sharedworld.takeover_countdown", secondsUntilAutoDecline()),
                this.width / 2,
                y + 8,
                0x808080
        );
    }

    Screen parent() {
        return this.parent;
    }
}
