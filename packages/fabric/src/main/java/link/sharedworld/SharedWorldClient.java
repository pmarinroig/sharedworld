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
                }
        );
        REALTIME_EVENTS.subscribe(guestRuntimeWatcher);
        REALTIME_EVENTS.subscribe(guestCacheWarmer);
        REALTIME_EVENTS.subscribe(presenceManager);
        hostingManager.setRealtimeConnectedSupplier(REALTIME_EVENTS::isConnected);
        REALTIME_EVENTS.subscribe(new link.sharedworld.realtime.RealtimeEvents.Subscriber() {
            @Override
            public void onRealtimeEvent(link.sharedworld.api.SharedWorldModels.RealtimeEventDto event) {
                // A pushed settings or membership change reaches the running
                // host through an immediate heartbeat, reusing the existing
                // fetch-and-apply path (HTTP stays the authority).
                if (("settings-changed".equals(event.kind()) || "membership-changed".equals(event.kind()))
                        && event.worldId().equals(hostingManager.runningWorldId())) {
                    hostingManager.requestImmediateHeartbeat();
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
        });
        SharedWorldE4mcCompatibility.logClientInitFinished();
    }

    private static void onPlayDisconnect(
            Minecraft client,
            ClientPacketListener handler,
            SharedWorldPlaySessionTracker.ActiveWorldSession activeSession
    ) {
        presenceManager.onDisconnect(activeSession);
        guestRuntimeWatcher.onDisconnect(activeSession);
        guestCacheWarmer.onDisconnect(activeSession);
        SharedWorldReleaseCoordinator.ReleaseDisplay releaseDisplay = releaseCoordinator.onClientDisconnectReturnDisplay(client);
        SharedWorldPlaySessionTracker.RecoverySession recoverySession = PLAY_SESSION_TRACKER.onDisconnect(handler);
        SharedWorldDevSessionBridge.clearHostingSession();
        sessionCoordinator.onUnexpectedGuestDisconnect(recoverySession);
        if (releaseDisplay != null) {
            SharedWorldClientLifecycleRouter.ensureLifecycleScreenVisible(client, releaseCoordinator);
        }
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
