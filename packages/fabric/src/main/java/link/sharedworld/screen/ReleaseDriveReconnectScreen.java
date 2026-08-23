package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldClientLifecycleRouter;
import link.sharedworld.SharedWorldText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The quit-time upload failed because the Google Drive grant is dead. The
 * lifecycle router parks navigation on the release error screen until the
 * upload is resolved, which makes the usual repair path (SettingsScreen's
 * Reconnect button) unreachable — so the same forced-consent OAuth round
 * (see SettingsScreen#onReconnectPressed) runs right here, and a successful
 * reconnect retries the upload immediately.
 */
public final class ReleaseDriveReconnectScreen extends link.sharedworld.versioned.VersionedScreen {
    /** Poll cadence + cap for the reconnect OAuth flow (~5 min, server TTL is longer). */
    private static final long RECONNECT_POLL_MS = 2_000L;
    private static final int RECONNECT_POLL_LIMIT = 150;

    private final Screen parent;
    private final Component body;
    private final SharedWorldStatusBanner statusBanner = new SharedWorldStatusBanner();
    private boolean reconnectInFlight;
    /** Bumped to orphan a running reconnect poll (screen left, retry pressed). */
    private volatile int reconnectGeneration;
    private Button reconnectButton;
    private Button retryButton;

    public ReleaseDriveReconnectScreen(Screen parent, Component title, Component body) {
        super(title);
        this.parent = parent;
        this.body = body;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.reconnectButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.sharedworld.account_reconnect"),
                        button -> this.onReconnectPressed())
                .bounds(centerX - 100, this.height - 54, 200, 20)
                .build());
        this.retryButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.sharedworld.retry_finalization"),
                        button -> this.retryUpload())
                .bounds(centerX - 100, this.height - 28, 200, 20)
                .build());
        this.updateButtons();
    }

    private void onReconnectPressed() {
        if (this.reconnectInFlight) {
            return;
        }
        this.reconnectInFlight = true;
        int generation = ++this.reconnectGeneration;
        this.statusBanner.set(SharedWorldStatusBanner.Kind.INFO,
                Component.translatable("screen.sharedworld.account_reconnect_waiting"));
        this.updateButtons();
        CompletableFuture
                .runAsync(() -> {
                    try {
                        var session = SharedWorldClient.apiClient().createStorageLink(true);
                        String url = session.authUrl();
                        this.minecraft.execute(() -> this.minecraft.keyboardHandler.setClipboard(url));
                        link.sharedworld.util.BrowserOpener.open(url);
                        for (int poll = 0; poll < RECONNECT_POLL_LIMIT; poll++) {
                            Thread.sleep(RECONNECT_POLL_MS);
                            if (generation != this.reconnectGeneration) {
                                return;
                            }
                            var polled = SharedWorldClient.apiClient().getStorageLink(session.id());
                            String status = polled.status() == null ? "" : polled.status();
                            if ("linked".equals(status)) {
                                this.onReconnectFinished(generation, null);
                                return;
                            }
                            if ("failed".equals(status) || "expired".equals(status) || "cancelled".equals(status)) {
                                this.onReconnectFinished(generation, SharedWorldText.errorMessageOrDefault(polled.errorMessage()));
                                return;
                            }
                        }
                        this.onReconnectFinished(generation, SharedWorldText.errorMessageOrDefault(null));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (Exception exception) {
                        this.onReconnectFinished(generation, SharedWorldText.errorMessageOrDefault(rootCause(exception).getMessage()));
                    }
                }, SharedWorldClient.ioExecutor());
    }

    private void onReconnectFinished(int generation, String errorMessage) {
        ScreenGuards.runIfCurrent(this, () -> {
            if (generation != this.reconnectGeneration) {
                return;
            }
            this.reconnectInFlight = false;
            if (errorMessage != null) {
                this.statusBanner.set(SharedWorldStatusBanner.Kind.ERROR, Component.literal(errorMessage));
                this.updateButtons();
                return;
            }
            this.statusBanner.set(SharedWorldStatusBanner.Kind.SUCCESS,
                    Component.translatable("screen.sharedworld.account_reconnect_done"));
            // The fix is in; the retry the player came here for should not
            // need a second button press.
            this.retryUpload();
        });
    }

    private void retryUpload() {
        var coordinator = SharedWorldClient.releaseCoordinator();
        // A false return means an auto-retry already pulled the release out of
        // the error phase; the saving screen shows that attempt's progress.
        coordinator.retry();
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft,
                SharedWorldClientLifecycleRouter.defaultSavingScreen(coordinator.activeWorldName()));
    }

    private void updateButtons() {
        if (this.reconnectButton != null) {
            this.reconnectButton.active = !this.reconnectInFlight;
        }
        if (this.retryButton != null) {
            this.retryButton.active = !this.reconnectInFlight;
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int top = this.height / 2 - 60;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, top, 0xFFFFFFFF);
        List<FormattedCharSequence> lines = this.font.split(this.body, Math.min(this.width - 60, 320));
        int y = top + 24;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawCenteredString(this.font, line, centerX, y, 0xFFFF8080);
            y += 12;
        }
        this.statusBanner.renderBottomCentered(guiGraphics, this.font, centerX, this.height - 60, Math.min(this.width - 40, 420));
    }

    @Override
    public void onClose() {
        // The router re-forces a lifecycle screen while the release is parked;
        // Esc deliberately does not offer an escape hatch from the upload.
        this.reconnectGeneration++;
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, this.parent);
    }

    @Override
    public void removed() {
        this.reconnectGeneration++;
        super.removed();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
