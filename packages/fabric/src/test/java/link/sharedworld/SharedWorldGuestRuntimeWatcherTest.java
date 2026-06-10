package link.sharedworld;

import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldGuestRuntimeWatcherTest {
    private static final SharedWorldPlaySessionTracker.ActiveWorldSession GUEST_SESSION = guestSession("world-1", 7L);

    @Test
    void healthyLiveRuntimeNeverTriggersDeparture() {
        List<String> departures = new ArrayList<>();
        AtomicInteger polls = new AtomicInteger();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                worldId -> {
                    polls.incrementAndGet();
                    return runtime("host-live", 7L);
                },
                Runnable::run,
                Runnable::run,
                (session, outcome) -> departures.add(outcome + ":" + session.worldId())
        );

        watcher.tickGuestSession(GUEST_SESSION, 1_000L);
        watcher.tickGuestSession(GUEST_SESSION, 2_000L);
        watcher.tickGuestSession(GUEST_SESSION, 6_500L);

        assertEquals(2, polls.get());
        assertEquals(List.of(), departures);
    }

    @Test
    void finalizingRuntimeTriggersOneDepartureAndStopsPolling() {
        List<String> departures = new ArrayList<>();
        AtomicInteger polls = new AtomicInteger();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                worldId -> {
                    polls.incrementAndGet();
                    return runtime("host-finalizing", 7L);
                },
                Runnable::run,
                Runnable::run,
                (session, outcome) -> departures.add(outcome + ":" + session.worldId())
        );

        watcher.tickGuestSession(GUEST_SESSION, 1_000L);
        watcher.tickGuestSession(GUEST_SESSION, 10_000L);
        watcher.tickGuestSession(GUEST_SESSION, 20_000L);

        assertEquals(1, polls.get());
        assertEquals(List.of("HOST_LEAVING:world-1"), departures);
    }

    @Test
    void epochAdvanceTriggersHostChangedDeparture() {
        List<String> departures = new ArrayList<>();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                worldId -> runtime("host-live", 8L),
                Runnable::run,
                Runnable::run,
                (session, outcome) -> departures.add(outcome + ":" + session.worldId())
        );

        watcher.tickGuestSession(GUEST_SESSION, 1_000L);

        assertEquals(List.of("HOST_CHANGED:world-1"), departures);
    }

    @Test
    void pollFailureNeverTriggersDepartureAndPollingResumes() {
        List<String> departures = new ArrayList<>();
        AtomicInteger polls = new AtomicInteger();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                worldId -> {
                    if (polls.incrementAndGet() == 1) {
                        throw new IllegalStateException("network blip");
                    }
                    return runtime("host-live", 7L);
                },
                Runnable::run,
                Runnable::run,
                (session, outcome) -> departures.add(outcome + ":" + session.worldId())
        );

        watcher.tickGuestSession(GUEST_SESSION, 1_000L);
        watcher.tickGuestSession(GUEST_SESSION, 6_000L);

        assertEquals(2, polls.get());
        assertEquals(List.of(), departures);
    }

    @Test
    void staleObservationAfterDisconnectIsIgnored() {
        List<String> departures = new ArrayList<>();
        Deque<Runnable> background = new ArrayDeque<>();
        Executor deferred = background::add;
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                worldId -> runtime("idle", 0L),
                deferred,
                Runnable::run,
                (session, outcome) -> departures.add(outcome + ":" + session.worldId())
        );

        watcher.tickGuestSession(GUEST_SESSION, 1_000L);
        watcher.onDisconnect(GUEST_SESSION);
        while (!background.isEmpty()) {
            background.pollFirst().run();
        }

        assertEquals(List.of(), departures);
    }

    @Test
    void staleObservationForPreviousWorldIsIgnored() {
        List<String> departures = new ArrayList<>();
        Deque<Runnable> background = new ArrayDeque<>();
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                worldId -> "world-1".equals(worldId) ? runtime("idle", 0L) : runtime("host-live", 3L),
                background::add,
                Runnable::run,
                (session, outcome) -> departures.add(outcome + ":" + session.worldId())
        );

        watcher.tickGuestSession(GUEST_SESSION, 1_000L);
        watcher.tickGuestSession(guestSession("world-2", 3L), 2_000L);
        while (!background.isEmpty()) {
            background.pollFirst().run();
        }

        assertEquals(List.of(), departures);
    }

    @Test
    void newGuestSessionAfterDepartureIsWatchedAgain() {
        List<String> departures = new ArrayList<>();
        Deque<WorldRuntimeStatusDto> responses = new ArrayDeque<>(List.of(
                runtime("host-finalizing", 7L),
                runtime("host-finalizing", 8L)
        ));
        SharedWorldGuestRuntimeWatcher watcher = new SharedWorldGuestRuntimeWatcher(
                worldId -> responses.removeFirst(),
                Runnable::run,
                Runnable::run,
                (session, outcome) -> departures.add(outcome + ":" + session.runtimeEpoch())
        );

        watcher.tickGuestSession(GUEST_SESSION, 1_000L);
        watcher.onDisconnect(GUEST_SESSION);
        watcher.tickGuestSession(guestSession("world-1", 8L), 2_000L);

        assertTrue(responses.isEmpty());
        assertEquals(List.of("HOST_LEAVING:7", "HOST_LEAVING:8"), departures);
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
}
