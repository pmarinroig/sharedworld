package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldCustomIconStore.SelectedIcon;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.ImportedWorldSourceDto;
import link.sharedworld.api.SharedWorldModels.StorageAccountSummaryDto;
import link.sharedworld.api.SharedWorldModels.StorageLinkSessionDto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.FaviconTexture;
import link.sharedworld.versioned.GuiBlit;
import link.sharedworld.versioned.VersionedScreen;
import net.minecraft.network.chat.Component;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The create wizard: a linear flow (Connect Google Drive → Choose a world →
 * Name it and create) driven by {@link CreateWizardModel}. Players with a
 * healthy linked account never see the connect step; a fresh link advances
 * automatically the moment authorization completes.
 */
public final class CreateSharedWorldScreen extends VersionedScreen implements LocalSaveSelectionList.Host {
    private static final int HEADER_HEIGHT = 33;
    private static final int FOOTER_HEIGHT = 36;
    private static final int CONTENT_MARGIN = 12;
    private static final String EDIT_ICON_SPRITE = "sharedworld:edit_icon";
    private static final String EDIT_ICON_HIGHLIGHTED_SPRITE = "sharedworld:edit_icon_highlighted";
    private static final String DELETE_ICON_HIGHLIGHTED_SPRITE = "sharedworld:delete_icon_highlighted";
    private static final String PING_5_SPRITE = "minecraft:server_list/ping_5";
    private static final int FOOTER_BUTTON_WIDTH = 150;
    private static final int STORAGE_LEFT_PADDING = 36;
    private static final int STORAGE_COPY_TOP = 56;
    private static final int STORAGE_BUTTON_TOP = 104;
    private static final long ICON_ERROR_TTL_MS = 4_000L;

