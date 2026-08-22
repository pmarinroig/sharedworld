package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.AccountDeleteStepDto;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.progress.SharedWorldProgressRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Runs the whole account deletion: every world first (backups round by round,
 * exactly like the single-world delete flow), then the bounded DELETE /account
 * loop, then the local wipe. Deliberately unescapable — a half-deleted account
 * is resumable, but wandering off mid-flow helps nobody.
 */
public final class DeleteAccountProgressScreen extends link.sharedworld.versioned.VersionedScreen {
    /** Far above any plausible step count; guards against a server that never reports done. */
    private static final int MAX_ACCOUNT_STEPS = 10_000;

    private final AccountScreen parent;
    private final DeleteAccountFlowModel model = new DeleteAccountFlowModel();
    private boolean started;
    private volatile float progress;
    private volatile Component progressDetail;

    public DeleteAccountProgressScreen(AccountScreen parent) {
        super(Component.translatable("screen.sharedworld.account_delete_progress_title"));
        this.parent = parent;
        this.progressDetail = this.title;
    }

    @Override
    protected void init() {
        if (!this.started) {
            this.started = true;
            this.startDeletion();
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
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.sharedworldRenderMenuBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        float fraction = this.progress;
        if (fraction > 0.0F) {
            SharedWorldProgressRenderer.renderCenteredBar(
                    guiGraphics, this.font, this.width, this.height,
                    this.title, this.progressDetail, fraction, null, null, partialTick);
        } else {
            SharedWorldProgressRenderer.renderCenteredBar(
                    guiGraphics, this.font, this.width, this.height,
                    this.title, this.progressDetail, 0.0F, 0.0F, 1.0F, partialTick);
        }
    }

    private void startDeletion() {
        CompletableFuture
                .runAsync(() -> {
                    try {
                        this.runDeletion();
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, SharedWorldClient.ioExecutor())
                .whenComplete((ignored, error) -> ScreenGuards.runIfCurrent(this, () -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        // Partial deletions are resumable: the account screen's
                        // delete button just continues where this run stopped.
                        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new SharedWorldErrorScreen(
                                this.parent,
                                Component.translatable("screen.sharedworld.error_title"),
                                Component.literal(SharedWorldText.errorMessageOrDefault(cause.getMessage()))
                        ));
                    } else {
                        link.sharedworld.versioned.ClientCompat.setScreen(this.minecraft, new AccountDeletedScreen());
                    }
                }));
    }

    private void runDeletion() throws Exception {
        SharedWorldApiClient api = SharedWorldClient.apiClient();
        List<WorldSummaryDto> worlds = api.listWorlds();
        this.model.onWorldsListed(worlds.size());
        this.publishProgress();
        for (WorldSummaryDto world : worlds) {
            if (isOwner(world)) {
                WorldDeleteRounds.deleteBackupsThenWorld(api, world.id(), (done, total) -> this.publishProgress());
            } else {
                api.deleteWorld(world.id());
            }
            SharedWorldClient.releaseCoordinator().discardPendingReleaseIfMatches(world.id());
            this.model.onWorldDeleted();
            this.publishProgress();
        }
        // The step loop is resumable server-side, so a transient failure
        // (timeout, blip) retries instead of stranding the user mid-deletion.
        int consecutiveFailures = 0;
        for (int step = 0; step < MAX_ACCOUNT_STEPS; step++) {
            AccountDeleteStepDto response;
            try {
                response = api.deleteAccountStep();
                consecutiveFailures = 0;
            } catch (IOException exception) {
                consecutiveFailures += 1;
                if (consecutiveFailures > 3) {
                    throw exception;
                }
                Thread.sleep(2_000L);
                continue;
            }
            this.model.onAccountStep(response.done(), response.remaining());
            this.publishProgress();
            if (response.done()) {
                break;
            }
        }
        if (this.model.stage() != DeleteAccountFlowModel.Stage.WIPING_LOCAL) {
            throw new IllegalStateException("Account deletion did not finish");
        }
        SharedWorldClient.wipeLocalDataForAccountDeletion();
        this.model.onLocalWipeFinished();
        this.publishProgress();
    }

    private void publishProgress() {
        this.progress = this.model.progress();
        this.progressDetail = switch (this.model.stage()) {
            case DELETING_WORLDS -> Component.translatable("screen.sharedworld.account_delete_progress_worlds",
                    this.model.deletedWorlds(), this.model.totalWorlds());
            case PURGING_ACCOUNT -> Component.translatable("screen.sharedworld.account_delete_progress_purge");
            case WIPING_LOCAL, DONE -> Component.translatable("screen.sharedworld.account_delete_progress_local");
        };
    }

    private static boolean isOwner(WorldSummaryDto world) {
        String ownerUuid = world.ownerUuid();
        if (ownerUuid == null || ownerUuid.isBlank()) {
            return false;
        }
        return ownerUuid.replace("-", "").equalsIgnoreCase(SharedWorldApiClient.currentPlayerUuid());
    }
}
