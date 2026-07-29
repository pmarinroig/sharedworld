package link.sharedworld.devhelper.e2e;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.SharedWorldPlaySessionTracker;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.host.MemberCommandGrant;
import link.sharedworld.host.SharedWorldHostingManager;
import link.sharedworld.integration.E4mcDomainTracker;
import link.sharedworld.screen.SharedWorldErrorScreen;
import link.sharedworld.screen.SharedWorldScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Auto-drive for the hermetic two-client e2e. Inert unless
 * -Dsharedworld.e2e.role=host|guest is set (only the e2eHost/e2eGuest run
 * configs set it; the dev-helper jar never ships). The driver replaces the
 * human in the manual fake-host/fake-guest workflow: it presses the same
 * buttons on the same production screens and observes the same coordinators,
 * emitting a marker per phase for the orchestrator to assert on.
 */
public final class SharedWorldE2eDriver implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-e2e");

    private enum HostStep {
        WAIT_TITLE,
        OPEN_MAIN,
        BEGIN_CREATE,
        CREATE_NAVIGATE,
        CREATE_LINK_DRIVE,
        CREATE_SUBMIT,
        AWAIT_WORLD_LISTED,
        BEGIN_HOSTING,
        CANCEL_FIRST_HOSTING,
        AWAIT_CANCEL_COMPLETE,
        AWAIT_PUBLISH,
        AWAIT_HOST_LIVE,
        OP_DRILL_AWAIT_COMMAND,
        OP_DRILL_AWAIT_GRANT,
        OP_DRILL_AWAIT_DIFFICULTY,
        AWAIT_SHUTDOWN_COMMAND,
        AWAIT_RELEASE_COMPLETE,
        AWAIT_EXIT
    }

    private enum GuestStep {
        WAIT_GO,
        WAIT_TITLE,
        OPEN_MAIN,
        AWAIT_WORLD_LISTED,
        BEGIN_JOIN,
        AWAIT_INGAME,
        AWAIT_COMMAND_DRILL,
        AWAIT_HOST_DEPARTURE,
        AWAIT_EXIT
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private E2eMarkers markers;
    private E2eCommands commands;
    private String role;
    private String worldName;

    private HostStep hostStep = HostStep.WAIT_TITLE;
    private GuestStep guestStep = GuestStep.WAIT_GO;
    private final AtomicBoolean asyncInFlight = new AtomicBoolean(false);
    private final AtomicReference<WorldSummaryDto> targetWorld = new AtomicReference<>();
    private boolean driveLinkPressed;
    private boolean driveLinkFetched;
    private boolean joinTargetInjected;
    private boolean cancelDrillDone;
    private boolean sawErrorScreen;
    private int ticksInStep;

    @Override
    public void onInitializeClient() {
        this.role = System.getProperty("sharedworld.e2e.role");
        if (this.role == null || this.role.isBlank()) {
            return;
        }
        String markerFile = System.getProperty("sharedworld.e2e.markerFile");
        if (markerFile == null || markerFile.isBlank()) {
            throw new IllegalStateException("sharedworld.e2e.role is set but sharedworld.e2e.markerFile is not.");
        }
        this.markers = new E2eMarkers(Path.of(markerFile));
        String commandFile = System.getProperty("sharedworld.e2e.commandFile");
        this.commands = new E2eCommands(commandFile == null || commandFile.isBlank() ? null : Path.of(commandFile));
        this.worldName = System.getProperty("sharedworld.e2e.worldName", "E2E Fixture");
        this.markers.emit("driver-armed", this.role);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft minecraft) {
        try {
            this.reportErrorScreens(minecraft);
            if ("host".equals(this.role)) {
                this.tickHost(minecraft);
            } else if ("guest".equals(this.role)) {
                this.tickGuest(minecraft);
            }
            // Every ~15s without a step transition, report what the driver is
            // looking at so orchestrator timeouts are diagnosable from markers
            // alone.
            if (this.ticksInStep > 0 && this.ticksInStep % 300 == 0) {
                String screenName = minecraft.screen == null ? "none" : minecraft.screen.getClass().getName();
                String step = "host".equals(this.role) ? this.hostStep.name() : this.guestStep.name();
                this.markers.emit("stuck", step + " screen=" + screenName);
            }
        } catch (Exception exception) {
            LOGGER.error("e2e driver tick failed", exception);
            this.markers.emit("driver-exception", exception.toString());
        }
    }

    private void reportErrorScreens(Minecraft minecraft) {
        boolean isErrorScreen = minecraft.screen instanceof SharedWorldErrorScreen;
        if (isErrorScreen && !this.sawErrorScreen) {
            this.markers.emit("error-screen", minecraft.screen.getTitle().getString());
        }
        this.sawErrorScreen = isErrorScreen;
    }

    // ---------------------------------------------------------------- host

    private void tickHost(Minecraft minecraft) {
        HostStep before = this.hostStep;
        switch (this.hostStep) {
            case WAIT_TITLE -> {
                dismissOnboarding(minecraft);
                if (minecraft.screen instanceof TitleScreen) {
                    this.hostStep = HostStep.OPEN_MAIN;
                }
            }
            case OPEN_MAIN -> {
                SharedWorldClient.openMainScreen(minecraft.screen);
                this.hostStep = HostStep.BEGIN_CREATE;
            }
            case BEGIN_CREATE -> {
                if (minecraft.screen instanceof SharedWorldScreen screen
                        && WidgetAutomation.pressButton(screen, "screen.sharedworld.create")) {
                    this.hostStep = HostStep.CREATE_NAVIGATE;
                }
            }
            case CREATE_NAVIGATE -> {
                Screen screen = minecraft.screen;
                if (screen == null) {
                    return;
                }
                if (WidgetAutomation.findButton(screen, "screen.sharedworld.storage_link_google_drive") != null
                        || WidgetAutomation.findButton(screen, "screen.sharedworld.storage_relink") != null) {
                    this.hostStep = HostStep.CREATE_LINK_DRIVE;
                    return;
                }
                WidgetAutomation.pressButton(screen, "screen.sharedworld.next");
            }
            case CREATE_LINK_DRIVE -> {
                Screen screen = minecraft.screen;
                if (screen == null) {
                    return;
                }
                if (WidgetAutomation.hasActiveButton(screen, "screen.sharedworld.create_world")) {
                    this.hostStep = HostStep.CREATE_SUBMIT;
                    return;
                }
                if (!this.driveLinkPressed && WidgetAutomation.pressButton(screen, "screen.sharedworld.storage_link_google_drive")) {
                    this.driveLinkPressed = true;
                    this.markers.emit("drive-link-started", null);
                    return;
                }
                this.fetchDriveAuthUrlOnce(screen);
            }
            case CREATE_SUBMIT -> {
                Screen screen = minecraft.screen;
                if (screen != null && WidgetAutomation.pressButton(screen, "screen.sharedworld.create_world")) {
                    this.markers.emit("create-submitted", this.worldName);
                    this.hostStep = HostStep.AWAIT_WORLD_LISTED;
                }
            }
            case AWAIT_WORLD_LISTED -> {
                if (minecraft.screen instanceof SharedWorldScreen) {
                    this.lookUpWorldByName(world -> this.markers.emit("world-created", world.id()));
                    if (this.targetWorld.get() != null) {
                        this.hostStep = HostStep.BEGIN_HOSTING;
                    }
                }
            }
            case BEGIN_HOSTING -> {
                WorldSummaryDto world = this.targetWorld.get();
                if (minecraft.screen instanceof SharedWorldScreen screen && world != null) {
                    SharedWorldClient.sessionCoordinator().beginJoin(screen, world);
                    this.markers.emit("hosting-requested", world.id());
                    // First pass exercises the cancel drill (cancel mid-startup,
                    // then re-host); the second pass hosts for real.
                    this.hostStep = this.cancelDrillDone ? HostStep.AWAIT_PUBLISH : HostStep.CANCEL_FIRST_HOSTING;
                }
            }
            case CANCEL_FIRST_HOSTING -> {
                // The impatient-player drill: cancel the startup mid-flight. The
                // tiny fixture world usually opens before the next driver tick,
                // so this exercises the world-open cancellation branch (forced
                // disconnect + reset) that historically wedged in CANCELLING.
                if (SharedWorldClient.hostingManager().isStartupCancelable()) {
                    SharedWorldClient.hostingManager().cancelStartup();
                    this.markers.emit("hosting-cancel-requested", null);
                    this.hostStep = HostStep.AWAIT_CANCEL_COMPLETE;
                }
            }
            case AWAIT_CANCEL_COMPLETE -> {
                if (SharedWorldHostingManager.Phase.IDLE == SharedWorldClient.hostingManager().phase()
                        && minecraft.level == null
                        && !minecraft.hasSingleplayerServer()) {
                    this.markers.emit("hosting-cancelled", null);
                    this.cancelDrillDone = true;
                    // The forced disconnect landed on a vanilla screen; reopen
                    // the SharedWorld screen and host again.
                    SharedWorldClient.openMainScreen(minecraft.screen);
                    this.hostStep = HostStep.BEGIN_HOSTING;
                }
            }
            case AWAIT_PUBLISH -> {
                IntegratedServer server = minecraft.getSingleplayerServer();
                if (!this.joinTargetInjected && server != null && server.isPublished()) {
                    // The hermetic transport: stand in for e4mc's relay by
                    // injecting the LAN port as the join target. Everything
                    // downstream (confirm-host heartbeat, backend runtime,
                    // guest connect) runs the production path unchanged.
                    String joinTarget = "127.0.0.1:" + server.getPort();
                    E4mcDomainTracker.captureAssignedDomain(joinTarget);
                    this.joinTargetInjected = true;
                    this.markers.emit("published", joinTarget);
                    this.hostStep = HostStep.AWAIT_HOST_LIVE;
                }
            }
            case AWAIT_HOST_LIVE -> {
                if (SharedWorldClient.hostingManager().phase() == SharedWorldHostingManager.Phase.RUNNING) {
                    this.markers.emit("host-live", this.targetWorld.get().id());
                    this.hostStep = HostStep.OP_DRILL_AWAIT_COMMAND;
                }
            }
            case OP_DRILL_AWAIT_COMMAND -> {
                // The host (owner) grants the guest command permission through the
                // real in-game /op — the same chat-command path a player uses.
                if (minecraft.player == null) {
                    return;
                }
                if ("op-drill".equals(this.commands.poll())) {
                    String guestName = firstNonOwnerMemberName();
                    if (guestName == null) {
                        this.markers.emit("op-drill-failed", "no non-owner member in hosted grants");
                        return;
                    }
                    minecraft.player.connection.sendCommand("op " + guestName);
                    this.markers.emit("op-drill-sent", guestName);
                    this.hostStep = HostStep.OP_DRILL_AWAIT_GRANT;
                }
            }
            case OP_DRILL_AWAIT_GRANT -> {
                boolean granted = SharedWorldDevSessionBridge.hostedMemberGrants().values().stream()
                        .anyMatch(MemberCommandGrant::canUseCommands);
                if (granted) {
                    this.markers.emit("op-drill-granted", null);
                    this.hostStep = HostStep.OP_DRILL_AWAIT_DIFFICULTY;
                }
            }
            case OP_DRILL_AWAIT_DIFFICULTY -> {
                // Proof the grant is live: the guest's vanilla /difficulty took
                // effect on the integrated server.
                IntegratedServer server = minecraft.getSingleplayerServer();
                if (server != null && server.getWorldData().getDifficulty() == net.minecraft.world.Difficulty.HARD) {
                    this.markers.emit("difficulty-changed", "hard");
                    this.hostStep = HostStep.AWAIT_SHUTDOWN_COMMAND;
                }
            }
            case AWAIT_SHUTDOWN_COMMAND -> {
                if ("shutdown".equals(this.commands.poll())) {
                    this.markers.emit("shutdown-received", null);
                    if (minecraft.screen == null) {
                        minecraft.setScreen(new PauseScreen(true));
                    }
                    this.hostStep = HostStep.AWAIT_RELEASE_COMPLETE;
                }
            }
            case AWAIT_RELEASE_COMPLETE -> {
                if (minecraft.screen instanceof PauseScreen pauseScreen) {
                    WidgetAutomation.pressButton(pauseScreen, "menu.returnToMenu");
                    return;
                }
                if (SharedWorldClient.hostingManager().isReleaseComplete()) {
                    this.markers.emit("release-complete", null);
                    this.hostStep = HostStep.AWAIT_EXIT;
                }
            }
            case AWAIT_EXIT -> {
                if ("exit".equals(this.commands.poll())) {
                    this.markers.emit("exiting", null);
                    minecraft.stop();
                }
            }
        }
        this.trackStepProgress(before != this.hostStep, this.hostStep.name());
    }

    /**
     * The create screen stands in a browser's place in dev-mock OAuth mode:
     * the auth URL it receives is the backend's own callback, and one GET
     * completes the link (the screen's poller then flips to linked). The URL
     * is read from the screen's storageLink field on the tick thread —
     * reflection instead of the clipboard because GLFW clipboard access off
     * the render thread segfaults under X11.
     */
    private void fetchDriveAuthUrlOnce(Screen screen) {
        if (this.driveLinkFetched || !(screen instanceof link.sharedworld.screen.CreateSharedWorldScreen)) {
            return;
        }
        String authUrl;
        try {
            java.lang.reflect.Field field = screen.getClass().getDeclaredField("storageLink");
            field.setAccessible(true);
            Object storageLink = field.get(screen);
            if (storageLink == null) {
                return;
            }
            authUrl = (String) storageLink.getClass().getMethod("authUrl").invoke(storageLink);
        } catch (ReflectiveOperationException exception) {
            this.markers.emit("drive-link-failed", "storageLink reflection failed: " + exception);
            return;
        }
        if (authUrl == null || !authUrl.startsWith("http")) {
            return;
        }
        this.driveLinkFetched = true;
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> response = this.httpClient.send(
                        HttpRequest.newBuilder(URI.create(authUrl)).timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                this.markers.emit("drive-link-callback-fetched", "HTTP " + response.statusCode());
            } catch (Exception exception) {
                this.markers.emit("drive-link-failed", exception.toString());
            }
        }, SharedWorldClient.ioExecutor());
    }

    // ---------------------------------------------------------------- guest

    private void tickGuest(Minecraft minecraft) {
        GuestStep before = this.guestStep;
        switch (this.guestStep) {
            case WAIT_GO -> {
                if ("guest-go".equals(this.commands.poll())) {
                    this.markers.emit("guest-go-received", null);
                    this.guestStep = GuestStep.WAIT_TITLE;
                }
            }
            case WAIT_TITLE -> {
                dismissOnboarding(minecraft);
                if (minecraft.screen instanceof TitleScreen) {
                    this.guestStep = GuestStep.OPEN_MAIN;
                }
            }
            case OPEN_MAIN -> {
                SharedWorldClient.openMainScreen(minecraft.screen);
                this.guestStep = GuestStep.AWAIT_WORLD_LISTED;
            }
            case AWAIT_WORLD_LISTED -> {
                if (minecraft.screen instanceof SharedWorldScreen) {
                    this.lookUpWorldByName(world -> this.markers.emit("guest-sees-world", world.id()));
                    if (this.targetWorld.get() != null) {
                        this.guestStep = GuestStep.BEGIN_JOIN;
                    }
                }
            }
            case BEGIN_JOIN -> {
                WorldSummaryDto world = this.targetWorld.get();
                if (minecraft.screen instanceof SharedWorldScreen screen && world != null) {
                    SharedWorldClient.sessionCoordinator().beginJoin(screen, world);
                    this.markers.emit("guest-join-requested", world.id());
                    this.guestStep = GuestStep.AWAIT_INGAME;
                }
            }
            case AWAIT_INGAME -> {
                SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
                if (minecraft.level != null && session != null
                        && session.role() == SharedWorldPlaySessionTracker.SessionRole.GUEST) {
                    this.markers.emit("guest-ingame", session.worldId());
                    this.guestStep = GuestStep.AWAIT_COMMAND_DRILL;
                }
            }
            case AWAIT_COMMAND_DRILL -> {
                if (minecraft.player == null) {
                    return;
                }
                if ("command-drill".equals(this.commands.poll())) {
                    // Freshly /op'ed by the host; a vanilla admin command must work.
                    minecraft.player.connection.sendCommand("difficulty hard");
                    this.markers.emit("guest-command-sent", "difficulty hard");
                    this.guestStep = GuestStep.AWAIT_HOST_DEPARTURE;
                }
            }
            case AWAIT_HOST_DEPARTURE -> {
                if (minecraft.level == null || SharedWorldClient.playSessionTracker().currentSession() == null) {
                    String screenName = minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName();
                    this.markers.emit("guest-observed-host-departure", screenName);
                    this.guestStep = GuestStep.AWAIT_EXIT;
                }
            }
            case AWAIT_EXIT -> {
                if ("exit".equals(this.commands.poll())) {
                    this.markers.emit("exiting", null);
                    minecraft.stop();
                }
            }
        }
        this.trackStepProgress(before != this.guestStep, this.guestStep.name());
    }

    // ---------------------------------------------------------------- shared

    private static String firstNonOwnerMemberName() {
        String ownerUuid = SharedWorldDevSessionBridge.hostingSharedWorldOwnerUuid();
        for (MemberCommandGrant grant : SharedWorldDevSessionBridge.hostedMemberGrants().values()) {
            if (ownerUuid == null || !link.sharedworld.CanonicalPlayerIdentity.sameUuid(grant.playerUuid(), ownerUuid)) {
                return grant.playerName();
            }
        }
        return null;
    }

    private void lookUpWorldByName(java.util.function.Consumer<WorldSummaryDto> onFound) {
        // Refresh at most every 2 seconds and never concurrently.
        if (this.targetWorld.get() != null || this.ticksInStep % 40 != 0 || !this.asyncInFlight.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                List<WorldSummaryDto> worlds = SharedWorldClient.apiClient().listWorlds();
                worlds.stream()
                        .filter(world -> this.worldName.equals(world.name()))
                        .findFirst()
                        .ifPresent(world -> {
                            if (this.targetWorld.compareAndSet(null, world)) {
                                onFound.accept(world);
                            }
                        });
            } catch (Exception exception) {
                LOGGER.debug("e2e world lookup failed; will retry", exception);
            } finally {
                this.asyncInFlight.set(false);
            }
        }, SharedWorldClient.ioExecutor());
    }

    /** A fresh run dir boots into accessibility onboarding; continue past it. */
    private static void dismissOnboarding(Minecraft minecraft) {
        if (minecraft.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen onboarding) {
            WidgetAutomation.pressButton(onboarding, "gui.continue");
        }
    }

    private void trackStepProgress(boolean changed, String stepName) {
        if (changed) {
            this.markers.emit("step", stepName);
            this.ticksInStep = 0;
        } else {
            this.ticksInStep += 1;
        }
    }
}
