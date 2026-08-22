package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.StorageAccountSummaryDto;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Per-player account management: shows the Google Drive link status and hosts
 * the two account-scoped actions — unlink (blocked server-side while worlds
 * still live on that Drive) and full data deletion.
 */
public final class AccountScreen extends link.sharedworld.versioned.VersionedScreen {
    private static final long SUCCESS_STATUS_TTL_MS = 7_000L;

    private final SharedWorldScreen parent;
    private final SharedWorldStatusBanner statusBanner = new SharedWorldStatusBanner();
    /** Poll cadence + cap for the reconnect OAuth flow (~5 min, server TTL is longer). */
    private static final long RECONNECT_POLL_MS = 2_000L;
    private static final int RECONNECT_POLL_LIMIT = 150;

    private StorageAccountSummaryDto account;
    private boolean accountCheckStarted;
    private boolean accountCheckFinished;
    private boolean unlinkArmed;
    private boolean unlinkInFlight;
    private boolean reconnectInFlight;
    /** Bumped to orphan a running reconnect poll (screen left, retry pressed). */
    private volatile int reconnectGeneration;
    private Button unlinkButton;
    private Button reconnectButton;
    private Button deleteAllButton;

    public AccountScreen(SharedWorldScreen parent) {
        super(Component.translatable("screen.sharedworld.account_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = this.height / 6 + 50;
        this.reconnectButton = this.addRenderableWidget(Button.builder(this.reconnectLabel(), button -> this.onReconnectPressed())
                .bounds(centerX - 100, top, 200, 20)
                .build());
        this.unlinkButton = this.addRenderableWidget(Button.builder(this.unlinkLabel(), button -> this.onUnlinkPressed())
                .bounds(centerX - 100, top + 26, 200, 20)
                .build());
        this.deleteAllButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.account_delete_all"), button -> {
                    link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new DeleteAccountConfirmScreen(this));
                })
                .bounds(centerX - 100, top + 52, 200, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> this.onClose())
                .bounds(centerX - 100, top + 96, 200, 20)
                .build());
        this.updateButtons();
        this.beginAccountCheckOnce();
    }

    @Override
    public void onClose() {
        this.reconnectGeneration++;
        // Returning to a live parent revives whatever widget was focused when
        // we left (the Account button would keep its outline).
        link.sharedworld.versioned.GuiCompat.clearFocus(this.parent);
        this.parent.clearTransientFocus();
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, this.parent);
    }

    @Override
    public void removed() {
        this.reconnectGeneration++;
        super.removed();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 6 - 6, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(this.font, this.statusLine(), this.width / 2, this.height / 6 + 22, 0xFFB0B0B0);
        this.statusBanner.renderBottomCentered(guiGraphics, this.font, this.width / 2, this.height - 30, Math.min(this.width - 40, 420));
    }

    private Component statusLine() {
        if (!this.accountCheckFinished) {
            return Component.translatable("screen.sharedworld.account_status_loading");
        }
        if (this.account == null || !this.account.linked()) {
            return Component.translatable("screen.sharedworld.account_status_not_linked");
        }
        String who = this.account.email() != null ? this.account.email() : "?";
        return Component.translatable(this.account.healthy()
                ? "screen.sharedworld.account_status_linked"
                : "screen.sharedworld.account_status_linked_unhealthy", who);
    }

    private Component unlinkLabel() {
        return Component.translatable(this.unlinkArmed
                ? "screen.sharedworld.account_unlink_confirm"
                : "screen.sharedworld.account_unlink");
    }

    private Component reconnectLabel() {
        boolean linked = this.account != null && this.account.linked();
        return Component.translatable(linked
                ? "screen.sharedworld.account_reconnect"
                : "screen.sharedworld.account_connect");
    }

    /**
     * The repair path for a dead Google grant (revoked, expired refresh
     * token): a fresh forced-consent OAuth round in the browser, polled here
     * until it lands on the same account row.
     */
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
            } else {
                this.statusBanner.setTransient(SharedWorldStatusBanner.Kind.SUCCESS,
                        Component.translatable("screen.sharedworld.account_reconnect_done"), SUCCESS_STATUS_TTL_MS);
                // Re-fetch the summary so the status line reflects the fresh link.
                this.accountCheckStarted = false;
                this.accountCheckFinished = false;
                this.beginAccountCheckOnce();
            }
            this.updateButtons();
        });
    }

    private void beginAccountCheckOnce() {
        if (this.accountCheckStarted) {
            return;
        }
        this.accountCheckStarted = true;
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return SharedWorldClient.apiClient().getStorageAccount();
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((result, error) -> ScreenGuards.runIfCurrent(this, () -> {
                    this.accountCheckFinished = true;
                    if (error != null) {
                        this.statusBanner.set(SharedWorldStatusBanner.Kind.ERROR,
                                Component.literal(SharedWorldText.errorMessageOrDefault(rootCause(error).getMessage())));
                    } else {
                        this.account = result;
                    }
                    this.updateButtons();
                }));
    }

    private void onUnlinkPressed() {
        if (this.unlinkInFlight) {
            return;
        }
        if (!this.unlinkArmed) {
            this.unlinkArmed = true;
            this.updateButtons();
            return;
        }
        this.unlinkArmed = false;
        this.unlinkInFlight = true;
        this.updateButtons();
        CompletableFuture
                .runAsync(() -> {
                    try {
                        SharedWorldClient.apiClient().unlinkStorageAccount();
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((ignored, error) -> ScreenGuards.runIfCurrent(this, () -> {
                    this.unlinkInFlight = false;
                    if (error != null) {
                        // 409 storage_unlink_blocked arrives with the server's
                        // actionable message ("delete your worlds first").
                        this.statusBanner.set(SharedWorldStatusBanner.Kind.ERROR,
                                Component.literal(SharedWorldText.errorMessageOrDefault(rootCause(error).getMessage())));
                    } else {
                        this.account = new StorageAccountSummaryDto(false, this.account == null ? null : this.account.provider(), null, false);
                        this.statusBanner.setTransient(SharedWorldStatusBanner.Kind.SUCCESS,
                                Component.translatable("screen.sharedworld.account_unlink_done"), SUCCESS_STATUS_TTL_MS);
                    }
                    this.updateButtons();
                }));
    }

    private void updateButtons() {
        boolean busy = this.unlinkInFlight || this.reconnectInFlight;
        if (this.unlinkButton != null) {
            this.unlinkButton.setMessage(this.unlinkLabel());
            this.unlinkButton.active = this.accountCheckFinished
                    && this.account != null && this.account.linked() && !busy;
        }
        if (this.reconnectButton != null) {
            this.reconnectButton.setMessage(this.reconnectLabel());
            this.reconnectButton.active = this.accountCheckFinished && !busy;
        }
        if (this.deleteAllButton != null) {
            this.deleteAllButton.active = !busy;
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
