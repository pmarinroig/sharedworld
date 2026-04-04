package link.sharedworld.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SharedWorldHostRecoveryStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void malformedMarkerReturnsNullAndClearsCorruptedFile() throws Exception {
        Path markerFile = this.tempDir.resolve("sharedworld-host-recovery.json");
        Files.writeString(markerFile, "{\"worldId\":");
        SharedWorldHostRecoveryStore store = new SharedWorldHostRecoveryStore(markerFile);

        assertNull(store.load());
        assertFalse(Files.exists(markerFile));
    }

    @Test
    void structurallyInvalidMarkerReturnsNullAndClearsCorruptedFile() throws Exception {
        Path markerFile = this.tempDir.resolve("sharedworld-host-recovery.json");
        Files.writeString(markerFile, "{\"worldId\":\"world-1\"}");
        SharedWorldHostRecoveryStore store = new SharedWorldHostRecoveryStore(markerFile);

        assertNull(store.load());
        assertFalse(Files.exists(markerFile));
    }

    @Test
    void saveUsesReplacementWriteAndLeavesLoadableMarker() throws Exception {
        Path markerFile = this.tempDir.resolve("sharedworld-host-recovery.json");
        SharedWorldHostRecoveryStore store = new SharedWorldHostRecoveryStore(markerFile);
        SharedWorldHostingManager.HostRecoveryRecord record = new SharedWorldHostingManager.HostRecoveryRecord(
                "world-1",
                "World",
                "host-1",
                7L,
                "2099-01-01T00:00:00.000Z"
        );

        store.save(record);

        assertEquals(record, store.load());
        assertFalse(Files.exists(markerFile.resolveSibling(markerFile.getFileName() + ".tmp")));
    }
}
