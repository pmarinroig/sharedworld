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
        GAMERULE_DRILL_AWAIT_CHANGE,
        BAN_DRILL_AWAIT_COMMAND,
        BAN_DRILL_AWAIT_SURVIVAL,
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
        AWAIT_GAMERULE_DRILL,
        AWAIT_HOST_DEPARTURE,
        AWAIT_EXIT
    }

    /**
     * The ui-tour role walks every SharedWorld screen and saves a PNG of each
     * into the run dir's screenshots/ folder, so layout work can be reviewed
     * from actual renders instead of imagination. Driven by scripts/ui-tour.ts.
     */
    private enum TourStep {
        WAIT_TITLE,
        OPEN_HUB,
        SHOT_HUB_EMPTY,
        BEGIN_CREATE,
        SHOT_CREATE_CONNECT,
        PRESS_CONNECT,
        AWAIT_LINK_ADVANCE,
        SHOT_CREATE_WORLD,
        TO_DETAILS,
        SHOT_CREATE_DETAILS,
        SUBMIT_CREATE,
        AWAIT_INVITE,
        SHOT_INVITE,
        DONE_TO_HUB,
        SHOT_HUB_SELECTED,
        OPEN_EDIT,
        SHOT_EDIT_TAB,
        OPEN_REPLACE,
        SHOT_REPLACE,
        HUB_WATCH,
        COMPLETE
    }

    private static final String[] TOUR_EDIT_TAB_SHOTS = {
            "08-edit-details", "09-edit-settings", "10-edit-backups", "11-edit-members", "12-edit-storage"
    };

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
    private TourStep tourStep = TourStep.WAIT_TITLE;
    private int tourSettleTicks;
    private int tourEditTabIndex;
    private final AtomicBoolean asyncInFlight = new AtomicBoolean(false);
    private final AtomicReference<WorldSummaryDto> targetWorld = new AtomicReference<>();
    private boolean driveLinkPressed;
    private boolean driveLinkFetched;
    private boolean joinTargetInjected;
    private long banDrillSentAt;
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
        // 0.3.0: surface the realtime channel's lifecycle as markers so the
        // orchestrator can assert real clients actually connect and push.
        link.sharedworld.SharedWorldClient.realtimeEvents().subscribe(
                new link.sharedworld.realtime.RealtimeEvents.Subscriber() {
                    @Override
                    public void onRealtimeConnectionChanged(boolean connected) {
                        SharedWorldE2eDriver.this.markers.emit(
                                connected ? "realtime-connected" : "realtime-disconnected", SharedWorldE2eDriver.this.role);
                    }

                    @Override
                    public void onRealtimeEvent(link.sharedworld.api.SharedWorldModels.RealtimeEventDto event) {
                        SharedWorldE2eDriver.this.markers.emit("realtime-event", event.kind() + " " + event.worldId());
                    }
                });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft minecraft) {
        try {
            this.reportErrorScreens(minecraft);
            if ("host".equals(this.role)) {
                this.tickHost(minecraft);
            } else if ("guest".equals(this.role)) {
                this.tickGuest(minecraft);
            } else if ("ui-tour".equals(this.role)) {
                this.tickUiTour(minecraft);
            }
            // Every ~15s without a step transition, report what the driver is
            // looking at so orchestrator timeouts are diagnosable from markers
            // alone.
            if (this.ticksInStep > 0 && this.ticksInStep % 300 == 0) {
                String screenName = minecraft.screen == null ? "none" : minecraft.screen.getClass().getName();
                String step = "host".equals(this.role) ? this.hostStep.name()
                        : "guest".equals(this.role) ? this.guestStep.name()
                        : this.tourStep.name();
                this.markers.emit("stuck", step + " screen=" + screenName);
            }
        } catch (Exception exception) {
            LOGGER.error("e2e driver tick failed", exception);
            this.markers.emit("driver-exception", exception.toString());
        }
    }

    /** The connect-Drive button (any of its labels) is only visible on the wizard's connect step. */
    private static boolean onConnectDriveStep(Screen screen) {
        return WidgetAutomation.hasVisibleButton(screen, "screen.sharedworld.storage_link_google_drive")
                || WidgetAutomation.hasVisibleButton(screen, "screen.sharedworld.storage_get_new_link")
                || WidgetAutomation.hasVisibleButton(screen, "screen.sharedworld.storage_try_again");
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
                if (WidgetAutomation.hasActiveButton(screen, "screen.sharedworld.create_world")) {
                    this.hostStep = HostStep.CREATE_SUBMIT;
                    return;
                }
                // The wizard's connect step comes first now; its button is only
                // visible while that step is showing.
                if (onConnectDriveStep(screen)) {
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
                if (!onConnectDriveStep(screen)) {
                    // Link completed and the wizard auto-advanced past the
                    // connect step; walk the remaining steps with Next.
                    this.hostStep = HostStep.CREATE_NAVIGATE;
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
                // A successful create lands on the share-code screen first.
                if (minecraft.screen instanceof link.sharedworld.screen.SharedWorldInviteScreen inviteScreen) {
                    if (WidgetAutomation.pressButton(inviteScreen, "screen.sharedworld.done")) {
                        this.markers.emit("post-create-invite-shown", null);
                    }
                    return;
                }
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
                    this.hostStep = HostStep.GAMERULE_DRILL_AWAIT_CHANGE;
                }
            }
            case GAMERULE_DRILL_AWAIT_CHANGE -> {
                // Gamerule persistence proof, local half: the guest's vanilla
                // /gamerule flipped keepInventory on the integrated server.
                // The hosting manager's next heartbeat snapshot reports it to
                // the backend; the orchestrator asserts that half against
                // GET /worlds/:id before allowing shutdown.
                IntegratedServer server = minecraft.getSingleplayerServer();
                if (server != null && Boolean.TRUE.equals(
                        link.sharedworld.host.WorldSettingsReader.readGameRules(server).get("keepInventory"))) {
                    this.markers.emit("gamerule-changed", "keepInventory=true");
                    this.hostStep = HostStep.BAN_DRILL_AWAIT_COMMAND;
                }
            }
            case BAN_DRILL_AWAIT_COMMAND -> {
                if (minecraft.player == null) {
                    return;
                }
                if ("ban-self-drill".equals(this.commands.poll())) {
                    // The field-reported footgun verbatim: the hosting owner types
                    // /ban on themselves. e4mc restores vanilla's ban on integrated
                    // servers; SharedWorld must reroute it into the membership ban,
                    // whose guards refuse owner and self targets — the session
                    // survives and no local banlist entry appears.
                    minecraft.player.connection.sendCommand("ban " + minecraft.player.getName().getString());
                    this.markers.emit("ban-drill-sent", null);
                    this.banDrillSentAt = System.currentTimeMillis();
                    this.hostStep = HostStep.BAN_DRILL_AWAIT_SURVIVAL;
                }
            }
            case BAN_DRILL_AWAIT_SURVIVAL -> {
                // The failure mode is the host being kicked out of its own
                // server; give a wrongly executed vanilla ban time to land,
                // then assert everything still stands.
                if (System.currentTimeMillis() - this.banDrillSentAt < 3_000L) {
                    return;
                }
                IntegratedServer server = minecraft.getSingleplayerServer();
                if (server == null || minecraft.player == null || minecraft.level == null) {
                    this.markers.emit("ban-drill-failed", "host lost its own server after self /ban");
                    return;
                }
                if (SharedWorldClient.hostingManager().phase() != SharedWorldHostingManager.Phase.RUNNING) {
                    this.markers.emit("ban-drill-failed", "hosting phase left RUNNING after self /ban");
                    return;
                }
                if (server.getPlayerList().getBans().isBanned(
                        new net.minecraft.server.players.NameAndId(minecraft.player.getUUID(), minecraft.player.getName().getString()))) {
                    this.markers.emit("ban-drill-failed", "vanilla banlist recorded the hosting player");
                    return;
                }
                this.markers.emit("ban-drill-survived", null);
                this.hostStep = HostStep.AWAIT_SHUTDOWN_COMMAND;
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
                    this.guestStep = GuestStep.AWAIT_GAMERULE_DRILL;
                }
            }
            case AWAIT_GAMERULE_DRILL -> {
                if (minecraft.player == null) {
                    return;
                }
                if ("gamerule-drill".equals(this.commands.poll())) {
                    // A managed gamerule changed in game must persist to the
                    // backend (the host reports it; see the orchestrator).
                    // 1.21.11 registry gamerules use snake_case command ids
                    // (the dev-e2e jar only runs on that bucket).
                    minecraft.player.connection.sendCommand("gamerule keep_inventory true");
                    this.markers.emit("guest-gamerule-sent", "keep_inventory true");
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
    // ---------------------------------------------------------------- ui tour

    private void tickUiTour(Minecraft minecraft) {
        TourStep before = this.tourStep;
        if (this.tourSettleTicks > 0) {
            this.tourSettleTicks--;
            return;
        }
        Screen screen = minecraft.screen;
        switch (this.tourStep) {
            case WAIT_TITLE -> {
                dismissOnboarding(minecraft);
                if (screen instanceof TitleScreen) {
                    this.tourStep = TourStep.OPEN_HUB;
                }
            }
            case OPEN_HUB -> {
                SharedWorldClient.openMainScreen(screen);
                this.settleThen(TourStep.SHOT_HUB_EMPTY, 40);
            }
            case SHOT_HUB_EMPTY -> {
                this.screenshot(minecraft, "01-hub-empty");
                this.settleThen(TourStep.BEGIN_CREATE, 10);
            }
            case BEGIN_CREATE -> {
                if (screen instanceof SharedWorldScreen hub && WidgetAutomation.pressButton(hub, "screen.sharedworld.create")) {
                    this.settleThen(TourStep.SHOT_CREATE_CONNECT, 30);
                }
            }
            case SHOT_CREATE_CONNECT -> {
                this.screenshot(minecraft, "02-create-connect");
                this.tourStep = TourStep.PRESS_CONNECT;
            }
            case PRESS_CONNECT -> {
                if (screen != null && WidgetAutomation.pressButton(screen, "screen.sharedworld.storage_link_google_drive")) {
                    this.tourStep = TourStep.AWAIT_LINK_ADVANCE;
                }
            }
            case AWAIT_LINK_ADVANCE -> {
                if (screen == null) {
                    return;
                }
                if (!onConnectDriveStep(screen)) {
                    this.settleThen(TourStep.SHOT_CREATE_WORLD, 20);
                    return;
                }
                this.fetchDriveAuthUrlOnce(screen);
            }
            case SHOT_CREATE_WORLD -> {
                this.emitListGeometry("wizard-list", screen);
                this.screenshot(minecraft, "03-create-world");
                this.tourStep = TourStep.TO_DETAILS;
            }
            case TO_DETAILS -> {
                if (screen != null && WidgetAutomation.pressButton(screen, "screen.sharedworld.next")) {
                    this.settleThen(TourStep.SHOT_CREATE_DETAILS, 20);
                }
            }
            case SHOT_CREATE_DETAILS -> {
                this.screenshot(minecraft, "04-create-details");
                this.tourStep = TourStep.SUBMIT_CREATE;
            }
            case SUBMIT_CREATE -> {
                if (screen != null && WidgetAutomation.pressButton(screen, "screen.sharedworld.create_world")) {
                    this.tourStep = TourStep.AWAIT_INVITE;
                }
            }
            case AWAIT_INVITE -> {
                if (screen instanceof link.sharedworld.screen.SharedWorldInviteScreen) {
                    this.settleThen(TourStep.SHOT_INVITE, 40);
                }
            }
            case SHOT_INVITE -> {
                this.screenshot(minecraft, "05-invite-created");
                this.tourStep = TourStep.DONE_TO_HUB;
            }
            case DONE_TO_HUB -> {
                if (screen != null && WidgetAutomation.pressButton(screen, "screen.sharedworld.done")) {
                    this.settleThen(TourStep.SHOT_HUB_SELECTED, 60);
                }
            }
            case SHOT_HUB_SELECTED -> {
                this.screenshot(minecraft, "06-hub-selected");
                this.tourStep = TourStep.OPEN_EDIT;
            }
            case OPEN_EDIT -> {
                if (screen instanceof SharedWorldScreen hub && WidgetAutomation.pressButton(hub, "screen.sharedworld.edit")) {
                    this.tourEditTabIndex = 0;
                    this.settleThen(TourStep.SHOT_EDIT_TAB, 40);
                }
            }
            case SHOT_EDIT_TAB -> {
                if (!(screen instanceof link.sharedworld.screen.EditSharedWorldScreen edit)) {
                    return;
                }
                this.screenshot(minecraft, TOUR_EDIT_TAB_SHOTS[this.tourEditTabIndex]);
                this.tourEditTabIndex++;
                if (this.tourEditTabIndex < TOUR_EDIT_TAB_SHOTS.length) {
                    edit.sharedworldSelectTab(this.tourEditTabIndex);
                    this.settleThen(TourStep.SHOT_EDIT_TAB, 20);
                } else {
                    edit.sharedworldSelectTab(0);
                    this.settleThen(TourStep.OPEN_REPLACE, 20);
                }
            }
            case OPEN_REPLACE -> {
                if (screen != null && WidgetAutomation.pressButton(screen, "screen.sharedworld.replace_world")) {
                    this.settleThen(TourStep.SHOT_REPLACE, 30);
                }
            }
            case SHOT_REPLACE -> {
                this.emitListGeometry("replace-list", screen);
                this.screenshot(minecraft, "07-replace");
                if (screen != null) {
                    WidgetAutomation.pressButton(screen, "gui.back");
                    // Back lands on the edit screen; leave it for the hub too.
                }
                this.settleThen(TourStep.HUB_WATCH, 20);
            }
            case HUB_WATCH -> {
                if (screen instanceof link.sharedworld.screen.EditSharedWorldScreen edit) {
                    WidgetAutomation.pressButton(edit, "gui.back");
                    return;
                }
                if (screen instanceof SharedWorldScreen) {
                    // Sit on the hub through several auto-refresh cycles so the
                    // rebuild log (and a final shot) reveal any periodic flicker.
                    this.markers.emit("hub-watch-started", null);
                    this.settleThen(TourStep.COMPLETE, 300);
                }
            }
            case COMPLETE -> {
                this.screenshot(minecraft, "13-hub-watch");
                this.markers.emit("tour-complete", null);
                this.tourStep = TourStep.WAIT_TITLE;
                this.tourSettleTicks = Integer.MAX_VALUE;
            }
        }
        this.trackStepProgress(before != this.tourStep, this.tourStep.name());
    }

    /** Layout diagnostics for the ui-tour: geometry of a screen's saveList field. */
    private void emitListGeometry(String label, Screen screen) {
        try {
            java.lang.reflect.Field field = screen.getClass().getDeclaredField("saveList");
            field.setAccessible(true);
            var list = (net.minecraft.client.gui.components.AbstractSelectionList<?>) field.get(screen);
            this.markers.emit(label, "rows=" + list.children().size()
                    + " x=" + list.getX() + " y=" + list.getY()
                    + " w=" + list.getWidth() + " h=" + list.getHeight()
                    + " visible=" + list.visible);
        } catch (ReflectiveOperationException exception) {
            this.markers.emit(label, "reflection failed: " + exception);
        }
    }

    private void settleThen(TourStep next, int ticks) {
        this.tourStep = next;
        this.tourSettleTicks = ticks;
    }

    /** Save the current frame as screenshots/<name>.png in the run dir. */
    private void screenshot(Minecraft minecraft, String name) {
        try {
            net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> {
                try (image) {
                    java.nio.file.Path directory = minecraft.gameDirectory.toPath().resolve("screenshots");
                    java.nio.file.Files.createDirectories(directory);
                    image.writeToFile(directory.resolve(name + ".png"));
                    this.markers.emit("shot", name);
                } catch (Exception exception) {
                    this.markers.emit("shot-failed", name + ": " + exception);
                }
            });
        } catch (Exception exception) {
            this.markers.emit("shot-failed", name + ": " + exception);
        }
    }

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
