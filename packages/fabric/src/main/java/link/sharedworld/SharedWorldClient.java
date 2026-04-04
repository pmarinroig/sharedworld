package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
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
    private static SharedWorldGuestCacheWarmer guestCacheWarmer;
    private static SharedWorldSessionCoordinator sessionCoordinator;
    private static final SharedWorldPlaySessionTracker PLAY_SESSION_TRACKER = new SharedWorldPlaySessionTracker();

    @Override
    public void onInitializeClient() {
        RuntimePlayerIdentity.resolveBackendPlayerUuidWithHyphens(Minecraft.getInstance().getUser());
        apiClient = new SharedWorldApiClient(SharedWorldClientConfigStore.shared().resolvedBackendBaseUrl());
        HostPlayerIdentity hostPlayerIdentity = apiClient::authenticatedWorldPlayerUuidWithHyphens;
        hostingManager = new SharedWorldHostingManager(apiClient);
        releaseCoordinator = new SharedWorldReleaseCoordinator(apiClient, hostingManager);
        presenceManager = new SharedWorldPresenceManager(apiClient);
        guestCacheWarmer = new SharedWorldGuestCacheWarmer(apiClient, hostPlayerIdentity);
        sessionCoordinator = new SharedWorldSessionCoordinator(apiClient);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            hostingManager.tick(client);
            releaseCoordinator.tick(client);
            presenceManager.tick(client);
            guestCacheWarmer.tick(client);
            sessionCoordinator.tick(client);
            if (SharedWorldClientLifecycleRouter.routeTick(client, releaseCoordinator)) {
                return;
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            PLAY_SESSION_TRACKER.onPlayJoin(handler);
            sessionCoordinator.onGuestSessionJoined(PLAY_SESSION_TRACKER.currentSession(handler));
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
    }

    private static void onPlayDisconnect(
            Minecraft client,
            ClientPacketListener handler,
            SharedWorldPlaySessionTracker.ActiveWorldSession activeSession
    ) {
        presenceManager.onDisconnect(activeSession);
        guestCacheWarmer.onDisconnect(activeSession);
        SharedWorldReleaseCoordinator.ReleaseDisplay releaseDisplay = releaseCoordinator.onClientDisconnectReturnDisplay(client);
        SharedWorldPlaySessionTracker.RecoverySession recoverySession = PLAY_SESSION_TRACKER.onDisconnect(handler);
        SharedWorldDevSessionBridge.clear();
        sessionCoordinator.onUnexpectedGuestDisconnect(recoverySession);
        if (releaseDisplay != null) {
            SharedWorldClientLifecycleRouter.ensureLifecycleScreenVisible(client, releaseCoordinator);
        }
    }

    public static SharedWorldApiClient apiClient() {
        return apiClient;
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

    public static SharedWorldPresenceManager presenceManager() {
        return presenceManager;
    }

    public static boolean isE4mcInstalled() {
        return FabricLoader.getInstance().isModLoaded("e4mc");
    }

    public static void openMainScreen(Screen parent) {
        SharedWorldViewState.rememberSharedWorld();
        parent.clearFocus();
        Minecraft.getInstance().setScreen(new SharedWorldScreen(parent));
    }

    public static void openMembershipRevokedScreen(Screen parent) {
        Minecraft.getInstance().setScreen(membershipRevokedScreen(parent));
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
