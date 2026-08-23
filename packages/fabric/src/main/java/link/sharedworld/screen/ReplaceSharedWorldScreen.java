package link.sharedworld.screen;

import java.util.ArrayList;
import java.util.List;

import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.WorldDetailsDto;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

/**
 * Picks the local save (from the saves list or an arbitrary folder) that will
 * REPLACE the shared world's content, behind a two-click confirm; replacing
 * overwrites the world for every member.
 */
public final class ReplaceSharedWorldScreen extends link.sharedworld.versioned.VersionedScreen implements LocalSaveSelectionList.Host {
    private static final int CONTENT_MARGIN = 12;
    /** Screen title sits at y 14 (9px tall); the list follows right under it. */
    private static final int LIST_TOP = 32;

    private final EditSharedWorldScreen parent;
    private final WorldDetailsDto world;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 36);
    private final List<LocalSaveCatalog.LocalSaveOption> localSaves = new ArrayList<>(LocalSaveCatalog.discover());

    private final SharedWorldStatusBanner banner = new SharedWorldStatusBanner();

    private LocalSaveSelectionList saveList;
    private Button selectFolderButton;
    private Button backButton;
    private Button replaceButton;
    private LocalSaveCatalog.LocalSaveOption selectedSave;
    private boolean confirmReplace;

    public ReplaceSharedWorldScreen(EditSharedWorldScreen parent, WorldDetailsDto world) {
        super(Component.translatable("screen.sharedworld.replace_title"));
        this.parent = parent;
        this.world = world;
        if (!this.localSaves.isEmpty()) {
            this.selectedSave = this.localSaves.get(0);
        }
    }

    @Override
    protected void init() {
        this.clearWidgets();
        LinearLayout footer = this.layout.addToFooter(link.sharedworld.versioned.LayoutCompat.horizontalLayout(8));
        this.backButton = footer.addChild(Button.builder(Component.translatable("gui.back"), ignored -> this.onClose())
                .width(150)
                .build());
        this.replaceButton = footer.addChild(Button.builder(Component.empty(), ignored -> this.onReplacePressed())
                .width(150)
                .build());
        this.layout.visitWidgets(this::addRenderableWidget);

        this.selectFolderButton = Button.builder(
                Component.translatable("screen.sharedworld.select_folder"),
                ignored -> this.selectWorldFolder()
        ).width(150).build();
        this.addRenderableWidget(this.selectFolderButton);
        // No tabs on this screen: the list is a plain widget (registerTabList
        // is only for TabManager-owned lists and would never render here).
        this.saveList = this.addRenderableWidget(new LocalSaveSelectionList(this.minecraft, 0, 0, 0, 36, this));
        this.saveList.setSaves(this.localSaves, this.selectedSave == null ? null : this.selectedSave.id());

        this.repositionElements();
        this.updateButtons();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        // Same shape as the create wizard's pick step: the list starts right
        // under the title and runs to the folder button, which sits right
        // above the footer.
        int folderButtonHeight = 20;
        int folderButtonY = this.height - this.layout.getFooterHeight() - folderButtonHeight - 6;
        this.saveList.sharedworldSetBounds(
                CONTENT_MARGIN,
                LIST_TOP,
                this.width - CONTENT_MARGIN * 2,
                Math.max(36, folderButtonY - 8 - LIST_TOP)
        );
        this.selectFolderButton.setPosition((this.width - this.selectFolderButton.getWidth()) / 2,
                folderButtonY);
    }

    @Override
    public void onClose() {
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, this.parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.updateButtons();
        this.sharedworldRenderMenuBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
        // Folder-pick errors surface in the shared banner, drawn just above the
        // button like the create wizard's pick step.
        this.banner.renderBottomCentered(guiGraphics, this.font, this.width / 2,
                this.selectFolderButton.getY() - 4, Math.min(this.width - 40, 420));
    }

    @Override
    public void onSaveSelected(LocalSaveCatalog.LocalSaveOption save) {
        this.selectedSave = save;
        this.confirmReplace = false;
        this.banner.clearSticky();
        this.saveList.setSaves(this.localSaves, save.id());
        this.updateButtons();
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

    private void onReplacePressed() {
        if (this.selectedSave == null) {
            return;
        }
        if (!this.confirmReplace) {
            this.confirmReplace = true;
            this.updateButtons();
            return;
        }
        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new ReplaceSharedWorldProgressScreen(this.parent, this.world, this.selectedSave.directory()));
    }

    private void updateButtons() {
        if (this.replaceButton == null) {
            return;
        }
        this.replaceButton.setMessage(Component.translatable(this.confirmReplace
                ? "screen.sharedworld.confirm_replace"
                : "screen.sharedworld.replace_action"));
        this.replaceButton.active = this.selectedSave != null;
    }
}
