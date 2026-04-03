package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldCustomIconStore.SelectedIcon;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

abstract class AbstractSharedWorldMetadataScreen extends Screen {
    private static final Identifier SCROLLER_SPRITE = Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND_SPRITE = Identifier.withDefaultNamespace("widget/scroller_background");
    private static final int FORM_WIDTH = 240;
    private static final int NAME_FIELD_WIDTH = 160;
    private static final int MOTD_FIELD_WIDTH = FORM_WIDTH;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int SCROLLBAR_GAP = 32;
    private static final int LABEL_TO_FIELD_GAP = 12;
    private static final int SECTION_GAP = 12;
    private static final int FIELD_HEIGHT = 20;

    protected final SharedWorldScreen parent;
    protected EditBox nameBox;
    protected EditBox motdBox;
    protected Button chooseIconButton;
    protected Button clearIconButton;
    protected Button doneButton;
    protected Button cancelButton;
    protected FaviconTexture previewIconTexture;
    protected SelectedIcon selectedIcon;
    protected String statusMessage = "";
    protected String nameErrorMessage = "";

    private double scrollOffset;
    private boolean draggingScrollbar;
    private int viewportTop;
    private int viewportBottom;
    private int contentHeight;
    private int nameLabelY;
    private int nameErrorY;
    private int motdLabelY;
    private int iconLabelY;
    private int iconPreviewY;
    private int statusY;

