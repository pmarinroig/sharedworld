package link.sharedworld;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SharedWorldPlaySessionTrackerTest {
    @Test
    void staleDisconnectForPreviousConnectionDoesNotClearNewPendingJoin() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object oldHandler = new Object();
        Object newHandler = new Object();

        tracker.beginGuestConnect("world-old", "Old World", "old.example");
        tracker.onPlayJoin(oldHandler);
        assertNotNull(tracker.currentSession(oldHandler));

        tracker.beginGuestConnect("world-new", "New World", "new.example");

        assertNull(tracker.currentSession(oldHandler));
        assertNull(tracker.onDisconnect(oldHandler));

        tracker.onPlayJoin(newHandler);

        SharedWorldPlaySessionTracker.ActiveWorldSession newSession = tracker.currentSession(newHandler);
        assertNotNull(newSession);
        assertEquals("world-new", newSession.worldId());
        assertEquals("new.example", newSession.joinTarget());
        assertNull(tracker.consumePendingRecovery());
    }

    @Test
    void matchingGuestDisconnectStillProducesRecovery() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object handler = new Object();

        tracker.beginGuestConnect("world-1", "World", "join.example");
        tracker.onPlayJoin(handler);

        SharedWorldPlaySessionTracker.RecoverySession recoverySession = tracker.onDisconnect(handler);

        assertNotNull(recoverySession);
        assertEquals("world-1", recoverySession.worldId());
        assertEquals("World", recoverySession.worldName());
        assertEquals("join.example", recoverySession.previousJoinTarget());
        assertNull(tracker.currentSession(handler));
    }

    @Test
    void staleDisconnectBeforeHostJoinDoesNotClearNewHostSession() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object oldHandler = new Object();
        Object newHandler = new Object();

        tracker.onPlayJoin(newHandler);
        tracker.beginHostSession("world-new", "New World");

        assertNull(tracker.onDisconnect(oldHandler));

        SharedWorldPlaySessionTracker.ActiveWorldSession hostSession = tracker.currentSession(newHandler);
        assertNotNull(hostSession);
        assertEquals("world-new", hostSession.worldId());
        assertEquals(SharedWorldPlaySessionTracker.SessionRole.HOST, hostSession.role());
    }

    @Test
    void hostSessionBindsToEarlierJoinHandlerAndClearsOnMatchingDisconnect() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object handler = new Object();

        tracker.onPlayJoin(handler);
        tracker.beginHostSession("world-host", "Host World");

        SharedWorldPlaySessionTracker.ActiveWorldSession hostSession = tracker.currentSession(handler);
        assertNotNull(hostSession);
        assertEquals("world-host", hostSession.worldId());
        assertEquals(SharedWorldPlaySessionTracker.SessionRole.HOST, hostSession.role());

        assertNull(tracker.onDisconnect(handler));
        assertNull(tracker.currentSession(handler));
    }
}
