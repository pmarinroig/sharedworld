package link.sharedworld;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldPlaySessionTrackerTest {
    @Test
    void staleDisconnectForPreviousConnectionDoesNotClearNewPendingJoin() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object oldHandler = new Object();
        Object newHandler = new Object();

        tracker.beginGuestConnect("world-old", "Old World", "old.example", 4L);
        tracker.onPlayJoin(oldHandler);
        assertNotNull(tracker.currentSession(oldHandler));

        tracker.beginGuestConnect("world-new", "New World", "new.example", 5L);

        assertNull(tracker.currentSession(oldHandler));
        assertNull(tracker.onDisconnect(oldHandler));

        tracker.onPlayJoin(newHandler);

        SharedWorldPlaySessionTracker.ActiveWorldSession newSession = tracker.currentSession(newHandler);
        assertNotNull(newSession);
        assertEquals("world-new", newSession.worldId());
        assertEquals("new.example", newSession.joinTarget());
        assertEquals(5L, newSession.runtimeEpoch());
        assertNull(tracker.consumePendingRecovery());
    }

    @Test
    void matchingGuestDisconnectStillProducesRecovery() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object handler = new Object();

        tracker.beginGuestConnect("world-1", "World", "join.example", 7L);
        tracker.onPlayJoin(handler);

        SharedWorldPlaySessionTracker.RecoverySession recoverySession = tracker.onDisconnect(handler);

        assertNotNull(recoverySession);
        assertEquals("world-1", recoverySession.worldId());
        assertEquals("World", recoverySession.worldName());
        assertEquals("join.example", recoverySession.previousJoinTarget());
        assertEquals(7L, recoverySession.runtimeEpoch());
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
    void pendingGuestSessionIsNotAdoptedForLocalServerJoin() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object handler = new Object();

        tracker.beginGuestConnect("world-1", "World", "join.example", 7L);
        tracker.onPlayJoin(handler, true);

        assertNull(tracker.currentSession(handler));
        // The abandoned pending session must be fully gone: a later remote join adopts nothing.
        Object laterHandler = new Object();
        tracker.onPlayJoin(laterHandler, false);
        assertNull(tracker.currentSession(laterHandler));
    }

    @Test
    void pendingGuestSessionExpiresAfterTtl() {
        AtomicLong now = new AtomicLong(1_000L);
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker(now::get);
        Object handler = new Object();

        tracker.beginGuestConnect("world-1", "World", "join.example", 7L);
        now.addAndGet(SharedWorldPlaySessionTracker.PENDING_GUEST_SESSION_TTL_MS + 1);
        tracker.onPlayJoin(handler, false);

        assertNull(tracker.currentSession(handler));
    }

    @Test
    void pendingGuestSessionInsideTtlIsAdoptedForRemoteJoin() {
        AtomicLong now = new AtomicLong(1_000L);
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker(now::get);
        Object handler = new Object();

        tracker.beginGuestConnect("world-1", "World", "join.example", 7L);
        now.addAndGet(SharedWorldPlaySessionTracker.PENDING_GUEST_SESSION_TTL_MS - 1);
        tracker.onPlayJoin(handler, false);

        SharedWorldPlaySessionTracker.ActiveWorldSession session = tracker.currentSession(handler);
        assertNotNull(session);
        assertEquals("world-1", session.worldId());
        assertEquals(SharedWorldPlaySessionTracker.SessionRole.GUEST, session.role());
    }

    @Test
    void hasPendingRecoveryReflectsGuestSessionLifecycle() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object handler = new Object();

        assertFalse(tracker.hasPendingRecovery());

        tracker.beginGuestConnect("world-1", "World", "join.example", 7L);
        assertFalse(tracker.hasPendingRecovery());
        tracker.onPlayJoin(handler, false);
        // Active guest session counts: the disconnect screen can be initialized before the
        // disconnect event has produced the recovery session.
        assertTrue(tracker.hasPendingRecovery());

        assertNotNull(tracker.onDisconnect(handler));
        assertTrue(tracker.hasPendingRecovery());
        assertNotNull(tracker.consumePendingRecovery());
        assertFalse(tracker.hasPendingRecovery());
    }

    @Test
    void hasPendingRecoveryFalseForHostSessionAndVanillaDisconnect() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object handler = new Object();

        tracker.onPlayJoin(handler, false);
        assertFalse(tracker.hasPendingRecovery());

        tracker.beginHostSession("world-host", "Host World");
        assertFalse(tracker.hasPendingRecovery());

        assertNull(tracker.onDisconnect(handler));
        assertFalse(tracker.hasPendingRecovery());
    }

    /**
     * The 26.x dialtone story: a manual guest leave whose PLAY disconnect
     * event never fires (the relay channel stays open) must not survive into
     * the next session. The new connection reaching PLAY is itself proof the
     * old session is dead — it gets evicted right there, before the hosting
     * registration, so an unkeyed reader (the runtime watcher) can never see
     * the stale guest session inside the new hosting's startup window.
     */
    @Test
    void staleGuestSessionIsEvictedWhenANewConnectionReachesPlay() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object guestHandler = new Object();
        Object localHandler = new Object();

        tracker.beginGuestConnect("world-1", "World", "join.example", 10L);
        tracker.onPlayJoin(guestHandler, false);
        assertNotNull(tracker.currentSession());

        // No onDisconnect: the disconnect event was lost. The player rejoins
        // and is elected host; the integrated server's PLAY join arrives.
        tracker.onPlayJoin(localHandler, true);

        assertNull(tracker.currentSession());
        tracker.beginHostSession("world-1", "World");
        SharedWorldPlaySessionTracker.ActiveWorldSession session = tracker.currentSession();
        assertNotNull(session);
        assertEquals(SharedWorldPlaySessionTracker.SessionRole.HOST, session.role());

        // The lost disconnect finally arriving must not touch the new session.
        assertNull(tracker.onDisconnect(guestHandler));
        assertNotNull(tracker.currentSession());
    }

    @Test
    void staleGuestSessionIsEvictedWhenAHostStartupBegins() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object handler = new Object();

        tracker.beginGuestConnect("world-1", "World", "join.example", 10L);
        tracker.onPlayJoin(handler, false);
        assertNotNull(tracker.currentSession());

        tracker.clearGuestSessionForHostStartup();

        assertNull(tracker.currentSession());
    }

    @Test
    void hostStartupEvictionPreservesPendingRecoveryAndHostSessions() {
        SharedWorldPlaySessionTracker tracker = new SharedWorldPlaySessionTracker();
        Object handler = new Object();

        tracker.beginGuestConnect("world-1", "World", "join.example", 10L);
        tracker.onPlayJoin(handler, false);
        assertNotNull(tracker.onDisconnect(handler));

        tracker.clearGuestSessionForHostStartup();
        // The unexpected-disconnect recovery belongs to its own flow.
        assertNotNull(tracker.consumePendingRecovery());

        tracker.onPlayJoin(handler, true);
        tracker.beginHostSession("world-2", "Hosted World");
        tracker.clearGuestSessionForHostStartup();
        assertNotNull(tracker.currentSession());
        assertEquals(SharedWorldPlaySessionTracker.SessionRole.HOST, tracker.currentSession().role());
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
