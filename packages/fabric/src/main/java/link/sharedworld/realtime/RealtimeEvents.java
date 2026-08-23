package link.sharedworld.realtime;

import link.sharedworld.api.SharedWorldModels.RealtimeEventDto;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Main-thread dispatch hub for pushed realtime events. The channel feeds it
 * (already on the main thread); consumers subscribe once at init. Connection
 * state is exposed so consumers stretch their polling fallbacks while
 * connected; push accelerates polling, it never replaces its correctness.
 */
public final class RealtimeEvents {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("sharedworld");

    public interface Subscriber {
        default void onRealtimeEvent(RealtimeEventDto event) {
        }

        default void onRealtimeConnectionChanged(boolean connected) {
        }
    }

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong eventCount = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean connected;

    /**
     * Monotonic count of dispatched events. Screens compare it against the
     * value they saw at their last refresh instead of subscribing; no
     * per-screen listener lifecycle to leak.
     */
    public long eventCount() {
        return eventCount.get();
    }

    public void subscribe(Subscriber subscriber) {
        subscribers.add(subscriber);
    }

    public boolean isConnected() {
        return connected;
    }

    /** Called on the main thread by the channel listener. */
    public void dispatchEvent(RealtimeEventDto event) {
        if (event == null || event.worldId() == null || event.kind() == null) {
            return;
        }
        eventCount.incrementAndGet();
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.onRealtimeEvent(event);
            } catch (RuntimeException error) {
                // One consumer's bug must not starve the others.
                LOGGER.warn("SharedWorld realtime subscriber failed on event {}", event.kind(), error);
            }
        }
    }

    /** Called on the main thread by the channel listener. */
    public void dispatchConnectionChanged(boolean nowConnected) {
        this.connected = nowConnected;
        LOGGER.debug("SharedWorld realtime connection change dispatch: connected={}, subscribers={}", nowConnected, subscribers.size());
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.onRealtimeConnectionChanged(nowConnected);
            } catch (RuntimeException error) {
                LOGGER.warn("SharedWorld realtime subscriber failed on connection change", error);
            }
        }
    }
}
