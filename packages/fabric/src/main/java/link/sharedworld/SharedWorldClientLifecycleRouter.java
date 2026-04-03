package link.sharedworld;

import link.sharedworld.host.SharedWorldReleaseCoordinator;
import link.sharedworld.host.SharedWorldTerminalReasonKind;
import link.sharedworld.screen.SharedWorldErrorScreen;
import link.sharedworld.screen.SharedWorldSavingScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

public final class SharedWorldClientLifecycleRouter {
    private SharedWorldClientLifecycleRouter() {
    }

    /**
     * Responsibility:
     * Centralize client-side lifecycle screen routing so event hooks stay wiring-only.
     *
     * Preconditions:
     * Coordinators already own the authoritative join/host/release state.
     *
     * Postconditions:
     * Blocking save/forced-exit screens and non-blocking terminal notices are routed consistently.
     *
     * Stale-work rule:
     * This helper renders coordinator state only; it must not invent or advance lifecycle transitions.
     *
     * Authority source:
     * Coordinator-owned lifecycle state only.
     */
    static boolean routeTick(
            Minecraft client,
            SharedWorldReleaseCoordinator releaseCoordinator
    ) {
        return ensureLifecycleScreenVisible(client, releaseCoordinator);
    }

    static boolean ensureLifecycleScreenVisible(Minecraft client, SharedWorldReleaseCoordinator releaseCoordinator) {
        SharedWorldReleaseCoordinator.ReleaseView view = releaseCoordinator.view();
        if (view == null) {
            return false;
        }
        if (client.level != null || client.hasSingleplayerServer()) {
            return false;
        }
        if (view.phase() == link.sharedworld.host.SharedWorldReleasePhase.COMPLETE) {
            return false;
        }
        if (client.screen instanceof SharedWorldSavingScreen || client.screen instanceof SharedWorldErrorScreen) {
            return false;
        }
        client.setScreen(screenForLifecycleView(releaseCoordinator, defaultParent()));
        return true;
    }

    public static Screen screenForLifecycleView(SharedWorldReleaseCoordinator releaseCoordinator, Screen parent) {
        SharedWorldReleaseCoordinator.ReleaseView view = releaseCoordinator.view();
        if (view == null || view.blocking()) {
            return defaultSavingScreen(releaseCoordinator.activeWorldName());
        }
        return new SharedWorldErrorScreen(
                parent,
                titleFor(view),
                Component.literal(detailFor(view)),
                actionLabelFor(view),
                () -> handleTerminalAction(releaseCoordinator, view, parent)
        );
    }

    public static Screen defaultSavingScreen(String worldName) {
        return savingScreen(defaultParent(), worldName);
    }

    static Screen savingScreen(Screen parent, String worldName) {
        return new SharedWorldSavingScreen(parent, worldName);
    }

    private static Screen defaultParent() {
        return new JoinMultiplayerScreen(new TitleScreen());
    }

    static Component titleFor(SharedWorldReleaseCoordinator.ReleaseView view) {
        return switch (view.errorKind()) {
            case TERMINATED_DELETED -> Component.translatable("screen.sharedworld.deleted_title");
            case TERMINATED_REVOKED -> Component.translatable("screen.sharedworld.revoked_title");
            default -> Component.translatable("screen.sharedworld.error_host_title");
        };
    }

    static String detailFor(SharedWorldReleaseCoordinator.ReleaseView view) {
        if (view.errorMessage() != null && !view.errorMessage().isBlank()) {
            return view.errorMessage();
        }
        return switch (view.errorKind()) {
            case TERMINATED_DELETED -> Component.translatable("screen.sharedworld.deleted_detail").getString();
            case TERMINATED_REVOKED -> Component.translatable("screen.sharedworld.revoked_detail").getString();
            case AUTHORITATIVE_LOSS -> Component.translatable("screen.sharedworld.lifecycle_authoritative_loss").getString();
            case OBSOLETE_LOCAL_STATE -> Component.translatable("screen.sharedworld.lifecycle_obsolete_local_state").getString();
            case UNEXPECTED_LOCAL_INVARIANT_BREACH -> Component.translatable("screen.sharedworld.lifecycle_unexpected_local_invariant").getString();
            default -> Component.translatable("screen.sharedworld.finalization_error_detail").getString();
        };
    }

    private static Component actionLabelFor(SharedWorldReleaseCoordinator.ReleaseView view) {
        if (view.canRetry()) {
            return Component.translatable("screen.sharedworld.retry_finalization");
        }
        if (view.canDiscardLocalState()) {
            return Component.translatable("screen.sharedworld.return_to_sharedworld");
        }
        return Component.translatable("gui.back");
    }

    private static void handleTerminalAction(SharedWorldReleaseCoordinator releaseCoordinator, SharedWorldReleaseCoordinator.ReleaseView view, Screen parent) {
        if (view.canRetry() && releaseCoordinator.retry()) {
            Minecraft.getInstance().setScreen(savingScreen(parent, releaseCoordinator.activeWorldName()));
            return;
        }
        if (view.canDiscardLocalState()) {
            if (releaseCoordinator.discardLocalReleaseState()) {
                SharedWorldClient.openMainScreen(parent);
            }
            return;
        }
        releaseCoordinator.acknowledgeTerminal();
        SharedWorldClient.openMainScreen(parent);
    }
}
