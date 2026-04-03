package link.sharedworld.screen;

import link.sharedworld.CanonicalPlayerIdentity;
import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.UncleanShutdownWarningDto;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public final class UncleanShutdownWarningScreen extends Screen {
    private final Screen parent;
    private final String worldId;
    private final String worldName;
    private final WorldRuntimeStatusDto runtimeStatus;
    private Button launchAnywayButton;
    private boolean launchConfirmationRequested;
    private boolean launchRequested;

    public UncleanShutdownWarningScreen(Screen parent, String worldId, String worldName, WorldRuntimeStatusDto runtimeStatus) {
        super(Component.translatable("screen.sharedworld.unclean_shutdown_title"));
        this.parent = parent;
        this.worldId = worldId;
        this.worldName = worldName;
        this.runtimeStatus = runtimeStatus;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.cancel"), button -> this.cancelLaunch())
                .bounds(centerX - 100, this.height - 52, 200, 20)
                .build());
        this.launchAnywayButton = this.addRenderableWidget(Button.builder(this.launchAnywayLabel(), button -> this.launchAnyway())
                .bounds(centerX - 100, this.height - 28, 200, 20)
                .build());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int top = this.height / 2 - 56;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, top, 0xFFFFFFFF);
        List<FormattedCharSequence> lines = this.font.split(
                this.noticeMessage(),
                Math.min(this.width - 60, 320)
        );
        int y = top + 24;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawCenteredString(this.font, line, centerX, y, 0xFFFFFFFF);
            y += 12;
        }
    }

    private String crashedHostName() {
        UncleanShutdownWarningDto warning = this.runtimeStatus == null ? null : this.runtimeStatus.uncleanShutdownWarning();
        if (warning == null || warning.hostPlayerName() == null || warning.hostPlayerName().isBlank()) {
            return SharedWorldText.string("screen.sharedworld.unknown");
        }
        return warning.hostPlayerName();
    }

    private void launchAnyway() {
        if (!this.isOwnUncleanShutdown() && !this.launchConfirmationRequested) {
            this.launchConfirmationRequested = true;
            if (this.launchAnywayButton != null) {
                this.launchAnywayButton.setMessage(this.launchAnywayLabel());
            }
            return;
        }
        if (this.launchRequested) {
            return;
        }
        this.launchRequested = true;
        SharedWorldClient.sessionCoordinator().acknowledgeUncleanShutdown(this.parent, this.worldId, SharedWorldText.displayWorldName(this.worldName));
    }

    private Component launchAnywayLabel() {
        if (this.isOwnUncleanShutdown()) {
            return Component.translatable("screen.sharedworld.unclean_shutdown_launch");
        }
        return Component.translatable(this.launchConfirmationRequested
                ? "screen.sharedworld.unclean_shutdown_confirm_launch"
                : "screen.sharedworld.unclean_shutdown_launch_anyway");
    }

    private Component noticeMessage() {
        if (this.isOwnUncleanShutdown()) {
            return Component.translatable(
                    "screen.sharedworld.unclean_shutdown_notice_self",
                    SharedWorldText.displayWorldName(this.worldName)
            );
        }
        return Component.translatable(
                "screen.sharedworld.unclean_shutdown_notice",
                SharedWorldText.displayWorldName(this.worldName),
                crashedHostName()
        );
    }

    private boolean isOwnUncleanShutdown() {
        UncleanShutdownWarningDto warning = this.runtimeStatus == null ? null : this.runtimeStatus.uncleanShutdownWarning();
        return warning != null
                && warning.hostUuid() != null
                && !warning.hostUuid().isBlank()
                && CanonicalPlayerIdentity.sameUuid(
                        warning.hostUuid(),
                        SharedWorldApiClient.currentWorldPlayerUuidWithHyphens()
                );
    }

    private void cancelLaunch() {
        if (this.parent != null) {
            this.minecraft.setScreen(this.parent);
            return;
        }
        SharedWorldClient.openMainScreen(new JoinMultiplayerScreen(new TitleScreen()));
    }
}