    private final SharedWorldScreen parent;
    private final CreateDraft restoredDraft;
    private final RestoreState restoreState;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, FOOTER_HEIGHT);
    private final List<LocalSaveCatalog.LocalSaveOption> localSaves = new java.util.ArrayList<>(LocalSaveCatalog.discover());
    private final CreateWizardModel wizard = new CreateWizardModel();
    private final DriveLinkAttemptController driveLinkController = new DriveLinkAttemptController();
    private final SharedWorldStatusBanner banner = new SharedWorldStatusBanner();

    private LocalSaveCatalog.LocalSaveOption selectedSave;
    private StorageLinkSessionDto storageLink;
    private StorageAccountSummaryDto storageAccount;
    private boolean accountCheckStarted;
    private FaviconTexture previewTexture;
    /** Step title sits at y 8 (9px tall); the pick list follows right under it. */
    private static final int PICK_LIST_TOP = 26;

    private ScreenRectangle contentArea;

    private LocalSaveSelectionList saveList;
    private Button selectFolderButton;
    private EditBox nameBox;
    private EditBox motdBox;
    private Button linkDriveButton;
    private Button backButton;
    private Button primaryButton;

    private SelectedIcon selectedIcon;
    private boolean clearCustomIcon;
    private boolean submitting;
    private boolean iconHovered;
    private boolean restoreErrorVisible;

    public CreateSharedWorldScreen(SharedWorldScreen parent) {
        this(parent, null, null);
    }

    CreateSharedWorldScreen(SharedWorldScreen parent, CreateDraft restoredDraft, RestoreState restoreState) {
        super(Component.translatable("screen.sharedworld.create_title"));
        this.parent = parent;
        this.restoredDraft = restoredDraft;
        this.restoreState = restoreState;
        if (restoredDraft != null && restoredDraft.selectedSave() != null) {
            // The draft carries the full option, not just an id: a folder-picked
            // save lives outside saves/ and is absent from discovery, and a
            // retry must never silently substitute a different world.
            LocalSaveCatalog.LocalSaveOption restoredSave = restoredDraft.selectedSave();
            this.localSaves.removeIf(save -> save.directory().equals(restoredSave.directory()));
            this.localSaves.add(0, restoredSave);
            this.selectedSave = restoredSave;
        }
        if (this.selectedSave == null && !this.localSaves.isEmpty()) {
            this.selectedSave = this.localSaves.get(0);
        }
        if (restoredDraft != null) {
            if (restoredDraft.storageLink() != null && "linked".equalsIgnoreCase(restoredDraft.storageLink().status())) {
                this.wizard.onLinkCompleted();
            }
            this.wizard.restoreToDetails();
        }
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.previewTexture = FaviconTexture.forWorld(this.minecraft.getTextureManager(), "sharedworld/create-preview");

        LinearLayout footer = this.layout.addToFooter(link.sharedworld.versioned.LayoutCompat.horizontalLayout(8));
        this.backButton = footer.addChild(Button.builder(Component.translatable("screen.sharedworld.cancel"), ignored -> this.onBack())
                .width(FOOTER_BUTTON_WIDTH)
                .build());
        this.primaryButton = footer.addChild(Button.builder(Component.translatable("screen.sharedworld.next"), ignored -> this.onPrimaryAction())
                .width(FOOTER_BUTTON_WIDTH)
                .build());
        this.layout.visitWidgets(this::addRenderableWidget);

        // No tabs on this screen: the list is a plain widget (registerTabList
        // is only for TabManager-owned lists and would never render here).
        this.saveList = this.addRenderableWidget(new LocalSaveSelectionList(this.minecraft, 0, 0, 0, 36, this));
        this.saveList.setSaves(this.localSaves, this.selectedSave == null ? null : this.selectedSave.id());
        this.selectFolderButton = Button.builder(
                Component.translatable("screen.sharedworld.select_folder"),
                ignored -> this.selectWorldFolder()
        ).width(FOOTER_BUTTON_WIDTH).build();
        this.addRenderableWidget(this.selectFolderButton);

        this.nameBox = new EditBox(this.font, 0, 0, 220, 20, Component.translatable("screen.sharedworld.world_name"));
        this.nameBox.setMaxLength(128);
        this.nameBox.setValue(this.restoredDraft != null
                ? blankOr(this.restoredDraft.name(), this.selectedSave == null ? "" : this.selectedSave.displayName())
                : (this.selectedSave == null ? "" : this.selectedSave.displayName()));
        this.addRenderableWidget(this.nameBox);

        this.motdBox = new EditBox(this.font, 0, 0, 240, 20, SharedWorldText.component("screen.sharedworld.motd_hint", SharedWorldApiClient.currentPlayerName()));
        this.motdBox.setMaxLength(256);
        this.motdBox.setHint(SharedWorldText.component("screen.sharedworld.motd_hint", SharedWorldApiClient.currentPlayerName()));
        if (this.restoredDraft != null && this.restoredDraft.motd() != null) {
            this.motdBox.setValue(this.restoredDraft.motd());
        }
        this.addRenderableWidget(this.motdBox);

        this.linkDriveButton = Button.builder(Component.translatable("screen.sharedworld.storage_link_google_drive"), ignored -> this.onConnectDrivePressed())
                .width(190)
                .build();
        this.addRenderableWidget(this.linkDriveButton);

        if (this.restoredDraft != null) {
            this.selectedIcon = this.restoredDraft.selectedIcon();
            this.clearCustomIcon = this.restoredDraft.clearCustomIcon();
            this.storageLink = this.restoredDraft.storageLink();
        }
        if (this.restoreState != null && this.restoreState.message() != null && !this.restoreState.message().isBlank()) {
            this.banner.set(SharedWorldStatusBanner.Kind.ERROR, Component.literal(this.restoreState.message()));
            // Survives updateStorageBanner()'s off-connect-step clearSticky;
            // without this flag every create failure was wiped before the
            // first frame rendered and the user saw nothing at all.
            this.restoreErrorVisible = true;
        }

        this.beginStorageAccountCheckOnce();
        this.refreshPreview();
        this.updateStorageBanner();
        this.applyStepVisibility();
        this.updateButtons();
        this.repositionElements();
    }

    private void beginStorageAccountCheckOnce() {
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
                .whenComplete((account, error) -> Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        this.wizard.onStorageAccountCheckFailed();
                        this.updateStorageBanner();
                        this.updateButtons();
                        return;
                    }
                    this.storageAccount = account;
                    boolean advanced = this.wizard.onStorageAccountChecked(account.linked() && account.healthy());
                    if (advanced) {
                        this.onStepChanged();
                    } else {
                        this.updateStorageBanner();
                        this.updateButtons();
                    }
                }));
    }

    @Override
    protected void repositionElements() {
        this.contentArea = new ScreenRectangle(
                0,
                HEADER_HEIGHT,
                this.width,
                this.height - FOOTER_HEIGHT - HEADER_HEIGHT
        );
        this.layout.arrangeElements();
        this.layoutStepWidgets();
    }

    private void layoutStepWidgets() {
        if (this.contentArea == null || this.saveList == null) {
            return;
        }
        ScreenRectangle area = this.contentArea;

        // Pick-world step widgets: the list starts right under the step title
        // (no header band to honor on this screen) and runs to the folder
        // button, which sits right above the footer.
        int folderButtonHeight = 20;
        int folderButtonY = this.height - FOOTER_HEIGHT - folderButtonHeight - 6;
        this.saveList.sharedworldSetBounds(
                area.left() + CONTENT_MARGIN,
                PICK_LIST_TOP,
                area.width() - CONTENT_MARGIN * 2,
                Math.max(36, folderButtonY - 8 - PICK_LIST_TOP)
        );
        this.selectFolderButton.setPosition(
                area.left() + (area.width() - this.selectFolderButton.getWidth()) / 2,
                folderButtonY
        );

        // Details step widgets.
        int left = area.left() + 38;
        this.nameBox.setPosition(left, area.top() + 34);
        this.nameBox.setWidth(Math.min(190, area.width() - 140));
        this.motdBox.setPosition(left, area.top() + 82);
        this.motdBox.setWidth(Math.min(190, area.width() - 140));

        // Connect step widget.
        this.linkDriveButton.setPosition(
                area.left() + (area.width() - this.linkDriveButton.getWidth()) / 2,
                area.top() + STORAGE_BUTTON_TOP
        );
    }

    private void applyStepVisibility() {
        CreateWizardModel.Step step = this.wizard.step();
        if (this.saveList != null) {
            this.saveList.sharedworldSetVisibleForTab(step == CreateWizardModel.Step.PICK_WORLD);
        }
        if (this.selectFolderButton != null) {
            this.selectFolderButton.visible = step == CreateWizardModel.Step.PICK_WORLD;
            this.selectFolderButton.active = !this.submitting;
        }
        if (this.nameBox != null) {
            this.nameBox.visible = step == CreateWizardModel.Step.DETAILS;
        }
        if (this.motdBox != null) {
            this.motdBox.visible = step == CreateWizardModel.Step.DETAILS;
        }
        if (this.linkDriveButton != null) {
            this.linkDriveButton.visible = step == CreateWizardModel.Step.CONNECT_DRIVE;
        }
    }

    private void onStepChanged() {
        this.applyStepVisibility();
        this.updateStorageBanner();
        this.updateButtons();
        this.sharedworldSetInitialFocus();
    }

    @Override
    protected void sharedworldSetInitialFocus() {
        switch (this.wizard.step()) {
            case DETAILS -> this.setInitialFocus(this.nameBox);
            case PICK_WORLD -> this.setInitialFocus(this.saveList);
            case CONNECT_DRIVE -> this.setInitialFocus(this.linkDriveButton);
        }
    }

    @Override
    protected boolean sharedworldMouseClicked(double mouseX, double mouseY) {
        if (this.wizard.step() == CreateWizardModel.Step.DETAILS && this.isIconHovered((int) mouseX, (int) mouseY)) {
            if (this.selectedIcon != null) {
                this.selectedIcon = null;
                this.clearCustomIcon = true;
                this.refreshPreview();
            } else {
                this.chooseIcon();
            }
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        this.cancelDriveLinkAttempt(true);
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, this.parent);
    }

    @Override
    public void removed() {
        this.cancelDriveLinkAttempt(true);
        super.removed();
        if (this.previewTexture != null) {
            this.previewTexture.clear();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.applyStepVisibility();
        this.iconHovered = this.isIconHovered(mouseX, mouseY);
        this.updateButtons();
        this.sharedworldRenderMenuBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.stepTitle(), this.width / 2, 8, 0xFFFFFFFF);
        if (this.wizard.step() == CreateWizardModel.Step.DETAILS) {
            // Destination context lives in the header where nothing collides.
            guiGraphics.drawCenteredString(this.font, this.storageDestinationLabel(), this.width / 2, 20, 0xFF8EA3BC);
        }

        switch (this.wizard.step()) {
            case CONNECT_DRIVE -> this.renderConnectDecorations(guiGraphics);
            case PICK_WORLD -> this.renderPickWorldDecorations(guiGraphics);
            case DETAILS -> this.renderDetailsDecorations(guiGraphics);
        }

        // On the pick step the folder button occupies the strip above the footer,
        // so the banner (folder-pick errors) draws just above the button instead.
        int bannerBottomY = this.wizard.step() == CreateWizardModel.Step.PICK_WORLD && this.selectFolderButton != null
                ? this.selectFolderButton.getY() - 4
                : this.height - FOOTER_HEIGHT - 6;
        this.banner.renderBottomCentered(guiGraphics, this.font, this.width / 2, bannerBottomY, Math.min(this.width - 40, 420));
        GuiBlit.footerSeparator(guiGraphics, this.height - this.layout.getFooterHeight() - 2, this.width);
    }

    private Component stepTitle() {
        return switch (this.wizard.step()) {
            case CONNECT_DRIVE -> Component.translatable("screen.sharedworld.create_step_connect_title");
            case PICK_WORLD -> Component.translatable("screen.sharedworld.create_step_world_title");
            case DETAILS -> Component.translatable("screen.sharedworld.create_step_details_title");
        };
    }

    private void renderConnectDecorations(GuiGraphics guiGraphics) {
        if (this.contentArea == null) {
            return;
        }
        int left = this.contentArea.left() + STORAGE_LEFT_PADDING;
        this.drawWrappedText(
                guiGraphics,
                Component.translatable("screen.sharedworld.storage_google_drive_detail"),
                left,
                this.contentArea.top() + STORAGE_COPY_TOP,
                this.contentArea.width() - STORAGE_LEFT_PADDING * 2,
                0xFFB8C5D6
        );
    }

    private void renderPickWorldDecorations(GuiGraphics guiGraphics) {
        if (this.contentArea == null || !this.localSaves.isEmpty()) {
            return;
        }
        int listBottom = this.selectFolderButton != null ? this.selectFolderButton.getY() - 8 : this.height - FOOTER_HEIGHT - 34;
        guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("screen.sharedworld.no_local_worlds"),
                this.width / 2,
                (PICK_LIST_TOP + listBottom) / 2 - 4,
                0xFFFFFFFF
        );
    }

    @Override
    public void onSaveActivated(LocalSaveCatalog.LocalSaveOption save) {
        if (this.wizard.step() == CreateWizardModel.Step.PICK_WORLD && this.wizard.advance(this.selectedSave != null, this.nameValid())) {
            this.onStepChanged();
        }
    }

    private void selectWorldFolder() {
        java.nio.file.Path chosen = SharedWorldFolderPicker.chooseFolder(
                SharedWorldText.string("screen.sharedworld.select_folder_title"));
        if (chosen == null) {
            return;
        }
        LocalSaveCatalog.LocalSaveOption option;
        try {
            option = LocalSaveFolderValidator.validate(
                    chosen,
                    this.minecraft.gameDirectory.toPath().resolve("sharedworld").resolve("worlds"),
                    link.sharedworld.versioned.ClientCompat.currentDataVersion()
            );
        } catch (LocalSaveFolderValidator.InvalidSaveFolderException exception) {
            this.banner.set(SharedWorldStatusBanner.Kind.ERROR, Component.literal(exception.getMessage()));
            return;
        }
        this.banner.clearSticky();
        this.localSaves.removeIf(save -> save.directory().equals(option.directory()));
        this.localSaves.add(0, option);
        this.onSaveSelected(option);
    }

    @Override
    public void onSaveSelected(LocalSaveCatalog.LocalSaveOption save) {
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

    private void renderDetailsDecorations(GuiGraphics guiGraphics) {
        if (this.contentArea == null) {
            return;
        }

        int left = this.contentArea.left() + 38;
        int top = this.contentArea.top();
        int iconX = this.iconAreaX();
        int iconY = this.iconAreaY();

        guiGraphics.drawString(this.font, Component.translatable("screen.sharedworld.world_name"), left, top + 24, 0xFFA0A0A0);
        guiGraphics.drawString(this.font, Component.translatable("screen.sharedworld.motd"), left, top + 72, 0xFFA0A0A0);
        GuiBlit.favicon(guiGraphics, this.previewTexture, iconX, iconY, 48);
        // Always-visible pencil badge: the icon well is a button, say so silently.
        // The dark chip keeps the pencil readable over any world screenshot.
        guiGraphics.fill(iconX + 48 - 16, iconY + 48 - 16, iconX + 48, iconY + 48, 0xB0000000);
        GuiBlit.sprite(guiGraphics, EDIT_ICON_SPRITE, iconX + 48 - 14, iconY + 48 - 14, 12, 12);

        if (this.iconHovered) {
            guiGraphics.fill(iconX, iconY, iconX + 48, iconY + 48, 0x80000000);
            String actionSprite = this.selectedIcon != null
                    ? DELETE_ICON_HIGHLIGHTED_SPRITE
                    : EDIT_ICON_HIGHLIGHTED_SPRITE;
            GuiBlit.sprite(guiGraphics, actionSprite, iconX + 12, iconY + 12, 24, 24);
        }

        this.renderServerCardPreview(guiGraphics);

        if (!this.nameValid()) {
            guiGraphics.drawString(
                    this.font,
                    Component.translatable("screen.sharedworld.validation_world_name_short"),
                    left,
                    link.sharedworld.versioned.WidgetCompat.bottom(this.nameBox) + 6,
                    0xFFFF5555
            );
        }
    }

    private Component storageDestinationLabel() {
        String email = null;
        if (this.wizard.storageState() == CreateWizardModel.StorageState.LINKED_THIS_RUN && this.storageLink != null) {
            email = this.storageLink.linkedAccountEmail();
        } else if (this.storageAccount != null && this.storageAccount.linked()) {
            email = this.storageAccount.email();
        }
        return email == null || email.isBlank()
                ? Component.translatable("screen.sharedworld.storage_saving_to_drive")
                : SharedWorldText.component("screen.sharedworld.storage_saving_to", email);
    }

    private void renderServerCardPreview(GuiGraphics guiGraphics) {
        int rowX = this.previewCardX();
        int rowY = this.previewCardY();
        int contentX = rowX + SharedWorldServerList.CONTENT_PADDING;
        int contentY = rowY + SharedWorldServerList.CONTENT_PADDING;
        SharedWorldServerList.renderSelectedOutline(guiGraphics, rowX, rowY, true);
        GuiBlit.favicon(guiGraphics, this.previewTexture, contentX, contentY, 32);
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
        CreateWizardModel.Step step = this.wizard.step();
        boolean firstStep = step == CreateWizardModel.Step.CONNECT_DRIVE
                || (step == CreateWizardModel.Step.PICK_WORLD && !this.wizard.connectStepRequired());
        this.backButton.setMessage(firstStep ? Component.translatable("screen.sharedworld.cancel") : Component.translatable("gui.back"));
        this.backButton.active = !this.submitting;

        if (this.wizard.advanceIsCreate()) {
            this.primaryButton.setMessage(Component.translatable(this.submitting
                    ? "screen.sharedworld.creating"
                    : "screen.sharedworld.create_world"));
        } else {
            this.primaryButton.setMessage(Component.translatable("screen.sharedworld.next"));
        }
        this.primaryButton.active = !this.submitting && this.wizard.canAdvance(this.selectedSave != null, this.nameValid());

        this.linkDriveButton.setMessage(Component.translatable(this.driveLinkButtonTranslationKey()));
        this.linkDriveButton.active = !this.submitting
                && !this.driveLinkOpeningBrowser()
                && this.wizard.storageState() != CreateWizardModel.StorageState.CHECKING;
    }

    private void onBack() {
        if (this.submitting) {
            return;
        }
        if (this.wizard.back()) {
            this.onStepChanged();
        } else {
            this.onClose();
        }
    }

    private void onPrimaryAction() {
        if (this.submitting) {
            return;
        }
        boolean saveSelected = this.selectedSave != null;
        if (this.wizard.advanceIsCreate()) {
            if (this.wizard.canAdvance(saveSelected, this.nameValid())) {
                this.submitCreate();
            }
            return;
        }
        if (this.wizard.advance(saveSelected, this.nameValid())) {
            this.onStepChanged();
        }
    }

    private void chooseIcon() {
        try {
            this.selectedIcon = SharedWorldClient.customIconStore().chooseIcon();
            if (this.selectedIcon != null) {
                this.clearCustomIcon = false;
                this.refreshPreview();
            }
        } catch (Exception exception) {
            this.banner.setTransient(
                    SharedWorldStatusBanner.Kind.ERROR,
                    Component.translatable("screen.sharedworld.icon_error_invalid_png"),
                    ICON_ERROR_TTL_MS
            );
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

    private void onConnectDrivePressed() {
        this.beginDriveLink(this.shouldRetryWithConsent());
    }

    /** A failed or expired link attempt retries through the full consent screen. */
    private boolean shouldRetryWithConsent() {
        return this.storageLink != null
                && ("failed".equalsIgnoreCase(this.storageLink.status()) || "expired".equalsIgnoreCase(this.storageLink.status()));
    }

    private void beginDriveLink(boolean forceConsent) {
        this.cancelDriveLinkAttempt(false);
        this.storageLink = null;
        DriveLinkAttempt attempt = this.driveLinkController.beginAttempt();
        this.updateStorageBanner();
        CompletableFuture.runAsync(() -> this.runDriveLinkAttempt(attempt, forceConsent), SharedWorldClient.ioExecutor());
    }

    private void runDriveLinkAttempt(DriveLinkAttempt attempt, boolean forceConsent) {
        try {
            StorageLinkSessionDto session = SharedWorldClient.apiClient().createStorageLink(forceConsent);
            attempt.setSession(session);
            this.scheduleCurrentAttemptUiUpdate(attempt, () -> {
                this.storageLink = session;
                this.updateStorageBanner();
                this.updateButtons();
            });
            this.openDriveLink(attempt);
            attempt.setPhase(DriveLinkUiPhase.WAITING_FOR_AUTH);
            this.scheduleCurrentAttemptUiUpdate(attempt, () -> {
                this.updateStorageBanner();
                this.updateButtons();
            });
            this.pollDriveLink(attempt);
        } catch (Exception exception) {
            attempt.setPhase(DriveLinkUiPhase.ERROR);
            this.scheduleCurrentAttemptUiUpdate(attempt, () -> {
                this.driveLinkController.clearIfCurrent(attempt);
                this.banner.set(
                        SharedWorldStatusBanner.Kind.ERROR,
                        Component.literal(SharedWorldMetadataFormat.friendlyMessage(exception))
                );
                this.updateButtons();
            });
        }
    }

    private void pollDriveLink(DriveLinkAttempt attempt) throws IOException, InterruptedException {
        new DriveLinkPoller(SharedWorldClient.apiClient()::getStorageLink, Thread::sleep).poll(
                attempt,
                updated -> this.scheduleCurrentAttemptUiUpdate(attempt, () -> {
                    this.storageLink = updated;
                    attempt.setPhase(DriveLinkUiPhase.forTerminalStatus(updated.status()));
                    this.driveLinkController.clearIfCurrent(attempt);
                    this.onDriveLinkTerminal(updated);
                })
        );
    }

    private void onDriveLinkTerminal(StorageLinkSessionDto session) {
        if ("linked".equalsIgnoreCase(session.status())) {
            // No textual confirmation: the wizard auto-advancing (and the
            // "Saving to Google Drive" header on the details step) IS the feedback.
            if (this.wizard.onLinkCompleted()) {
                this.onStepChanged();
                return;
            }
        }
        this.updateStorageBanner();
        this.updateButtons();
    }

    private void openDriveLink(DriveLinkAttempt attempt) throws IOException {
        if (attempt.authUrl() == null) {
            throw new IOException(SharedWorldText.string("screen.sharedworld.storage_missing_auth_url"));
        }
        this.minecraft.keyboardHandler.setClipboard(attempt.authUrl());
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(attempt.authUrl()));
            attempt.setCopiedFallback(false);
            return;
        }
        attempt.setCopiedFallback(true);
    }

    private void cancelDriveLinkAttempt(boolean cancelBackend) {
        DriveLinkAttempt attempt = this.driveLinkController.cancelCurrent();
        if (attempt == null) {
            return;
        }
        if (cancelBackend && attempt.sessionId() != null && attempt.phase().isPending()) {
            CompletableFuture.runAsync(() -> {
                try {
                    SharedWorldClient.apiClient().cancelStorageLink(attempt.sessionId());
                } catch (Exception ignored) {
                }
            }, SharedWorldClient.ioExecutor());
        }
    }

    private String driveLinkButtonTranslationKey() {
        if (this.driveLinkWaitingForAuthorization()) {
            return "screen.sharedworld.storage_get_new_link";
        }
        if (this.shouldRetryWithConsent()) {
            return "screen.sharedworld.storage_try_again";
        }
        return "screen.sharedworld.storage_link_google_drive";
    }

    private boolean driveLinkOpeningBrowser() {
        DriveLinkAttempt attempt = this.driveLinkController.currentAttempt();
        return attempt != null && attempt.phase() == DriveLinkUiPhase.OPENING_BROWSER;
    }

    private boolean driveLinkWaitingForAuthorization() {
        DriveLinkAttempt attempt = this.driveLinkController.currentAttempt();
        return attempt != null && attempt.phase() == DriveLinkUiPhase.WAITING_FOR_AUTH;
    }

    private void scheduleCurrentAttemptUiUpdate(DriveLinkAttempt attempt, Runnable update) {
        Minecraft.getInstance().execute(() -> {
            if (!this.driveLinkController.isCurrent(attempt)) {
                return;
            }
            update.run();
        });
    }

    private void updateStorageBanner() {
        this.restoreErrorVisible = updateStorageBanner(
                this.banner,
                this.wizard.step() == CreateWizardModel.Step.CONNECT_DRIVE,
                this.restoreErrorVisible,
                this.driveLinkController.currentAttempt(),
                this.wizard.storageState(),
                this.storageLink
        );
    }

    /**
     * Storage progress/error messaging via the shared banner, extracted for
     * tests. Returns whether a restored create-failure error still owns the
     * banner: off the connect step that error must survive this method's
     * sticky-clearing (it used to be wiped in the same init() that set it, so
     * every create failure bounced the user back with no message at all); on
     * the connect step link messaging takes over.
     */
    static boolean updateStorageBanner(
            SharedWorldStatusBanner banner,
            boolean onConnectStep,
            boolean restoreErrorVisible,
            DriveLinkAttempt attempt,
            CreateWizardModel.StorageState storageState,
            StorageLinkSessionDto storageLink
    ) {
        if (!onConnectStep) {
            // Leaving the connect step must not strand its sticky messages
            // (e.g. "Checking your Google Drive connection..."); transient
            // successes keep their own expiry.
            if (!restoreErrorVisible) {
                banner.clearSticky();
            }
            return restoreErrorVisible;
        }
        if (attempt != null && attempt.phase() == DriveLinkUiPhase.OPENING_BROWSER) {
            banner.set(SharedWorldStatusBanner.Kind.WARNING, Component.translatable("screen.sharedworld.storage_waiting_for_browser"));
            return false;
        }
        if (attempt != null && attempt.phase() == DriveLinkUiPhase.WAITING_FOR_AUTH) {
            banner.set(SharedWorldStatusBanner.Kind.WARNING, Component.translatable(attempt.copiedFallback()
                    ? "screen.sharedworld.storage_link_copied"
                    : "screen.sharedworld.storage_waiting_authorization"));
            return false;
        }
        if (storageState == CreateWizardModel.StorageState.CHECKING) {
            banner.set(SharedWorldStatusBanner.Kind.INFO, Component.translatable("screen.sharedworld.storage_checking_account"));
            return false;
        }
        if (storageLink != null
                && !"cancelled".equalsIgnoreCase(storageLink.status())
                && !"linked".equalsIgnoreCase(storageLink.status())
                && storageLink.errorMessage() != null
                && !storageLink.errorMessage().isBlank()) {
            banner.set(SharedWorldStatusBanner.Kind.ERROR, Component.literal(storageLink.errorMessage()));
            return false;
        }
        banner.clearSticky();
        return false;
    }

    private void submitCreate() {
        LocalSaveCatalog.LocalSaveOption save = this.selectedSave;
        if (save == null) {
            return;
        }
        this.submitting = true;
        this.updateButtons();
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new CreateSharedWorldProgressScreen(
                this.parent,
                this.buildDraft(),
                this.buildRequest(save)
        ));
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
        StorageLinkSessionDto freshLink = this.wizard.storageState() == CreateWizardModel.StorageState.LINKED_THIS_RUN
                ? this.storageLink
                : null;
        return new CreateRequest(
                save,
                freshLink,
                this.worldName(),
                SharedWorldMetadataFormat.effectiveMotd(this.motdBox.getValue()),
                this.selectedIcon,
                this.clearCustomIcon
        );
    }

    private CreateDraft buildDraft() {
        return new CreateDraft(
                this.selectedSave,
                this.worldName(),
                this.motdBox.getValue(),
                this.selectedIcon,
                this.clearCustomIcon,
                this.wizard.storageState() == CreateWizardModel.StorageState.LINKED_THIS_RUN ? this.storageLink : null
        );
    }

    private boolean nameValid() {
        return this.worldName().length() >= 3;
    }

    private String worldName() {
        return this.nameBox == null ? "" : this.nameBox.getValue().trim();
    }

    private void drawWrappedText(GuiGraphics guiGraphics, Component text, int x, int y, int width, int color) {
        List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(text, width);
        for (int index = 0; index < lines.size(); index++) {
            guiGraphics.drawString(this.font, lines.get(index), x, y + index * 9, color);
        }
    }

    private boolean isIconHovered(int mouseX, int mouseY) {
        if (this.contentArea == null || this.wizard.step() != CreateWizardModel.Step.DETAILS) {
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
        // Bottom-anchored above the banner band so it can never collide with
        // the footer or the banner, whatever the window height.
        return this.height - FOOTER_HEIGHT - SharedWorldStatusBanner.BAND_HEIGHT - SharedWorldServerList.ROW_HEIGHT - 2;
    }

    private String previewWorldName() {
        String name = this.worldName();
        return name.isBlank() ? SharedWorldText.string("screen.sharedworld.name_hint") : name;
    }

    private String previewMotd() {
        return SharedWorldMetadataFormat.effectiveMotd(this.motdBox.getValue());
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static CreateSharedWorldScreen restored(SharedWorldScreen parent, CreateDraft draft, String errorMessage) {
        return new CreateSharedWorldScreen(parent, draft, new RestoreState(errorMessage));
    }

    record CreateDraft(
            LocalSaveCatalog.LocalSaveOption selectedSave,
            String name,
            String motd,
            SelectedIcon selectedIcon,
            boolean clearCustomIcon,
            StorageLinkSessionDto storageLink
    ) {
    }

    /**
     * A create request either carries the link session completed during this
     * wizard run, or a null {@code storageLink} meaning "use the player's
     * already-linked storage account".
     */
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

    private record RestoreState(String message) {
    }

    enum DriveLinkUiPhase {
        IDLE,
        OPENING_BROWSER,
        WAITING_FOR_AUTH,
        LINKED,
        ERROR;

        boolean isPending() {
            return this == OPENING_BROWSER || this == WAITING_FOR_AUTH;
        }

        static DriveLinkUiPhase forTerminalStatus(String status) {
            if ("linked".equalsIgnoreCase(status)) {
                return LINKED;
            }
            if ("cancelled".equalsIgnoreCase(status)) {
                return IDLE;
            }
            return ERROR;
        }
    }

    static final class DriveLinkAttempt {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile DriveLinkUiPhase phase;
        private volatile String sessionId;
        private volatile String authUrl;
        private volatile boolean copiedFallback;

        DriveLinkAttempt(DriveLinkUiPhase phase) {
            this.phase = phase;
        }

        boolean cancel() {
            return this.cancelled.compareAndSet(false, true);
        }

        boolean isCancelled() {
            return this.cancelled.get();
        }

        DriveLinkUiPhase phase() {
            return this.phase;
        }

        void setPhase(DriveLinkUiPhase phase) {
            this.phase = phase;
        }

        void setSession(StorageLinkSessionDto session) {
            this.sessionId = session.id();
            this.authUrl = session.authUrl();
        }

        String sessionId() {
            return this.sessionId;
        }

        String authUrl() {
            return this.authUrl;
        }

        boolean copiedFallback() {
            return this.copiedFallback;
        }

        void setCopiedFallback(boolean copiedFallback) {
            this.copiedFallback = copiedFallback;
        }
    }

    static final class DriveLinkAttemptController {
        private final AtomicReference<DriveLinkAttempt> currentAttempt = new AtomicReference<>();

        DriveLinkAttempt beginAttempt() {
            DriveLinkAttempt attempt = new DriveLinkAttempt(DriveLinkUiPhase.OPENING_BROWSER);
            DriveLinkAttempt previous = this.currentAttempt.getAndSet(attempt);
            if (previous != null) {
                previous.cancel();
            }
            return attempt;
        }

        DriveLinkAttempt currentAttempt() {
            return this.currentAttempt.get();
        }

        boolean isCurrent(DriveLinkAttempt attempt) {
            return this.currentAttempt.get() == attempt && !attempt.isCancelled();
        }

        void clearIfCurrent(DriveLinkAttempt attempt) {
            this.currentAttempt.compareAndSet(attempt, null);
        }

        DriveLinkAttempt cancelCurrent() {
            DriveLinkAttempt attempt = this.currentAttempt.getAndSet(null);
            if (attempt != null) {
                attempt.cancel();
            }
            return attempt;
        }
    }

    static final class DriveLinkPoller {
        private final StorageLinkFetcher fetcher;
        private final PollDelay delay;

        DriveLinkPoller(StorageLinkFetcher fetcher, PollDelay delay) {
            this.fetcher = fetcher;
            this.delay = delay;
        }

        void poll(DriveLinkAttempt attempt, Consumer<StorageLinkSessionDto> onTerminal) throws IOException, InterruptedException {
            if (attempt.sessionId() == null) {
                throw new IOException("SharedWorld did not receive a Google Drive session id.");
            }
            while (!attempt.isCancelled()) {
                StorageLinkSessionDto updated = this.fetcher.get(attempt.sessionId());
                if (attempt.isCancelled()) {
                    return;
                }
                if (isTerminalStatus(updated.status())) {
                    onTerminal.accept(updated);
                    return;
                }
                // 2s is plenty for an OAuth browser round-trip and halves the
                // per-link request burst.
                this.delay.sleep(2_000L);
            }
        }

        static boolean isTerminalStatus(String status) {
            return "linked".equalsIgnoreCase(status)
                    || "failed".equalsIgnoreCase(status)
                    || "expired".equalsIgnoreCase(status)
                    || "cancelled".equalsIgnoreCase(status);
        }

        @FunctionalInterface
        interface StorageLinkFetcher {
            StorageLinkSessionDto get(String sessionId) throws IOException, InterruptedException;
        }

        @FunctionalInterface
        interface PollDelay {
            void sleep(long millis) throws InterruptedException;
        }
    }
}
