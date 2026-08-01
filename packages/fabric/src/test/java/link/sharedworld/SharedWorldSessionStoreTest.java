package link.sharedworld;

import link.sharedworld.api.SharedWorldModels.SessionTokenDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SharedWorldSessionStoreTest {
    @TempDir
    Path tempDir;

    private static final String BACKEND = "https://backend.example";
    private static final String PLAYER = "11111111-1111-1111-1111-111111111111";

    private static SessionTokenDto session(String token, String expiresAt) {
        return new SessionTokenDto(token, "1111", "HostA", expiresAt);
    }

    @Test
    void sessionsSurviveAStoreReload() {
        Path file = tempDir.resolve("sessions.json");
        new SharedWorldSessionStore(file).save(BACKEND, PLAYER, session("token-1", "2099-01-01T00:00:00.000Z"));

        SessionTokenDto reloaded = new SharedWorldSessionStore(file).load(BACKEND, PLAYER);

        assertEquals("token-1", reloaded.token());
        assertEquals("2099-01-01T00:00:00.000Z", reloaded.expiresAt());
    }

    @Test
    void sessionsAreKeyedByBackendAndPlayer() {
        Path file = tempDir.resolve("sessions.json");
        SharedWorldSessionStore store = new SharedWorldSessionStore(file);
        store.save(BACKEND, PLAYER, session("token-prod", "2099-01-01T00:00:00.000Z"));
        store.save("http://127.0.0.1:8787", PLAYER, session("token-dev", "2099-01-01T00:00:00.000Z"));

        assertEquals("token-prod", store.load(BACKEND, PLAYER).token());
        assertEquals("token-dev", store.load("http://127.0.0.1:8787", PLAYER).token());
        assertNull(store.load(BACKEND, "22222222-2222-2222-2222-222222222222"));
    }

    @Test
    void expiredLookingTokensAreKeptButMalformedExpiriesAreTreatedAsAbsent() {
        Path file = tempDir.resolve("sessions.json");
        SharedWorldSessionStore store = new SharedWorldSessionStore(file);

        // The server is the authority on token lifetime: judging expiry by the
        // LOCAL clock made a skewed clock drop every session and forced a full
        // Mojang handshake per launch. A truly expired token costs one 401.
        store.save(BACKEND, PLAYER, session("token-old", "2001-01-01T00:00:00.000Z"));
        assertEquals("token-old", store.load(BACKEND, PLAYER).token(),
                "expiry is the server's call, not the local clock's");

        // save() still refuses malformed expiries outright.
        store.clear(BACKEND, PLAYER);
        store.save(BACKEND, PLAYER, session("token-bad", "not-a-timestamp"));
        assertNull(store.load(BACKEND, PLAYER));
    }

    @Test
    void clearRemovesOnlyTheMatchingEntry() {
        Path file = tempDir.resolve("sessions.json");
        SharedWorldSessionStore store = new SharedWorldSessionStore(file);
        store.save(BACKEND, PLAYER, session("token-1", "2099-01-01T00:00:00.000Z"));
        store.save("http://other.example", PLAYER, session("token-2", "2099-01-01T00:00:00.000Z"));

        store.clear(BACKEND, PLAYER);

        assertNull(store.load(BACKEND, PLAYER));
        assertEquals("token-2", store.load("http://other.example", PLAYER).token());
    }

    @Test
    void corruptStoreFilesAreToleratedAsEmpty() throws Exception {
        Path file = tempDir.resolve("sessions.json");
        Files.writeString(file, "{not json at all");

        assertNull(new SharedWorldSessionStore(file).load(BACKEND, PLAYER));
    }
}
