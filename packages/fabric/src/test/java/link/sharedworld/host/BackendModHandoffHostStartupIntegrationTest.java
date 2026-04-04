package link.sharedworld.host;

import link.sharedworld.SharedWorldSessionCoordinator;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import link.sharedworld.support.SharedWorldCoordinatorHarness;
import link.sharedworld.sync.ManagedWorldStore;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class BackendModHandoffHostStartupIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void waitingPromotionReentersThroughBackendAndOpensTheSyncedWorldWithoutReset() throws Exception {
        SharedWorldApiClient hostClient = SharedWorldIntegrationBackend.apiClient(SharedWorldIntegrationBackend.HOST);
        SharedWorldApiClient guestClient = SharedWorldIntegrationBackend.apiClient(SharedWorldIntegrationBackend.GUEST);
        Path root = Files.createTempDirectory("sharedworld-handoff-host-startup");
        try {
            SharedWorldModels.CreateWorldResultDto created = hostClient.createWorld(
                    SharedWorldIntegrationBackend.uniqueName("Integration Handoff Startup"),
                    null,
                    null,
                    null,
                    null,
                    null
            );
            SharedWorldModels.HostAssignmentDto hostAssignment = created.initialUploadAssignment();
            assertNotNull(hostAssignment);

            SharedWorldModels.InviteCodeDto invite = hostClient.createInvite(created.world().id());
            guestClient.redeemInvite(invite.code());

            Path source = root.resolve("source");
            writeWorldFixture(source);
            new link.sharedworld.sync.WorldSyncCoordinator(hostClient, new ManagedWorldStore(root.resolve("host-managed")))
                    .uploadSnapshot(
                            created.world().id(),
                            source,
                            SharedWorldIntegrationBackend.HOST.playerUuidHyphenated(),
                            hostAssignment.runtimeEpoch(),
                            hostAssignment.hostToken()
                    );
            hostClient.heartbeatHost(created.world().id(), hostAssignment.runtimeEpoch(), hostAssignment.hostToken(), "join.example");
            hostClient.beginFinalization(created.world().id(), hostAssignment.runtimeEpoch(), hostAssignment.hostToken());

            SharedWorldCoordinatorHarness.DeterministicAsync async = new SharedWorldCoordinatorHarness.DeterministicAsync();
            SharedWorldCoordinatorHarness.FakeClock clock = new SharedWorldCoordinatorHarness.FakeClock(1_700_000_000_000L);
            SharedWorldCoordinatorHarness.FakeClientShell clientShell = new SharedWorldCoordinatorHarness.FakeClientShell();
            clientShell.setLocalServerState(false, false, false);
            RecordingWorldOpenController worldOpenController = new RecordingWorldOpenController();
            SharedWorldHostingManager hostingManager = new SharedWorldHostingManager(
                    guestClient,
                    new ManagedWorldStore(root.resolve("guest-managed")),
                    null,
                    worldOpenController,
                    new HostStartupProgressRelayController(
                            (worldId, runtimeEpoch, hostToken, progress) -> {
                            },
                            Runnable::run,
                            () -> 0L
                    )
            );
            SharedWorldSessionCoordinator coordinator = new SharedWorldSessionCoordinator(
                    new RealSessionBackend(guestClient),
                    new SharedWorldCoordinatorHarness.InMemoryRecoveryStore(),
                    async,
                    clock,
                    clientShell,
                    new SharedWorldCoordinatorHarness.FakePlayerIdentity(SharedWorldIntegrationBackend.GUEST.playerUuid()),
                    (parent, result, startupMode) -> hostingManager.beginHosting(parent, result.world(), result.latestManifest(), result.assignment(), startupMode),
                    new SharedWorldSessionCoordinator.SessionUi() {
                        @Override
                        public Screen joinError(Screen parent, Throwable error) {
                            clientShell.markNextScreen("join-error");
                            return null;
                        }

                        @Override
                        public Screen hostAcquired(Screen parent, SharedWorldModels.EnterSessionResponseDto result) {
                            clientShell.markNextScreen("host-acquired");
                            return null;
                        }

                        @Override
                        public Screen waiting(Screen parent, String worldId, String worldName, String ownerUuid) {
                            clientShell.markNextScreen("waiting");
                            return null;
                        }

                        @Override
                        public Screen uncleanShutdownWarning(Screen parent, String worldId, String worldName, SharedWorldModels.WorldRuntimeStatusDto runtimeStatus) {
                            clientShell.markNextScreen("unclean-shutdown-warning");
                            return null;
                        }

                        @Override
                        public Screen deleted(Screen parent) {
                            clientShell.markNextScreen("deleted");
                            return null;
                        }
                    }
            );

            coordinator.beginJoin(null, SharedWorldIntegrationBackend.worldSummary(created.world()));
            async.runUntilIdle();
            assertTrue(clientShell.actions().contains("setScreen:waiting"));

            hostClient.completeFinalization(created.world().id(), hostAssignment.runtimeEpoch(), hostAssignment.hostToken());

            for (int attempt = 0; attempt < 5 && worldOpenController.openedWorldDirectory == null; attempt++) {
                clock.advance(1_000L);
                coordinator.tick(null);
                async.runUntilIdle();
                waitForOpenController(worldOpenController);
            }

            assertTrue(clientShell.actions().contains("setScreen:host-acquired"));
            assertNotNull(worldOpenController.openedWorldDirectory);

            CompoundTag level = NbtIo.readCompressed(worldOpenController.openedWorldDirectory.resolve("level.dat"), NbtAccounter.unlimitedHeap());
            CompoundTag data = level.getCompoundOrEmpty("Data");

            assertEquals("Integration Handoff World", data.getString("LevelName").orElse(""));
            assertEquals(424242L, data.getLong("RandomSeed").orElse(0L));
            assertEquals("stone-arch", data.getString("SharedWorldStableMarker").orElse(""));
            assertEquals("guest-b", data.getCompoundOrEmpty("Player").getString("SharedWorldPlayerMarker").orElse(""));
        } finally {
            deleteTree(root);
        }
    }

    private static void waitForOpenController(RecordingWorldOpenController worldOpenController) throws InterruptedException {
        for (int attempt = 0; attempt < 20 && worldOpenController.openedWorldDirectory == null; attempt++) {
            Thread.sleep(50L);
        }
    }

    private static void writeWorldFixture(Path source) throws Exception {
        Files.createDirectories(source.resolve("playerdata"));

        CompoundTag hostPlayer = new CompoundTag();
        hostPlayer.putString("SharedWorldPlayerMarker", "host-a");

        CompoundTag guestPlayer = new CompoundTag();
        guestPlayer.putString("SharedWorldPlayerMarker", "guest-b");

        CompoundTag data = new CompoundTag();
        data.putString("LevelName", "Integration Handoff World");
        data.putLong("RandomSeed", 424242L);
        data.putString("SharedWorldStableMarker", "stone-arch");
        data.put("Player", hostPlayer);

        CompoundTag level = new CompoundTag();
        level.put("Data", data);
        NbtIo.writeCompressed(level, source.resolve("level.dat"));
        NbtIo.writeCompressed(guestPlayer, source.resolve("playerdata").resolve(SharedWorldIntegrationBackend.GUEST.playerUuidHyphenated() + ".dat"));
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static final class RecordingWorldOpenController implements SharedWorldHostingManager.WorldOpenController {
        private Path openedWorldDirectory;

        @Override
        public void openExistingWorld(ManagedWorldStore worldStore, SharedWorldModels.WorldSummaryDto world, Path worldDirectory) {
            this.openedWorldDirectory = worldDirectory;
        }
    }

    private static final class RealSessionBackend implements SharedWorldSessionCoordinator.SessionBackend {
        private final SharedWorldApiClient client;

        private RealSessionBackend(SharedWorldApiClient client) {
            this.client = client;
        }

        @Override
        public SharedWorldModels.EnterSessionResponseDto enterSession(String worldId, String waiterSessionId, boolean acknowledgeUncleanShutdown) throws Exception {
            return this.client.enterSession(worldId, waiterSessionId, acknowledgeUncleanShutdown);
        }

        @Override
        public SharedWorldModels.ObserveWaitingResponseDto observeWaiting(String worldId, String waiterSessionId) throws Exception {
            return this.client.observeWaiting(worldId, waiterSessionId);
        }

        @Override
        public SharedWorldModels.WorldRuntimeStatusDto cancelWaiting(String worldId, String waiterSessionId) throws Exception {
            return this.client.cancelWaiting(worldId, waiterSessionId);
        }

        @Override
        public SharedWorldModels.FinalizationActionResultDto abandonFinalization(String worldId) throws Exception {
            return this.client.abandonFinalization(worldId);
        }
    }
}
