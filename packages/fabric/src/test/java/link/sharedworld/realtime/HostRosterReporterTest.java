package link.sharedworld.realtime;

import link.sharedworld.api.SharedWorldModels.RoomPlayerDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostRosterReporterTest {
    private record Sent(String worldId, long runtimeEpoch, List<RoomPlayerDto> players) {
    }

    private static final RoomPlayerDto HOST = new RoomPlayerDto("uuid-host", "Host");
    private static final RoomPlayerDto GUEST = new RoomPlayerDto("uuid-guest", "Guest");

    private final List<Sent> sent = new ArrayList<>();
    private final AtomicReference<String> worldId = new AtomicReference<>("world-1");
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private final HostRosterReporter reporter = new HostRosterReporter(
            worldId::get,
            () -> 7L,
            connected::get,
            (world, epoch, players) -> sent.add(new Sent(world, epoch, players))
    );

    @Test
    void reportsTheFullRosterOnlyWhenItChanges() {
        reporter.maybeReport(List.of(HOST), 1_000);
        reporter.maybeReport(List.of(HOST), 3_000);
        reporter.maybeReport(List.of(HOST, GUEST), 5_000);

        assertEquals(2, sent.size());
        assertEquals(new Sent("world-1", 7L, List.of(HOST)), sent.get(0));
        assertEquals(new Sent("world-1", 7L, List.of(HOST, GUEST)), sent.get(1));
    }

    @Test
    void localPollingIsThrottledToOnceASecond() {
        reporter.maybeReport(List.of(HOST), 1_000);
        reporter.maybeReport(List.of(HOST, GUEST), 1_500);
        assertEquals(1, sent.size());
        reporter.maybeReport(List.of(HOST, GUEST), 2_100);
        assertEquals(2, sent.size());
    }

    @Test
    void aReconnectResendsTheRosterEvenIfUnchanged() {
        reporter.maybeReport(List.of(HOST), 1_000);
        connected.set(false);
        reporter.maybeReport(List.of(HOST), 3_000);
        connected.set(true);
        reporter.maybeReport(List.of(HOST), 5_000);

        assertEquals(2, sent.size());
        assertEquals(List.of(HOST), sent.get(1).players());
    }

    @Test
    void nothingIsSentWithoutARunningHostingSession() {
        worldId.set(null);
        reporter.maybeReport(List.of(HOST), 1_000);
        assertEquals(0, sent.size());
    }

    @Test
    void aNewHostingSessionStartsFromAFreshBaseline() {
        reporter.maybeReport(List.of(HOST), 1_000);
        worldId.set("world-2");
        reporter.maybeReport(List.of(HOST), 5_000);

        assertEquals(2, sent.size());
        assertEquals("world-2", sent.get(1).worldId());
    }
}
