package link.sharedworld.screen;

import link.sharedworld.api.SharedWorldModels.StorageLinkSessionDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CreateSharedWorldScreenDriveLinkPollerTest {
    @Test
    void driveLinkPollerKeepsPollingPastSixtySecondsUntilLinked() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        AtomicReference<StorageLinkSessionDto> terminal = new AtomicReference<>();
        CreateSharedWorldScreen.DriveLinkAttempt attempt = waitingAttempt();
        CreateSharedWorldScreen.DriveLinkPoller poller = new CreateSharedWorldScreen.DriveLinkPoller(
                sessionId -> polls.incrementAndGet() > 65 ? session("linked") : session("pending"),
                millis -> sleeps.incrementAndGet()
        );

        poller.poll(attempt, terminal::set);

        assertEquals(66, polls.get());
        assertEquals(65, sleeps.get());
        assertEquals("linked", terminal.get().status());
    }

    @Test
    void driveLinkPollerStopsImmediatelyWhenLinked() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        AtomicReference<StorageLinkSessionDto> terminal = new AtomicReference<>();
        CreateSharedWorldScreen.DriveLinkAttempt attempt = waitingAttempt();
        CreateSharedWorldScreen.DriveLinkPoller poller = new CreateSharedWorldScreen.DriveLinkPoller(
                sessionId -> {
                    polls.incrementAndGet();
                    return session("linked");
                },
                millis -> sleeps.incrementAndGet()
        );

        poller.poll(attempt, terminal::set);

        assertEquals(1, polls.get());
        assertEquals(0, sleeps.get());
        assertEquals("linked", terminal.get().status());
    }

    @Test
    void driveLinkPollerStopsWhenSessionExpires() throws Exception {
        AtomicReference<StorageLinkSessionDto> terminal = new AtomicReference<>();
        CreateSharedWorldScreen.DriveLinkAttempt attempt = waitingAttempt();
        CreateSharedWorldScreen.DriveLinkPoller poller = new CreateSharedWorldScreen.DriveLinkPoller(
                sessionId -> session("expired"),
                millis -> {
                    throw new AssertionError("poller should not sleep after expired status");
                }
        );

        poller.poll(attempt, terminal::set);

        assertEquals("expired", terminal.get().status());
    }

    @Test
    void driveLinkPollerSkipsTerminalUpdateAfterAttemptCancellation() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicReference<StorageLinkSessionDto> terminal = new AtomicReference<>();
        CreateSharedWorldScreen.DriveLinkAttempt attempt = waitingAttempt();
        CreateSharedWorldScreen.DriveLinkPoller poller = new CreateSharedWorldScreen.DriveLinkPoller(
                sessionId -> {
                    polls.incrementAndGet();
                    attempt.cancel();
                    return session("linked");
                },
                millis -> {
                    throw new AssertionError("poller should not sleep after cancellation");
                }
        );

        poller.poll(attempt, terminal::set);

        assertEquals(1, polls.get());
        assertNull(terminal.get());
    }

    @Test
    void attemptControllerCancelsSupersededAttemptAndTracksCurrentOne() {
        CreateSharedWorldScreen.DriveLinkAttemptController controller = new CreateSharedWorldScreen.DriveLinkAttemptController();

        CreateSharedWorldScreen.DriveLinkAttempt first = controller.beginAttempt();
        first.setPhase(CreateSharedWorldScreen.DriveLinkUiPhase.WAITING_FOR_AUTH);
        CreateSharedWorldScreen.DriveLinkAttempt second = controller.beginAttempt();

        assertTrue(first.isCancelled());
        assertFalse(second.isCancelled());
        assertTrue(controller.isCurrent(second));
        assertFalse(controller.isCurrent(first));
        assertSame(second, controller.currentAttempt());

        controller.clearIfCurrent(second);

        assertNull(controller.currentAttempt());
    }

    private static CreateSharedWorldScreen.DriveLinkAttempt waitingAttempt() {
        CreateSharedWorldScreen.DriveLinkAttempt attempt = new CreateSharedWorldScreen.DriveLinkAttempt(CreateSharedWorldScreen.DriveLinkUiPhase.WAITING_FOR_AUTH);
        attempt.setSession(session("pending"));
        return attempt;
    }

    private static StorageLinkSessionDto session(String status) {
        return new StorageLinkSessionDto(
                "storage-1",
                "google-drive",
                status,
                "https://example.invalid/auth",
                Instant.EPOCH.toString(),
                null,
                null,
                null
        );
    }
}
