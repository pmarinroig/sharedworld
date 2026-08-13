package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.host.HostingEvents;
import link.sharedworld.host.SharedWorldHostingManager;
import link.sharedworld.host.SharedWorldReleaseCoordinator;
import link.sharedworld.screen.HandoffWaitingScreen;
import link.sharedworld.screen.SharedWorldErrorScreen;
import link.sharedworld.screen.SharedWorldSavingScreen;
import link.sharedworld.screen.SharedWorldScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class SharedWorldClient implements ClientModInitializer {
    public static final String MOD_ID = "sharedworld";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final ExecutorService IO_EXECUTOR = Executors.newFixedThreadPool(4, new SharedWorldThreadFactory());
    private static final SharedWorldListState LIST_STATE = new SharedWorldListState();
    private static final SharedWorldCustomIconStore CUSTOM_ICON_STORE = new SharedWorldCustomIconStore();
    private static SharedWorldApiClient apiClient;
    private static SharedWorldHostingManager hostingManager;
    private static SharedWorldReleaseCoordinator releaseCoordinator;
    private static SharedWorldPresenceManager presenceManager;
    private static SharedWorldGuestRuntimeWatcher guestRuntimeWatcher;
    private static SharedWorldGuestCacheWarmer guestCacheWarmer;
    private static SharedWorldSessionCoordinator sessionCoordinator;
    private static final SharedWorldPlaySessionTracker PLAY_SESSION_TRACKER = new SharedWorldPlaySessionTracker();
    private static final link.sharedworld.realtime.RealtimeEvents REALTIME_EVENTS = new link.sharedworld.realtime.RealtimeEvents();
    private static link.sharedworld.realtime.SharedWorldPushChannel pushChannel;
    private static link.sharedworld.realtime.HostRosterReporter hostRosterReporter;
    private static volatile boolean pushChannelStarted;

    @Override
    public void onInitializeClient() {
        SharedWorldE4mcCompatibility.logClientInitStarted();
        link.sharedworld.versioned.ScreenBackdropCompat.install();
        RuntimePlayerIdentity.resolveBackendPlayerUuidWithHyphens(Minecraft.getInstance().getUser());
        apiClient = new SharedWorldApiClient(SharedWorldClientConfigStore.shared().resolvedBackendBaseUrl());
        apiClient.setSessionPersistence(SharedWorldSessionStore.shared());
        HostPlayerIdentity hostPlayerIdentity = apiClient::authenticatedWorldPlayerUuidWithHyphens;
        hostingManager = new SharedWorldHostingManager(
                apiClient,
                new ClientHostingEvents(),
                IO_EXECUTOR,
                runnable -> Minecraft.getInstance().execute(runnable)
        );
        releaseCoordinator = new SharedWorldReleaseCoordinator(apiClient, hostingManager);
        presenceManager = new SharedWorldPresenceManager(apiClient);
        guestRuntimeWatcher = new SharedWorldGuestRuntimeWatcher(apiClient);
        guestCacheWarmer = new SharedWorldGuestCacheWarmer(apiClient, hostPlayerIdentity);
        sessionCoordinator = new SharedWorldSessionCoordinator(apiClient);
        pushChannel = new link.sharedworld.realtime.SharedWorldPushChannel(
                SharedWorldClientConfigStore.shared().resolvedBackendBaseUrl(),
                new link.sharedworld.realtime.JdkWebSocketConnector(),
                () -> apiClient.ensureSession().token(),
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor((runnable) -> {
                    Thread thread = new Thread(runnable, "sharedworld-realtime");
                    thread.setDaemon(true);
                    return thread;
                }),
                runnable -> Minecraft.getInstance().execute(runnable),
                new link.sharedworld.realtime.SharedWorldPushChannel.Listener() {
                    @Override
                    public void onConnectionChanged(boolean connected) {
                        REALTIME_EVENTS.dispatchConnectionChanged(connected);
                    }

                    @Override
                    public void onEvent(link.sharedworld.api.SharedWorldModels.RealtimeEventDto event) {
                        REALTIME_EVENTS.dispatchEvent(event);
                    }
                },
                // Activity gates the reconnect cap: anything session-shaped
                // (or a recently open SharedWorld screen) keeps reconnects
                // aggressive; idle at the title screen backs off to minutes.
                () -> PLAY_SESSION_TRACKER.currentSession() != null
                        || hostingManager.phase() != link.sharedworld.host.SharedWorldHostingManager.Phase.IDLE
                        || releaseCoordinator.isActive()
                        || sessionCoordinator.waitingView() != null
                        || SharedWorldActivity.screenRecentlyOpen()
        );
        REALTIME_EVENTS.subscribe(guestRuntimeWatcher);
        REALTIME_EVENTS.subscribe(guestCacheWarmer);
        REALTIME_EVENTS.subscribe(presenceManager);
        // The presence manager owns guest liveness: world-presence frames over
        // the socket, and merged beats whose responses feed the watcher and
        // warmer (the disconnected fallback lane's only data source).
        presenceManager.setWorldPresenceAnnouncer(pushChannel::sendWorldPresence);
        presenceManager.setBeatObserver((worldId, response) -> {
            guestCacheWarmer.onMergedSnapshotObservation(worldId, response.lastSnapshotId());
            Minecraft.getInstance().execute(() ->
                    guestRuntimeWatcher.onMergedObservation(worldId, response.toRuntimeStatus()));
        });
        hostingManager.setRealtimeConnectedSupplier(REALTIME_EVENTS::isConnected);
        REALTIME_EVENTS.subscribe(new link.sharedworld.realtime.RealtimeEvents.Subscriber() {
            @Override
            public void onRealtimeEvent(link.sharedworld.api.SharedWorldModels.RealtimeEventDto event) {
                // Pushed changes reach the running host through an immediate
                // heartbeat, reusing the existing fetch-and-apply path (HTTP
                // stays the authority). world-deleted joins the list because
                // the 5-minute safety-net cadence would otherwise be the only
                // way a host learns its world vanished; a runtime push whose
                // payload contradicts our own hosting state (foreign host or
                // epoch, non-hosting phase) is probed the same way — the
                // heartbeat's 409/403/404 remains the verdict.
                if (("settings-changed".equals(event.kind())
                        || "membership-changed".equals(event.kind())
                        || "world-deleted".equals(event.kind()))
                        && event.worldId().equals(hostingManager.runningWorldId())) {
                    hostingManager.requestImmediateHeartbeat();
                    return;
                }
                if ("runtime-changed".equals(event.kind())
                        && event.runtime() != null
                        && event.worldId().equals(hostingManager.runningWorldId())) {
                    link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto runtime = event.runtime();
                    boolean foreign = runtime.runtimeEpoch() != hostingManager.currentRuntimeEpoch()
                            || !("host-starting".equals(runtime.phase())
                                    || "host-live".equals(runtime.phase())
                                    || "host-finalizing".equals(runtime.phase()));
                    if (foreign) {
                        hostingManager.requestImmediateHeartbeat();
                    }
                }
            }
        });
        REALTIME_EVENTS.subscribe(new link.sharedworld.realtime.RealtimeEvents.Subscriber() {
            @Override
            public void onRealtimeEvent(link.sharedworld.api.SharedWorldModels.RealtimeEventDto event) {
                // The waiting flow observes immediately on a pushed runtime
                // change instead of waiting out its poll interval.
                if ("runtime-changed".equals(event.kind())) {
                    sessionCoordinator.onWaitingWorldRuntimeChanged(event.worldId());
                }
            }
        });
        hostRosterReporter = new link.sharedworld.realtime.HostRosterReporter(
                () -> hostingManager.runningWorldId(),
                () -> hostingManager.currentRuntimeEpoch(),
                REALTIME_EVENTS::isConnected,
                (worldId, epoch, players) -> pushChannel.sendHostPlayers(worldId, epoch, players)
        );
        link.sharedworld.command.SharedWorldCommands.register(
                apiClient,
                SharedWorldClient::hostingManager,
                IO_EXECUTOR,
                runnable -> Minecraft.getInstance().execute(runnable)
        );
        // Reclaim staging copies and partial download temps a crashed or killed
        // client left behind; off the render thread since it walks world dirs.
        IO_EXECUTOR.execute(() -> new link.sharedworld.sync.ManagedWorldStore().pruneTransientArtifacts());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            reconcileTrackedGuestSession(client);
            hostingManager.tick(client);
            releaseCoordinator.tick(client);
            presenceManager.tick(client);
            guestRuntimeWatcher.tick(client);
            guestCacheWarmer.tick(client);
            sessionCoordinator.tick(client);
            hostRosterReporter.tick(client);
            if (SharedWorldClientLifecycleRouter.routeTick(client, releaseCoordinator)) {
                return;
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            PLAY_SESSION_TRACKER.onPlayJoin(handler, client.isLocalServer());
            sessionCoordinator.onGuestSessionJoined(PLAY_SESSION_TRACKER.currentSession(handler));
            // Sessions that begin without passing through the SharedWorld
            // screen (auto-rejoin, direct connect) still get the channel.
            if (PLAY_SESSION_TRACKER.currentSession(handler) != null) {
                ensureRealtimeStarted();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SharedWorldPlaySessionTracker.ActiveWorldSession activeSession = PLAY_SESSION_TRACKER.currentSession(handler);
            if (client.isSameThread()) {
                onPlayDisconnect(client, handler, activeSession);
                return;
            }
            client.execute(() -> onPlayDisconnect(client, handler, activeSession));
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            releaseCoordinator.onClientStopping(client);
            PLAY_SESSION_TRACKER.clear();
            SharedWorldDevSessionBridge.clear();
            // Clean socket close: the gateway pokes absence/grace immediately
            // instead of waiting for TCP death to be noticed.
            if (pushChannel != null) {
                pushChannel.stop();
            }
        });
        SharedWorldE4mcCompatibility.logClientInitFinished();
    }

    private static void onPlayDisconnect(
            Minecraft client,
            ClientPacketListener handler,
            SharedWorldPlaySessionTracker.ActiveWorldSession activeSession
    ) {
        SharedWorldPlaySessionTracker.ActiveWorldSession session = activeSession;
        boolean connectionKeyMatched = session != null;
        if (session == null && client.getConnection() != null && PLAY_SESSION_TRACKER.currentSession() != null) {
            LOGGER.warn(
                    "SharedWorld PLAY disconnect did not match the tracked session and a live connection blocks the unkeyed fallback; the tick reconciler owns the cleanup if the session is stale."
            );
        }
        if (session == null && client.getConnection() == null) {
            // The keyed lookup missed (connection-key mismatch on a deferred
            // DISCONNECT), but no newer connection took over — so whatever
            // session the tracker still holds belongs to the world that just
            // closed. Without this fallback that session became a ZOMBIE:
            // presence was never withdrawn (the player stayed on rosters)
            // and a later runtime-changed push could auto-host the player
            // from the world list screen. The key guard's real purpose —
            // an old connection's disconnect must not tear down a NEW live
            // session — is preserved by the getConnection() == null check.
            session = PLAY_SESSION_TRACKER.currentSession();
        }
        presenceManager.onDisconnect(session);
        guestRuntimeWatcher.onDisconnect(session);
        guestCacheWarmer.onDisconnect(session);
        SharedWorldReleaseCoordinator.ReleaseDisplay releaseDisplay = releaseCoordinator.onClientDisconnectReturnDisplay(client);
        SharedWorldPlaySessionTracker.RecoverySession recoverySession = connectionKeyMatched
                ? PLAY_SESSION_TRACKER.onDisconnect(handler)
                : session != null ? PLAY_SESSION_TRACKER.onDisconnect() : PLAY_SESSION_TRACKER.onDisconnect(handler);
        SharedWorldDevSessionBridge.clearHostingSession();
        sessionCoordinator.onUnexpectedGuestDisconnect(recoverySession);
        if (releaseDisplay != null) {
            SharedWorldClientLifecycleRouter.ensureLifecycleScreenVisible(client, releaseCoordinator);
        }
    }

    /**
     * Authoritative teardown for an INTENTIONAL guest leave, invoked from the
     * per-bucket disconnect mixins at the moment the player chooses to leave.
     * Session lifecycle must not depend on the fabric PLAY DISCONNECT event:
     * on relayed transports (e4mc dialtone, observed on 26.x) the underlying
     * channel can stay open after a manual quit, so that event never fires and
     * everything keyed on it leaks the session. The user's intent IS the
     * disconnect; presence, watcher, warmer and tracker all end here. A later
     * PLAY DISCONNECT for the same connection finds no session and no-ops.
     */
    static void onUserInitiatedGuestLeave() {
        SharedWorldPlaySessionTracker.ActiveWorldSession session = PLAY_SESSION_TRACKER.currentSession();
        if (session == null || session.role() != SharedWorldPlaySessionTracker.SessionRole.GUEST) {
            return;
        }
        presenceManager.onDisconnect(session);
        guestRuntimeWatcher.onDisconnect(session);
        guestCacheWarmer.onDisconnect(session);
        // markUserInitiatedDisconnect already ran in the disconnect hook, so
        // this teardown produces no recovery session.
        PLAY_SESSION_TRACKER.onDisconnect();
    }

    /**
     * Ticks the session slot must survive without a connection before the
     * reconciler declares the disconnect event lost. One tick would race the
     * legitimate teardown that runs inside the same tick as the world close.
     */
    private static final int GUEST_SESSION_RECONCILE_TICKS = 40;
    private static int guestSessionConnectionlessTicks;

    /**
     * Safety net for guest-session ends that fire NO event at all (neither
     * the intent mixin nor the PLAY disconnect — e.g. the host dying behind a
     * relay that never closes the local channel). The client is the authority
     * on whether a connection exists; a tracked guest session with no
     * connection and no level for two seconds is dead, and it ends through
     * the exact same path an observed disconnect would have taken, recovery
     * semantics included.
     */
    private static void reconcileTrackedGuestSession(Minecraft client) {
        SharedWorldPlaySessionTracker.ActiveWorldSession session = PLAY_SESSION_TRACKER.currentSession();
        if (session == null
                || session.role() != SharedWorldPlaySessionTracker.SessionRole.GUEST
                || client.getConnection() != null
                || client.level != null) {
            guestSessionConnectionlessTicks = 0;
            return;
        }
        guestSessionConnectionlessTicks += 1;
        if (guestSessionConnectionlessTicks < GUEST_SESSION_RECONCILE_TICKS) {
            return;
        }
        guestSessionConnectionlessTicks = 0;
        LOGGER.warn(
                "SharedWorld guest session for {} outlived its connection without any disconnect event; reconciling the teardown now.",
                session.worldId()
        );
        presenceManager.onDisconnect(session);
        guestRuntimeWatcher.onDisconnect(session);
        guestCacheWarmer.onDisconnect(session);
        SharedWorldPlaySessionTracker.RecoverySession recoverySession = PLAY_SESSION_TRACKER.onDisconnect();
        sessionCoordinator.onUnexpectedGuestDisconnect(recoverySession);
    }

    public static SharedWorldApiClient apiClient() {
        return apiClient;
    }

    /**
     * Lazily open the realtime channel the first time SharedWorld UI or a
     * session needs it. Once started it stays connected (an idle hibernated
     * socket is free server-side) and reconnects on its own forever.
     */
    public static void ensureRealtimeStarted() {
        if (!pushChannelStarted && pushChannel != null) {
            pushChannelStarted = true;
            pushChannel.start();
            return;
        }
        if (pushChannel != null) {
            // Already started: activity just began, so collapse any pending
            // long idle-backoff reconnect into an immediate attempt.
            pushChannel.nudge();
        }
    }

    public static link.sharedworld.realtime.RealtimeEvents realtimeEvents() {
        return REALTIME_EVENTS;
    }

    public static link.sharedworld.realtime.SharedWorldPushChannel pushChannel() {
        return pushChannel;
    }

    public static ExecutorService ioExecutor() {
        return IO_EXECUTOR;
    }

    public static SharedWorldHostingManager hostingManager() {
        return hostingManager;
    }

    public static SharedWorldReleaseCoordinator releaseCoordinator() {
        return releaseCoordinator;
    }

    public static SharedWorldPlaySessionTracker playSessionTracker() {
        return PLAY_SESSION_TRACKER;
    }

    public static SharedWorldSessionCoordinator sessionCoordinator() {
        return sessionCoordinator;
    }

    /**
     * A kick/ban disconnect must not auto-rejoin. Covers both orderings of
     * screen-init vs the disconnect event: flag the still-active session so
     * the event never arms recovery, drop any already-armed pending session,
     * and clear the persisted record an already-run event may have written.
     */
    public static void abandonGuestRecoveryAfterDeliberateRemoval() {
        PLAY_SESSION_TRACKER.markUserInitiatedDisconnect();
        sessionCoordinator.clearPersistedGuestRecovery();
    }

    public static SharedWorldCustomIconStore customIconStore() {
        return CUSTOM_ICON_STORE;
    }

    public static SharedWorldGuestCacheWarmer guestCacheWarmer() {
        return guestCacheWarmer;
    }

    public static boolean isE4mcInstalled() {
        return FabricLoader.getInstance().isModLoaded("e4mc");
    }

    public static void openMainScreen(Screen parent) {
        SharedWorldViewState.rememberSharedWorld();
        link.sharedworld.versioned.GuiCompat.clearFocus(parent);
        link.sharedworld.versioned.ClientCompat.setScreen(Minecraft.getInstance(), new SharedWorldScreen(parent));
    }

    public static void openMembershipRevokedScreen(Screen parent) {
        link.sharedworld.versioned.ClientCompat.setScreen(Minecraft.getInstance(), membershipRevokedScreen(parent));
    }

    public static SharedWorldErrorScreen membershipRevokedScreen(Screen parent) {
        return new SharedWorldErrorScreen(
                parent == null ? defaultSharedWorldParent() : parent,
                net.minecraft.network.chat.Component.translatable("screen.sharedworld.kicked_title"),
                net.minecraft.network.chat.Component.translatable("screen.sharedworld.kicked_detail")
        );
    }

    private static Screen defaultSharedWorldParent() {
        return new SharedWorldScreen(new JoinMultiplayerScreen(new TitleScreen()));
    }

    public static List<WorldSummaryDto> cachedWorlds() {
        return LIST_STATE.cachedWorlds();
    }

    public static List<WorldSummaryDto> orderFreshWorlds(List<WorldSummaryDto> worlds) {
        return LIST_STATE.orderFreshWorlds(worlds);
    }

    public static List<WorldSummaryDto> applyFreshWorlds(List<WorldSummaryDto> worlds) {
        return LIST_STATE.applyFreshWorlds(worlds);
    }

    public static boolean orderedWorldListsEqual(List<WorldSummaryDto> left, List<WorldSummaryDto> right) {
        return SharedWorldListComparison.orderedWorldsEqual(left, right);
    }

    public static List<WorldSummaryDto> moveCachedWorld(String worldId, int offset) {
        return LIST_STATE.moveWorld(worldId, offset);
    }

    public static boolean canMoveCachedWorld(String worldId, int offset) {
        return LIST_STATE.canMoveWorld(worldId, offset);
    }

    public static String cachedSelectedWorldId() {
        return LIST_STATE.selectedWorldId();
    }

    public static void rememberSelectedWorld(String worldId) {
        LIST_STATE.rememberSelectedWorld(worldId);
    }

    public static void rememberVanillaView() {
        SharedWorldViewState.rememberVanilla();
    }

    public static boolean shouldOpenSharedWorldByDefault() {
        return SharedWorldViewState.shouldOpenSharedWorldByDefault();
    }

    /**
     * Production glue between local host execution and the rest of the client.
     * The hosting manager itself never reaches into these singletons; every
     * cross-component effect flows through this listener.
     */
    private static final class ClientHostingEvents implements HostingEvents {
        @Override
        public void onHostStartupBegan(String worldId) {
            guestCacheWarmer.pauseWorld(worldId);
            // Hosting and guesting are mutually exclusive: any guest session
            // still tracked at this boundary is stale by definition and must
            // not survive into the startup window, where a runtime push could
            // read it as "I am a guest whose host just changed" and tear the
            // new hosting straight back down.
            PLAY_SESSION_TRACKER.clearGuestSessionForHostStartup();
        }

        @Override
        public void onHostSessionLive(String worldId, String worldName) {
            PLAY_SESSION_TRACKER.beginHostSession(worldId, worldName);
            refreshHostedPermissionLevels();
            // Difficulty is owner-managed through the Settings tab; no host
            // (owner included) changes it from the pause menu mid-session.
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null && link.sharedworld.host.SharedWorldServerIdentity.isManagedWorldServer(server)) {
                server.execute(() -> {
                    link.sharedworld.versioned.ServerSettingsCompat.setDifficultyLocked(server, true);
                    // Membership is the join authority; never let a stale
                    // whitelist (or e4mc's useWhiteList) refuse members.
                    link.sharedworld.versioned.ServerSettingsCompat.forceWhitelistOff(server);
                });
            }
        }

        @Override
        public void onHostStateCleared(String worldId) {
            if (worldId != null) {
                guestCacheWarmer.resumeWorld(worldId);
            }
            PLAY_SESSION_TRACKER.clear();
            refreshHostedPermissionLevels();
        }

        @Override
        public void onHostedMemberPermissionsChanged() {
            refreshHostedPermissionLevels();
            pruneLocalBansForMembers();
        }

        /**
         * Membership is the only ban authority on a hosted shared world, but
         * e4mc's restored vanilla /ban used to write banned-players.json on
         * whichever machine hosted. Heal those stale entries so an active
         * member is never refused by a leftover local ban; fires with the
         * grant sync, which lands before the join target is published.
         */
        private void pruneLocalBansForMembers() {
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null || !link.sharedworld.host.SharedWorldServerIdentity.isManagedWorldServer(server)) {
                return;
            }
            var grants = SharedWorldDevSessionBridge.hostedMemberGrants().values();
            server.execute(() -> {
                // Same authority rule for the whitelist: an enabled one (an
                // earlier session's /whitelist on, or e4mc's useWhiteList
                // config) would silently refuse legit members.
                link.sharedworld.versioned.ServerSettingsCompat.forceWhitelistOff(server);
                for (link.sharedworld.host.MemberCommandGrant grant : grants) {
                    try {
                        java.util.UUID uuid = java.util.UUID.fromString(
                                CanonicalPlayerIdentity.normalizeUuidWithHyphens(grant.playerUuid(), "member UUID"));
                        link.sharedworld.versioned.ServerSettingsCompat.pruneLocalBan(server, uuid, grant.playerName());
                    } catch (RuntimeException exception) {
                        LOGGER.warn("SharedWorld could not prune a stale local ban for {}", grant.playerName(), exception);
                    }
                }
            });
        }

        @Override
        public void onWorldSettingsChanged(link.sharedworld.api.SharedWorldModels.WorldSettingsDto settings) {
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null || !link.sharedworld.host.SharedWorldServerIdentity.isManagedWorldServer(server)) {
                return;
            }
            // Difficulty/gamerule setters must run on the server thread; the
            // heartbeat callback arrives on the client main thread.
            server.execute(() -> link.sharedworld.host.WorldSettingsApplier.apply(server, settings));
        }

        @Override
        public void onWorldGameRulesSnapshotRequested(java.util.function.Consumer<link.sharedworld.host.WorldSettingsReader.Snapshot> consumer) {
            var server = Minecraft.getInstance().getSingleplayerServer();
            // Same identity guard as onWorldSettingsChanged ([P9]): a vanilla
            // singleplayer server is never even observed, so the consumer
            // simply never fires and the manager keeps no baseline.
            if (server == null || !link.sharedworld.host.SharedWorldServerIdentity.isManagedWorldServer(server)) {
                return;
            }
            // Gamerule reads must run on the server thread; the consumer is
            // invoked there and the manager trampolines back itself.
            server.execute(() -> consumer.accept(link.sharedworld.host.WorldSettingsReader.readSnapshot(server)));
        }

        @Override
        public void onWorldDeleted() {
            releaseCoordinator.onWorldDeleted();
        }

        @Override
        public void onMembershipRevoked() {
            releaseCoordinator.onMembershipRevoked();
        }

        @Override
        public void onHostAuthorityLost(
                SharedWorldHostingManager.ActiveHostSession session,
                SharedWorldReleaseCoordinator.HostAuthorityLossStage stage,
                String message
        ) {
            releaseCoordinator.onHostAuthorityLost(session, stage, message);
        }

        @Override
        public boolean hasPendingReleaseRecovery(String worldId) {
            return releaseCoordinator.hasPendingReleaseRecovery(worldId);
        }

        private static void refreshHostedPermissionLevels() {
            var server = Minecraft.getInstance().getSingleplayerServer();
            // Same identity guard as onWorldSettingsChanged: permission
            // re-sends must never touch a vanilla singleplayer server ([P9]).
            if (server == null || !link.sharedworld.host.SharedWorldServerIdentity.isManagedWorldServer(server)) {
                return;
            }
            var playerList = server.getPlayerList();
            if (playerList == null) {
                return;
            }
            for (var serverPlayer : playerList.getPlayers()) {
                playerList.sendPlayerPermissionLevel(serverPlayer);
                // Re-send the command tree so .requires() gates re-evaluate against
                // the player's new permission level without a reconnect.
                server.getCommands().sendCommands(serverPlayer);
            }
        }
    }

    private static final class SharedWorldThreadFactory implements ThreadFactory {
        private int nextId = 1;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "sharedworld-io-" + nextId++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
