package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.StorageAccountSummaryDto;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static link.sharedworld.screen.EditScreenFormats.blankOr;

/**
 * Global, per-computer SharedWorld settings. The Storage tab manages the two
 * account-scoped storage links (Google Drive OAuth, S3 bucket form) plus full
 * data deletion; the Advanced tab holds the custom join address every member
 * may need when they end up hosting (worlds are edited per-world under Edit
 * world, but hosting reachability belongs to this machine).
 */
public final class SettingsScreen extends link.sharedworld.versioned.VersionedScreen {
    private static final int FOOTER_HEIGHT = 36;
    private static final long SUCCESS_STATUS_TTL_MS = 7_000L;
    /** Poll cadence + cap for the storage link flows (~5 min, server TTL is longer). */
    private static final long RECONNECT_POLL_MS = 2_000L;
    private static final int RECONNECT_POLL_LIMIT = 150;

    private final SharedWorldScreen parent;
    private final SharedWorldStatusBanner statusBanner = new SharedWorldStatusBanner();
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 0, FOOTER_HEIGHT);
    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    private final StorageTab storageTab = new StorageTab();
    private final AdvancedTab advancedTab = new AdvancedTab();

    private StorageAccountSummaryDto account;
    private StorageAccountSummaryDto s3Account;
    private boolean accountCheckStarted;
    private boolean accountCheckFinished;
    private boolean unlinkArmed;
    private boolean unlinkInFlight;
    private boolean s3UnlinkArmed;
    private boolean s3UnlinkInFlight;
    private boolean reconnectInFlight;
    /** Bumped to orphan a running reconnect poll (screen left, retry pressed). */
    private volatile int reconnectGeneration;

    private TabNavigationBar tabNavigationBar;
    private ScreenRectangle contentArea;
    private Tab lastTab;
    private Button driveConnectButton;
    private Button driveUnlinkButton;
    private Button s3ConnectButton;
    private Button s3UnlinkButton;
    private Button deleteAllButton;
    private Button backButton;
    private Button saveButton;
    private EditBox customJoinBox;
    /** Last value persisted to the local config store ("" = none). */
    private String savedCustomJoinAddress = "";

    public SettingsScreen(SharedWorldScreen parent) {
        super(Component.translatable("screen.sharedworld.settings_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearWidgets();

        LinearLayout footer = this.layout.addToFooter(link.sharedworld.versioned.LayoutCompat.horizontalLayout(8));
        this.backButton = footer.addChild(Button.builder(Component.translatable("gui.back"), ignored -> this.onClose())
                .width(150)
                .build());
        this.saveButton = footer.addChild(Button.builder(Component.translatable("screen.sharedworld.save_settings"), ignored -> this.saveAdvancedSettings())
                .width(150)
                .build());
        this.layout.visitWidgets(this::addRenderableWidget);

        this.driveConnectButton = Button.builder(this.driveConnectLabel(), button -> this.onDriveConnectPressed()).width(98).build();
        this.driveUnlinkButton = Button.builder(this.driveUnlinkLabel(), button -> this.onDriveUnlinkPressed()).width(98).build();
        this.s3ConnectButton = Button.builder(this.s3ConnectLabel(), button -> this.beginLinkFlow("s3", false)).width(98).build();
        this.s3UnlinkButton = Button.builder(this.s3UnlinkLabel(), button -> this.onS3UnlinkPressed()).width(98).build();
        this.deleteAllButton = Button.builder(Component.translatable("screen.sharedworld.account_delete_all"), button -> {
            link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new DeleteAccountConfirmScreen(this));
        }).width(200).build();

        this.savedCustomJoinAddress = blankOr(link.sharedworld.SharedWorldClientConfigStore.shared().customJoinAddress(), "");
        this.customJoinBox = new EditBox(this.font, 0, 0, 220, 20, Component.translatable("screen.sharedworld.custom_join_address"));
        this.customJoinBox.setMaxLength(260);
        this.customJoinBox.setHint(Component.translatable("screen.sharedworld.custom_join_address_hint"));
        this.customJoinBox.setValue(this.savedCustomJoinAddress);

        this.tabNavigationBar = link.sharedworld.versioned.TabBarCompat.create(this.tabManager, this.width, this.storageTab, this.advancedTab);
        this.addRenderableWidget(this.tabNavigationBar);

        this.updateButtons();
        this.repositionElements();
        this.tabNavigationBar.selectTab(0, false);
        this.beginAccountCheckOnce();
    }

    @Override
    protected void repositionElements() {
        if (this.tabNavigationBar == null) {
            return;
        }
        link.sharedworld.versioned.TabBarCompat.arrange(this.tabNavigationBar, this.width);
        int headerBottom = this.tabNavigationBar.getRectangle().bottom();
        this.contentArea = new ScreenRectangle(0, headerBottom, this.width, this.height - this.layout.getFooterHeight() - headerBottom);
        this.tabManager.setTabArea(this.contentArea);
        this.layout.setHeaderHeight(headerBottom);
        this.layout.arrangeElements();
    }

    @Override
    protected TabNavigationBar sharedworldTabNavigationBar() {
        return this.tabNavigationBar;
    }

    /** Automation hook for the dev-helper drivers: select a tab by index. */
    public void sharedworldSelectTab(int index) {
        if (this.tabNavigationBar != null) {
            this.tabNavigationBar.selectTab(index, true);
        }
    }

    @Override
    protected void sharedworldSetInitialFocus() {
        if (this.tabManager.getCurrentTab() == this.advancedTab) {
            this.setInitialFocus(this.customJoinBox);
        }
    }

    @Override
    public void onClose() {
        this.reconnectGeneration++;
        // Returning to a live parent revives whatever widget was focused when
        // we left (the Settings button would keep its outline).
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
        this.syncTabState();
        this.updateButtons();
        this.sharedworldRenderMenuBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (this.contentArea != null) {
            if (this.tabManager.getCurrentTab() == this.storageTab) {
                this.renderStorageDecorations(guiGraphics);
            } else if (this.tabManager.getCurrentTab() == this.advancedTab) {
                this.renderAdvancedDecorations(guiGraphics);
            }
        }
        this.statusBanner.renderBottomCentered(guiGraphics, this.font, this.width / 2, this.height - FOOTER_HEIGHT - 2, Math.min(this.width - 40, 420));
    }

    private void syncTabState() {
        Tab currentTab = this.tabManager.getCurrentTab();
        if (currentTab == this.lastTab) {
            return;
        }
        this.lastTab = currentTab;
        // A pending "Confirm?" should not survive a tab hop.
        this.unlinkArmed = false;
        this.s3UnlinkArmed = false;
        this.repositionElements();
        this.updateButtons();
    }

    private void renderStorageDecorations(GuiGraphics guiGraphics) {
        int centerX = this.width / 2;
        int top = this.contentArea.top();
        guiGraphics.drawCenteredString(this.font, this.driveStatusLine(), centerX, top + 12, 0xFFB0B0B0);
        guiGraphics.drawCenteredString(this.font, this.s3StatusLine(), centerX, top + 62, 0xFFB0B0B0);
    }

    private void renderAdvancedDecorations(GuiGraphics guiGraphics) {
        int left = this.customJoinBox.getX();
        int textWidth = Math.min(320, this.contentArea.width() - 76);
        guiGraphics.drawString(this.font, Component.translatable("screen.sharedworld.custom_join_address"), left, this.contentArea.top() + 12, 0xFFA0A0A0);
        int textTop = link.sharedworld.versioned.WidgetCompat.bottom(this.customJoinBox) + 6;
        if (!this.isAdvancedValid()) {
            guiGraphics.drawString(this.font, Component.translatable("screen.sharedworld.custom_join_address_invalid"), left, textTop, 0xFFFF5555);
            textTop += 14;
        }
        textTop = this.drawWrappedText(guiGraphics, Component.translatable("screen.sharedworld.custom_join_address_explain_1"), left, textTop, textWidth, 0xFFA0A0A0) + 6;
        textTop = this.drawWrappedText(guiGraphics, Component.translatable("screen.sharedworld.custom_join_address_explain_2"), left, textTop, textWidth, 0xFFA0A0A0) + 6;
        this.drawWrappedText(guiGraphics, Component.translatable("screen.sharedworld.custom_join_address_explain_3"), left, textTop, textWidth, 0xFFA0A0A0);
    }

    private int drawWrappedText(GuiGraphics guiGraphics, Component text, int x, int y, int width, int color) {
        List<FormattedCharSequence> lines = this.font.split(text, width);
        for (int index = 0; index < lines.size(); index++) {
            guiGraphics.drawString(this.font, lines.get(index), x, y + index * 9, color);
        }
        return y + lines.size() * 9;
    }

    private Component driveStatusLine() {
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

    private Component s3StatusLine() {
        if (!this.accountCheckFinished) {
            return Component.translatable("screen.sharedworld.account_status_loading");
        }
        if (this.s3Account == null || !this.s3Account.linked()) {
            return Component.translatable("screen.sharedworld.account_s3_status_not_linked");
        }
        String which = this.s3Account.email() != null ? this.s3Account.email() : "?";
        return Component.translatable("screen.sharedworld.account_s3_status_linked", which);
    }

    private Component driveConnectLabel() {
        boolean linked = this.account != null && this.account.linked();
        return Component.translatable(linked
                ? "screen.sharedworld.storage_reconnect"
                : "screen.sharedworld.storage_connect");
    }

    private Component driveUnlinkLabel() {
        return Component.translatable(this.unlinkArmed
                ? "screen.sharedworld.storage_disconnect_confirm"
                : "screen.sharedworld.storage_disconnect");
    }

    private Component s3ConnectLabel() {
        boolean linked = this.s3Account != null && this.s3Account.linked();
        return Component.translatable(linked
                ? "screen.sharedworld.storage_reconnect"
                : "screen.sharedworld.storage_connect");
    }

    private Component s3UnlinkLabel() {
        return Component.translatable(this.s3UnlinkArmed
                ? "screen.sharedworld.storage_disconnect_confirm"
                : "screen.sharedworld.storage_disconnect");
    }

    private boolean isAdvancedDirty() {
        if (this.customJoinBox == null) {
            return false;
        }
        String current = blankOr(link.sharedworld.host.CustomJoinAddressPolicy.normalize(this.customJoinBox.getValue()), "");
        return !current.equals(this.savedCustomJoinAddress);
    }

    private boolean isAdvancedValid() {
        if (this.customJoinBox == null) {
            return false;
        }
        String value = link.sharedworld.host.CustomJoinAddressPolicy.normalize(this.customJoinBox.getValue());
        return value == null || link.sharedworld.host.CustomJoinAddressPolicy.isValid(value);
    }

    private void saveAdvancedSettings() {
        if (!this.isAdvancedDirty() || !this.isAdvancedValid()) {
            return;
        }
        String value = link.sharedworld.host.CustomJoinAddressPolicy.normalize(this.customJoinBox.getValue());
        link.sharedworld.SharedWorldClientConfigStore.shared().setCustomJoinAddress(value);
        this.savedCustomJoinAddress = value == null ? "" : value;
        this.statusBanner.setTransient(SharedWorldStatusBanner.Kind.SUCCESS,
                Component.translatable("screen.sharedworld.settings_saved"), SUCCESS_STATUS_TTL_MS);
        this.updateButtons();
    }

    /**
     * The repair path for a dead Google grant (revoked, expired refresh
     * token): a fresh forced-consent OAuth round in the browser, polled here
     * until it lands on the same account row.
     */
    private void onDriveConnectPressed() {
        this.beginLinkFlow(null, true);
    }

    private void onDriveUnlinkPressed() {
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
                                Component.translatable("screen.sharedworld.storage_disconnect_done"), SUCCESS_STATUS_TTL_MS);
                    }
                    this.updateButtons();
                }));
    }

    private void onS3UnlinkPressed() {
        if (this.s3UnlinkInFlight) {
            return;
        }
        if (!this.s3UnlinkArmed) {
            this.s3UnlinkArmed = true;
            this.updateButtons();
            return;
        }
        this.s3UnlinkArmed = false;
        this.s3UnlinkInFlight = true;
        this.updateButtons();
        CompletableFuture
                .runAsync(() -> {
                    try {
                        SharedWorldClient.apiClient().unlinkStorageAccount("s3");
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((ignored, error) -> ScreenGuards.runIfCurrent(this, () -> {
                    this.s3UnlinkInFlight = false;
                    if (error != null) {
                        this.statusBanner.set(SharedWorldStatusBanner.Kind.ERROR,
                                Component.literal(SharedWorldText.errorMessageOrDefault(rootCause(error).getMessage())));
                    } else {
                        this.s3Account = new StorageAccountSummaryDto(false, "s3", null, false);
                        this.statusBanner.setTransient(SharedWorldStatusBanner.Kind.SUCCESS,
                                Component.translatable("screen.sharedworld.storage_disconnect_done"), SUCCESS_STATUS_TTL_MS);
                    }
                    this.updateButtons();
                }));
    }

    /** provider null = Google Drive OAuth; "s3" = the bucket form. */
    private void beginLinkFlow(String provider, boolean forceConsent) {
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
                        var session = SharedWorldClient.apiClient().createStorageLink(forceConsent, provider);
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
                // Re-fetch the summary so the status lines reflect the fresh link.
                this.accountCheckStarted = false;
                this.accountCheckFinished = false;
                this.beginAccountCheckOnce();
            }
            this.updateButtons();
        });
    }

    private record AccountSummaries(StorageAccountSummaryDto drive, StorageAccountSummaryDto s3) {
    }

    private void beginAccountCheckOnce() {
        if (this.accountCheckStarted) {
            return;
        }
        this.accountCheckStarted = true;
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return new AccountSummaries(
                                SharedWorldClient.apiClient().getStorageAccount(),
                                SharedWorldClient.apiClient().getStorageAccount("s3"));
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
                        this.account = result.drive();
                        this.s3Account = result.s3();
                    }
                    this.updateButtons();
                }));
    }

    private void updateButtons() {
        boolean busy = this.unlinkInFlight || this.reconnectInFlight || this.s3UnlinkInFlight;
        if (this.driveConnectButton != null) {
            this.driveConnectButton.setMessage(this.driveConnectLabel());
            this.driveConnectButton.active = this.accountCheckFinished && !busy;
        }
        if (this.driveUnlinkButton != null) {
            this.driveUnlinkButton.setMessage(this.driveUnlinkLabel());
            this.driveUnlinkButton.active = this.accountCheckFinished
                    && this.account != null && this.account.linked() && !busy;
        }
        if (this.s3ConnectButton != null) {
            this.s3ConnectButton.setMessage(this.s3ConnectLabel());
            this.s3ConnectButton.active = this.accountCheckFinished && !busy;
        }
        if (this.s3UnlinkButton != null) {
            this.s3UnlinkButton.setMessage(this.s3UnlinkLabel());
            this.s3UnlinkButton.active = this.accountCheckFinished
                    && this.s3Account != null && this.s3Account.linked() && !busy;
        }
        if (this.deleteAllButton != null) {
            this.deleteAllButton.active = !busy;
        }
        if (this.saveButton != null) {
            boolean onAdvanced = this.tabManager.getCurrentTab() == this.advancedTab;
            this.saveButton.visible = onAdvanced;
            this.saveButton.active = onAdvanced && this.isAdvancedDirty() && this.isAdvancedValid();
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private final class StorageTab extends link.sharedworld.versioned.VersionedTab {
        @Override
        public Component getTabTitle() {
            return Component.translatable("screen.sharedworld.tab_storage");
        }

        @Override
        protected Component sharedworldTabExtraNarration() {
            return this.getTabTitle();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(SettingsScreen.this.driveConnectButton);
            consumer.accept(SettingsScreen.this.driveUnlinkButton);
            consumer.accept(SettingsScreen.this.s3ConnectButton);
            consumer.accept(SettingsScreen.this.s3UnlinkButton);
            consumer.accept(SettingsScreen.this.deleteAllButton);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            int centerX = SettingsScreen.this.width / 2;
            int top = area.top();
            SettingsScreen.this.driveConnectButton.setPosition(centerX - 100, top + 24);
            SettingsScreen.this.driveUnlinkButton.setPosition(centerX + 2, top + 24);
            SettingsScreen.this.s3ConnectButton.setPosition(centerX - 100, top + 74);
            SettingsScreen.this.s3UnlinkButton.setPosition(centerX + 2, top + 74);
            SettingsScreen.this.deleteAllButton.setPosition(centerX - 100, top + 108);
        }
    }

    private final class AdvancedTab extends link.sharedworld.versioned.VersionedTab {
        @Override
        public Component getTabTitle() {
            return Component.translatable("screen.sharedworld.tab_advanced");
        }

        @Override
        protected Component sharedworldTabExtraNarration() {
            return this.getTabTitle();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(SettingsScreen.this.customJoinBox);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            SettingsScreen.this.customJoinBox.setWidth(Math.min(220, area.width() - 76));
            SettingsScreen.this.customJoinBox.setPosition(SettingsScreen.this.width / 2 - SettingsScreen.this.customJoinBox.getWidth() / 2, area.top() + 24);
        }
    }
}
