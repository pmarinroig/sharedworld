package link.sharedworld.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldReleaseStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void saveIsAtomicAndRoundTrips() throws Exception {
        Path file = this.tempDir.resolve("sharedworld-release.json");
        SharedWorldReleaseStore store = new SharedWorldReleaseStore(file);
        SharedWorldReleaseStore.ReleaseRecord record = new SharedWorldReleaseStore.ReleaseRecord();
        record.worldId = "world-1";
        record.hostUuid = "host-uuid";
        record.runtimeEpoch = 7L;
        record.hostToken = "token-7";
        record.phase = SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT;

        store.save(record);

        assertTrue(Files.exists(file));
        assertTrue(Files.notExists(file.resolveSibling(file.getFileName() + ".tmp")));
        SharedWorldReleaseStore.ReleaseRecord loaded = store.load();
        assertEquals("world-1", loaded.worldId);
        assertEquals(7L, loaded.runtimeEpoch);
        assertEquals(SharedWorldReleasePhase.UPLOADING_FINAL_SNAPSHOT, loaded.phase);
    }

    @Test
    void corruptedRecordSelfClearsInsteadOfCrashingEveryStartup() throws Exception {
        Path file = this.tempDir.resolve("sharedworld-release.json");
        Files.writeString(file, "not-json{{{");
        SharedWorldReleaseStore store = new SharedWorldReleaseStore(file);

        assertNull(store.load());
        assertTrue(Files.notExists(file));
        // A cleared store keeps working afterwards.
        assertNull(store.load());
    }
}
