package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldPresenceManagerTest {
    private static final SharedWorldPlaySessionTracker.ActiveWorldSession GUEST_SESSION =
            new SharedWorldPlaySessionTracker.ActiveWorldSession(
                    "world-1",
                    "World One",
                    SharedWorldPlaySessionTracker.SessionRole.GUEST,
                    "join.target"
            );

    @Test
    void initialGuestJoinSendsPresenceTrue() {
        List<String> calls = new ArrayList<>();
        SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                update -> calls.add(format(update)),
                Runnable::run
        );

        manager.tickGuestSession("world-1", 1_000L);

        assertEquals(List.of("world-1:true:1:1"), calls);
    }

    @Test
    void repeatedTicksWithinIntervalDoNotSendDuplicates() {
        List<String> calls = new ArrayList<>();
        SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                update -> calls.add(format(update)),
                Runnable::run
        );

        manager.tickGuestSession("world-1", 1_000L);
        manager.tickGuestSession("world-1", 5_000L);
        manager.tickGuestSession("world-1", 15_999L);
        manager.tickGuestSession("world-1", 16_000L);

        assertEquals(List.of("world-1:true:1:1", "world-1:true:1:2"), calls);
    }

    @Test
    void disconnectWithoutInflightRequestSendsPresenceFalse() {
        List<String> calls = new ArrayList<>();
        SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                update -> calls.add(format(update)),
                Runnable::run
        );

        manager.onDisconnect(GUEST_SESSION);

        assertEquals(List.of("world-1:false:1:1"), calls);
    }

    @Test
    void disconnectDuringInflightHeartbeatSchedulesANewerPresenceFalseImmediately() {
        List<SharedWorldPresenceManager.PresenceUpdate> calls = new ArrayList<>();
        AtomicReference<SharedWorldPresenceManager> managerRef = new AtomicReference<>();
        SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                update -> {
                    calls.add(update);
                    if (update.present()) {
                        managerRef.get().onDisconnect(GUEST_SESSION);
                    }
                },
                Runnable::run
        );
        managerRef.set(manager);

        manager.tickGuestSession("world-1", 1_000L);

        assertEquals(2, calls.size());
        assertEquals("world-1", calls.get(0).worldId());
        assertTrue(calls.get(0).present());
        assertEquals("world-1", calls.get(1).worldId());
        assertFalse(calls.get(1).present());
        assertEquals(calls.get(0).guestSessionEpoch(), calls.get(1).guestSessionEpoch());
        assertTrue(calls.get(1).presenceSequence() > calls.get(0).presenceSequence());
    }

    @Test
    void newGuestSessionGetsANewEpoch() {
        List<String> calls = new ArrayList<>();
        SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                update -> calls.add(format(update)),
                Runnable::run
        );

        manager.tickGuestSession("world-1", 1_000L);
        manager.onDisconnect(GUEST_SESSION);
        manager.tickGuestSession("world-1", 2_000L);

        assertEquals(List.of("world-1:true:1:1", "world-1:false:1:2", "world-1:true:2:1"), calls);
    }

    @Test
    void failedRequestDoesNotBlockLaterPresenceUpdates() {
        List<String> calls = new ArrayList<>();
        SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                update -> {
                    calls.add(format(update));
                    if (update.present()) {
                        throw new IllegalStateException("boom");
                    }
                },
                Runnable::run
        );

        manager.tickGuestSession("world-1", 1_000L);
        manager.onDisconnect(GUEST_SESSION);

        assertEquals(List.of("world-1:true:1:1", "world-1:false:1:2"), calls);
    }

    @Test
    void membershipRevokedTriggersRevocationHandler() {
        List<String> calls = new ArrayList<>();
        List<String> forcedExits = new ArrayList<>();
        SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                update -> {
                    calls.add(format(update));
                    throw new SharedWorldApiClient.SharedWorldApiException("membership_revoked", "Removed", 403);
                },
                Runnable::run,
                (reason, worldId) -> forcedExits.add(reason + ":" + worldId)
        );

        manager.tickGuestSession("world-1", 1_000L);

        assertEquals(List.of("world-1:true:1:1"), calls);
        assertEquals(List.of("REVOKED:world-1"), forcedExits);
    }

    @Test
    void membershipRevokedDoesNotQueueAdditionalPresenceFlushes() {
        AtomicInteger sends = new AtomicInteger();
        SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                update -> {
                    sends.incrementAndGet();
                    throw new SharedWorldApiClient.SharedWorldApiException("membership_revoked", "Removed", 403);
                },
                Runnable::run,
                (reason, worldId) -> {
                }
        );

        manager.tickGuestSession("world-1", 1_000L);

        assertTrue(sends.get() == 1);
    }

    @Test
    void deletedWorldTriggersDeletedExitHandler() {
        List<String> forcedExits = new ArrayList<>();
        SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                update -> {
                    throw new SharedWorldApiClient.SharedWorldApiException("world_not_found", "Deleted", 404);
                },
                Runnable::run,
                (reason, worldId) -> forcedExits.add(reason + ":" + worldId)
        );

        manager.tickGuestSession("world-1", 1_000L);

        assertEquals(List.of("DELETED:world-1"), forcedExits);
    }

    @Test
    void forcedExitIgnoresDelayedResponseForDifferentGuestWorld() {
        SharedWorldPlaySessionTracker.ActiveWorldSession currentSession =
                new SharedWorldPlaySessionTracker.ActiveWorldSession(
                        "world-2",
                        "World Two",
                        SharedWorldPlaySessionTracker.SessionRole.GUEST,
                        "join.target"
                );

        assertFalse(SharedWorldPresenceManager.matchesForcedGuestSession(currentSession, "world-1"));
    }

    @Test
    void forcedExitMatchesOnlyTheCurrentGuestWorld() {
        SharedWorldPlaySessionTracker.ActiveWorldSession currentSession =
                new SharedWorldPlaySessionTracker.ActiveWorldSession(
                        "world-1",
                        "World One",
                        SharedWorldPlaySessionTracker.SessionRole.GUEST,
                        "join.target"
                );

        assertTrue(SharedWorldPresenceManager.matchesForcedGuestSession(currentSession, "world-1"));
    }

    @Test
    void delayedFailureFromOldEpochDoesNotTriggerForcedExitForNewSession() {
        List<String> forcedExits = new ArrayList<>();
        List<SharedWorldPresenceManager.PresenceUpdate> captured = new ArrayList<>();
        List<Runnable> queued = new ArrayList<>();
        SharedWorldPresenceManager manager = new SharedWorldPresenceManager(
                update -> {
                    captured.add(update);
                    if (update.guestSessionEpoch() == 1L) {
                        throw new SharedWorldApiClient.SharedWorldApiException("membership_revoked", "Removed", 403);
                    }
                },
                queued::add,
                (reason, worldId) -> forcedExits.add(reason + ":" + worldId)
        );

        manager.tickGuestSession("world-1", 1_000L);
        manager.onDisconnect(GUEST_SESSION);
        manager.tickGuestSession("world-1", 2_000L);

        assertEquals(3, queued.size());
        queued.get(1).run();
        queued.get(2).run();
        queued.get(0).run();

        assertEquals(3, captured.size());
        assertEquals(List.of(), forcedExits);
    }

    private static String format(SharedWorldPresenceManager.PresenceUpdate update) {
        return update.worldId() + ":" + update.present() + ":" + update.guestSessionEpoch() + ":" + update.presenceSequence();
    }
}
