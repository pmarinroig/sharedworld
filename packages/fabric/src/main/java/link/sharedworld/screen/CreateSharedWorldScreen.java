package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldCustomIconStore.SelectedIcon;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.ImportedWorldSourceDto;
import link.sharedworld.api.SharedWorldModels.StorageLinkSessionDto;
import net.minecraft.client.Minecraft;
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
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class CreateSharedWorldScreen extends Screen {
    private static final int FOOTER_HEIGHT = 36;
    private static final int CONTENT_MARGIN = 12;
    private static final Identifier EDIT_ICON_SPRITE = Identifier.fromNamespaceAndPath("sharedworld", "edit_icon");
    private static final Identifier EDIT_ICON_HIGHLIGHTED_SPRITE = Identifier.fromNamespaceAndPath("sharedworld", "edit_icon_highlighted");
    private static final Identifier DELETE_ICON_SPRITE = Identifier.fromNamespaceAndPath("sharedworld", "delete_icon");
    private static final Identifier DELETE_ICON_HIGHLIGHTED_SPRITE = Identifier.fromNamespaceAndPath("sharedworld", "delete_icon_highlighted");
    private static final Identifier UNREACHABLE_SPRITE = Identifier.withDefaultNamespace("server_list/unreachable");
    private static final Identifier PING_5_SPRITE = Identifier.withDefaultNamespace("server_list/ping_5");
    private static final int FOOTER_BUTTON_WIDTH = 150;
    private static final int STORAGE_LEFT_PADDING = 36;
    private static final int STORAGE_COPY_TOP = 56;
    private static final int STORAGE_BUTTON_TOP = 94;
    private static final int STORAGE_MESSAGE_TOP = 126;

    private final SharedWorldScreen parent;
    private final CreateDraft restoredDraft;
    private final RestoreState restoreState;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, FOOTER_HEIGHT);
    private final List<LocalSaveCatalog.LocalSaveOption> localSaves = LocalSaveCatalog.discover();
    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);

    private final WorldTab worldTab = new WorldTab();
    private final DetailsTab detailsTab = new DetailsTab();
    private final StorageTab storageTab = new StorageTab();

    private LocalSaveCatalog.LocalSaveOption selectedSave;
    private StorageLinkSessionDto storageLink;
    private FaviconTexture previewTexture;
    private TabNavigationBar tabNavigationBar;
    private ScreenRectangle contentArea;

    private LocalSaveSelectionList saveList;
    private EditBox nameBox;
    private EditBox motdBox;
    private Button chooseIconButton;
    private Button clearIconButton;
    private Button linkDriveButton;
    private Button backButton;
    private Button primaryButton;

    private SelectedIcon selectedIcon;
    private boolean clearCustomIcon;
    private boolean submitting;
    private boolean driveLinkInFlight;
    private boolean driveLinkCopiedFallback;
    private String storageMessage = "";
    private int storageMessageColor = 0xFFB8C5D6;
    private String iconMessage = "";
    private long iconMessageExpiresAtMs;
    private boolean iconHovered;

    public CreateSharedWorldScreen(SharedWorldScreen parent) {
        this(parent, null, null);
    }

    CreateSharedWorldScreen(SharedWorldScreen parent, CreateDraft restoredDraft, RestoreState restoreState) {
        super(Component.translatable("screen.sharedworld.create_title"));
        this.parent = parent;
        this.restoredDraft = restoredDraft;
        this.restoreState = restoreState;
        if (restoredDraft != null && restoredDraft.selectedSaveId() != null) {
            this.selectedSave = this.localSaves.stream()
                    .filter(save -> restoredDraft.selectedSaveId().equals(save.id()))
                    .findFirst()
                    .orElse(null);
        }
        if (this.selectedSave == null && !this.localSaves.isEmpty()) {
            this.selectedSave = this.localSaves.get(0);
        }
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.previewTexture = FaviconTexture.forWorld(this.minecraft.getTextureManager(), "sharedworld/create-preview");

        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        this.backButton = footer.addChild(Button.builder(Component.translatable("screen.sharedworld.cancel"), ignored -> this.onBack())
                .width(FOOTER_BUTTON_WIDTH)
                .build());
        this.primaryButton = footer.addChild(Button.builder(Component.translatable("screen.sharedworld.next"), ignored -> this.onPrimaryAction())
                .width(FOOTER_BUTTON_WIDTH)
                .build());

        this.layout.visitWidgets(this::addRenderableWidget);

        this.saveList = new LocalSaveSelectionList(this.minecraft, 0, 0, 0, 36, this);
        this.saveList.setSaves(this.localSaves, this.selectedSave == null ? null : this.selectedSave.id());

        this.nameBox = new EditBox(this.font, 0, 0, 220, 20, Component.translatable("screen.sharedworld.world_name"));
        this.nameBox.setMaxLength(128);
        this.nameBox.setValue(this.restoredDraft != null
                ? blankOr(this.restoredDraft.name(), this.selectedSave == null ? "" : this.selectedSave.displayName())
                : (this.selectedSave == null ? "" : this.selectedSave.displayName()));

        this.motdBox = new EditBox(this.font, 0, 0, 240, 20, SharedWorldText.component("screen.sharedworld.motd_hint", SharedWorldApiClient.currentPlayerName()));
        this.motdBox.setMaxLength(256);
        this.motdBox.setHint(SharedWorldText.component("screen.sharedworld.motd_hint", SharedWorldApiClient.currentPlayerName()));
        if (this.restoredDraft != null && this.restoredDraft.motd() != null) {
            this.motdBox.setValue(this.restoredDraft.motd());
        }

        this.chooseIconButton = Button.builder(Component.literal("+"), ignored -> this.chooseIcon())
                .width(20)
                .build();
        this.clearIconButton = Button.builder(Component.literal("x"), ignored -> {
                    this.selectedIcon = null;
                    this.clearCustomIcon = true;
                    this.refreshPreview();
                })
                .width(20)
                .build();

        this.linkDriveButton = Button.builder(Component.translatable("screen.sharedworld.storage_link_google_drive"), ignored -> this.beginDriveLink())
                .width(150)
                .build();

        this.tabNavigationBar = TabNavigationBar.builder(this.tabManager, this.width)
                .addTabs(this.worldTab, this.detailsTab, this.storageTab)
                .build();
        this.addRenderableWidget(this.tabNavigationBar);

        if (this.restoredDraft != null) {
            this.selectedIcon = this.restoredDraft.selectedIcon();
            this.clearCustomIcon = this.restoredDraft.clearCustomIcon();
            this.storageLink = this.restoredDraft.storageLink();
        }
        this.refreshPreview();
        this.refreshStorageState();
        if (this.restoreState != null && this.restoreState.message() != null && !this.restoreState.message().isBlank()) {
            this.storageMessage = this.restoreState.message();
            this.storageMessageColor = this.restoreState.messageColor();
        }
        this.updateButtons();
        this.tabNavigationBar.selectTab(this.restoreState == null ? 0 : this.restoreState.tabIndex(), false);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (this.tabNavigationBar == null) {
            return;
        }

        this.tabNavigationBar.setWidth(this.width);
        this.tabNavigationBar.arrangeElements();
        int headerBottom = this.tabNavigationBar.getRectangle().bottom();
        this.contentArea = new ScreenRectangle(
                0,
                headerBottom,
                this.width,
                this.height - this.layout.getFooterHeight() - headerBottom
        );
        this.tabManager.setTabArea(this.contentArea);
        this.layout.setHeaderHeight(headerBottom);
        this.layout.arrangeElements();
    }

    @Override
    protected void setInitialFocus() {
        if (this.tabManager.getCurrentTab() == this.detailsTab) {
            this.setInitialFocus(this.nameBox);
            return;
        }
        if (this.tabManager.getCurrentTab() == this.worldTab) {
            this.setInitialFocus(this.saveList);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.tabNavigationBar != null && this.tabNavigationBar.keyPressed(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.tabManager.getCurrentTab() == this.detailsTab && this.isIconHovered((int) event.x(), (int) event.y())) {
            if (this.selectedIcon != null) {
                this.selectedIcon = null;
                this.clearCustomIcon = true;
                this.refreshPreview();
            } else {
                this.chooseIcon();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void removed() {
        super.removed();
        if (this.previewTexture != null) {
            this.previewTexture.clear();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.iconHovered = this.isIconHovered(mouseX, mouseY);
        this.updateButtons();
        this.renderMenuBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (this.tabManager.getCurrentTab() == this.worldTab && this.localSaves.isEmpty() && this.contentArea != null) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.translatable("screen.sharedworld.no_local_worlds"),
                    this.width / 2,
                    this.contentArea.top() + this.contentArea.height() / 2 - 4,
                    0xFFFFFFFF
            );
        }

        if (this.tabManager.getCurrentTab() == this.detailsTab) {
            this.renderDetailsDecorations(guiGraphics);
        } else if (this.tabManager.getCurrentTab() == this.storageTab) {
            this.renderStorageDecorations(guiGraphics);
        }

        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, FOOTER_SEPARATOR, 0, this.height - this.layout.getFooterHeight() - 2, 0.0F, 0.0F, this.width, 2, 32, 2);
    }

    void onSaveSelected(LocalSaveCatalog.LocalSaveOption save) {
        String previousDefault = this.selectedSave == null ? "" : blankOr(this.selectedSave.displayName(), "");
        String currentName = this.nameBox.getValue();
        this.selectedSave = save;
        if (currentName == null || currentName.isBlank() || currentName.equals(previousDefault)) {
            this.nameBox.setValue(save.displayName());
        }
        this.saveList.setSaves(this.localSaves, save.id());
        this.refreshPreview();
        this.updateButtons();
    }

    void openDetailsTab() {
        if (this.tabNavigationBar != null && this.selectedSave != null) {
            this.tabNavigationBar.selectTab(1, true);
        }
    }

    private void renderDetailsDecorations(GuiGraphics guiGraphics) {
        if (this.contentArea == null) {
            return;
        }

        int left = this.contentArea.left() + 38;
        int top = this.contentArea.top();
        int iconX = this.iconAreaX();
        int iconY = this.iconAreaY();

        guiGraphics.drawString(this.font, Component.translatable("screen.sharedworld.world_name"), left, top + 24, 0xFFA0A0A0);
        guiGraphics.drawString(this.font, Component.translatable("screen.sharedworld.motd"), left, top + 78, 0xFFA0A0A0);
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, this.previewTexture.textureLocation(), iconX, iconY, 0.0F, 0.0F, 48, 48, 48, 48);

        if (this.iconHovered) {
            guiGraphics.fill(iconX, iconY, iconX + 48, iconY + 48, 0x80000000);
            Identifier actionSprite = this.selectedIcon != null
                    ? DELETE_ICON_HIGHLIGHTED_SPRITE
                    : EDIT_ICON_HIGHLIGHTED_SPRITE;
            guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, actionSprite, iconX + 12, iconY + 12, 24, 24);
        }

        this.renderServerCardPreview(guiGraphics, left, top + 134);

        if (!this.nameValid()) {
            guiGraphics.drawString(
                    this.font,
                    Component.translatable("screen.sharedworld.validation_world_name_short"),
                    left,
                    this.nameBox.getBottom() + 6,
                    0xFFFF5555
            );
        }

        if (this.shouldShowIconMessage()) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.literal(this.iconMessage),
                    iconX + 24,
                    iconY + 48 + 6,
                    0xFFFF5555
            );
        }
    }

    private void renderStorageDecorations(GuiGraphics guiGraphics) {
        if (this.contentArea == null) {
            return;
        }

        int left = this.contentArea.left() + STORAGE_LEFT_PADDING;
        int y = this.contentArea.top() + STORAGE_COPY_TOP;
        this.drawWrappedText(
                guiGraphics,
                Component.translatable("screen.sharedworld.storage_google_drive_detail"),
                left,
                y,
                this.contentArea.width() - 72,
                0xFFB8C5D6
        );

        if (!this.storageMessage.isBlank()) {
            this.drawWrappedText(
                    guiGraphics,
                    Component.literal(this.storageMessage),
                    left,
                    this.contentArea.top() + STORAGE_MESSAGE_TOP,
                    this.contentArea.width() - 72,
                    this.storageMessageColor
            );
        }
    }

    private void renderServerCardPreview(GuiGraphics guiGraphics, int x, int y) {
        int rowX = this.previewCardX();
        int rowY = this.previewCardY();
        int contentX = rowX + SharedWorldServerList.CONTENT_PADDING;
        int contentY = rowY + SharedWorldServerList.CONTENT_PADDING;
        SharedWorldServerList.renderSelectedOutline(guiGraphics, rowX, rowY, true);
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, this.previewTexture.textureLocation(), contentX, contentY, 0.0F, 0.0F, 32, 32, 32, 32);
        SharedWorldServerList.renderRowContents(
                guiGraphics,
                this.font,
                rowX,
                rowY,
                this.previewWorldName(),
                this.previewMotd(),
                SharedWorldText.playerCount(0, 8),
                PING_5_SPRITE
        );
    }

    private void updateButtons() {
        Tab currentTab = this.tabManager.getCurrentTab();
        boolean detailsAllowed = this.selectedSave != null;
        boolean storageAllowed = this.selectedSave != null && this.nameValid();

        if (this.tabNavigationBar != null) {
            this.tabNavigationBar.setTabActiveState(0, true);
            this.tabNavigationBar.setTabActiveState(1, detailsAllowed);
            this.tabNavigationBar.setTabActiveState(2, storageAllowed);
        }

        this.backButton.setMessage(currentTab == this.worldTab ? Component.translatable("screen.sharedworld.cancel") : Component.translatable("gui.back"));
        this.backButton.active = !this.submitting;

        if (currentTab == this.storageTab) {
            this.primaryButton.setMessage(Component.translatable(this.submitting
                    ? "screen.sharedworld.creating"
                    : "screen.sharedworld.create_world"));
            this.primaryButton.active = !this.submitting && !this.driveLinkInFlight && this.selectedSave != null && this.nameValid() && this.storageLinked();
        } else {
            this.primaryButton.setMessage(Component.translatable("screen.sharedworld.next"));
            this.primaryButton.active = !this.submitting && ((currentTab == this.worldTab && this.selectedSave != null) || (currentTab == this.detailsTab && this.nameValid()));
        }

        this.linkDriveButton.setMessage(Component.translatable(this.storageLinked()
                ? "screen.sharedworld.storage_relink"
                : (this.driveLinkInFlight
                ? "screen.sharedworld.storage_waiting_for_browser"
                : "screen.sharedworld.storage_link_google_drive")));
        this.linkDriveButton.active = !this.submitting && !this.driveLinkInFlight;
        this.chooseIconButton.visible = false;
        this.chooseIconButton.active = false;
        this.clearIconButton.visible = false;
        this.clearIconButton.active = false;
    }

    private void onBack() {
        if (this.submitting) {
            return;
        }

        Tab currentTab = this.tabManager.getCurrentTab();
        if (currentTab == this.worldTab) {
            this.onClose();
        } else if (currentTab == this.detailsTab) {
            this.tabNavigationBar.selectTab(0, true);
        } else if (currentTab == this.storageTab) {
            this.tabNavigationBar.selectTab(1, true);
        }
    }

    private void onPrimaryAction() {
        if (this.submitting) {
            return;
        }

        Tab currentTab = this.tabManager.getCurrentTab();
        if (currentTab == this.worldTab && this.selectedSave != null) {
            this.tabNavigationBar.selectTab(1, true);
        } else if (currentTab == this.detailsTab && this.nameValid()) {
            this.tabNavigationBar.selectTab(2, true);
        } else if (currentTab == this.storageTab && this.storageLinked()) {
            this.submitCreate();
        }
    }

    private void chooseIcon() {
        try {
            this.selectedIcon = SharedWorldClient.customIconStore().chooseIcon();
            if (this.selectedIcon != null) {
                this.clearCustomIcon = false;
                this.clearIconMessage();
                this.refreshPreview();
            }
        } catch (Exception exception) {
            this.showIconMessage(SharedWorldText.string("screen.sharedworld.icon_error_invalid_png"));
        }
    }

    private void refreshPreview() {
        SharedWorldMetadataIcons.uploadPreview(
                SharedWorldClient.customIconStore(),
                this.previewTexture,
                this.selectedIcon,
                () -> this.selectedSave == null ? null : this.selectedSave.iconPath()
        );
    }

    private void showIconMessage(String message) {
        this.iconMessage = message;
        this.iconMessageExpiresAtMs = System.currentTimeMillis() + 4_000L;
    }

    private void clearIconMessage() {
        this.iconMessage = "";
        this.iconMessageExpiresAtMs = 0L;
    }

    private boolean shouldShowIconMessage() {
        return this.selectedIcon == null
                && this.iconMessage != null
                && !this.iconMessage.isBlank()
                && System.currentTimeMillis() < this.iconMessageExpiresAtMs;
    }

    private void beginDriveLink() {
        this.driveLinkInFlight = true;
        this.driveLinkCopiedFallback = false;
        this.storageMessage = "";
        CompletableFuture.runAsync(() -> {
            try {
                if (this.storageLink == null || !"linked".equalsIgnoreCase(this.storageLink.status())) {
                    this.storageLink = SharedWorldClient.apiClient().createStorageLink();
                }
                this.openDriveLink(this.storageLink.authUrl());
                this.pollDriveLink();
            } catch (Exception exception) {
                Minecraft.getInstance().execute(() -> {
                    this.driveLinkInFlight = false;
                    this.storageMessage = AbstractSharedWorldMetadataScreen.friendlyMessage(exception);
                    this.storageMessageColor = 0xFFFF5555;
                    this.refreshStorageState();
                });
            }
        }, SharedWorldClient.ioExecutor());
    }

    private void pollDriveLink() throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 60; attempt++) {
            StorageLinkSessionDto updated = SharedWorldClient.apiClient().getStorageLink(this.storageLink.id());
            this.storageLink = updated;
            if ("linked".equalsIgnoreCase(updated.status()) || "failed".equalsIgnoreCase(updated.status()) || "expired".equalsIgnoreCase(updated.status())) {
                Minecraft.getInstance().execute(() -> {
                    this.driveLinkInFlight = false;
                    this.refreshStorageState();
                });
                return;
            }
            Thread.sleep(1_000L);
        }
        Minecraft.getInstance().execute(() -> {
            this.driveLinkInFlight = false;
            this.storageMessage = SharedWorldText.string("screen.sharedworld.storage_waiting_authorization");
            this.storageMessageColor = 0xFFFFD37A;
            this.refreshStorageState();
        });
    }

    private void openDriveLink(String authUrl) throws IOException {
        this.minecraft.keyboardHandler.setClipboard(authUrl);
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(authUrl));
            Minecraft.getInstance().execute(() -> {
                this.storageMessage = "";
                this.refreshStorageState();
            });
            return;
        }
        Minecraft.getInstance().execute(() -> {
            this.driveLinkCopiedFallback = true;
            this.storageMessage = SharedWorldText.string("screen.sharedworld.storage_link_copied");
            this.storageMessageColor = 0xFFFFD37A;
            this.refreshStorageState();
        });
    }

    private void refreshStorageState() {
        if (this.storageLinked()) {
            this.storageMessage = "";
        } else if (this.storageLink != null && this.storageLink.errorMessage() != null && !this.storageLink.errorMessage().isBlank()) {
            this.storageMessage = this.storageLink.errorMessage();
            this.storageMessageColor = 0xFFFF5555;
        } else if (this.driveLinkCopiedFallback) {
            this.storageMessage = SharedWorldText.string("screen.sharedworld.storage_link_copied");
            this.storageMessageColor = 0xFFFFD37A;
        }
        this.updateButtons();
    }

    private void submitCreate() {
        LocalSaveCatalog.LocalSaveOption save = this.selectedSave;
        if (save == null) {
            return;
        }
        this.submitting = true;
        this.updateButtons();
        this.minecraft.setScreen(new CreateSharedWorldProgressScreen(
                this.parent,
                this.buildDraft(),
                this.buildRequest(save)
        ));
    }

    private StorageLinkSessionDto requireLinkedSession() throws IOException {
        if (!this.storageLinked()) {
            throw new IOException(SharedWorldText.string("screen.sharedworld.storage_link_required"));
        }
        return this.storageLink;
    }

    static void importSaveIntoManagedWorld(Path source, Path workingCopy) throws IOException {
        Files.createDirectories(workingCopy);
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().isBlank()) {
                    continue;
                }
                Path target = workingCopy.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private CreateRequest buildRequest(LocalSaveCatalog.LocalSaveOption save) {
        try {
            return new CreateRequest(
                    save,
                    this.requireLinkedSession(),
                    this.worldName(),
                    AbstractSharedWorldMetadataScreen.effectiveMotd(this.motdBox.getValue()),
                    this.selectedIcon,
                    this.clearCustomIcon
            );
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private CreateDraft buildDraft() {
        return new CreateDraft(
                this.selectedSave == null ? null : this.selectedSave.id(),
                this.worldName(),
                this.motdBox.getValue(),
                this.selectedIcon,
                this.clearCustomIcon,
                this.storageLink
        );
    }

    private boolean storageLinked() {
        return this.storageLink != null && "linked".equalsIgnoreCase(this.storageLink.status());
    }

    private boolean nameValid() {
        return this.worldName().length() >= 3;
    }

    private String worldName() {
        return this.nameBox == null ? "" : this.nameBox.getValue().trim();
    }

    private int storageStatusColor() {
        if (this.storageLinked()) {
            return 0xFF55FF55;
        }
        if (this.driveLinkInFlight) {
            return 0xFFFFFFFF;
        }
        if (this.storageLink != null && ("failed".equalsIgnoreCase(this.storageLink.status()) || "expired".equalsIgnoreCase(this.storageLink.status()))) {
            return 0xFFFF5555;
        }
        return 0xFFFFD37A;
    }

    private void drawWrappedText(GuiGraphics guiGraphics, Component text, int x, int y, int width, int color) {
        List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(text, width);
        for (int index = 0; index < lines.size(); index++) {
            guiGraphics.drawString(this.font, lines.get(index), x, y + index * 9, color);
        }
    }

    private boolean isIconHovered(int mouseX, int mouseY) {
        if (this.contentArea == null || this.tabManager.getCurrentTab() != this.detailsTab) {
            return false;
        }
        int iconX = this.iconAreaX();
        int iconY = this.iconAreaY();
        return mouseX >= iconX && mouseX <= iconX + 48 && mouseY >= iconY && mouseY <= iconY + 48;
    }

    private int iconAreaX() {
        if (this.contentArea == null) {
            return 0;
        }
        int fieldsRight = this.contentArea.left() + 38 + Math.min(190, this.contentArea.width() - 140);
        int previewRight = this.previewCardX() + SharedWorldServerList.ROW_WIDTH;
        return fieldsRight + ((previewRight - fieldsRight) - 48) / 2;
    }

    private int iconAreaY() {
        if (this.contentArea == null) {
            return 0;
        }
        int top = this.contentArea.top();
        int previewTop = this.previewCardY();
        return top + ((previewTop - top) - 48) / 2;
    }

    private int previewCardX() {
        return this.contentArea.left() + (this.contentArea.width() - SharedWorldServerList.ROW_WIDTH) / 2;
    }

    private int previewCardY() {
        return this.contentArea.top() + 134;
    }

    private String previewWorldName() {
        String name = this.worldName();
        return name.isBlank() ? SharedWorldText.string("screen.sharedworld.name_hint") : name;
    }

    private String previewMotd() {
        return AbstractSharedWorldMetadataScreen.effectiveMotd(this.motdBox.getValue());
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static CreateSharedWorldScreen restored(SharedWorldScreen parent, CreateDraft draft, String errorMessage) {
        return new CreateSharedWorldScreen(parent, draft, new RestoreState(2, errorMessage, 0xFFFF5555));
    }

    record CreateDraft(
            String selectedSaveId,
            String name,
            String motd,
            SelectedIcon selectedIcon,
            boolean clearCustomIcon,
            StorageLinkSessionDto storageLink
    ) {
    }

    record CreateRequest(
            LocalSaveCatalog.LocalSaveOption save,
            StorageLinkSessionDto storageLink,
            String name,
            String motd,
            SelectedIcon selectedIcon,
            boolean clearCustomIcon
    ) {
        ImportedWorldSourceDto importSource() {
            return new ImportedWorldSourceDto("local-save", this.save.id(), this.save.displayName());
        }
    }

    private record RestoreState(int tabIndex, String message, int messageColor) {
    }

    private final class WorldTab implements Tab {
        @Override
        public Component getTabTitle() {
            return Component.translatable("screen.sharedworld.tab_world");
        }

        @Override
        public Component getTabExtraNarration() {
            return this.getTabTitle();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(CreateSharedWorldScreen.this.saveList);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            CreateSharedWorldScreen.this.saveList.setPosition(area.left() + CONTENT_MARGIN, area.top() + CONTENT_MARGIN);
            CreateSharedWorldScreen.this.saveList.setWidth(area.width() - CONTENT_MARGIN * 2);
            CreateSharedWorldScreen.this.saveList.setHeight(area.height() - CONTENT_MARGIN * 2);
        }
    }

    private final class DetailsTab implements Tab {
        @Override
        public Component getTabTitle() {
            return Component.translatable("screen.sharedworld.tab_details");
        }

        @Override
        public Component getTabExtraNarration() {
            return this.getTabTitle();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(CreateSharedWorldScreen.this.nameBox);
            consumer.accept(CreateSharedWorldScreen.this.motdBox);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            int left = area.left() + 38;
            CreateSharedWorldScreen.this.nameBox.setPosition(left, area.top() + 34);
            CreateSharedWorldScreen.this.nameBox.setWidth(Math.min(190, area.width() - 140));
            CreateSharedWorldScreen.this.motdBox.setPosition(left, area.top() + 88);
            CreateSharedWorldScreen.this.motdBox.setWidth(Math.min(190, area.width() - 140));
        }
    }

    private final class StorageTab implements Tab {
        @Override
        public Component getTabTitle() {
            return Component.translatable("screen.sharedworld.tab_storage");
        }

        @Override
        public Component getTabExtraNarration() {
            return this.getTabTitle();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(CreateSharedWorldScreen.this.linkDriveButton);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            CreateSharedWorldScreen.this.linkDriveButton.setPosition(area.left() + STORAGE_LEFT_PADDING, area.top() + STORAGE_BUTTON_TOP);
        }
    }
}