    protected AbstractSharedWorldMetadataScreen(Component title, SharedWorldScreen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.formLeft();
        this.previewIconTexture = this.createPreviewTexture();

        this.nameBox = this.addBox(left, Component.translatable("screen.sharedworld.name_hint"));
        this.nameBox.setValue(this.initialNameValue());

        this.motdBox = new EditBox(this.font, left, 0, MOTD_FIELD_WIDTH, 20, SharedWorldText.component("screen.sharedworld.motd_hint", SharedWorldApiClient.currentPlayerName()));
        this.motdBox.setMaxLength(256);
        this.motdBox.setHint(SharedWorldText.component("screen.sharedworld.motd_hint", SharedWorldApiClient.currentPlayerName()));
        this.motdBox.setValue(this.initialMotdValue());
        this.addRenderableWidget(this.motdBox);

        this.chooseIconButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.choose_icon"), button -> this.chooseIcon())
                .bounds(left + 40, 0, NAME_FIELD_WIDTH - 40, 20)
                .build());
        this.clearIconButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.clear_icon"), button -> {
                    this.selectedIcon = null;
                    this.statusMessage = "";
                    this.onCustomIconCleared();
                    this.refreshPreview();
                })
                .bounds(left + 40, 0, NAME_FIELD_WIDTH - 40, 20)
                .build());

        this.cancelButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.cancel"), button -> this.minecraft.setScreen(this.parent))
                .bounds(left, 0, (FORM_WIDTH - 4) / 2, 20)
                .build());
        this.doneButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.done"), button -> this.submit())
                .bounds(left + (FORM_WIDTH + 4) / 2, 0, (FORM_WIDTH - 4) / 2, 20)
                .build());

        this.setInitialFocus(this.nameBox);
        this.refreshPreview();
        this.layoutForm();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.layoutForm();
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        this.renderForm(guiGraphics);
        this.renderScrollbar(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.maxScroll() <= 0 || !this.isInsideViewport(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        this.setScrollOffset(this.scrollOffset - verticalAmount * 16.0D);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.isOverScrollbar(event.x(), event.y())) {
            this.draggingScrollbar = true;
            this.scrollToMouse(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.draggingScrollbar) {
            this.scrollToMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    @Override
    public void removed() {
        super.removed();
        if (this.previewIconTexture != null) {
            this.previewIconTexture.clear();
        }
    }

    protected abstract FaviconTexture createPreviewTexture();

    protected abstract String initialNameValue();

    protected abstract String initialMotdValue();

    protected abstract Path fallbackPreviewPath();

    protected abstract String existingCustomIconStorageKey();

    protected abstract boolean shouldClearCustomIconOnSubmit();

    protected abstract void onCustomIconChosen();

    protected abstract void onCustomIconCleared();

    protected abstract String submittingMessage();

    protected abstract CompletableFuture<String> submitWorld(String name, String motd, String customIconBase64, boolean clearCustomIcon);

    protected int viewportTopInset() {
        return 44;
    }

    protected static String decodeMotdInput(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\\u00A7", "§").replace("\\u00a7", "§").replace("\\n", "\n").trim();
    }

    protected static String effectiveMotd(String input) {
        String decoded = decodeMotdInput(input);
        return decoded == null || decoded.isBlank() ? defaultMotd() : decoded;
    }

    protected static String encodeMotdInput(String motd) {
        if (motd == null || motd.isBlank()) {
            return defaultMotd();
        }
        return motd.replace("§", "\\u00a7").replace("\n", "\\n");
    }

    protected static String defaultMotd() {
        return SharedWorldText.defaultMotd();
    }

    protected static String friendlyMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return SharedWorldText.string("screen.sharedworld.error_generic");
        }
        return throwable.getMessage();
    }

    private EditBox addBox(int left, Component hint) {
        EditBox box = new EditBox(this.font, left, 0, NAME_FIELD_WIDTH, 20, hint);
        box.setMaxLength(128);
        if (hint != null) {
            box.setHint(hint);
        }
        this.addRenderableWidget(box);
        return box;
    }

    private void chooseIcon() {
        try {
            this.selectedIcon = SharedWorldClient.customIconStore().chooseIcon();
            this.statusMessage = "";
            this.onCustomIconChosen();
            this.refreshPreview();
        } catch (Exception exception) {
            this.statusMessage = friendlyMessage(exception);
            this.layoutForm();
        }
    }

    private void refreshPreview() {
        SharedWorldMetadataIcons.uploadPreview(
                SharedWorldClient.customIconStore(),
                this.previewIconTexture,
                this.selectedIcon,
                this::fallbackPreviewPath
        );
        this.clearIconButton.active = this.selectedIcon != null
                || (!this.shouldClearCustomIconOnSubmit() && this.existingCustomIconStorageKey() != null && !this.existingCustomIconStorageKey().isBlank());
        this.layoutForm();
    }

    private void submit() {
        String name = this.nameBox.getValue().trim();
        if (name.length() < 3) {
            this.nameErrorMessage = SharedWorldText.string("screen.sharedworld.validation_world_name_short");
            this.statusMessage = "";
            this.layoutForm();
            return;
        }

        this.nameErrorMessage = "";
        this.statusMessage = this.submittingMessage();
        this.layoutForm();
        CompletableFuture
                .supplyAsync(this::prepareCustomIcon, SharedWorldClient.ioExecutor())
                .thenCompose(customIconStorageKey -> this.submitWorld(
                        name,
                        effectiveMotd(this.motdBox.getValue()),
                        customIconStorageKey,
                        this.shouldClearCustomIconOnSubmit()
                ))
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        this.statusMessage = friendlyMessage(cause);
                        this.layoutForm();
                    } else {
                        this.parent.onChildOperationFinished(result);
                        this.parent.clearFocus();
                        this.minecraft.setScreen(this.parent);
                    }
                }));
    }

    private String prepareCustomIcon() {
        if (this.shouldClearCustomIconOnSubmit()) {
            return null;
        }
        if (this.selectedIcon == null) {
            return null;
        }
        try {
            return SharedWorldMetadataIcons.encodeSelectedIcon(SharedWorldClient.customIconStore(), this.selectedIcon);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private void renderForm(GuiGraphics guiGraphics) {
        int left = this.formLeft();
        guiGraphics.enableScissor(left, this.viewportTop, left + FORM_WIDTH, this.viewportBottom);
        guiGraphics.drawString(this.font, Component.translatable("screen.sharedworld.world_name"), left, this.nameLabelY, 0xFFA0A0A0);
        if (!this.nameErrorMessage.isBlank()) {
            this.drawWrappedLines(guiGraphics, this.wrapMessage(this.nameErrorMessage), left, this.nameErrorY, 0xFFFF5555);
        }
        guiGraphics.drawString(this.font, Component.translatable("screen.sharedworld.motd"), left, this.motdLabelY, 0xFFA0A0A0);
        guiGraphics.drawString(this.font, Component.translatable("screen.sharedworld.server_icon"), left, this.iconLabelY, 0xFFA0A0A0);
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, this.previewIconTexture.textureLocation(), left, this.iconPreviewY, 0.0F, 0.0F, 32, 32, 32, 32);
        if (!this.safeStatusMessage().isBlank()) {
            this.drawWrappedLines(guiGraphics, this.wrapMessage(this.safeStatusMessage()), left, this.statusY, 0xFFA0A0A0);
        }
        guiGraphics.disableScissor();
    }

    private void drawWrappedLines(GuiGraphics guiGraphics, List<FormattedCharSequence> lines, int x, int y, int color) {
        for (int index = 0; index < lines.size(); index++) {
            guiGraphics.drawString(this.font, lines.get(index), x, y + index * 9, color);
        }
    }

    private void renderScrollbar(GuiGraphics guiGraphics) {
        if (this.maxScroll() <= 0) {
            return;
        }
        int viewportHeight = this.viewportBottom - this.viewportTop;
        int thumbHeight = Math.max(16, (int) ((viewportHeight * (double) viewportHeight) / Math.max(this.contentHeight, viewportHeight)) - 16);
        int trackTravel = Math.max(1, viewportHeight - thumbHeight);
        int thumbY = this.viewportTop + (int) Math.round((this.scrollOffset / this.maxScroll()) * trackTravel);
        int x = this.scrollbarX();

        guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND_SPRITE, x, this.viewportTop, SCROLLBAR_WIDTH, viewportHeight);
        guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_SPRITE, x, thumbY, SCROLLBAR_WIDTH, thumbHeight);
    }

    private void layoutForm() {
        int left = this.formLeft();
        this.viewportTop = this.viewportTopInset();
        this.viewportBottom = this.height - 52;
        this.cancelButton.setPosition(left, this.height - 28);
        this.doneButton.setPosition(left + (FORM_WIDTH + 4) / 2, this.height - 28);

        int y = this.viewportTop + 6;
        int visibleTop = this.viewportTop;
        int visibleBottom = this.viewportBottom;

        this.nameLabelY = y - (int) this.scrollOffset;
        y += LABEL_TO_FIELD_GAP;
        this.nameBox.setPosition(left, y - (int) this.scrollOffset);
        y += FIELD_HEIGHT;

        List<FormattedCharSequence> nameErrorLines = this.wrapMessage(this.nameErrorMessage);
        this.nameErrorY = y - (int) this.scrollOffset;
        if (!nameErrorLines.isEmpty()) {
            y += 4;
            this.nameErrorY = y - (int) this.scrollOffset;
            y += nameErrorLines.size() * 9 + 4;
        }
        y += SECTION_GAP;

        this.motdLabelY = y - (int) this.scrollOffset;
        y += LABEL_TO_FIELD_GAP;
        this.motdBox.setPosition(left, y - (int) this.scrollOffset);
        y += FIELD_HEIGHT + SECTION_GAP;

        this.iconLabelY = y - (int) this.scrollOffset;
        y += LABEL_TO_FIELD_GAP;
        this.iconPreviewY = y - (int) this.scrollOffset;
        this.chooseIconButton.setPosition(left + 40, this.iconPreviewY);
        this.clearIconButton.setPosition(left + 40, this.iconPreviewY + 24);
        y += 48 + SECTION_GAP;

        List<FormattedCharSequence> statusLines = this.wrapMessage(this.safeStatusMessage());
        this.statusY = y - (int) this.scrollOffset;
        if (!statusLines.isEmpty()) {
            y += statusLines.size() * 9 + 6;
        }

        this.contentHeight = Math.max(0, y - (this.viewportTop + 6));
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, this.maxScroll()));

        this.updateWidgetVisibility(this.nameBox, visibleTop, visibleBottom);
        this.updateWidgetVisibility(this.motdBox, visibleTop, visibleBottom);
        this.updateWidgetVisibility(this.chooseIconButton, visibleTop, visibleBottom);
        this.updateWidgetVisibility(this.clearIconButton, visibleTop, visibleBottom);
    }

    private void updateWidgetVisibility(AbstractWidget widget, int visibleTop, int visibleBottom) {
        widget.visible = widget.getBottom() > visibleTop && widget.getY() < visibleBottom;
    }

    private List<FormattedCharSequence> wrapMessage(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        return this.font.split(Component.literal(message), FORM_WIDTH);
    }

    private String safeStatusMessage() {
        return this.statusMessage == null ? "" : this.statusMessage;
    }

    private void setScrollOffset(double newOffset) {
        this.scrollOffset = Math.max(0.0D, Math.min(this.maxScroll(), newOffset));
        this.layoutForm();
    }

    private void scrollToMouse(double mouseY) {
        int maxScroll = this.maxScroll();
        if (maxScroll <= 0) {
            this.setScrollOffset(0.0D);
            return;
        }

        int viewportHeight = this.viewportBottom - this.viewportTop;
        int thumbHeight = Math.max(32, (int) ((viewportHeight * (double) viewportHeight) / Math.max(this.contentHeight, viewportHeight)));
        int trackTravel = Math.max(1, viewportHeight - thumbHeight);
        double progress = (mouseY - this.viewportTop - thumbHeight / 2.0D) / trackTravel;
        this.setScrollOffset(progress * maxScroll);
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        return this.maxScroll() > 0
                && mouseX >= this.scrollbarX()
                && mouseX <= this.scrollbarX() + SCROLLBAR_WIDTH
                && mouseY >= this.viewportTop
                && mouseY <= this.viewportBottom;
    }

    private boolean isInsideViewport(double mouseX, double mouseY) {
        int left = this.formLeft();
        return mouseX >= left
                && mouseX <= left + FORM_WIDTH + SCROLLBAR_WIDTH + SCROLLBAR_GAP
                && mouseY >= this.viewportTop
                && mouseY <= this.viewportBottom;
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - (this.viewportBottom - this.viewportTop));
    }

    private int formLeft() {
        return this.width / 2 - FORM_WIDTH / 2;
    }

    private int scrollbarX() {
        return this.formLeft() + FORM_WIDTH + SCROLLBAR_GAP;
    }
}
