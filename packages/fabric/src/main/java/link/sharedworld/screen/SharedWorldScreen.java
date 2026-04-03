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

public final class SharedWorldScreen extends Screen {
    private static final long AUTO_REFRESH_IDLE_MS = 15_000L;
    private static final long AUTO_REFRESH_ACTIVE_MS = 2_500L;

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
    private boolean loading;
    private boolean backendReachable = true;
    private boolean refreshInFlight;
    private long nextAutoRefreshAt;

    public SharedWorldScreen(Screen parent) {
        super(Component.translatable("screen.sharedworld.title"));
        this.parent = parent;
        this.worlds.addAll(SharedWorldClient.cachedWorlds());
    }

    @Override
    protected void init() {
        this.layout.addTitleHeader(this.title, this.font);
        this.serverList = this.layout.addToContents(new SharedWorldServerList(
                this.minecraft,
                this.width,
                this.layout.getContentHeight(),
                this.layout.getHeaderHeight(),
                36,
                this
        ));
        this.serverList.setWorlds(this.worlds, SharedWorldClient.cachedSelectedWorldId());

        LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical().spacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();

        LinearLayout topRow = footer.addChild(LinearLayout.horizontal().spacing(4));
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
                    this.minecraft.setScreen(new RedeemInviteScreen(this));
                })
                .width(74)
                .build());
        topRow.addChild(Button.builder(Component.translatable("screen.sharedworld.create"), button -> {
                    this.releaseWidgetFocus();
                    this.minecraft.setScreen(new CreateSharedWorldScreen(this));
                })
                .width(74)
                .build());

        LinearLayout bottomRow = footer.addChild(LinearLayout.horizontal().spacing(4));
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

        this.repositionElements();
        this.refreshWorlds();
        this.updateButtons();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        if (this.serverList != null) {
            this.serverList.updateSize(this.width, this.layout);
        }
        if (this.vanillaButton != null) {
            this.vanillaButton.setPosition(this.width - 118, 8);
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
                    this.nextAutoRefreshAt = net.minecraft.util.Util.getMillis() + this.autoRefreshIntervalMs();
                    this.updateButtons();
                }));
    }

    public void onChildOperationFinished(String message) {
        this.refreshWorlds();
    }

    @Override
    public void onClose() {
        this.parent.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft == null || this.minecraft.screen != this) {
            return;
        }

        long now = net.minecraft.util.Util.getMillis();
        if (!this.refreshInFlight && now >= this.nextAutoRefreshAt) {
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
                    Component.translatable("screen.sharedworld.missing_e4mc").withStyle(ChatFormatting.RED),
                    this.width / 2,
                    this.height - 74,
                    0xFFFF5555
            );
        }
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
        if (!SharedWorldClient.isE4mcInstalled()) {
            this.minecraft.setScreen(new SharedWorldErrorScreen(
                    this,
                    Component.translatable("screen.sharedworld.error_title"),
                    Component.translatable("screen.sharedworld.missing_e4mc")
            ));
            return;
        }

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
            this.minecraft.setScreen(new SharedWorldInviteScreen(this, selected));
        }
    }

    private void openEditWorld() {
        WorldSummaryDto selected = this.selectedWorld();
        if (selected != null) {
            this.minecraft.setScreen(new EditSharedWorldScreen(this, selected));
        }
    }

    private void openDeleteWorld() {
        WorldSummaryDto selected = this.selectedWorld();
        if (selected != null) {
            this.minecraft.setScreen(new DeleteSharedWorldScreen(this, selected));
        }
    }

    private void openVanillaServers() {
        SharedWorldClient.rememberVanillaView();
        this.parent.clearFocus();
        this.releaseWidgetFocus();
        this.minecraft.setScreen(this.parent);
    }

    private WorldSummaryDto selectedWorld() {
        return this.serverList == null ? null : this.serverList.selectedWorld();
    }

    private static String displayName(WorldSummaryDto world) {
        return SharedWorldText.displayWorldName(world.name());
    }

    public void clearTransientFocus() {
        this.releaseWidgetFocus();
    }

    private void releaseWidgetFocus() {
        this.clearFocus();
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
    }

    private static String friendlyError(Throwable error) {
        Throwable cause = rootCause(error);
        if (cause instanceof UnknownHostException || cause instanceof ConnectException) {
            return SharedWorldText.string("screen.sharedworld.error_internet_unreachable");
        }
        if (cause instanceof IOException && cause.getMessage() != null) {
            String message = cause.getMessage();
            if (message.contains("UnresolvedAddressException") || message.contains("Connection refused")) {
                return SharedWorldText.string("screen.sharedworld.error_backend_unreachable");
            }
            return message;
        }
        return cause.getMessage() != null ? cause.getMessage() : error.getMessage();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void openBlockingError(Component title, Component message) {
        this.releaseWidgetFocus();
        this.updateButtons();
        this.minecraft.setScreen(new SharedWorldErrorScreen(this, title, message));
    }

    private static Component friendlyErrorComponent(Throwable error) {
        return Component.literal(SharedWorldText.errorMessageOrDefault(friendlyError(error)));
    }

    private long autoRefreshIntervalMs() {
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
