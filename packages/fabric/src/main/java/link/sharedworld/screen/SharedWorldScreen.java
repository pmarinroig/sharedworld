package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SharedWorldScreen extends link.sharedworld.versioned.VersionedScreen {
    private static final long AUTO_REFRESH_IDLE_MS = 15_000L;
    private static final long AUTO_REFRESH_ACTIVE_MS = 10_000L;
    private static final long EVENT_REFRESH_DEBOUNCE_MS = 1_000L;
    /** Safety-net cadence while pushed events drive the list (0.3.0). */
    private static final long AUTO_REFRESH_PUSH_FALLBACK_MS = 60_000L;
    private static final long SUCCESS_STATUS_TTL_MS = 7_000L;

    private final SharedWorldStatusBanner statusBanner = new SharedWorldStatusBanner();
    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 60);
    private final List<WorldSummaryDto> worlds = new ArrayList<>();
    private SharedWorldServerList serverList;
    private Button joinButton;
    private Button inviteButton;
    private Button redeemButton;
    private Button editButton;
    private Button deleteButton;
    private Button refreshButton;
    private Button vanillaButton;
    private Button settingsButton;
    private boolean loading;
    private boolean backendReachable = true;
    private boolean refreshInFlight;
    private long lastRefreshStartedAt;
    private long nextAutoRefreshAt;
    private long seenRealtimeEventCount;

    public SharedWorldScreen(Screen parent) {
        super(Component.translatable("screen.sharedworld.title"));
        this.parent = parent;
        this.worlds.addAll(SharedWorldClient.cachedWorlds());
        SharedWorldClient.ensureRealtimeStarted();
    }

    @Override
    protected void init() {
        link.sharedworld.SharedWorldActivity.touchScreen();
        link.sharedworld.versioned.LayoutCompat.addTitleHeader(this.layout, this.title, this.font);
        this.serverList = link.sharedworld.versioned.LayoutCompat.addContentsList(this.layout, new SharedWorldServerList(
                this.minecraft,
                this.width,
                link.sharedworld.versioned.LayoutCompat.contentHeight(this.layout),
                this.layout.getHeaderHeight(),
                36,
                this
        ), this::addRenderableWidget);
        this.serverList.setWorlds(this.worlds, SharedWorldClient.cachedSelectedWorldId());

        LinearLayout footer = this.layout.addToFooter(link.sharedworld.versioned.LayoutCompat.verticalLayout(4));
        link.sharedworld.versioned.LayoutCompat.defaultCellSetting(footer).alignHorizontallyCenter();

        LinearLayout topRow = footer.addChild(link.sharedworld.versioned.LayoutCompat.horizontalLayout(4));
        this.joinButton = topRow.addChild(Button.builder(Component.translatable("screen.sharedworld.join"), button -> {
                    this.releaseWidgetFocus();
                    this.joinSelected();
                })
                .width(74)
                .build());
        this.inviteButton = topRow.addChild(Button.builder(Component.translatable("screen.sharedworld.invite"), button -> {
                    this.releaseWidgetFocus();
                    this.openCreateInvite();
                })
                .width(74)
                .build());
        this.redeemButton = topRow.addChild(Button.builder(Component.translatable("screen.sharedworld.redeem"), button -> {
                    this.releaseWidgetFocus();
                    link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new RedeemInviteScreen(this));
                })
                .width(74)
                .build());
        topRow.addChild(Button.builder(Component.translatable("screen.sharedworld.create"), button -> {
                    this.releaseWidgetFocus();
                    link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new CreateSharedWorldScreen(this));
                })
                .width(74)
                .build());

        LinearLayout bottomRow = footer.addChild(link.sharedworld.versioned.LayoutCompat.horizontalLayout(4));
        this.editButton = bottomRow.addChild(Button.builder(Component.translatable("screen.sharedworld.edit"), button -> {
                    this.releaseWidgetFocus();
                    this.openEditWorld();
                })
                .width(74)
                .build());
        this.deleteButton = bottomRow.addChild(Button.builder(Component.translatable("screen.sharedworld.delete"), button -> {
                    this.releaseWidgetFocus();
                    this.openDeleteWorld();
                })
                .width(74)
                .build());
        this.refreshButton = bottomRow.addChild(Button.builder(Component.translatable("screen.sharedworld.refresh"), button -> {
                    this.releaseWidgetFocus();
                    this.minecraft.execute(this::releaseWidgetFocus);
                    this.refreshWorlds();
                })
                .width(74)
                .build());
        bottomRow.addChild(Button.builder(Component.translatable("gui.back"), button -> this.onClose())
                .width(74)
                .build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.vanillaButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.vanilla"), button -> this.openVanillaServers())
                .bounds(this.width - 118, 8, 110, 20)
                .build());
        this.settingsButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.settings"), button -> {
                    this.releaseWidgetFocus();
                    link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new SettingsScreen(this));
                })
                .bounds(8, 8, 80, 20)
                .build());

        this.repositionElements();
        this.refreshWorlds();
        this.updateButtons();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        if (this.serverList != null) {
            this.serverList.sharedworldUpdateSize(this.width, this.layout);
        }
        if (this.vanillaButton != null) {
            link.sharedworld.versioned.WidgetCompat.setPosition(this.vanillaButton, this.width - 118, 8);
        }
        if (this.settingsButton != null) {
            link.sharedworld.versioned.WidgetCompat.setPosition(this.settingsButton, 8, 8);
        }
    }

    public void refreshWorlds() {
        if (this.refreshInFlight) {
            return;
        }

        WorldSummaryDto selected = this.selectedWorld();
        String selectedWorldId = selected == null ? SharedWorldClient.cachedSelectedWorldId() : selected.id();
        boolean coldLoad = this.worlds.isEmpty();
        this.refreshInFlight = true;
        this.loading = coldLoad;
        this.updateButtons();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return SharedWorldClient.apiClient().listWorlds();
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    this.refreshInFlight = false;
                    this.loading = false;
                    if (error != null) {
                        this.backendReachable = false;
                        SharedWorldClient.LOGGER.warn("Failed to refresh Shared Worlds list", rootCause(error));
                    } else {
                        this.backendReachable = true;
                        List<WorldSummaryDto> orderedWorlds = SharedWorldClient.orderFreshWorlds(result);
                        for (WorldSummaryDto world : orderedWorlds) {
                            SharedWorldClient.customIconStore().resolveCachedIcon(world);
                        }
                        boolean worldsChanged = !SharedWorldClient.orderedWorldListsEqual(this.worlds, orderedWorlds);
                        List<WorldSummaryDto> cachedWorlds = SharedWorldClient.applyFreshWorlds(orderedWorlds);
                        if (worldsChanged) {
                            this.worlds.clear();
                            this.worlds.addAll(cachedWorlds);
                            if (this.serverList != null) {
                                this.serverList.setWorlds(this.worlds, selectedWorldId);
                            }
                            this.releaseWidgetFocus();
                        }
                    }
                    this.nextAutoRefreshAt = link.sharedworld.util.MonotonicClock.millis() + this.autoRefreshIntervalMs();
                    this.seenRealtimeEventCount = SharedWorldClient.realtimeEvents().eventCount();
                    this.updateButtons();
                }));
    }

    public void onChildOperationFinished(String message) {
        this.onChildOperationFinished(message, null);
    }

    /** A child screen aborted an operation on the player's request. */
    public void showTransientWarning(String message) {
        this.statusBanner.setTransient(SharedWorldStatusBanner.Kind.WARNING, Component.literal(message), SUCCESS_STATUS_TTL_MS);
    }

    /**
     * A child screen finished an operation. The outcome is already visible in
     * the list itself (a row appears, disappears, or gets selected), so no
     * textual confirmation is shown — just land with the affected world
     * selected so the action buttons are live instead of greyed out.
     */
    public void onChildOperationFinished(String message, String selectWorldId) {
        if (selectWorldId != null) {
            SharedWorldClient.rememberSelectedWorld(selectWorldId);
            if (this.serverList != null) {
                this.serverList.setWorlds(this.worlds, selectWorldId);
            }
        }
        this.refreshWorlds();
    }

    @Override
    public void onClose() {
        if (this.minecraft == null) {
            return;
        }
        if (canCloseThroughParent(this.parent.children())) {
            this.parent.onClose();
            return;
        }
        // A synthetic, never-shown parent (title-origin flows build one so the
        // vanilla-servers button has somewhere to go) has an unset minecraft
        // field; closing through it would crash. Land where its own onClose
        // would have led.
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new net.minecraft.client.gui.screens.TitleScreen());
    }

    /** A screen that was never shown has no initialized state to close through. */
    static boolean canCloseThroughParent(java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> parentChildren) {
        return !parentChildren.isEmpty();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft == null || link.sharedworld.versioned.ClientCompat.currentScreen(this.minecraft) != this) {
            return;
        }
        link.sharedworld.SharedWorldActivity.touchScreen();

        long now = link.sharedworld.util.MonotonicClock.millis();
        boolean pushedChange = SharedWorldClient.realtimeEvents().eventCount() != this.seenRealtimeEventCount;
        // Event bursts (a release fires runtime+presence+snapshot changes in
        // quick succession) coalesce into one refresh per second.
        boolean debounced = now - this.lastRefreshStartedAt >= EVENT_REFRESH_DEBOUNCE_MS;
        if (!this.refreshInFlight && ((pushedChange && debounced) || now >= this.nextAutoRefreshAt)) {
            this.lastRefreshStartedAt = now;
            this.refreshWorlds();
        }
    }

    @Override
    public void removed() {
        super.removed();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (this.serverList != null && this.serverList.children().isEmpty() && !this.loading) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.translatable("screen.sharedworld.no_worlds"),
                    this.width / 2,
                    this.serverList.getY() + 24,
                    0xFFFFFFFF
            );
        }

        if (!SharedWorldClient.isE4mcInstalled()) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.translatable("screen.sharedworld.missing_e4mc").withStyle(ChatFormatting.YELLOW),
                    this.width / 2,
                    this.height - 74,
                    0xFFFFD37A
            );
        }
        int bannerBottom = SharedWorldClient.isE4mcInstalled() ? this.height - 64 : this.height - 78;
        this.statusBanner.renderBottomCentered(guiGraphics, this.font, this.width / 2, bannerBottom, Math.min(this.width - 40, 420));
    }

    public void onEntrySelected(WorldSummaryDto world) {
        SharedWorldClient.rememberSelectedWorld(world == null ? null : world.id());
        this.updateButtons();
    }

    public boolean canMoveWorld(WorldSummaryDto world, int offset) {
        return world != null && SharedWorldClient.canMoveCachedWorld(world.id(), offset);
    }

    public void moveWorld(WorldSummaryDto world, int offset) {
        if (world == null || !this.canMoveWorld(world, offset)) {
            return;
        }

        this.worlds.clear();
        this.worlds.addAll(SharedWorldClient.moveCachedWorld(world.id(), offset));
        if (this.serverList != null) {
            this.serverList.setWorlds(this.worlds, world.id());
        }
        this.releaseWidgetFocus();
        this.updateButtons();
    }

    public boolean backendReachable() {
        return this.backendReachable;
    }

    public void joinSelected() {
        WorldSummaryDto selected = this.selectedWorld();
        if (selected == null) {
            return;
        }
        // 0.5.0: e4mc is no longer a hard requirement here. Joining never
        // needs it, and hosting without it is caught by the hosting manager
        // with a clear error unless a custom join address is configured.
        this.loading = true;
        this.updateButtons();
        SharedWorldClient.sessionCoordinator().beginJoin(this, selected);
        this.loading = false;
        this.releaseWidgetFocus();
        this.updateButtons();
    }

    private void openCreateInvite() {
        WorldSummaryDto selected = this.selectedWorld();
        if (selected != null && this.isCurrentPlayerOwner(selected)) {
            link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new SharedWorldInviteScreen(this, selected));
        }
    }

    private void openEditWorld() {
        WorldSummaryDto selected = this.selectedWorld();
        if (selected != null) {
            link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new EditSharedWorldScreen(this, selected));
        }
    }

    private void openDeleteWorld() {
        WorldSummaryDto selected = this.selectedWorld();
        if (selected != null) {
            link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new DeleteSharedWorldScreen(this, selected));
        }
    }

    private void openVanillaServers() {
        SharedWorldClient.rememberVanillaView();
        link.sharedworld.versioned.GuiCompat.clearFocus(this.parent);
        this.releaseWidgetFocus();
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, this.parent);
    }

    private WorldSummaryDto selectedWorld() {
        return this.serverList == null ? null : this.serverList.selectedWorld();
    }

    public void clearTransientFocus() {
        this.releaseWidgetFocus();
    }

    private void releaseWidgetFocus() {
        link.sharedworld.versioned.GuiCompat.clearFocus(this);
        this.setFocused(null);
        if (this.joinButton != null) {
            this.joinButton.setFocused(false);
        }
        if (this.inviteButton != null) {
            this.inviteButton.setFocused(false);
        }
        if (this.redeemButton != null) {
            this.redeemButton.setFocused(false);
        }
        if (this.editButton != null) {
            this.editButton.setFocused(false);
        }
        if (this.deleteButton != null) {
            this.deleteButton.setFocused(false);
        }
        if (this.refreshButton != null) {
            this.refreshButton.setFocused(false);
        }
        if (this.vanillaButton != null) {
            this.vanillaButton.setFocused(false);
        }
        if (this.settingsButton != null) {
            this.settingsButton.setFocused(false);
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private long autoRefreshIntervalMs() {
        // While the realtime channel is connected, changes arrive as pushes
        // and the timer is only a safety net — stretch it well out.
        if (SharedWorldClient.realtimeEvents().isConnected()) {
            return AUTO_REFRESH_PUSH_FALLBACK_MS;
        }
        for (WorldSummaryDto world : this.worlds) {
            if ("hosting".equals(world.status()) || "handoff".equals(world.status()) || "finalizing".equals(world.status())) {
                return AUTO_REFRESH_ACTIVE_MS;
            }
        }
        return AUTO_REFRESH_IDLE_MS;
    }

    private void updateButtons() {
        WorldSummaryDto selected = this.selectedWorld();
        boolean hasSelection = selected != null;
        boolean ownsSelection = hasSelection && this.isCurrentPlayerOwner(selected);
        if (this.joinButton != null) {
            this.joinButton.setMessage(Component.translatable("screen.sharedworld.join"));
            this.joinButton.active = hasSelection && !this.loading;
        }
        if (this.inviteButton != null) {
            this.inviteButton.active = ownsSelection && !this.loading;
        }
        if (this.editButton != null) {
            this.editButton.active = ownsSelection && !this.loading;
        }
        if (this.deleteButton != null) {
            this.deleteButton.setMessage(Component.translatable("screen.sharedworld.delete"));
            this.deleteButton.active = hasSelection && !this.loading;
        }
        if (this.redeemButton != null) {
            this.redeemButton.active = !this.loading;
        }
    }

    private boolean isCurrentPlayerOwner(WorldSummaryDto world) {
        if (world == null || world.ownerUuid() == null || world.ownerUuid().isBlank()) {
            return false;
        }
        String currentPlayer = link.sharedworld.api.SharedWorldApiClient.currentBackendPlayerUuidWithHyphens()
                .replace("-", "")
                .toLowerCase();
        String owner = world.ownerUuid().replace("-", "").toLowerCase();
        return owner.equals(currentPlayer);
    }
}
