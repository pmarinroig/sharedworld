package link.sharedworld.integration;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncCoordinator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class BackendModHandoffIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void gracefulHandoffPreservesWorldDataAndMaterializesTheNewHostPlayer() throws Exception {
        SharedWorldApiClient hostClient = SharedWorldIntegrationBackend.apiClient(SharedWorldIntegrationBackend.HOST);
        SharedWorldApiClient guestClient = SharedWorldIntegrationBackend.apiClient(SharedWorldIntegrationBackend.GUEST);
        Path root = Files.createTempDirectory("sharedworld-handoff-integration");
        try {
            SharedWorldModels.CreateWorldResultDto created = hostClient.createWorld(
                    SharedWorldIntegrationBackend.uniqueName("Integration Handoff"),
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

            WorldSyncCoordinator hostSync = new WorldSyncCoordinator(hostClient, new ManagedWorldStore(root.resolve("host-managed")));
            hostSync.uploadSnapshot(
                    created.world().id(),
                    source,
                    SharedWorldIntegrationBackend.HOST.playerUuidHyphenated(),
                    hostAssignment.runtimeEpoch(),
                    hostAssignment.hostToken()
            );
            hostClient.heartbeatHost(created.world().id(), hostAssignment.runtimeEpoch(), hostAssignment.hostToken(), "join.example");

            hostClient.beginFinalization(created.world().id(), hostAssignment.runtimeEpoch(), hostAssignment.hostToken());
            SharedWorldModels.EnterSessionResponseDto waiting = guestClient.enterSession(created.world().id());
            assertEquals("wait", waiting.action());

            hostClient.completeFinalization(created.world().id(), hostAssignment.runtimeEpoch(), hostAssignment.hostToken());
            SharedWorldModels.HostAssignmentDto guestAssignment = observeUntilHost(guestClient, created.world().id(), waiting.waiterSessionId());
            assertEquals(SharedWorldIntegrationBackend.GUEST.playerUuid(), guestAssignment.playerUuid());

            ManagedWorldStore guestStore = new ManagedWorldStore(root.resolve("guest-managed"));
            WorldSyncCoordinator guestSync = new WorldSyncCoordinator(guestClient, guestStore);
            Path workingCopy = guestSync.ensureSynchronizedWorkingCopy(
                    created.world().id(),
                    SharedWorldIntegrationBackend.GUEST.playerUuidHyphenated()
            );

            CompoundTag level = NbtIo.readCompressed(workingCopy.resolve("level.dat"), NbtAccounter.unlimitedHeap());
            CompoundTag data = level.getCompoundOrEmpty("Data");

            assertEquals("Integration Handoff World", data.getString("LevelName").orElse(""));
            assertEquals(424242L, data.getLong("RandomSeed").orElse(0L));
            assertEquals("stone-arch", data.getString("SharedWorldStableMarker").orElse(""));
            assertEquals("guest-b", data.getCompoundOrEmpty("Player").getString("SharedWorldPlayerMarker").orElse(""));
            assertFalse(Files.exists(workingCopy.resolve("playerdata").resolve(SharedWorldIntegrationBackend.GUEST.playerUuidHyphenated() + ".dat")));
            assertTrue(Files.exists(workingCopy.resolve("playerdata").resolve(SharedWorldIntegrationBackend.HOST.playerUuidHyphenated() + ".dat")));
            assertFalse(Files.exists(workingCopy.resolve("playerdata").resolve(offlinePlayerUuid(SharedWorldIntegrationBackend.HOST.playerName()) + ".dat")));
            assertFalse(Files.exists(workingCopy.resolve("playerdata").resolve(offlinePlayerUuid(SharedWorldIntegrationBackend.GUEST.playerName()) + ".dat")));

            data.put("Player", updatedGuestPlayer("guest-b-updated"));
            level.put("Data", data);
            NbtIo.writeCompressed(level, workingCopy.resolve("level.dat"));

            guestSync.uploadSnapshot(
                    created.world().id(),
                    workingCopy,
                    SharedWorldIntegrationBackend.GUEST.playerUuidHyphenated(),
                    guestAssignment.runtimeEpoch(),
                    guestAssignment.hostToken()
            );

            WorldSyncCoordinator canonicalSync = new WorldSyncCoordinator(
                    guestClient,
                    new ManagedWorldStore(root.resolve("canonical-managed"))
            );
            Path canonicalCopy = canonicalSync.ensureCanonicalSynchronizedWorkingCopy(
                    created.world().id(),
                    SharedWorldIntegrationBackend.GUEST.playerUuidHyphenated()
            );
            CompoundTag canonicalLevel = NbtIo.readCompressed(canonicalCopy.resolve("level.dat"), NbtAccounter.unlimitedHeap());
            assertFalse(canonicalLevel.getCompoundOrEmpty("Data").contains("Player"));
            CompoundTag canonicalGuestPlayer = NbtIo.readCompressed(
                    canonicalCopy.resolve("playerdata").resolve(SharedWorldIntegrationBackend.GUEST.playerUuidHyphenated() + ".dat"),
                    NbtAccounter.unlimitedHeap()
            );
            assertEquals("guest-b-updated", canonicalGuestPlayer.getString("SharedWorldPlayerMarker").orElse(""));
            assertFalse(Files.exists(canonicalCopy.resolve("playerdata").resolve(offlinePlayerUuid(SharedWorldIntegrationBackend.HOST.playerName()) + ".dat")));
            assertFalse(Files.exists(canonicalCopy.resolve("playerdata").resolve(offlinePlayerUuid(SharedWorldIntegrationBackend.GUEST.playerName()) + ".dat")));
        } finally {
            deleteTree(root);
        }
    }

    private static SharedWorldModels.HostAssignmentDto observeUntilHost(
            SharedWorldApiClient guestClient,
            String worldId,
            String waiterSessionId
    ) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            SharedWorldModels.ObserveWaitingResponseDto observed = guestClient.observeWaiting(worldId, waiterSessionId);
            if ("restart".equals(observed.action())) {
                SharedWorldModels.EnterSessionResponseDto restarted = guestClient.enterSession(worldId);
                if ("host".equals(restarted.action()) && restarted.assignment() != null) {
                    return restarted.assignment();
                }
            }
        }
        throw new AssertionError("Guest was never promoted to host.");
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

    private static CompoundTag updatedGuestPlayer(String marker) {
        CompoundTag guestPlayer = new CompoundTag();
        guestPlayer.putString("SharedWorldPlayerMarker", marker);
        return guestPlayer;
    }

    private static UUID offlinePlayerUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
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
}
