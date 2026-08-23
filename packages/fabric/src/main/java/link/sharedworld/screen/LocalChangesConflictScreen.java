package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.host.SharedWorldHostingManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The one place SharedWorld ever asks before touching unsaved progress. Shown
 * during host startup when the working copy holds changes that were never
 * uploaded (a release that failed or was abandoned) AND the shared copy has
 * moved on since; the only case with no automatic answer: uploading
 * supersedes the other host's newer version (restorable from Backups),
 * discarding deletes this computer's changes. Every other case is resolved
 * silently (publish-first when the shared copy is unchanged; plain sync when
 * nothing actually differs).
 *
 * <p>Discard needs a second click. Closing or the countdown cancels the
 * startup with the working copy intact; an unanswered dialog must neither
 * hold the host lease forever nor decide about data on its own.
 */
public final class LocalChangesConflictScreen extends link.sharedworld.versioned.VersionedScreen {
    private static final int AUTO_CANCEL_TICKS = 120 * 20;

    private final HostAcquiredScreen startupScreen;
    private final SharedWorldHostingManager.LocalChangesPrompt prompt;
    private Button discardButton;
    private boolean discardArmed;
    private boolean decided;
    private int ticksShown;

    public LocalChangesConflictScreen(HostAcquiredScreen startupScreen, SharedWorldHostingManager.LocalChangesPrompt prompt) {
        super(Component.translatable("screen.sharedworld.local_changes_title"));
        this.startupScreen = startupScreen;
        this.prompt = prompt;
    }

    @Override
    public void tick() {
        super.tick();
        this.ticksShown += 1;
        if (this.ticksShown >= AUTO_CANCEL_TICKS) {
            cancel();
        }
    }

    int secondsUntilAutoCancel() {
        return Math.max(0, (AUTO_CANCEL_TICKS - this.ticksShown) / 20);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.sharedworld.local_changes_upload"),
                        button -> decide(SharedWorldHostingManager.LocalChangesDecision.UPLOAD_LOCAL))
                .bounds(centerX - 100, this.height - 100, 200, 20)
                .build());
        this.discardButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.sharedworld.local_changes_discard"),
                        button -> {
                            if (!this.discardArmed) {
                                this.discardArmed = true;
                                button.setMessage(Component.translatable("screen.sharedworld.local_changes_discard_confirm"));
                                return;
                            }
                            decide(SharedWorldHostingManager.LocalChangesDecision.DISCARD_LOCAL);
                        })
                .bounds(centerX - 100, this.height - 76, 200, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.local_changes_cancel"), button -> cancel())
                .bounds(centerX - 100, this.height - 52, 200, 20)
                .build());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private void decide(SharedWorldHostingManager.LocalChangesDecision decision) {
        if (this.decided) {
            return;
        }
        this.decided = true;
        SharedWorldClient.hostingManager().resolveLocalChanges(decision);
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, this.startupScreen);
    }

    private void cancel() {
        if (this.decided) {
            return;
        }
        this.decided = true;
        this.startupScreen.requestCancel();
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, this.startupScreen);
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.getTitle(), this.width / 2, 30, 0xFFFFFF);
        List<FormattedCharSequence> lines = this.font.split(
                Component.translatable(
                        "screen.sharedworld.local_changes_body",
                        this.prompt.worldName() == null ? "?" : this.prompt.worldName(),
                        friendlySince(this.prompt.since())
                ),
                this.width - 80
        );
        int y = 54;
        for (FormattedCharSequence line : lines) {
            graphics.drawCenteredString(this.font, line, this.width / 2, y, 0xC0C0C0);
            y += 12;
        }
        graphics.drawCenteredString(
                this.font,
                Component.translatable("screen.sharedworld.local_changes_countdown", secondsUntilAutoCancel()),
                this.width / 2,
                Math.max(y + 8, this.height - 116),
                0x808080
        );
    }

    /** ISO instant → "2026-08-17 12:57" in local time; anything unparseable is shown as-is. */
    static String friendlySince(String since) {
        if (since == null || since.isBlank()) {
            return "?";
        }
        try {
            java.time.ZonedDateTime local = java.time.Instant.parse(since).atZone(java.time.ZoneId.systemDefault());
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(local);
        } catch (RuntimeException exception) {
            return since;
        }
    }
}
