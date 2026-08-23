package link.sharedworld;

import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Socket-native watcher: no polling; observations arrive as pushed runtime
 * payloads or merged-beat runtime slices, all funneled through the same
 * departure-verdict logic. These tests pin the semantics the old poll suite
 * pinned: departure only on authoritative observations, one-shot per
 * session, stale/foreign observations dropped, rejected dispatches retried.
 */
final class SharedWorldGuestRuntimeWatcherTest {
    private static final SharedWorldPlaySessionTracker.ActiveWorldSession GUEST_SESSION = guestSession("world-1", 7L);

    @Test
    void healthyLiveObservationsNeverTriggerDeparture() {
        List<String> departures = new ArrayList<>();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                (session, outcome) -> {
                    departures.add(outcome + ":" + session.worldId());
                    return true;
                }
        );

        watcher.observeForSession(GUEST_SESSION, "world-1", runtime("host-live", 7L));
        watcher.observeForSession(GUEST_SESSION, "world-1", runtime("host-live", 7L));

        assertEquals(List.of(), departures);
    }

    @Test
    void aMissingStatusNeverTriggersDeparture() {
        List<String> departures = new ArrayList<>();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                (session, outcome) -> {
                    departures.add(outcome + ":" + session.worldId());
                    return true;
                }
        );

        watcher.observeForSession(GUEST_SESSION, "world-1", null);

        assertEquals(List.of(), departures);
    }

    @Test
    void finalizingObservationTriggersExactlyOneDeparture() {
        List<String> departures = new ArrayList<>();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                (session, outcome) -> {
                    departures.add(outcome + ":" + session.worldId());
                    return true;
                }
        );

        watcher.observeForSession(GUEST_SESSION, "world-1", runtime("host-finalizing", 7L));
        watcher.observeForSession(GUEST_SESSION, "world-1", runtime("host-finalizing", 7L));
        watcher.observeForSession(GUEST_SESSION, "world-1", runtime("idle", 0L));

        assertEquals(List.of("HOST_LEAVING:world-1"), departures);
    }

    @Test
    void aFirstEverPushIsActedOnWithoutAnyPriorTick() {
        // The bootstrap pin: pre-0.4.1 the watcher ignored pushes until a
        // poll had assigned activeWorldId; the session is the authority now.
        List<String> departures = new ArrayList<>();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                (session, outcome) -> {
                    departures.add(outcome + ":" + session.worldId());
                    return true;
                }
        );

        watcher.observeForSession(GUEST_SESSION, "world-1", runtime("host-live", 8L));

        assertEquals(List.of("HOST_CHANGED:world-1"), departures);
    }

    @Test
    void observationsForAnotherWorldAreDropped() {
        List<String> departures = new ArrayList<>();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                (session, outcome) -> {
                    departures.add(outcome + ":" + session.worldId());
                    return true;
                }
        );

        // The session moved to world-2; a stale world-1 "idle" arrives late.
        SharedWorldPlaySessionTracker.ActiveWorldSession second = guestSession("world-2", 3L);
        watcher.observeForSession(second, "world-2", runtime2("host-live", 3L));
        watcher.observeForSession(second, "world-1", runtime("idle", 0L));

        assertEquals(List.of(), departures);
    }

    @Test
    void aNewGuestSessionAfterDepartureIsWatchedAgain() {
        List<String> departures = new ArrayList<>();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                (session, outcome) -> {
                    departures.add(outcome + ":" + session.runtimeEpoch());
                    return true;
                }
        );

        watcher.observeForSession(GUEST_SESSION, "world-1", runtime("host-finalizing", 7L));
        watcher.onDisconnect(GUEST_SESSION);
        SharedWorldPlaySessionTracker.ActiveWorldSession next = guestSession("world-1", 8L);
        watcher.observeForSession(next, "world-1", runtime("host-finalizing", 8L));

        assertEquals(List.of("HOST_LEAVING:7", "HOST_LEAVING:8"), departures);
    }

    @Test
    void aRejectedDepartureDispatchIsRetriedOnALaterObservation() {
        List<String> departures = new ArrayList<>();
        AtomicInteger dispatches = new AtomicInteger();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                (session, outcome) -> {
                    // First dispatch is rejected (e.g. another join flow was
                    // active); the watcher must not consume its one-shot.
                    if (dispatches.incrementAndGet() == 1) {
                        return false;
                    }
                    departures.add(outcome + ":" + session.worldId());
                    return true;
                }
        );

        watcher.observeForSession(GUEST_SESSION, "world-1", runtime("idle", 0L));
        watcher.observeForSession(GUEST_SESSION, "world-1", runtime("idle", 0L));
        watcher.observeForSession(GUEST_SESSION, "world-1", runtime("idle", 0L));

        assertEquals(2, dispatches.get());
        assertEquals(List.of("HOST_GONE:world-1"), departures);
    }

    private static SharedWorldPlaySessionTracker.ActiveWorldSession guestSession(String worldId, long runtimeEpoch) {
        return new SharedWorldPlaySessionTracker.ActiveWorldSession(
                worldId,
                "World",
                SharedWorldPlaySessionTracker.SessionRole.GUEST,
                "join.example",
                runtimeEpoch
        );
    }

    private static WorldRuntimeStatusDto runtime(String phase, long runtimeEpoch) {
        return new WorldRuntimeStatusDto(
                "world-1",
                phase,
                runtimeEpoch,
                "player-host",
                "Host",
                null,
                null,
                "join.example",
                null,
                null,
                null,
                null
        );
    }

    private static WorldRuntimeStatusDto runtime2(String phase, long runtimeEpoch) {
        return new WorldRuntimeStatusDto(
                "world-2",
                phase,
                runtimeEpoch,
                "player-host",
                "Host",
                null,
                null,
                "join.example",
                null,
                null,
                null,
                null
        );
    }
}
