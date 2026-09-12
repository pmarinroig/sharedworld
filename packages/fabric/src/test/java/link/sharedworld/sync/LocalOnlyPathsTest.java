package link.sharedworld.sync;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LocalOnlyPathsTest {
    @Test
    void distantHorizonsDatabasesStayLocalInEveryDimension() {
        assertTrue(LocalOnlyPaths.isLocalOnly("data/DistantHorizons.sqlite"));
        assertTrue(LocalOnlyPaths.isLocalOnly("data/DistantHorizons.sqlite-wal"));
        assertTrue(LocalOnlyPaths.isLocalOnly("data/DistantHorizons.sqlite-shm"));
        assertTrue(LocalOnlyPaths.isLocalOnly("data/DistantHorizons.sqlite-journal"));
        assertTrue(LocalOnlyPaths.isLocalOnly("DIM-1/data/DistantHorizons.sqlite"));
        assertTrue(LocalOnlyPaths.isLocalOnly("dimensions/mymod/sky/data/DistantHorizons.sqlite"));
    }

    @Test
    void theRuleIsNarrow() {
        assertFalse(LocalOnlyPaths.isLocalOnly("DistantHorizons.sqlite"), "only inside a data folder");
        assertFalse(LocalOnlyPaths.isLocalOnly("data/DistantHorizons.sqlite.bak"));
        assertFalse(LocalOnlyPaths.isLocalOnly("data/raids.dat"));
        assertFalse(LocalOnlyPaths.isLocalOnly("xaeromap.txt"), "Xaero's world id must keep syncing");
        assertFalse(LocalOnlyPaths.isLocalOnly("level.dat"));
        assertFalse(LocalOnlyPaths.isLocalOnly("region/r.0.0.mca"));
    }

    @Test
    void theOldFilenameRulesStillApply() {
        assertTrue(LocalOnlyPaths.isLocalOnly("session.lock"));
        assertTrue(LocalOnlyPaths.isLocalOnly("level.dat_old"));
        assertTrue(LocalOnlyPaths.isLocalOnly("DIM1/level.dat_old"));
        assertTrue(LocalOnlyPaths.isLocalOnly(Path.of("/w"), Path.of("/w/DIM1/data/DistantHorizons.sqlite")));
    }
}
