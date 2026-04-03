package link.sharedworld.host;

import net.minecraft.client.server.IntegratedServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WorldSnapshotCaptureCoordinatorTest {
    @Test
    void autosaveWindowDrainsBeforeCopyAndRestoresWindow() throws Exception {
        List<String> calls = new ArrayList<>();
        WorldSnapshotCaptureCoordinator coordinator = new WorldSnapshotCaptureCoordinator(
                new FakeHooks(calls),
                worldId -> {
                    calls.add("copy:" + worldId);
                    return Path.of("/tmp", worldId);
                }
        );

        Path result = coordinator.capture("world-1", null, WorldSnapshotCaptureCoordinator.CaptureMode.AUTOSAVE_WINDOW);

        assertEquals(Path.of("/tmp", "world-1"), result);
        assertEquals(List.of("open:world-1", "drain", "copy:world-1", "close"), calls);
    }

    @Test
    void autosaveWindowRestoresAutosaveWhenCopyFails() {
        List<String> calls = new ArrayList<>();
        WorldSnapshotCaptureCoordinator coordinator = new WorldSnapshotCaptureCoordinator(
                new FakeHooks(calls),
                worldId -> {
                    calls.add("copy:" + worldId);
                    throw new IOException("boom");
                }
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> coordinator.capture("world-1", null, WorldSnapshotCaptureCoordinator.CaptureMode.AUTOSAVE_WINDOW)
        );

        assertEquals("boom", exception.getMessage());
        assertEquals(List.of("open:world-1", "drain", "copy:world-1", "close"), calls);
    }

    @Test
    void autosaveWindowRestoresAutosaveWhenDrainFailsAndSkipsCopy() {
        List<String> calls = new ArrayList<>();
        WorldSnapshotCaptureCoordinator coordinator = new WorldSnapshotCaptureCoordinator(
                new FakeHooks(calls, new WorldSnapshotCaptureCoordinator.AutoSaveWindow() {
                    @Override
                    public void awaitDrains() throws IOException {
                        calls.add("drain");
                        throw new IOException("timed out");
                    }

                    @Override
                    public void close() {
                        calls.add("close");
                    }
                }),
                worldId -> {
                    calls.add("copy:" + worldId);
                    return Path.of("/tmp", worldId);
                }
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> coordinator.capture("world-1", null, WorldSnapshotCaptureCoordinator.CaptureMode.AUTOSAVE_WINDOW)
        );

        assertEquals("timed out", exception.getMessage());
        assertEquals(List.of("open:world-1", "drain", "close"), calls);
    }

    @Test
    void autosaveWindowPreservesDrainFailureWhenRestoreAlsoFails() {
        List<String> calls = new ArrayList<>();
        WorldSnapshotCaptureCoordinator coordinator = new WorldSnapshotCaptureCoordinator(
                new FakeHooks(calls, new WorldSnapshotCaptureCoordinator.AutoSaveWindow() {
                    @Override
                    public void awaitDrains() throws IOException {
                        calls.add("drain");
                        throw new IOException("drain failed");
                    }

                    @Override
                    public void close() throws IOException {
                        calls.add("close");
                        throw new IOException("restore failed");
                    }
                }),
                worldId -> {
                    calls.add("copy:" + worldId);
                    return Path.of("/tmp", worldId);
                }
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> coordinator.capture("world-1", null, WorldSnapshotCaptureCoordinator.CaptureMode.AUTOSAVE_WINDOW)
        );

        assertEquals("drain failed", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("restore failed", exception.getSuppressed()[0].getMessage());
        assertEquals(List.of("open:world-1", "drain", "close"), calls);
    }

    @Test
    void finalizationFlushPathDoesNotOpenAutosaveWindow() throws Exception {
        List<String> calls = new ArrayList<>();
        WorldSnapshotCaptureCoordinator coordinator = new WorldSnapshotCaptureCoordinator(
                new FakeHooks(calls),
                worldId -> {
                    calls.add("copy:" + worldId);
                    return Path.of("/tmp", worldId);
                }
        );

        Path result = coordinator.capture("world-1", null, WorldSnapshotCaptureCoordinator.CaptureMode.FINALIZATION_FLUSH);

        assertEquals(Path.of("/tmp", "world-1"), result);
        assertEquals(List.of("flush:world-1", "copy:world-1"), calls);
    }

    @Test
    void finalizationFlushFailureSkipsCopy() {
        List<String> calls = new ArrayList<>();
        WorldSnapshotCaptureCoordinator coordinator = new WorldSnapshotCaptureCoordinator(
                new WorldSnapshotCaptureCoordinator.SnapshotHooks() {
                    @Override
                    public WorldSnapshotCaptureCoordinator.AutoSaveWindow openAutosaveWindow(String worldId, IntegratedServer server) {
                        calls.add("open:" + worldId);
                        return new WorldSnapshotCaptureCoordinator.AutoSaveWindow() {
                            @Override
                            public void awaitDrains() {
                            }

                            @Override
                            public void close() {
                            }
                        };
                    }

                    @Override
                    public void flushForFinalization(String worldId, IntegratedServer server) throws IOException, InterruptedException {
                        calls.add("flush:" + worldId);
                        throw new IOException("flush failed");
                    }
                },
                worldId -> {
                    calls.add("copy:" + worldId);
                    return Path.of("/tmp", worldId);
                }
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> coordinator.capture("world-1", null, WorldSnapshotCaptureCoordinator.CaptureMode.FINALIZATION_FLUSH)
        );

        assertEquals("flush failed", exception.getMessage());
        assertEquals(List.of("flush:world-1"), calls);
    }

    private static final class FakeHooks implements WorldSnapshotCaptureCoordinator.SnapshotHooks {
        private final List<String> calls;
        private final WorldSnapshotCaptureCoordinator.AutoSaveWindow window;

        private FakeHooks(List<String> calls) {
            this(calls, null);
        }

        private FakeHooks(List<String> calls, WorldSnapshotCaptureCoordinator.AutoSaveWindow window) {
            this.calls = calls;
            this.window = window;
        }

        @Override
        public WorldSnapshotCaptureCoordinator.AutoSaveWindow openAutosaveWindow(String worldId, IntegratedServer server) {
            this.calls.add("open:" + worldId);
            if (this.window != null) {
                return this.window;
            }
            return new WorldSnapshotCaptureCoordinator.AutoSaveWindow() {
                @Override
                public void awaitDrains() {
                    calls.add("drain");
                }

                @Override
                public void close() {
                    calls.add("close");
                }
            };
        }

        @Override
        public void flushForFinalization(String worldId, IntegratedServer server) {
            this.calls.add("flush:" + worldId);
        }
    }
}
