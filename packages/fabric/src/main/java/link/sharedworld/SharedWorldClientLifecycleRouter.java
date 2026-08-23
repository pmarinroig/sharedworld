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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SharedWorldClientLifecycleRouter {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-release");

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
        if (autoAcknowledgeCompletedReleaseAtMenu(releaseCoordinator, client.level != null, client.hasSingleplayerServer())) {
            LOGGER.info("SharedWorld release diagnostics [router]: auto-acknowledged COMPLETE release state at menu.");
            return false;
        }
        Screen currentScreen = link.sharedworld.versioned.ClientCompat.currentScreen(client);
        boolean onLifecycleScreen = currentScreen instanceof SharedWorldSavingScreen
                || currentScreen instanceof SharedWorldErrorScreen
                || currentScreen instanceof link.sharedworld.screen.ReleaseDriveReconnectScreen;
        if (!shouldForceLifecycleScreen(
                client.level != null,
                client.hasSingleplayerServer(),
                onLifecycleScreen,
                link.sharedworld.versioned.ClientCompat.isWorldEntryScreen(currentScreen))) {
            return false;
        }
        link.sharedworld.versioned.ClientCompat.setScreen(client, screenForLifecycleView(releaseCoordinator, defaultParent()));
        return true;
    }

    /**
     * The "no level open" window also covers vanilla world creation/loading (the moments between
     * clicking Create New World and the integrated server attaching); forcing a lifecycle screen
     * there would clobber the player's world-entry flow.
     */
    static boolean shouldForceLifecycleScreen(
            boolean hasLevel,
            boolean hasSingleplayerServer,
            boolean onLifecycleScreen,
            boolean onWorldEntryScreen
    ) {
        return !hasLevel && !hasSingleplayerServer && !onLifecycleScreen && !onWorldEntryScreen;
    }

    static boolean autoAcknowledgeCompletedReleaseAtMenu(
            SharedWorldReleaseCoordinator releaseCoordinator,
            boolean hasLevel,
            boolean hasSingleplayerServer
    ) {
        SharedWorldReleaseCoordinator.ReleaseView view = releaseCoordinator.view();
        if (view == null
                || hasLevel
                || hasSingleplayerServer
                || view.phase() != link.sharedworld.host.SharedWorldReleasePhase.COMPLETE) {
            return false;
        }
        releaseCoordinator.acknowledgeTerminal();
        return true;
    }

    public static Screen screenForLifecycleView(SharedWorldReleaseCoordinator releaseCoordinator, Screen parent) {
        SharedWorldReleaseCoordinator.ReleaseView view = releaseCoordinator.view();
        if (view == null || view.blocking()) {
            return defaultSavingScreen(releaseCoordinator.activeWorldName());
        }
        if (view.needsDriveReconnect()) {
            // A dead Drive grant can only be repaired via the OAuth reconnect
            // flow, and this parked screen is the only UI the router lets the
            // player reach, so the flow lives on the screen itself.
            return new link.sharedworld.screen.ReleaseDriveReconnectScreen(parent, titleFor(view), Component.literal(detailFor(view)));
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
            // Not error_host_title: these screens appear while QUITTING a world,
            // and "Could Not Start Shared World" read as the wrong direction.
            default -> Component.translatable("screen.sharedworld.release_error_title");
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
            link.sharedworld.versioned.ClientCompat.setScreen(Minecraft.getInstance(), savingScreen(parent, releaseCoordinator.activeWorldName()));
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
