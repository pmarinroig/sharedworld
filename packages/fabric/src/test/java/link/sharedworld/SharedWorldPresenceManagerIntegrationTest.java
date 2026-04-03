package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class SharedWorldPresenceManagerIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void delayedOlderHeartbeatCannotRestoreGuestPresenceAfterDisconnect() throws Exception {
        SharedWorldIntegrationBackend.SessionTestWorld world = SharedWorldIntegrationBackend.createIdleWorldForSessionTests("Presence Ordering");
        SharedWorldModels.EnterSessionResponseDto entered = world.hostClient().enterSession(world.world().id());
        SharedWorldModels.HostAssignmentDto assignment = entered.assignment();
        world.hostClient().heartbeatHost(world.world().id(), assignment.runtimeEpoch(), assignment.hostToken(), "join.example");

        CountDownLatch firstHeartbeatStarted = new CountDownLatch(1);
        CountDownLatch allowFirstHeartbeat = new CountDownLatch(1);
        CountDownLatch firstHeartbeatFinished = new CountDownLatch(1);
        CountDownLatch disconnectFinished = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                    update -> {
                        if (update.present() && update.presenceSequence() == 1L) {
                            firstHeartbeatStarted.countDown();
                            assertTrue(allowFirstHeartbeat.await(5, TimeUnit.SECONDS));
                        }
                        sendPresence(world.guestClient(), update);
                        if (update.present() && update.presenceSequence() == 1L) {
                            firstHeartbeatFinished.countDown();
                        } else if (!update.present()) {
                            disconnectFinished.countDown();
                        }
                    },
                    executor
            );

            manager.tickGuestSession(world.world().id(), 1_000L);
            assertTrue(firstHeartbeatStarted.await(5, TimeUnit.SECONDS));

            manager.onDisconnect(new SharedWorldPlaySessionTracker.ActiveWorldSession(
                    world.world().id(),
                    world.world().name(),
                    SharedWorldPlaySessionTracker.SessionRole.GUEST,
                    "join.example"
            ));
            assertTrue(disconnectFinished.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("HostA"), world.hostClient().listWorlds().getFirst().onlinePlayerNames());

            allowFirstHeartbeat.countDown();
            assertTrue(firstHeartbeatFinished.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("HostA"), world.hostClient().listWorlds().getFirst().onlinePlayerNames());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void eagerGuestLeaveTombstoneCanBeFollowedByDisconnectCallbackWithoutBreakingRejoin() throws Exception {
        SharedWorldIntegrationBackend.SessionTestWorld world = SharedWorldIntegrationBackend.createIdleWorldForSessionTests("Presence Leave");
        SharedWorldModels.EnterSessionResponseDto entered = world.hostClient().enterSession(world.world().id());
        SharedWorldModels.HostAssignmentDto assignment = entered.assignment();
        world.hostClient().heartbeatHost(world.world().id(), assignment.runtimeEpoch(), assignment.hostToken(), "join.example");

        SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                update -> sendPresence(world.guestClient(), update),
                Runnable::run
        );
        SharedWorldPlaySessionTracker.ActiveWorldSession session = new SharedWorldPlaySessionTracker.ActiveWorldSession(
                world.world().id(),
                world.world().name(),
                SharedWorldPlaySessionTracker.SessionRole.GUEST,
                "join.example"
        );

        manager.tickGuestSession(world.world().id(), 1_000L);
        assertEquals(List.of("Guest", "HostA"), world.hostClient().listWorlds().getFirst().onlinePlayerNames());

        manager.onDisconnect(session);
        manager.onDisconnect(session);
        assertEquals(List.of("HostA"), world.hostClient().listWorlds().getFirst().onlinePlayerNames());

        manager.tickGuestSession(world.world().id(), 2_000L);
        assertEquals(List.of("Guest", "HostA"), world.hostClient().listWorlds().getFirst().onlinePlayerNames());
    }

    private static void sendPresence(SharedWorldApiClient client, SharedWorldPresenceManager.PresenceUpdate update) throws Exception {
        client.setPresence(
                update.worldId(),
                update.present(),
                update.guestSessionEpoch(),
                update.presenceSequence()
        );
    }
}
