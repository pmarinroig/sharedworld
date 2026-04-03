package link.sharedworld.host;

import link.sharedworld.mixin.DimensionDataStorageAccessor;
import link.sharedworld.mixin.EntityStorageAccessor;
import link.sharedworld.mixin.PersistentEntitySectionManagerAccessor;
import link.sharedworld.mixin.ServerLevelEntityManagerAccessor;
import link.sharedworld.sync.ManagedWorldStore;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WorldSnapshotCaptureCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldSnapshotCaptureCoordinator.class);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SERVER_TASK_TIMEOUT = Duration.ofSeconds(30);
    private final SnapshotHooks hooks;
    private final SnapshotCopyOperation copyOperation;

    WorldSnapshotCaptureCoordinator(ManagedWorldStore worldStore) {
        this(new VanillaSnapshotHooks(), worldStore::createSnapshotStagingCopy);
    }

    WorldSnapshotCaptureCoordinator(SnapshotHooks hooks, SnapshotCopyOperation copyOperation) {
        this.hooks = hooks;
        this.copyOperation = copyOperation;
    }

    Path capture(String worldId, IntegratedServer server, CaptureMode mode) throws IOException, InterruptedException {
        return switch (mode) {
            case AUTOSAVE_WINDOW -> captureAutosaveWindow(worldId, server);
            case FINALIZATION_FLUSH -> captureFinalizationFlush(worldId, server);
        };
    }

    private Path captureAutosaveWindow(String worldId, IntegratedServer server) throws IOException, InterruptedException {
        try (AutoSaveWindow window = this.hooks.openAutosaveWindow(worldId, server)) {
            long drainStartedAt = System.nanoTime();
            window.awaitDrains();
            LOGGER.info(
                    "SharedWorld finished autosave snapshot write drain for {} in {} ms",
                    worldId,
                    (System.nanoTime() - drainStartedAt) / 1_000_000L
            );

            return copyWithLogging(worldId, CaptureMode.AUTOSAVE_WINDOW);
        }
    }

    private Path captureFinalizationFlush(String worldId, IntegratedServer server) throws IOException, InterruptedException {
        this.hooks.flushForFinalization(worldId, server);
        return copyWithLogging(worldId, CaptureMode.FINALIZATION_FLUSH);
    }

    private Path copyWithLogging(String worldId, CaptureMode mode) throws IOException {
        long stagingStartedAt = System.nanoTime();
        LOGGER.info("SharedWorld starting {} snapshot staging copy for {}", mode.logLabel(), worldId);
        Path stagingDirectory = this.copyOperation.copy(worldId);
        LOGGER.info(
                "SharedWorld finished {} snapshot staging copy for {} in {} ms",
                mode.logLabel(),
                worldId,
                (System.nanoTime() - stagingStartedAt) / 1_000_000L
        );
        return stagingDirectory;
    }

    enum CaptureMode {
        AUTOSAVE_WINDOW("autosave"),
        FINALIZATION_FLUSH("release-finalization");

        private final String logLabel;

        CaptureMode(String logLabel) {
            this.logLabel = logLabel;
        }

        String logLabel() {
            return this.logLabel;
        }
    }

    interface SnapshotHooks {
        AutoSaveWindow openAutosaveWindow(String worldId, IntegratedServer server) throws IOException, InterruptedException;

        void flushForFinalization(String worldId, IntegratedServer server) throws IOException, InterruptedException;
    }

    interface AutoSaveWindow extends AutoCloseable {
        void awaitDrains() throws IOException, InterruptedException;

        @Override
        void close() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface SnapshotCopyOperation {
        Path copy(String worldId) throws IOException;
    }

    private static final class VanillaSnapshotHooks implements SnapshotHooks {
        @Override
        public AutoSaveWindow openAutosaveWindow(String worldId, IntegratedServer server) throws IOException, InterruptedException {
            if (server == null) {
                return NoOpAutoSaveWindow.INSTANCE;
            }

            AutoSaveWindowOpenRequest openRequest = new AutoSaveWindowOpenRequest(server, worldId);
            try {
                return awaitServerTask(
                        server,
                        openRequest::open,
                        SERVER_TASK_TIMEOUT,
                        worldId,
                        "open",
                        "SharedWorld failed to open the autosave snapshot window."
                );
            } catch (IOException | InterruptedException | RuntimeException | Error exception) {
                openRequest.cancel();
                throw exception;
            }
        }

        @Override
        public void flushForFinalization(String worldId, IntegratedServer server) throws IOException, InterruptedException {
            if (server == null) {
                return;
            }

            long saveStartedAt = System.nanoTime();
            LOGGER.info("SharedWorld starting release-finalization snapshot save flush for {}", worldId);
            awaitServerTask(
                    server,
                    () -> {
                        server.saveEverything(true, true, false);
                        return null;
                    },
                    SERVER_TASK_TIMEOUT,
                    worldId,
                    "finalization-flush",
                    "SharedWorld failed while flushing the final host-release save barrier."
            );
            LOGGER.info(
                    "SharedWorld finished release-finalization snapshot save flush for {} in {} ms",
                    worldId,
                    (System.nanoTime() - saveStartedAt) / 1_000_000L
            );
        }
    }

    private static final class VanillaAutoSaveWindow implements AutoSaveWindow {
        private final IntegratedServer server;
        private final String worldId;
        private final boolean previousAutoSave;
        private final List<CompletableFuture<?>> drainFutures;
        private final List<ConsecutiveExecutor> entityDeserializerQueues;

        private VanillaAutoSaveWindow(
                IntegratedServer server,
                String worldId,
                boolean previousAutoSave,
                List<CompletableFuture<?>> drainFutures,
                List<ConsecutiveExecutor> entityDeserializerQueues
        ) {
            this.server = server;
            this.worldId = worldId;
            this.previousAutoSave = previousAutoSave;
            this.drainFutures = List.copyOf(drainFutures);
            this.entityDeserializerQueues = List.copyOf(entityDeserializerQueues);
        }

        @Override
        public void awaitDrains() throws IOException, InterruptedException {
            if (!this.drainFutures.isEmpty()) {
                awaitFuture(
                        CompletableFuture.allOf(this.drainFutures.toArray(CompletableFuture[]::new)),
                        DRAIN_TIMEOUT,
                        this.worldId,
                        "drain",
                        "SharedWorld failed while draining queued world writes for snapshot capture."
                );
            }

            if (!this.entityDeserializerQueues.isEmpty()) {
                awaitServerTask(this.server, () -> {
                    for (ConsecutiveExecutor queue : this.entityDeserializerQueues) {
                        queue.runAll();
                    }
                    return null;
                }, SERVER_TASK_TIMEOUT, this.worldId, "entity-deserializer-drain",
                        "SharedWorld failed while draining entity deserializer tasks for snapshot capture.");
            }
        }

        @Override
        public void close() throws IOException, InterruptedException {
            try {
                awaitServerTask(this.server, () -> {
                    server.setAutoSave(this.previousAutoSave);
                    LOGGER.info(
                            "SharedWorld restored vanilla autosave={} after snapshot window for {}",
                            this.previousAutoSave,
                            this.worldId
                    );
                    return null;
                }, SERVER_TASK_TIMEOUT, this.worldId, "restore",
                        "SharedWorld failed to restore vanilla autosave after snapshot capture.");
            } catch (IOException | InterruptedException | RuntimeException | Error exception) {
                LOGGER.warn("SharedWorld failed to restore vanilla autosave after snapshot capture for {}", this.worldId, exception);
                throw exception;
            }
        }
    }

    private static final class AutoSaveWindowOpenRequest {
        private final IntegratedServer server;
        private final String worldId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile boolean autoSaveDisabled;
        private volatile boolean previousAutoSave;

        private AutoSaveWindowOpenRequest(IntegratedServer server, String worldId) {
            this.server = server;
            this.worldId = worldId;
        }

        private AutoSaveWindow open() {
            if (this.cancelled.get()) {
                return NoOpAutoSaveWindow.INSTANCE;
            }

            this.previousAutoSave = this.server.isAutoSave();
            LOGGER.info("SharedWorld disabling vanilla autosave for snapshot window on {}", this.worldId);
            this.server.setAutoSave(false);
            this.autoSaveDisabled = true;

            try {
                List<CompletableFuture<?>> drainFutures = new ArrayList<>();
                List<ConsecutiveExecutor> entityDeserializerQueues = new ArrayList<>();
                for (ServerLevel level : this.server.getAllLevels()) {
                    drainFutures.add(level.getChunkSource().chunkMap.synchronize(false));

                    PersistentEntitySectionManager<?> entityManager = ((ServerLevelEntityManagerAccessor) level).sharedworld$getEntityManager();
                    EntityPersistentStorage<?> permanentStorage = ((PersistentEntitySectionManagerAccessor) entityManager).sharedworld$getPermanentStorage();
                    if (permanentStorage instanceof EntityStorage entityStorage) {
                        drainFutures.add(((EntityStorageAccessor) entityStorage).sharedworld$getSimpleRegionStorage().synchronize(false));
                        entityDeserializerQueues.add(((EntityStorageAccessor) entityStorage).sharedworld$getEntityDeserializerQueue());
                    } else {
                        permanentStorage.flush(false);
                    }

                    DimensionDataStorage dataStorage = level.getDataStorage();
                    CompletableFuture<?> pendingWriteFuture = ((DimensionDataStorageAccessor) dataStorage).sharedworld$getPendingWriteFuture();
                    if (pendingWriteFuture != null) {
                        drainFutures.add(pendingWriteFuture);
                    }
                }

                if (this.cancelled.get()) {
                    restoreAutosaveOnServerThread();
                    return NoOpAutoSaveWindow.INSTANCE;
                }

                return new VanillaAutoSaveWindow(this.server, this.worldId, this.previousAutoSave, drainFutures, entityDeserializerQueues);
            } catch (RuntimeException | Error exception) {
                restoreAutosaveOnServerThread();
                throw exception;
            }
        }

        private void cancel() {
            this.cancelled.set(true);
            if (!this.autoSaveDisabled) {
                return;
            }
            this.server.submit(() -> {
                restoreAutosaveOnServerThread();
                return null;
            });
        }

        private void restoreAutosaveOnServerThread() {
            if (!this.autoSaveDisabled) {
                return;
            }
            this.server.setAutoSave(this.previousAutoSave);
            this.autoSaveDisabled = false;
            LOGGER.info(
                    "SharedWorld restored vanilla autosave={} after abandoning snapshot window open for {}",
                    this.previousAutoSave,
                    this.worldId
            );
        }
    }

    private enum NoOpAutoSaveWindow implements AutoSaveWindow {
        INSTANCE;

        @Override
        public void awaitDrains() {
        }

        @Override
        public void close() {
        }
    }

    private static <T> T awaitServerTask(
            IntegratedServer server,
            Supplier<T> supplier,
            Duration timeout,
            String worldId,
            String phase,
            String failureMessage
    ) throws IOException, InterruptedException {
        return awaitFuture(server.submit(supplier), timeout, worldId, phase, failureMessage);
    }

    private static <T> T awaitFuture(
            CompletableFuture<T> future,
            Duration timeout,
            String worldId,
            String phase,
            String failureMessage
    ) throws IOException, InterruptedException {
        try {
            return future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            String timeoutMessage = "SharedWorld timed out after " + timeout.toSeconds()
                    + " seconds during snapshot capture phase '" + phase + "' for " + worldId + ".";
            LOGGER.warn(timeoutMessage, exception);
            throw new IOException(timeoutMessage, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(failureMessage, cause);
        }
    }
}
