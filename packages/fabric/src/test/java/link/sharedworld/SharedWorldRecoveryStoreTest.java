package link.sharedworld;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldRecoveryStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void saveIsAtomicAndRoundTrips() throws Exception {
        Path file = this.tempDir.resolve("sharedworld-recovery.json");
        SharedWorldRecoveryStore store = new SharedWorldRecoveryStore(file);
        store.save(new SharedWorldRecoveryStore.RecoveryRecord("world-1", "World", 7L, "waiting", "join.example", "wait-1"));

        assertTrue(Files.exists(file));
        assertTrue(Files.notExists(file.resolveSibling(file.getFileName() + ".tmp")));
        SharedWorldRecoveryStore.RecoveryRecord loaded = store.load();
        assertEquals("world-1", loaded.worldId());
        assertEquals("wait-1", loaded.waiterSessionId());
    }

    @Test
    void corruptedRecordSelfClearsInsteadOfCrashingEveryStartup() throws Exception {
        Path file = this.tempDir.resolve("sharedworld-recovery.json");
        Files.writeString(file, "not-json{{{");
        SharedWorldRecoveryStore store = new SharedWorldRecoveryStore(file);

        assertNull(store.load());
        assertTrue(Files.notExists(file));
        assertNull(store.load());
    }
}
