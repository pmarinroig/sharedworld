package link.sharedworld.realtime;

import link.sharedworld.api.SharedWorldModels.RealtimeEventDto;
import link.sharedworld.api.SharedWorldModels.RoomPlayerDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedWorldPushChannelTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static final class FakeTransport implements SharedWorldPushChannel.Transport {
        final ConcurrentLinkedQueue<String> sent = new ConcurrentLinkedQueue<>();
        final SharedWorldPushChannel.TransportEvents events;
        volatile boolean closed;

        FakeTransport(SharedWorldPushChannel.TransportEvents events) {
            this.events = events;
        }

        @Override
        public void sendText(String text) {
            sent.add(text);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeConnector implements SharedWorldPushChannel.TransportConnector {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<FakeTransport> current = new AtomicReference<>();
        final AtomicReference<String> lastToken = new AtomicReference<>();
        final AtomicReference<URI> lastUri = new AtomicReference<>();
        volatile int failFirstAttempts;
        final CountDownLatch connectedOnce = new CountDownLatch(1);
        final CountDownLatch connectedTwice = new CountDownLatch(2);

        @Override
        public SharedWorldPushChannel.Transport connect(
                URI uri, String bearerToken, SharedWorldPushChannel.TransportEvents events) throws Exception {
            int attempt = attempts.incrementAndGet();
            lastToken.set(bearerToken);
            lastUri.set(uri);
            if (attempt <= failFirstAttempts) {
                throw new IllegalStateException("simulated connect failure");
            }
            FakeTransport transport = new FakeTransport(events);
            current.set(transport);
            connectedOnce.countDown();
            connectedTwice.countDown();
            return transport;
        }
    }

    private static final class RecordingListener implements SharedWorldPushChannel.Listener {
        final CopyOnWriteArrayList<Boolean> connectionChanges = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<RealtimeEventDto> events = new CopyOnWriteArrayList<>();
        final CountDownLatch firstEvent = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);

        @Override
        public void onConnectionChanged(boolean connected) {
            connectionChanges.add(connected);
            if (!connected) {
                disconnected.countDown();
            }
        }

        @Override
        public void onEvent(RealtimeEventDto event) {
            events.add(event);
            firstEvent.countDown();
        }
    }

    private SharedWorldPushChannel channel(FakeConnector connector, RecordingListener listener) {
        return new SharedWorldPushChannel(
                "https://backend.example",
                connector,
                () -> "token-1",
                scheduler,
                Runnable::run,
                listener,
                50L
        );
    }

    @Test
    void endpointDerivesFromTheBackendBaseUrl() {
        assertEquals(URI.create("wss://x.example/ws"), SharedWorldPushChannel.websocketEndpoint("https://x.example"));
        assertEquals(URI.create("ws://127.0.0.1:18787/ws"), SharedWorldPushChannel.websocketEndpoint("http://127.0.0.1:18787/"));
    }

    @Test
    void connectsWithTheSessionTokenAndDispatchesEventFrames() throws Exception {
        FakeConnector connector = new FakeConnector();
        RecordingListener listener = new RecordingListener();
        SharedWorldPushChannel channel = channel(connector, listener);
        channel.start();
        assertTrue(connector.connectedOnce.await(5, TimeUnit.SECONDS));
        assertEquals("token-1", connector.lastToken.get());
        assertEquals(URI.create("wss://backend.example/ws"), connector.lastUri.get());

        connector.current.get().events.onMessage(
                "{\"v\":1,\"type\":\"event\",\"event\":{\"worldId\":\"w1\",\"kind\":\"settings-changed\"}}");
        assertTrue(listener.firstEvent.await(5, TimeUnit.SECONDS));
        assertEquals("settings-changed", listener.events.get(0).kind());
        assertEquals("w1", listener.events.get(0).worldId());
        assertEquals(List.of(true), listener.connectionChanges.subList(0, 1));
        assertTrue(channel.isConnected());
    }

    @Test
    void keepalivesFlowAndTheAckNeverReachesTheListener() throws Exception {
        FakeConnector connector = new FakeConnector();
        RecordingListener listener = new RecordingListener();
        SharedWorldPushChannel channel = channel(connector, listener);
        channel.start();
        assertTrue(connector.connectedOnce.await(5, TimeUnit.SECONDS));
        FakeTransport transport = connector.current.get();

        long deadline = System.currentTimeMillis() + 5_000;
        while (transport.sent.stream().noneMatch(SharedWorldPushChannel.KEEPALIVE_REQUEST::equals)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(transport.sent.contains(SharedWorldPushChannel.KEEPALIVE_REQUEST));
        transport.events.onMessage(SharedWorldPushChannel.KEEPALIVE_RESPONSE);
        Thread.sleep(100);
        assertEquals(0, listener.events.size());
    }

    @Test
    void aDroppedSocketReconnectsAndReportsTheGap() throws Exception {
        FakeConnector connector = new FakeConnector();
        RecordingListener listener = new RecordingListener();
        SharedWorldPushChannel channel = channel(connector, listener);
        channel.start();
        assertTrue(connector.connectedOnce.await(5, TimeUnit.SECONDS));

        connector.current.get().events.onDisconnect();
        assertTrue(listener.disconnected.await(5, TimeUnit.SECONDS));
        assertTrue(connector.connectedTwice.await(10, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 5_000;
        while (listener.connectionChanges.size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(List.of(true, false, true), listener.connectionChanges.subList(0, 3));
    }

    @Test
    void connectFailuresRetryWithoutTearingTheChannelDown() throws Exception {
        FakeConnector connector = new FakeConnector();
        connector.failFirstAttempts = 2;
        RecordingListener listener = new RecordingListener();
        SharedWorldPushChannel channel = channel(connector, listener);
        channel.start();
        assertTrue(connector.connectedOnce.await(15, TimeUnit.SECONDS));
        assertEquals(3, connector.attempts.get());
        assertTrue(channel.isConnected());
    }

    @Test
    void stopClosesTheSocketAndSuppressesReconnects() throws Exception {
        FakeConnector connector = new FakeConnector();
        RecordingListener listener = new RecordingListener();
        SharedWorldPushChannel channel = channel(connector, listener);
        channel.start();
        assertTrue(connector.connectedOnce.await(5, TimeUnit.SECONDS));
        FakeTransport transport = connector.current.get();

        channel.stop();
        assertTrue(listener.disconnected.await(5, TimeUnit.SECONDS));
        assertTrue(transport.closed);
        Thread.sleep(300);
        assertEquals(1, connector.attempts.get());
    }

    @Test
    void hostPlayersFramesCarryTheFullRosterVocabulary() throws Exception {
        FakeConnector connector = new FakeConnector();
        RecordingListener listener = new RecordingListener();
        SharedWorldPushChannel channel = channel(connector, listener);
        channel.start();
        assertTrue(connector.connectedOnce.await(5, TimeUnit.SECONDS));

        channel.sendHostPlayers("w1", 3, List.of(new RoomPlayerDto("uuid-a", "Alpha")));
        long deadline = System.currentTimeMillis() + 5_000;
        String frame = null;
        while (frame == null && System.currentTimeMillis() < deadline) {
            frame = connector.current.get().sent.stream()
                    .filter((text) -> text.contains("host-players"))
                    .findFirst()
                    .orElse(null);
            Thread.sleep(20);
        }
        assertNotNull(frame);
        assertTrue(frame.contains("\"worldId\":\"w1\""));
        assertTrue(frame.contains("\"runtimeEpoch\":3"));
        assertTrue(frame.contains("\"playerUuid\":\"uuid-a\""));
        assertTrue(frame.contains("\"v\":1"));
    }

    @Test
    void malformedFramesAreIgnored() throws Exception {
        FakeConnector connector = new FakeConnector();
        RecordingListener listener = new RecordingListener();
        SharedWorldPushChannel channel = channel(connector, listener);
        channel.start();
        assertTrue(connector.connectedOnce.await(5, TimeUnit.SECONDS));

        connector.current.get().events.onMessage("not json at all {{{");
        connector.current.get().events.onMessage("{\"v\":1,\"type\":\"mystery\"}");
        Thread.sleep(150);
        assertEquals(0, listener.events.size());
        assertTrue(channel.isConnected());
    }
}
