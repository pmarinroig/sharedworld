package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldClientLifecycleRouter;
import link.sharedworld.SharedWorldText;
import link.sharedworld.host.SharedWorldReleaseCoordinator;
import link.sharedworld.host.SharedWorldReleasePhase;
import link.sharedworld.progress.SharedWorldProgressRenderer;
import link.sharedworld.progress.SharedWorldProgressState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SharedWorldSavingScreen extends Screen {
    private static final float PREPARING_BASE = 0.04F;
    private static final float PREPARING_END = 0.22F;
    private static final float UPLOADING_START = 0.24F;
    private static final float UPLOADING_END = 0.88F;
    private static final float FINISHING_BASE = 0.88F;
    private static final float FINISHING_END = 0.98F;

    private final Screen parent;
    private final String worldName;
    private boolean errorScreenOpened;

    public SharedWorldSavingScreen(Screen parent, String worldName) {
        super(Component.translatable("screen.sharedworld.saving_title"));
        this.parent = parent;
        this.worldName = worldName;
    }

    @Override
    public void tick() {
        SharedWorldReleaseCoordinator coordinator = SharedWorldClient.releaseCoordinator();
        SharedWorldReleaseCoordinator.ReleaseView view = coordinator.view();
        if (view == null) {
            SharedWorldClient.openMainScreen(this.parent);
            return;
        }
        if (view.phase() == SharedWorldReleasePhase.ERROR_RECOVERABLE && !this.errorScreenOpened) {
            this.errorScreenOpened = true;
            this.minecraft.setScreen(SharedWorldClientLifecycleRouter.screenForLifecycleView(coordinator, this.parent));
            return;
        }
        if (view.phase() == SharedWorldReleasePhase.COMPLETE) {
            coordinator.acknowledgeTerminal();
            SharedWorldClient.openMainScreen(this.parent);
            return;
        }
        if (view.phase() == SharedWorldReleasePhase.TERMINATED_DELETED) {
            coordinator.acknowledgeTerminal();
            this.minecraft.setScreen(new SharedWorldErrorScreen(
                    this.parent,
                    Component.translatable("screen.sharedworld.deleted_title"),
                    Component.translatable("screen.sharedworld.deleted_detail")
            ));
            return;
        }
        if (view.phase() == SharedWorldReleasePhase.TERMINATED_REVOKED) {
            coordinator.acknowledgeTerminal();
            SharedWorldClient.openMembershipRevokedScreen(this.parent);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderPanorama(guiGraphics, partialTick);
        this.renderBlurredBackground(guiGraphics);
        renderMenuBackgroundTexture(guiGraphics, MENU_BACKGROUND, 0, 0, 0.0F, 0.0F, this.width, this.height);
        this.minecraft.gui.renderDeferredSubtitles();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        SavingProgressVisual visual = this.progressVisual();
        SharedWorldProgressRenderer.renderCenteredBar(
                guiGraphics,
                this.font,
                this.width,
                this.height,
                this.title,
                visual.label(),
                visual.progress(),
                visual.activityStart(),
                visual.activityEnd(),
                partialTick
        );
    }

    private SharedWorldProgressState progressState() {
        SharedWorldReleaseCoordinator.ReleaseView view = SharedWorldClient.releaseCoordinator().view();
        return view != null && view.progressState() != null
                ? view.progressState()
                : SharedWorldProgressState.indeterminate(this.title, Component.translatable("screen.sharedworld.progress.uploading_world"), "release_preparing", null);
    }

    private SavingProgressVisual progressVisual() {
        SharedWorldProgressState state = this.progressState();
        String phase = state.phase();
        if ("release_uploading".equals(phase) && state.displayedFraction() != null) {
            float mappedProgress = mapProgress(state.displayedFraction().floatValue(), UPLOADING_START, UPLOADING_END);
            return new SavingProgressVisual(state.label(), mappedProgress, null, null);
        }
        if ("release_finishing".equals(phase)) {
            return new SavingProgressVisual(state.label(), FINISHING_BASE, FINISHING_BASE, FINISHING_END);
        }
        return new SavingProgressVisual(state.label(), PREPARING_BASE, PREPARING_BASE, PREPARING_END);
    }

    private static float mapProgress(float fraction, float start, float end) {
        return start + ((end - start) * Math.max(0.0F, Math.min(1.0F, fraction)));
    }

    private record SavingProgressVisual(
            Component label,
            float progress,
            Float activityStart,
            Float activityEnd
    ) {
    }
}
