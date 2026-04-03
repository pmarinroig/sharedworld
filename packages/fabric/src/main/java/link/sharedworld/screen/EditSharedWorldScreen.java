package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldCustomIconStore.SelectedIcon;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.StorageUsageSummaryDto;
import link.sharedworld.api.SharedWorldModels.WorldDetailsDto;
import link.sharedworld.api.SharedWorldModels.WorldMembershipDto;
import link.sharedworld.api.SharedWorldModels.WorldSnapshotSummaryDto;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.sync.ManagedWorldStore;
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

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

public final class EditSharedWorldScreen extends Screen {
    private static final int FOOTER_HEIGHT = 36;
    private static final int CONTENT_MARGIN = 12;
    private static final Identifier EDIT_ICON_HIGHLIGHTED_SPRITE = Identifier.fromNamespaceAndPath("sharedworld", "edit_icon_highlighted");
    private static final Identifier DELETE_ICON_HIGHLIGHTED_SPRITE = Identifier.fromNamespaceAndPath("sharedworld", "delete_icon_highlighted");
    private static final Identifier PING_5_SPRITE = Identifier.withDefaultNamespace("server_list/ping_5");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SharedWorldScreen parent;
    private final WorldSummaryDto world;
    private final ManagedWorldStore worldStore = new ManagedWorldStore();
    private final EditSharedWorldDataController dataController;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 0, FOOTER_HEIGHT);
    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);

    private final DetailsTab detailsTab = new DetailsTab();
    private final BackupsTab backupsTab = new BackupsTab();
    private final MembersTab membersTab = new MembersTab();
    private final StorageTab storageTab = new StorageTab();

    private WorldDetailsDto details;
    private StorageUsageSummaryDto storageUsage;
    private List<WorldSnapshotSummaryDto> snapshots = List.of();
    private List<WorldMembershipDto> memberships = List.of();
    private WorldSnapshotSummaryDto selectedSnapshot;
    private WorldMembershipDto selectedMember;

    private FaviconTexture previewTexture;
    private TabNavigationBar tabNavigationBar;
    private ScreenRectangle contentArea;

    private EditBox nameBox;
    private EditBox motdBox;
    private SnapshotBrowserList snapshotList;
    private MemberBrowserList memberList;
    private Button backButton;
    private Button secondaryButton;
    private Button primaryButton;
    private SelectedIcon selectedIcon;
    private boolean clearCustomIcon;
    private boolean loading = true;
    private boolean savingDetails;
    private boolean actionInFlight;
    private boolean confirmRestore;
    private boolean confirmDelete;
    private boolean confirmKick;
    private boolean iconHovered;
    private String statusMessage = "";
    private int statusColor = 0xFFB8C5D6;
    private Tab lastTab;

    public EditSharedWorldScreen(SharedWorldScreen parent, WorldSummaryDto world) {
        super(Component.translatable("screen.sharedworld.edit_title"));
        this.parent = parent;
        this.world = world;
        this.dataController = new EditSharedWorldDataController(
                SharedWorldClient.apiClient(),
                SharedWorldClient.customIconStore(),
                SharedWorldClient.ioExecutor(),
                runnable -> Minecraft.getInstance().execute(runnable)
        );
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.previewTexture = FaviconTexture.forWorld(this.minecraft.getTextureManager(), "sharedworld/edit-preview/" + this.world.id());

        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        this.backButton = footer.addChild(Button.builder(Component.translatable("gui.back"), ignored -> this.onBack())
                .width(120)
                .build());
        this.secondaryButton = footer.addChild(Button.builder(Component.literal(""), ignored -> this.onSecondaryAction())
                .width(120)
                .build());
        this.primaryButton = footer.addChild(Button.builder(Component.literal(""), ignored -> this.onPrimaryAction())
                .width(150)
                .build());
        this.layout.visitWidgets(this::addRenderableWidget);

        this.nameBox = new EditBox(this.font, 0, 0, 220, 20, Component.translatable("screen.sharedworld.world_name"));
        this.nameBox.setMaxLength(128);

        this.motdBox = new EditBox(this.font, 0, 0, 240, 20, SharedWorldText.component("screen.sharedworld.motd_hint", SharedWorldApiClient.currentPlayerName()));
        this.motdBox.setMaxLength(256);
        this.motdBox.setHint(SharedWorldText.component("screen.sharedworld.motd_hint", SharedWorldApiClient.currentPlayerName()));

        this.snapshotList = new SnapshotBrowserList(this.minecraft, 120, 100, 0, 36, this);
        this.memberList = new MemberBrowserList(this.minecraft, 120, 100, 0, 36, this);
        this.tabNavigationBar = TabNavigationBar.builder(this.tabManager, this.width)
                .addTabs(this.detailsTab, this.backupsTab, this.membersTab, this.storageTab)
                .build();
        this.addRenderableWidget(this.tabNavigationBar);

        if (this.details == null) {
            this.reloadData();
        } else {
            this.populateDetailFields();
            this.refreshPreview();
            this.refreshStatus();
        }

        this.updateButtons();
        this.repositionElements();
        this.tabNavigationBar.selectTab(0, false);
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
        this.layoutFooterButtons();
        this.layoutBrowserLists();
    }

    @Override
    protected void setInitialFocus() {
        Tab currentTab = this.tabManager.getCurrentTab();
        if (currentTab == this.detailsTab) {
            this.setInitialFocus(this.nameBox);
        } else if (currentTab == this.backupsTab) {
            this.setInitialFocus(this.snapshotList);
        } else if (currentTab == this.membersTab) {
            this.setInitialFocus(this.memberList);
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
            if (!this.isOwner()) {
                return true;
            }
            if (this.selectedIcon != null || this.hasCurrentCustomIcon()) {
                this.clearIconSelection();
            } else {
                this.chooseIcon();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        this.parent.clearTransientFocus();
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
        this.syncTabState();
        this.iconHovered = this.isIconHovered(mouseX, mouseY);
        this.updateButtons();
        this.renderMenuBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (this.contentArea != null) {
            this.renderActiveTabDecorations(guiGraphics);
        }
    }

    void onSnapshotSelected(WorldSnapshotSummaryDto snapshot) {
        this.selectedSnapshot = snapshot;
        this.resetConfirms();
        this.refreshStatus();
    }

    void onMemberSelected(WorldMembershipDto membership) {
        this.selectedMember = membership;
        this.resetConfirms();
        this.refreshStatus();
    }

    private void renderActiveTabDecorations(GuiGraphics guiGraphics) {
        if (this.tabManager.getCurrentTab() == this.detailsTab) {
            this.renderDetailsDecorations(guiGraphics);
        } else if (this.tabManager.getCurrentTab() == this.backupsTab) {
            this.renderBackupsDecorations(guiGraphics);
        } else if (this.tabManager.getCurrentTab() == this.membersTab) {
            this.renderMembersDecorations(guiGraphics);
        } else if (this.tabManager.getCurrentTab() == this.storageTab) {
            this.renderStorageDecorations(guiGraphics);
        }
    }

    private void renderDetailsDecorations(GuiGraphics guiGraphics) {
        int left = this.contentArea.left() + 38;
        int top = this.contentArea.top();
        int iconX = this.iconAreaX();
        int iconY = this.iconAreaY();

        guiGraphics.drawString(this.font, Component.translatable("screen.sharedworld.world_name"), left, top + 34, 0xFFA0A0A0);
        guiGraphics.drawString(this.font, Component.translatable("screen.sharedworld.motd"), left, top + 88, 0xFFA0A0A0);
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, this.previewTexture.textureLocation(), iconX, iconY, 0.0F, 0.0F, 48, 48, 48, 48);

        if (this.iconHovered && this.isOwner()) {
            guiGraphics.fill(iconX, iconY, iconX + 48, iconY + 48, 0x80000000);
            Identifier sprite = this.selectedIcon != null || this.hasCurrentCustomIcon() ? DELETE_ICON_HIGHLIGHTED_SPRITE : EDIT_ICON_HIGHLIGHTED_SPRITE;
            guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, iconX + 12, iconY + 12, 24, 24);
        }

        this.renderServerCardPreview(guiGraphics);
        if (this.shouldShowDetailsValidation()) {
            guiGraphics.drawString(
                    this.font,
                    Component.translatable("screen.sharedworld.validation_world_name_short"),
                    left,
                    this.nameBox.getBottom() + 6,
                    0xFFFF5555
            );
        }
    }

    private void renderBackupsDecorations(GuiGraphics guiGraphics) {
        int detailX = this.contentArea.left() + 202;
        int detailY = this.contentArea.top() + 18;
        int detailWidth = this.contentArea.width() - 226;

        this.drawPanel(guiGraphics, detailX, detailY, detailWidth, this.contentArea.height() - 36, 0x7F000000, 0xFF6C6C6C);

        if (this.selectedSnapshot == null) {
            this.drawWrappedText(guiGraphics, Component.translatable("screen.sharedworld.backups_empty"), detailX + 12, detailY + 18, detailWidth - 24, 0xFFB8C5D6);
            return;
        }

        this.drawKeyValue(guiGraphics, detailX + 12, detailY + 18, Component.translatable("screen.sharedworld.backups_created"), formatTimestamp(this.selectedSnapshot.createdAt()), 84);
        this.drawKeyValue(guiGraphics, detailX + 12, detailY + 34, Component.translatable("screen.sharedworld.backups_files"), String.valueOf(this.selectedSnapshot.fileCount()), 84);
        this.drawKeyValue(guiGraphics, detailX + 12, detailY + 50, Component.translatable("screen.sharedworld.backups_size"), formatBytes(this.selectedSnapshot.totalCompressedSize()), 84);
        this.drawKeyValue(guiGraphics, detailX + 12, detailY + 66, Component.translatable("screen.sharedworld.backups_state"), SharedWorldText.string(this.selectedSnapshot.isLatest()
                ? "screen.sharedworld.backups_state_current"
                : "screen.sharedworld.backups_state_earlier"), 84);
        if (!this.isOwner() && !this.selectedSnapshot.isLatest()) {
            this.drawWrappedText(guiGraphics, Component.translatable("screen.sharedworld.backups_owner_only"), detailX + 12, detailY + 96, detailWidth - 24, 0xFFFFD37A);
        }

    }

    private void renderMembersDecorations(GuiGraphics guiGraphics) {
        int detailX = this.contentArea.left() + 188;
        int detailY = this.contentArea.top() + 18;
        int detailWidth = this.contentArea.width() - 212;

        this.drawPanel(guiGraphics, detailX, detailY, detailWidth, this.contentArea.height() - 36, 0x7F000000, 0xFF6C6C6C);

        if (this.selectedMember == null) {
            this.drawWrappedText(guiGraphics, Component.translatable("screen.sharedworld.members_empty"), detailX + 12, detailY + 18, detailWidth - 24, 0xFFB8C5D6);
            return;
        }

        this.drawKeyValue(guiGraphics, detailX + 12, detailY + 18, Component.translatable("screen.sharedworld.members_player"), this.selectedMember.playerName(), 84);
        this.drawKeyValue(guiGraphics, detailX + 12, detailY + 34, Component.translatable("screen.sharedworld.members_role"), formatRole(this.selectedMember.role()), 84);
        this.drawKeyValue(guiGraphics, detailX + 12, detailY + 50, Component.translatable("screen.sharedworld.members_joined"), formatTimestamp(this.selectedMember.joinedAt()), 84);
        if (!this.isOwner() && !this.isOwnerMembership(this.selectedMember)) {
            this.drawWrappedText(guiGraphics, Component.translatable("screen.sharedworld.members_owner_only"), detailX + 12, detailY + 80, detailWidth - 24, 0xFFFFD37A);
        }

    }

    private void renderStorageDecorations(GuiGraphics guiGraphics) {
        int left = this.contentArea.left() + 36;
        int width = this.contentArea.width() - 72;
        int top = this.contentArea.top() + 18;
        this.drawPanel(guiGraphics, left, top, width, this.contentArea.height() - 36, 0x7F000000, 0xFF6C6C6C);
        this.drawKeyValue(guiGraphics, left + 14, top + 18, Component.translatable("screen.sharedworld.storage_provider"), formatStorageProvider(this.details));
        this.drawKeyValue(guiGraphics, left + 14, top + 40, Component.translatable("screen.sharedworld.storage_account"), formatStorageAccount(this.details, this.storageUsage));
        this.drawKeyValue(guiGraphics, left + 14, top + 62, Component.translatable("screen.sharedworld.storage_used_by_world"), formatUsedByWorld(this.storageUsage));
        this.drawKeyValue(guiGraphics, left + 14, top + 84, Component.translatable("screen.sharedworld.storage_quota"), formatQuota(this.storageUsage));
    }

    private void updateButtons() {
        Tab currentTab = this.tabManager.getCurrentTab();
        boolean tabsUnlocked = !this.loading;

        if (this.tabNavigationBar != null) {
            this.tabNavigationBar.setTabActiveState(0, true);
            this.tabNavigationBar.setTabActiveState(1, tabsUnlocked);
            this.tabNavigationBar.setTabActiveState(2, tabsUnlocked);
            this.tabNavigationBar.setTabActiveState(3, tabsUnlocked);
        }

        this.backButton.setMessage(Component.translatable("gui.back"));
        this.backButton.active = !this.actionInFlight;

        if (currentTab == this.detailsTab) {
            this.primaryButton.visible = true;
            this.secondaryButton.visible = false;
            this.secondaryButton.active = false;
            this.primaryButton.setMessage(Component.translatable(this.savingDetails
                    ? "screen.sharedworld.saving"
                    : "screen.sharedworld.save_changes"));
            this.primaryButton.active = !this.loading && !this.actionInFlight && this.isOwner() && this.isDetailsValid() && this.isDetailsDirty() && !this.savingDetails;
        } else if (currentTab == this.backupsTab) {
            this.primaryButton.visible = true;
            this.secondaryButton.visible = true;
            this.secondaryButton.setMessage(Component.translatable(this.confirmDelete
                    ? "screen.sharedworld.confirm_delete"
                    : "screen.sharedworld.delete_backup"));
            this.secondaryButton.active = !this.loading && !this.actionInFlight && this.isOwner() && this.selectedSnapshot != null && !this.selectedSnapshot.isLatest();
            this.primaryButton.setMessage(Component.translatable(this.confirmRestore
                    ? "screen.sharedworld.confirm_restore"
                    : "screen.sharedworld.restore_backup"));
            this.primaryButton.active = !this.loading && !this.actionInFlight && this.isOwner() && this.selectedSnapshot != null && !this.selectedSnapshot.isLatest();
        } else if (currentTab == this.membersTab) {
            this.primaryButton.visible = true;
            this.secondaryButton.visible = false;
            this.secondaryButton.active = false;
            this.primaryButton.setMessage(Component.translatable(this.confirmKick
                    ? "screen.sharedworld.confirm_remove"
                    : "screen.sharedworld.remove_member"));
            this.primaryButton.active = !this.loading && !this.actionInFlight && this.isOwner() && this.selectedMember != null && !this.isOwnerMembership(this.selectedMember);
        } else {
            this.primaryButton.visible = false;
            this.primaryButton.active = false;
            this.secondaryButton.visible = false;
            this.secondaryButton.active = false;
        }

        this.nameBox.setEditable(this.isOwner());
        this.motdBox.setEditable(this.isOwner());
    }

    private void syncTabState() {
        Tab currentTab = this.tabManager.getCurrentTab();
        if (currentTab == this.lastTab) {
            return;
        }
        this.lastTab = currentTab;
        this.repositionElements();
        this.resetConfirms();
        this.refreshStatus();
    }

    private void layoutFooterButtons() {
        int availableWidth = Math.min(420, this.width - 48);
        int buttonWidth = Math.max(100, (availableWidth - 16) / 3);
        this.backButton.setWidth(buttonWidth);
        this.secondaryButton.setWidth(buttonWidth);
        this.primaryButton.setWidth(buttonWidth);
        this.layout.arrangeElements();
    }

    private void layoutBrowserLists() {
        if (this.contentArea == null) {
            return;
        }
        this.snapshotList.setPosition(this.contentArea.left() + CONTENT_MARGIN, this.contentArea.top() + 18);
        this.snapshotList.setWidth(178);
        this.snapshotList.setHeight(this.contentArea.height() - 36);
        this.memberList.setPosition(this.contentArea.left() + CONTENT_MARGIN, this.contentArea.top() + 18);
        this.memberList.setWidth(164);
        this.memberList.setHeight(this.contentArea.height() - 36);
    }

    private void onBack() {
        if (!this.actionInFlight) {
            this.onClose();
        }
    }

    private void onPrimaryAction() {
        Tab currentTab = this.tabManager.getCurrentTab();
        if (currentTab == this.detailsTab) {
            this.saveDetails();
            return;
        }
        if (currentTab == this.backupsTab) {
            if (this.confirmRestore) {
                this.restoreSnapshot();
            } else if (this.selectedSnapshot != null && !this.selectedSnapshot.isLatest() && this.isOwner()) {
                this.confirmRestore = true;
                this.confirmDelete = false;
                this.refreshStatus();
            }
            return;
        }
        if (currentTab == this.membersTab) {
            if (this.confirmKick) {
                this.kickSelectedMember();
            } else if (this.selectedMember != null && !this.isOwnerMembership(this.selectedMember) && this.isOwner()) {
                this.confirmKick = true;
                this.refreshStatus();
            }
            return;
        }
    }

    private void onSecondaryAction() {
        Tab currentTab = this.tabManager.getCurrentTab();
        if (currentTab == this.backupsTab) {
            if (this.confirmDelete) {
                this.deleteSnapshot();
            } else if (this.selectedSnapshot != null && !this.selectedSnapshot.isLatest() && this.isOwner()) {
                this.confirmDelete = true;
                this.confirmRestore = false;
                this.refreshStatus();
            }
            return;
        }
    }

    private void reloadData() {
        this.loading = true;
        this.actionInFlight = true;
        this.setStatusInfoKey("screen.sharedworld.edit_status_loading");
        this.dataController.reload(this.world.id(), loaded -> {
            this.loading = false;
            this.actionInFlight = false;
            this.applyLoadedState(loaded);
            this.refreshStatus();
            this.updateButtons();
        }, error -> {
            this.loading = false;
            this.actionInFlight = false;
            this.setStatusError(AbstractSharedWorldMetadataScreen.friendlyMessage(error));
            this.updateButtons();
        });
    }

    private void applyLoadedState(EditSharedWorldDataController.LoadedState loaded) {
        this.details = loaded.details();
        this.storageUsage = loaded.details().storageUsage();
        this.snapshots = EditSharedWorldDataController.sortedSnapshots(loaded.snapshots());
        this.memberships = EditSharedWorldDataController.normalizedMemberships(loaded.details());
        this.selectedSnapshot = this.chooseSelectedSnapshot(this.selectedSnapshot == null ? null : this.selectedSnapshot.snapshotId());
        this.selectedMember = this.chooseSelectedMember(this.selectedMember == null ? null : this.selectedMember.playerUuid());
        this.populateDetailFields();
        this.refreshPreview();
        this.snapshotList.setSnapshots(this.snapshots, this.selectedSnapshot == null ? null : this.selectedSnapshot.snapshotId());
        this.memberList.setMembers(this.memberships, this.selectedMember == null ? null : this.selectedMember.playerUuid());
    }

    private void populateDetailFields() {
        this.nameBox.setValue(this.details == null || this.details.name() == null ? "" : this.details.name());
        this.motdBox.setValue(this.details == null ? AbstractSharedWorldMetadataScreen.defaultMotd() : AbstractSharedWorldMetadataScreen.encodeMotdInput(this.details.motd()));
        this.selectedIcon = null;
        this.clearCustomIcon = false;
    }

    private void saveDetails() {
        if (!this.isOwner() || !this.isDetailsValid() || !this.isDetailsDirty()) {
            return;
        }
        this.savingDetails = true;
        this.actionInFlight = true;
        this.setStatusInfoKey("screen.sharedworld.edit_status_saving");
        this.dataController.saveDetails(
                new EditSharedWorldDataController.SaveDetailsRequest(
                        this.world.id(),
                        this.nameBox.getValue().trim(),
                        AbstractSharedWorldMetadataScreen.effectiveMotd(this.motdBox.getValue()),
                        this.selectedIcon,
                        this.clearCustomIcon
                ),
                updated -> {
                    this.savingDetails = false;
                    this.actionInFlight = false;
                    this.setStatusSuccessKey("screen.sharedworld.edit_status_saved");
                    this.reloadData();
                    this.parent.onChildOperationFinished(SharedWorldText.string("screen.sharedworld.operation_updated", SharedWorldText.displayWorldName(updated.name())));
                },
                error -> {
                    this.savingDetails = false;
                    this.actionInFlight = false;
                    this.setStatusError(AbstractSharedWorldMetadataScreen.friendlyMessage(error));
                    this.updateButtons();
                }
        );
    }

    private void restoreSnapshot() {
        WorldSnapshotSummaryDto snapshot = this.selectedSnapshot;
        if (snapshot == null || snapshot.isLatest() || !this.isOwner()) {
            return;
        }
        this.actionInFlight = true;
        this.setStatusWarningKey("screen.sharedworld.edit_status_restoring_backup");
        this.dataController.restoreSnapshot(this.world.id(), snapshot.snapshotId(), () -> {
            this.actionInFlight = false;
            this.confirmRestore = false;
            this.setStatusSuccessKey("screen.sharedworld.edit_status_backup_restored");
            this.selectedSnapshot = null;
            this.reloadData();
        }, error -> {
            this.actionInFlight = false;
            this.confirmRestore = false;
            this.setStatusError(AbstractSharedWorldMetadataScreen.friendlyMessage(error));
            this.updateButtons();
        });
    }

    private void deleteSnapshot() {
        WorldSnapshotSummaryDto snapshot = this.selectedSnapshot;
        if (snapshot == null || snapshot.isLatest() || !this.isOwner()) {
            return;
        }
        this.actionInFlight = true;
        this.setStatusWarningKey("screen.sharedworld.edit_status_deleting_backup");
        this.dataController.deleteSnapshot(this.world.id(), snapshot.snapshotId(), () -> {
            this.actionInFlight = false;
            this.confirmDelete = false;
            this.setStatusSuccessKey("screen.sharedworld.edit_status_backup_deleted");
            this.reloadData();
        }, error -> {
            this.actionInFlight = false;
            this.confirmDelete = false;
            this.setStatusError(AbstractSharedWorldMetadataScreen.friendlyMessage(error));
            this.updateButtons();
        });
    }

    private void kickSelectedMember() {
        WorldMembershipDto member = this.selectedMember;
        if (member == null || this.isOwnerMembership(member) || !this.isOwner()) {
            return;
        }
        this.actionInFlight = true;
        this.setStatusWarningKey("screen.sharedworld.edit_status_removing_member");
        this.dataController.kickMember(this.world.id(), member.playerUuid(), () -> {
            this.actionInFlight = false;
            this.confirmKick = false;
            this.setStatusSuccessKey("screen.sharedworld.edit_status_member_removed");
            this.reloadData();
        }, error -> {
            this.actionInFlight = false;
            this.confirmKick = false;
            this.setStatusError(AbstractSharedWorldMetadataScreen.friendlyMessage(error));
            this.updateButtons();
        });
    }

    private void chooseIcon() {
        try {
            this.selectedIcon = SharedWorldClient.customIconStore().chooseIcon();
            if (this.selectedIcon != null) {
                this.clearCustomIcon = false;
                this.refreshPreview();
                this.refreshStatus();
            }
        } catch (Exception exception) {
            this.setStatusError(AbstractSharedWorldMetadataScreen.friendlyMessage(exception));
        }
    }

    private void clearIconSelection() {
        this.selectedIcon = null;
        this.clearCustomIcon = true;
        this.refreshPreview();
        this.refreshStatus();
    }

    private void refreshPreview() {
        SharedWorldMetadataIcons.uploadPreview(
                SharedWorldClient.customIconStore(),
                this.previewTexture,
                this.selectedIcon,
                this::fallbackPreviewPath
        );
    }

    private Path fallbackPreviewPath() {
        if (!this.clearCustomIcon) {
            Path customIcon = SharedWorldClient.customIconStore().resolveCachedIcon(this.world);
            if (customIcon != null) {
                return customIcon;
            }
            if (this.details != null && !Objects.equals(this.details.customIconStorageKey(), this.world.customIconStorageKey())) {
                WorldSummaryDto summary = new WorldSummaryDto(
                        this.details.id(),
                        this.details.slug(),
                        this.details.name(),
                        this.details.ownerUuid(),
                        this.details.motd(),
                        this.details.customIconStorageKey(),
                        this.details.customIconDownload(),
                        this.details.memberCount(),
                        this.details.status(),
                        this.details.lastSnapshotId(),
                        this.details.lastSnapshotAt(),
                        this.details.activeHostUuid(),
                        this.details.activeHostPlayerName(),
                        this.details.activeJoinTarget(),
                        this.details.onlinePlayerCount(),
                        this.details.onlinePlayerNames(),
                        this.details.storageProvider(),
                        this.details.storageLinked(),
                        this.details.storageAccountEmail()
                );
                customIcon = SharedWorldClient.customIconStore().resolveCachedIcon(summary);
                if (customIcon != null) {
                    return customIcon;
                }
            }
        }
        return this.worldStore.workingCopy(this.world.id()).resolve("icon.png");
    }

    private void refreshStatus() {
        if (this.loading) {
            this.setStatusInfoKey("screen.sharedworld.edit_status_loading");
            return;
        }
        if (this.confirmRestore) {
            this.setStatusWarningKey("screen.sharedworld.edit_status_restore_confirm");
            return;
        }
        if (this.confirmDelete) {
            this.setStatusWarningKey("screen.sharedworld.edit_status_delete_confirm");
            return;
        }
        if (this.confirmKick) {
            this.setStatusWarningKey("screen.sharedworld.edit_status_remove_confirm");
            return;
        }
        Tab currentTab = this.tabManager.getCurrentTab();
        if (currentTab == this.detailsTab) {
            if (this.isDetailsDirty()) {
                this.setStatusInfoKey("screen.sharedworld.edit_status_ready_to_save");
            } else {
                this.statusMessage = "";
            }
        } else if (currentTab == this.backupsTab) {
            this.statusMessage = "";
        } else if (currentTab == this.membersTab) {
            this.statusMessage = "";
        } else {
            this.statusMessage = "";
        }
    }

    private boolean isDetailsDirty() {
        if (this.details == null) {
            return false;
        }
        String currentName = this.nameBox.getValue().trim();
        String currentMotd = AbstractSharedWorldMetadataScreen.effectiveMotd(this.motdBox.getValue());
        return !Objects.equals(currentName, blankOr(this.details.name(), ""))
                || !Objects.equals(currentMotd, blankOr(this.details.motd(), ""))
                || this.selectedIcon != null
                || this.clearCustomIcon;
    }

    private boolean isDetailsValid() {
        return this.nameBox.getValue().trim().length() >= 3;
    }

    private boolean isOwner() {
        if (this.details != null && this.details.membership() != null) {
            return "owner".equalsIgnoreCase(this.details.membership().role());
        }
        return this.world.ownerUuid() != null && this.world.ownerUuid().equalsIgnoreCase(SharedWorldApiClient.currentBackendPlayerUuidWithHyphens());
    }

    private boolean isOwnerMembership(WorldMembershipDto membership) {
        return membership != null && "owner".equalsIgnoreCase(membership.role());
    }

    private boolean hasCurrentCustomIcon() {
        return !this.clearCustomIcon && this.details != null && this.details.customIconStorageKey() != null && !this.details.customIconStorageKey().isBlank();
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
        int left = this.contentArea.left() + 38;
        int fieldsRight = left + Math.min(190, this.contentArea.width() - 140);
        int previewRight = this.previewCardX() + SharedWorldServerList.ROW_WIDTH;
        return fieldsRight + ((previewRight - fieldsRight) - 48) / 2;
    }

    private int iconAreaY() {
        int top = this.contentArea.top();
        int previewTop = this.previewCardY();
        return top + ((previewTop - top) - 48) / 2;
    }

    private int previewCardX() {
        return this.contentArea.left() + (this.contentArea.width() - SharedWorldServerList.ROW_WIDTH) / 2;
    }

    private int previewCardY() {
        return this.contentArea.top() + 144;
    }

    private void renderServerCardPreview(GuiGraphics guiGraphics) {
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

    private String previewWorldName() {
        String name = this.nameBox.getValue().trim();
        return name.isBlank() ? SharedWorldText.displayWorldName(this.world.name()) : name;
    }

    private String previewMotd() {
        return AbstractSharedWorldMetadataScreen.effectiveMotd(this.motdBox.getValue());
    }

    private WorldSnapshotSummaryDto chooseSelectedSnapshot(String preferredId) {
        if (this.snapshots.isEmpty()) {
            return null;
        }
        if (preferredId != null) {
            for (WorldSnapshotSummaryDto snapshot : this.snapshots) {
                if (preferredId.equals(snapshot.snapshotId())) {
                    return snapshot;
                }
            }
        }
        return this.snapshots.get(0);
    }

    private WorldMembershipDto chooseSelectedMember(String preferredPlayerUuid) {
        if (this.memberships.isEmpty()) {
            return null;
        }
        if (preferredPlayerUuid != null) {
            for (WorldMembershipDto membership : this.memberships) {
                if (preferredPlayerUuid.equals(membership.playerUuid())) {
                    return membership;
                }
            }
        }
        return this.memberships.get(0);
    }

    private void resetConfirms() {
        this.confirmRestore = false;
        this.confirmDelete = false;
        this.confirmKick = false;
    }

    private void setStatusInfo(String message) {
        this.statusMessage = message;
        this.statusColor = 0xFFB8C5D6;
    }

    private void setStatusInfoKey(String key, Object... args) {
        this.setStatusInfo(SharedWorldText.string(key, args));
    }

    private void setStatusSuccess(String message) {
        this.statusMessage = message;
        this.statusColor = 0xFF9FE3A5;
    }

    private void setStatusSuccessKey(String key, Object... args) {
        this.setStatusSuccess(SharedWorldText.string(key, args));
    }

    private void setStatusWarning(String message) {
        this.statusMessage = message;
        this.statusColor = 0xFFFFD37A;
    }

    private void setStatusWarningKey(String key, Object... args) {
        this.setStatusWarning(SharedWorldText.string(key, args));
    }

    private void setStatusError(String message) {
        this.statusMessage = message;
        this.statusColor = 0xFFFF8D8D;
    }

    private void drawPanel(GuiGraphics guiGraphics, int x, int y, int width, int height, int fill, int border) {
        guiGraphics.fill(x, y, x + width, y + height, fill);
        guiGraphics.hLine(x, x + width - 1, y, border);
        guiGraphics.hLine(x, x + width - 1, y + height - 1, border);
        guiGraphics.vLine(x, y, y + height - 1, border);
        guiGraphics.vLine(x + width - 1, y, y + height - 1, border);
    }

    private void drawKeyValue(GuiGraphics guiGraphics, int x, int y, Component key, String value) {
        this.drawKeyValue(guiGraphics, x, y, key, value, 108);
    }

    private void drawKeyValue(GuiGraphics guiGraphics, int x, int y, Component key, String value, int valueOffset) {
        guiGraphics.drawString(this.font, key, x, y, 0xFF8EA3BC);
        guiGraphics.drawString(this.font, Component.literal(blankOr(value, SharedWorldText.string("screen.sharedworld.not_set"))), x + valueOffset, y, 0xFFFFFFFF);
    }

    private void drawWrappedText(GuiGraphics guiGraphics, Component text, int x, int y, int width, int color) {
        List<FormattedCharSequence> lines = this.font.split(text, width);
        for (int index = 0; index < lines.size(); index++) {
            guiGraphics.drawString(this.font, lines.get(index), x, y + index * 9, color);
        }
    }

    private boolean shouldShowDetailsValidation() {
        if (this.loading || this.details == null) {
            return false;
        }
        String currentName = this.nameBox.getValue().trim();
        return !currentName.isEmpty() && currentName.length() < 3;
    }

    private static String formatRole(String role) {
        return SharedWorldText.string("owner".equalsIgnoreCase(role)
                ? "screen.sharedworld.role_owner"
                : "screen.sharedworld.role_member");
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String formatTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return DATE_FORMAT.format(Instant.parse(value).atZone(ZoneId.systemDefault()));
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String formatBytes(long value) {
        if (value >= 1024L * 1024L * 1024L) {
            return SharedWorldText.string("screen.sharedworld.size_gb", String.format(Locale.ROOT, "%.1f", value / (1024.0 * 1024.0 * 1024.0)));
        }
        if (value >= 1024L * 1024L) {
            return SharedWorldText.string("screen.sharedworld.size_mb", String.format(Locale.ROOT, "%.1f", value / (1024.0 * 1024.0)));
        }
        if (value >= 1024L) {
            return SharedWorldText.string("screen.sharedworld.size_kb", String.format(Locale.ROOT, "%.1f", value / 1024.0));
        }
        return SharedWorldText.string("screen.sharedworld.size_b", value);
    }

    private static String formatQuota(StorageUsageSummaryDto usage) {
        if (usage == null || usage.quotaTotalBytes() == null || usage.quotaTotalBytes() <= 0) {
            return SharedWorldText.string("screen.sharedworld.unknown");
        }
        return formatBytes(usage.quotaUsedBytes() == null ? 0L : usage.quotaUsedBytes()) + " / " + formatBytes(usage.quotaTotalBytes());
    }

    private static String formatUsedByWorld(StorageUsageSummaryDto usage) {
        if (usage == null) {
            return SharedWorldText.string("screen.sharedworld.unknown");
        }
        return formatBytes(usage.usedBytes());
    }

    private static String formatStorageProvider(WorldDetailsDto details) {
        if (details == null || details.storageProvider() == null || details.storageProvider().isBlank()) {
            return SharedWorldText.string("screen.sharedworld.unknown");
        }
        return "google-drive".equalsIgnoreCase(details.storageProvider())
                ? SharedWorldText.string("screen.sharedworld.storage_provider_google_drive")
                : details.storageProvider();
    }

    private static String formatStorageAccount(WorldDetailsDto details, StorageUsageSummaryDto usage) {
        String account = details != null && details.storageLinked()
                ? blankOr(details.storageAccountEmail(), SharedWorldText.string("screen.sharedworld.storage_linked"))
                : null;
        if ((account == null || account.isBlank()) && usage != null && usage.accountEmail() != null && !usage.accountEmail().isBlank()) {
            account = usage.accountEmail();
        }
        return blankOr(account, SharedWorldText.string("screen.sharedworld.storage_not_linked"));
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
            consumer.accept(EditSharedWorldScreen.this.nameBox);
            consumer.accept(EditSharedWorldScreen.this.motdBox);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            int left = area.left() + 38;
            EditSharedWorldScreen.this.nameBox.setPosition(left, area.top() + 44);
            EditSharedWorldScreen.this.nameBox.setWidth(Math.min(190, area.width() - 140));
            EditSharedWorldScreen.this.motdBox.setPosition(left, area.top() + 98);
            EditSharedWorldScreen.this.motdBox.setWidth(Math.min(190, area.width() - 140));
        }
    }

    private final class BackupsTab implements Tab {
        @Override
        public Component getTabTitle() {
            return Component.translatable("screen.sharedworld.tab_backups");
        }

        @Override
        public Component getTabExtraNarration() {
            return this.getTabTitle();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(EditSharedWorldScreen.this.snapshotList);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            EditSharedWorldScreen.this.layoutBrowserLists();
        }
    }

    private final class MembersTab implements Tab {
        @Override
        public Component getTabTitle() {
            return Component.translatable("screen.sharedworld.tab_members");
        }

        @Override
        public Component getTabExtraNarration() {
            return this.getTabTitle();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(EditSharedWorldScreen.this.memberList);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            EditSharedWorldScreen.this.layoutBrowserLists();
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
        }

        @Override
        public void doLayout(ScreenRectangle area) {
        }
    }
}
