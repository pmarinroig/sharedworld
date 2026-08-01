package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.integration.SharedWorldConnector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class SharedWorldCoordinatorSupport {
    private SharedWorldCoordinatorSupport() {
    }

    public interface AsyncBridge {
        <T> void supply(ThrowingSupplier<T> supplier, BiConsumer<T, Throwable> completion);

        void run(ThrowingRunnable runnable, Consumer<Throwable> completion);
    }

    public interface ClientShell {
        boolean hasSingleplayerServer();

        boolean hasLevel();

        boolean isLocalServer();

        Screen currentScreen();

        void setScreen(Screen screen);

        void disconnectFromWorld();

        void openMainScreen(Screen parent);

        void openMembershipRevokedScreen(Screen parent);

        void connect(Screen parent, String joinTarget, String worldId, String worldName, long runtimeEpoch, Consumer<Throwable> failureHandler);

        void clearPlaySession();

        /** The active SharedWorld play session, or null when not in one. Test shells default to none. */
        default SharedWorldPlaySessionTracker.ActiveWorldSession currentPlaySession() {
            return null;
        }

        /**
         * Whether the open singleplayer server is running a SharedWorld-managed world.
         * Fails closed ([P9]): a shell that cannot tell must treat the world as
         * NOT managed, so lifecycle machinery never touches a vanilla world.
         * Shells that do run managed worlds must override this.
         */
        default boolean isManagedWorldOpen() {
            return false;
        }
    }

    @FunctionalInterface
    public interface Clock {
        long nowMillis();
    }

    @FunctionalInterface
    public interface PlayerIdentity {
        String currentPlayerUuid();
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static AsyncBridge asyncBridge(Executor backgroundExecutor, Consumer<Runnable> mainThreadExecutor) {
        Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
        Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        return new AsyncBridge() {
            @Override
            public <T> void supply(ThrowingSupplier<T> supplier, BiConsumer<T, Throwable> completion) {
                CompletableFuture
                        .supplyAsync(() -> {
                            try {
                                return supplier.get();
                            } catch (Exception exception) {
                                throw new RuntimeException(exception);
                            }
                        }, backgroundExecutor)
                        .whenComplete((result, error) -> mainThreadExecutor.accept(() -> completion.accept(result, error)));
            }

            @Override
            public void run(ThrowingRunnable runnable, Consumer<Throwable> completion) {
                CompletableFuture
                        .runAsync(() -> {
                            try {
                                runnable.run();
                            } catch (Exception exception) {
                                throw new RuntimeException(exception);
                            }
                        }, backgroundExecutor)
                        .whenComplete((unused, error) -> mainThreadExecutor.accept(() -> completion.accept(error)));
            }
        };
    }

    public static ClientShell liveClientShell() {
        return new ClientShell() {
            @Override
            public boolean hasSingleplayerServer() {
                return Minecraft.getInstance().hasSingleplayerServer();
            }

            @Override
            public boolean hasLevel() {
                return Minecraft.getInstance().level != null;
            }

            @Override
            public boolean isLocalServer() {
                return Minecraft.getInstance().isLocalServer();
            }

            @Override
            public Screen currentScreen() {
                return link.sharedworld.versioned.ClientCompat.currentScreen(Minecraft.getInstance());
            }

            @Override
            public void setScreen(Screen screen) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.isSameThread()) {
                    link.sharedworld.versioned.ClientCompat.setScreen(minecraft, screen);
                    return;
                }
                minecraft.execute(() -> link.sharedworld.versioned.ClientCompat.setScreen(minecraft, screen));
            }

            @Override
            public void disconnectFromWorld() {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.isSameThread()) {
                    link.sharedworld.versioned.ClientCompat.disconnectFromWorld(minecraft);
                    return;
                }
                minecraft.execute(() -> link.sharedworld.versioned.ClientCompat.disconnectFromWorld(minecraft));
            }

            @Override
            public void openMainScreen(Screen parent) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.isSameThread()) {
                    SharedWorldClient.openMainScreen(parent);
                    return;
                }
                minecraft.execute(() -> SharedWorldClient.openMainScreen(parent));
            }

            @Override
            public void openMembershipRevokedScreen(Screen parent) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.isSameThread()) {
                    SharedWorldClient.openMembershipRevokedScreen(parent);
                    return;
                }
                minecraft.execute(() -> SharedWorldClient.openMembershipRevokedScreen(parent));
            }

            @Override
            public void connect(Screen parent, String joinTarget, String worldId, String worldName, long runtimeEpoch, Consumer<Throwable> failureHandler) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.isSameThread()) {
                    SharedWorldConnector.connect(parent, joinTarget, worldId, worldName, runtimeEpoch, failureHandler);
                    return;
                }
                minecraft.execute(() -> SharedWorldConnector.connect(parent, joinTarget, worldId, worldName, runtimeEpoch, failureHandler));
            }

            @Override
            public void clearPlaySession() {
                SharedWorldClient.playSessionTracker().clear();
                SharedWorldDevSessionBridge.clearHostingSession();
            }

            @Override
            public SharedWorldPlaySessionTracker.ActiveWorldSession currentPlaySession() {
                return SharedWorldClient.playSessionTracker().currentSession();
            }

            @Override
            public boolean isManagedWorldOpen() {
                return link.sharedworld.host.SharedWorldServerIdentity.isManagedWorldServer(
                        Minecraft.getInstance().getSingleplayerServer());
            }
        };
    }

    public static Clock systemClock() {
        return System::currentTimeMillis;
    }

    public static PlayerIdentity currentPlayerIdentity() {
        return SharedWorldApiClient::currentPlayerUuid;
    }
}
